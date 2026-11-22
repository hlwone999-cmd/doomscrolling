package app.tidemark

import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tidemark.data.AppDatabase
import app.tidemark.data.GoalEventRow
import app.tidemark.data.GoalRow
import app.tidemark.data.ReelEventRow
import app.tidemark.data.SessionRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The data layer on the device's own SQLite (the one that matters for
 * Android 8-10). Same cases as replica/tests/check_data_layer.py.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase
    private val ig = "com.instagram.android"
    private val day = "2026-11-21"

    @Before fun setUp() {
        db = AppDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private suspend fun open(at: Long = 1_000, isOpen: Int? = 1) = db.sessions().insert(
        SessionRow(packageName = ig, surfaceKind = "reels", dayKey = day, tzOffsetMin = 420,
            startedAt = at, lastEventAt = at, rulesVersion = 1, isOpen = isOpen, createdAt = at, updatedAt = at)
    )

    private suspend fun count(sessionId: Long, key: String, at: Long) =
        db.reelEvents().insert(ReelEventRow(sessionId = sessionId, at = at, itemKey = key, dwellMs = 400))

    @Test fun seedRowsExist() = runBlocking {
        // Instagram, YouTube, two TikTok packages on; Facebook and Snapchat opt-in.
        assertEquals(4, db.trackedApps().enabled().size)
    }

    @Test fun onlyOneOpenSession() = runBlocking {
        open()
        try {
            open(2_000)
            fail("second open session was accepted")
        } catch (expected: SQLiteConstraintException) {
        }
    }

    @Test fun duplicateKeysIgnoredAndRolledUp() = runBlocking {
        val id = open()
        val ids = listOf("i:0", "i:1", "i:1", "i:0", "i:2").mapIndexed { i, k -> count(id, k, 1_100L + i) }
        assertEquals(listOf(true, true, false, false, true), ids.map { it != -1L })
        assertEquals(3, db.stats().totalFor(day))
    }

    @Test fun closeOnceAddsActiveTime() = runBlocking {
        val id = open()
        assertEquals(1, db.sessions().close(id, 9_000, "left_surface", 8_000, 9_000))
        assertEquals(0, db.sessions().close(id, 9_500, "left_surface", 8_500, 9_500))
        open(10_000) // a new session may open now
        Unit
    }

    @Test fun danglingSessionEndsAtLastActivity() = runBlocking {
        val id = open(1_000)
        count(id, "i:0", 4_000)
        db.sessions().touch(id, 7_000, 0)
        assertEquals(1, db.sessions().closeDangling(99_999))
        assertEquals(0, db.sessions().closeDangling(100_000))
        open(200_000)
        Unit
    }

    @Test fun demoRowsDoNotBlockRealSession() = runBlocking {
        val demo = open(1_000, isOpen = null)
        open(1_500)
        count(demo, "i:demo-0", 1_001)
        db.sessions().finishDemo(demo, 5_000, 4_000)
        assertEquals(1, db.stats().totalFor(day))
    }

    @Test fun noOpenSessionAfterRecovery() = runBlocking {
        open()
        db.sessions().closeDangling(5_000)
        val id = open(6_000)
        assertTrue(id > 0)
    }

    @Test fun liveActiveTimeAndOneCreditOnClose() = runBlocking {
        val id = open(1_000)
        db.sessions().touch(id, 6_000, 4_500)
        db.sessions().touch(id, 7_000, 4_000) // never goes backwards
        assertEquals(4_500L, db.sessions().openSession()!!.activeMs)
        db.sessions().close(id, 8_000, "left_surface", 5_000, 8_000)
        assertEquals(5_000L, db.stats().rangeOnce(day, day).single().activeMs)
    }

    @Test fun pruneKeepsTotalsAndOpenSession() = runBlocking {
        val a = open(1_000)
        repeat(3) { count(a, "i:$it", 1_000L + it) }
        db.sessions().close(a, 2_000, "left_surface", 1_000, 2_000)
        val b = open(3_000)
        count(b, "i:0", 3_001)
        assertEquals(3, db.reelEvents().pruneBefore(10_000))
        assertEquals(4, db.stats().totalFor(day))
    }

    @Test fun deleteDaysKeepsTheOpenSession() = runBlocking {
        val a = open(1_000)
        count(a, "i:0", 1_001)
        db.sessions().close(a, 2_000, "left_surface", 1_000, 2_000)
        open(3_000)
        db.sessions().deleteDays(day, day)
        db.stats().deleteDays(day, day)
        assertEquals(0, db.stats().totalFor(day))
        assertTrue(db.sessions().openSession() != null)
    }

    @Test fun goalSaveKeepsOnePerScope() = runBlocking {
        val g1 = db.goals().save(GoalRow(value = 100, createdAt = 0, updatedAt = 0))
        val g2 = db.goals().save(GoalRow(value = 80, createdAt = 0, updatedAt = 0)) // same scope: replaces
        assertEquals(g1, g2)
        assertEquals(80, db.goals().byId(g1)!!.value)
        db.goals().save(GoalRow(packageName = ig, value = 50, createdAt = 0, updatedAt = 0))
        assertEquals(2, db.goals().allOnce().size)
    }

    @Test fun goalNoticesOncePerDaySnoozesRepeat() = runBlocking {
        val g = db.goals().save(GoalRow(value = 100, action = GoalRow.ACTION_BLOCK, snoozesPerDay = 2, createdAt = 0, updatedAt = 0))
        fun ev(kind: String, seq: Int = 0) = GoalEventRow(goalId = g, dayKey = day, at = 0, kind = kind, seq = seq, valueAtEvent = 80)
        assertTrue(db.goals().insertEvent(ev("warned")) != -1L)
        assertEquals(-1L, db.goals().insertEvent(ev("warned")))
        assertTrue(db.goals().insertEvent(ev("snoozed", 1)) != -1L)
        assertTrue(db.goals().insertEvent(ev("snoozed", 2)) != -1L)
        assertEquals(2, db.goals().countKind(g, day, "snoozed"))
    }

    @Test fun importNeverOverwritesExistingDays() = runBlocking {
        val a = open(1_000)
        count(a, "i:0", 1_001)
        val ids = db.stats().insertIfMissing(listOf(
            app.tidemark.data.DailyStatRow(day, ig, 99, 0, 0, 0),
            app.tidemark.data.DailyStatRow("2026-11-01", ig, 7, 0, 1, 0),
        ))
        assertEquals(1, ids.count { it != -1L })
        assertEquals(1, db.stats().totalFor(day))
        assertEquals(7, db.stats().totalFor("2026-11-01"))
    }
}

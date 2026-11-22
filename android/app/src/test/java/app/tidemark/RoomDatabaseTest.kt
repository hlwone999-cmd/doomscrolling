package app.tidemark

import android.app.Application
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.tidemark.data.AppDatabase
import app.tidemark.data.ReelEventRow
import app.tidemark.data.SessionRow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The real Room database in the JVM (Robolectric, native SQLite): the same
 * entities, DAOs, generated SQL (INSERT OR ABORT for sessions, INSERT OR
 * IGNORE for videos) and triggers the phone runs. These cover the device
 * error "write failed: Open/Count: SQLiteConstraintException".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RoomDatabaseTest {
    private lateinit var db: AppDatabase
    private val tiktok = "com.zhiliaoapp.musically"
    private val day = "2026-11-21"

    @Before fun setUp() {
        db = AppDatabase.buildInMemory(ApplicationProvider.getApplicationContext())
    }

    @After fun tearDown() = db.close()

    private suspend fun open(at: Long): Long = db.sessions().insert(
        SessionRow(packageName = tiktok, surfaceKind = "tiktok_feed", dayKey = day, tzOffsetMin = 420,
            startedAt = at, lastEventAt = at, rulesVersion = 2, isOpen = 1, createdAt = at, updatedAt = at)
    )

    private suspend fun close(id: Long, at: Long, activeMs: Long = 1_000) =
        db.sessions().close(id, at, "left_surface", activeMs, at)

    private suspend fun video(sessionId: Long, key: String, at: Long): Long =
        db.reelEvents().insert(ReelEventRow(sessionId = sessionId, at = at, itemKey = key, dwellMs = 400))

    private suspend fun today() = db.stats().rangeOnce(day, day).singleOrNull { it.packageName == tiktok }

    // 1
    @Test fun opensTheFirstSession() = runBlocking {
        val id = open(1_000)
        assertTrue(id > 0)
        assertEquals(1, today()!!.sessions)
    }

    // 2: the device failure. With the old triggers this threw
    // "UNIQUE constraint failed: daily_stat.day_key, daily_stat.package".
    @Test fun opensASecondSessionAfterTheFirstIsClosed() = runBlocking {
        val first = open(1_000)
        video(first, "p:0", 1_100)
        close(first, 2_000)
        val second = open(3_000)
        assertTrue(second > first)
        video(second, "p:0", 3_100)
        close(second, 4_000)
        repeat(3) { k -> close(open(10_000L + k * 1_000), 10_500L + k * 1_000) }
        val stat = today()!!
        assertEquals(5, stat.sessions)
        assertEquals(2, stat.reels)
    }

    // 3
    @Test fun storesTheFirstVideo() = runBlocking {
        val s = open(1_000)
        assertTrue(video(s, "p:0", 1_100) > 0)
        assertEquals(1, db.stats().totalFor(day))
        assertEquals(1, db.sessions().openSession()!!.reelCount)
    }

    // 4
    @Test fun storesTenDifferentVideos() = runBlocking {
        val s = open(1_000)
        for (i in 0 until 10) assertTrue(video(s, "p:$i", 1_100L + i) > 0)
        assertEquals(10, db.stats().totalFor(day))
    }

    // 5
    @Test fun rejectsADuplicateVideoWithoutAnError() = runBlocking {
        val s = open(1_000)
        assertTrue(video(s, "p:3", 1_100) > 0)
        assertEquals(-1L, video(s, "p:3", 1_200))
        assertEquals(1, db.stats().totalFor(day))
    }

    // 6
    @Test fun recoversASessionLeftOpenAfterReconnect() = runBlocking {
        val crashed = open(1_000)
        video(crashed, "p:0", 1_100)
        db.sessions().touch(crashed, 5_000, 3_500)
        // Service reconnects: the writer closes what the old process left open.
        assertEquals(1, db.sessions().closeDangling(60_000))
        assertNull(db.sessions().openSession())
        val fresh = open(61_000)
        assertTrue(video(fresh, "p:0", 61_100) > 0)
        assertEquals(2, db.stats().totalFor(day))
        assertEquals(3_500L, today()!!.activeMs) // the crashed session's touched time, not wall time
    }

    // 7
    @Test fun twoCloseOpensNeverCreateTwoOpenSessions() = runBlocking {
        open(1_000)
        try {
            open(1_005)
            fail("a second open session was accepted")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().contains("session.is_open"))
        }
        // What the writer does next: close the stale row, then open.
        db.sessions().closeDangling(1_010)
        assertTrue(open(1_010) > 0)
        assertEquals(1, db.query("SELECT COUNT(*) FROM session WHERE is_open = 1", null).use { it.moveToFirst(); it.getInt(0) })
    }

    // 8
    @Test fun failedWriteLeavesTheDatabaseConsistent() = runBlocking {
        val s = open(1_000)
        video(s, "p:0", 1_100)
        val before = today()!!
        try {
            video(999, "p:1", 1_200) // no such session: the foreign key must reject it
            fail("an orphan video was stored")
        } catch (expected: SQLiteConstraintException) {
        }
        assertEquals(before, today())
        assertEquals(1, db.sessions().openSession()!!.reelCount)
    }

    // The fix reaches phones that still have the old triggers: replaced on open, data kept.
    @Test fun oldTriggersAreReplacedWithoutLosingData() = runBlocking {
        val first = open(1_000)
        video(first, "p:0", 1_100)
        close(first, 2_000)
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("DROP TRIGGER IF EXISTS session_open_rollup")
        sqlite.execSQL(
            """CREATE TRIGGER session_open_rollup AFTER INSERT ON session
               BEGIN
                 INSERT OR IGNORE INTO daily_stat (day_key, package, reels, active_ms, sessions, updated_at)
                   VALUES (NEW.day_key, NEW.package, 0, 0, 0, NEW.started_at);
                 UPDATE daily_stat SET sessions = sessions + 1, updated_at = NEW.started_at
                  WHERE day_key = NEW.day_key AND package = NEW.package;
               END"""
        )
        try {
            open(3_000)
            fail("the old trigger should reproduce the device error")
        } catch (expected: SQLiteConstraintException) {
            assertTrue(expected.message.orEmpty().contains("daily_stat"))
        }
        AppDatabase.installTriggers(sqlite)
        assertTrue(open(3_000) > 0)
        assertEquals(1, db.stats().totalFor(day)) // the earlier video is still there
        assertEquals(2, today()!!.sessions)
    }
}

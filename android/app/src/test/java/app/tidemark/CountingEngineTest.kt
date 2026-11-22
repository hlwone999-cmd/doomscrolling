package app.tidemark

import app.tidemark.detect.CountingEngine
import app.tidemark.detect.EndReason
import app.tidemark.detect.EngineConfig
import app.tidemark.detect.EngineSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CountingEngineTest {

    private class FakeSink : EngineSink {
        val opened = mutableListOf<Pair<Long, String>>()
        val counted = mutableListOf<Pair<Long, String>>()
        val closed = mutableListOf<Triple<Long, Long, EndReason>>()
        val activeMs = mutableListOf<Long>()
        var overlay = false
        var touches = 0
        val touchActive = mutableListOf<Long>()
        override fun openSession(token: Long, pkg: String, surface: String, at: Long) { opened += token to pkg }
        override fun countVideo(token: Long, itemKey: String, at: Long, dwellMs: Long) { counted += token to itemKey }
        override fun touchSession(token: Long, at: Long, activeMs: Long) { touches++; touchActive += activeMs }
        override fun closeSession(token: Long, endedAt: Long, activeMs: Long, reason: EndReason) {
            closed += Triple(token, endedAt, reason); this.activeMs += activeMs
        }
        override fun overlayVisible(visible: Boolean) { overlay = visible }
    }

    private val cfg = EngineConfig(settleMs = 400, graceMs = 30_000, hideDelayMs = 1_500, touchEveryMs = 5_000)
    private var day = "2026-11-21"
    private lateinit var sink: FakeSink
    private lateinit var e: CountingEngine
    private val ig = "com.instagram.android"
    private val yt = "com.google.android.youtube"

    @Before fun setUp() {
        sink = FakeSink()
        e = CountingEngine(cfg, sink) { day }
    }

    /** Settle a key: observe it, then let settleMs pass. */
    private fun settle(key: String, at: Long) {
        e.onObserve(key, at)
        e.tick(at + cfg.settleMs)
    }

    @Test fun firstVideoCountsAfterSettle() {
        e.onSurface(ig, "reels", 0)
        assertTrue(sink.overlay)
        e.onObserve("i:0", 10)
        e.tick(300)
        assertEquals(0, sink.counted.size)
        e.tick(410)
        assertEquals(listOf(1L to "i:0"), sink.counted)
    }

    @Test fun flingPastDoesNotCount() {
        e.onSurface(ig, "reels", 0)
        e.onScroll(10)
        e.onObserve("i:1", 100)
        e.onScroll(200)              // moved on before 400 ms
        e.onObserve("i:2", 250)
        e.onScroll(350)
        e.onObserve("i:3", 400)
        e.tick(900)
        assertEquals(listOf(1L to "i:3"), sink.counted)
    }

    @Test fun swipingBackDoesNotRecount() {
        e.onSurface(ig, "reels", 0)
        settle("i:0", 0)
        e.onScroll(1000); settle("i:1", 1100)
        e.onScroll(3000); settle("i:0", 3100)   // back to the first video
        assertEquals(listOf("i:0", "i:1"), sink.counted.map { it.second })
    }

    @Test fun keyChangeWithoutScrollDoesNotRecount() {
        e.onSurface(ig, "reels", 0)
        settle("h:aaaaaaaaaaaaaaaa", 0)
        // Rotation, a like, or the comments sheet changes the fingerprint; no scroll happened.
        settle("h:bbbbbbbbbbbbbbbb", 2000)
        assertEquals(1, sink.counted.size)
    }

    @Test fun swipeBackThenKeyChangeDoesNotCount() {
        e.onSurface(ig, "reels", 0)
        settle("h:aaaaaaaaaaaaaaaa", 0)
        e.onScroll(1000); settle("h:cccccccccccccccc", 1100)
        e.onScroll(2000); settle("h:aaaaaaaaaaaaaaaa", 2100) // back: uses up the scroll
        settle("h:dddddddddddddddd", 4000)                   // same video, fingerprint drifted
        assertEquals(2, sink.counted.size)
    }

    @Test fun duplicateObservationsCountOnce() {
        e.onSurface(ig, "reels", 0)
        repeat(5) { e.onObserve("i:7", 10L + it) }
        e.tick(500); e.tick(900); e.tick(5000)
        assertEquals(1, sink.counted.size)
    }

    @Test fun shortLeaveKeepsSessionAndDedupe() {
        e.onSurface(ig, "reels", 0)
        settle("i:0", 0)
        e.onOffSurface(1000)          // notification shade
        e.tick(2600)
        assertFalse(sink.overlay)     // hidden after the 1.5 s delay
        e.onSurface(ig, "reels", 10_000)
        assertTrue(sink.overlay)
        settle("i:0", 10_000)         // same video on return
        assertEquals(1, sink.opened.size)
        assertEquals(1, sink.counted.size)
        assertTrue(sink.closed.isEmpty())
    }

    @Test fun longLeaveClosesAtLeaveTime() {
        e.onSurface(ig, "reels", 0)
        settle("i:0", 0)
        e.onOffSurface(20_000)
        e.tick(50_000)
        assertEquals(Triple(1L, 20_000L, EndReason.LEFT_SURFACE), sink.closed.single())
        assertEquals(20_000L, sink.activeMs.single())
        e.onSurface(ig, "reels", 60_000)
        assertEquals(2, sink.opened.size)
    }

    @Test fun returnAfterGraceWithoutTickStillStartsNewSession() {
        e.onSurface(ig, "reels", 0)
        e.onOffSurface(1_000)
        e.onSurface(ig, "reels", 40_000) // tick never ran
        assertEquals(EndReason.LEFT_SURFACE, sink.closed.single().third)
        assertEquals(2, sink.opened.size)
    }

    @Test fun appSwitchClosesAndOpens() {
        e.onSurface(ig, "reels", 0)
        e.onSurface(yt, "shorts", 5_000)
        assertEquals(EndReason.APP_SWITCH, sink.closed.single().third)
        assertEquals(listOf(1L to ig, 2L to yt), sink.opened)
    }

    @Test fun mixedKeyKindsIgnoredInOneSession() {
        e.onSurface(ig, "reels", 0)
        settle("i:0", 0)
        e.onScroll(1000); settle("h:aaaaaaaaaaaaaaaa", 1100)
        assertEquals(1, sink.counted.size)
    }

    @Test fun nullKeyNeverCounts() {
        e.onSurface(ig, "reels", 0)
        e.onObserve(null, 0)
        e.tick(5_000)
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun screenOffClosesImmediately() {
        e.onSurface(ig, "reels", 0)
        e.onScreenOff(8_000)
        assertEquals(Triple(1L, 8_000L, EndReason.SCREEN_OFF), sink.closed.single())
        assertFalse(sink.overlay)
    }

    @Test fun dayRolloverSplitsSession() {
        e.onSurface(ig, "reels", 0)
        settle("i:0", 0)
        day = "2026-11-22"
        e.tick(10_000)
        assertEquals(EndReason.DAY_ROLLOVER, sink.closed.single().third)
        assertEquals(2, sink.opened.size)
        settle("i:0", 10_000)        // new session, new day: counts again by design
        assertEquals(2, sink.counted.size)
    }

    /** BUG-001: a day change during the grace period must end the old session when the user left. */
    @Test fun dayRolloverDuringGraceEndsAtLeaveTime() {
        e.onSurface(ig, "reels", 0)
        e.onOffSurface(1_000)
        day = "2026-11-22"
        e.onSurface(ig, "reels", 20_000)
        assertEquals(Triple(1L, 1_000L, EndReason.DAY_ROLLOVER), sink.closed.single())
        assertEquals(1_000L, sink.activeMs.single())
    }

    @Test fun nextDeadlineTracksPendingSettle() {
        e.onSurface(ig, "reels", 1_000)
        e.onObserve("i:0", 2_000)
        assertEquals(2_400L, e.nextDeadline())
    }

    @Test fun stopWhenIdleDoesNothing() {
        e.stop(0, EndReason.PAUSED)
        assertTrue(sink.closed.isEmpty())
        assertNull(e.nextDeadline())
    }

    @Test fun touchesWhileWatching() {
        e.onSurface(ig, "reels", 0)
        e.tick(5_000); e.tick(10_000)
        assertEquals(2, sink.touches)
        assertEquals(listOf(5_000L, 10_000L), sink.touchActive)
    }

    @Test fun liveActiveTimeExcludesTimeAway() {
        e.onSurface(ig, "reels", 0)
        e.onOffSurface(4_000)            // away 6 s
        e.onSurface(ig, "reels", 10_000)
        assertEquals(7_000L, e.sessionActiveMs(13_000))
        e.tick(15_000)
        assertEquals(9_000L, sink.touchActive.last())
    }
}

package app.tidemark

import app.tidemark.detect.CountingEngine
import app.tidemark.detect.EndReason
import app.tidemark.detect.EngineConfig
import app.tidemark.detect.EngineSink
import app.tidemark.detect.FeedPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole counting path the service uses (pager signals -> FeedPipeline ->
 * PageTracker -> CountingEngine), with plain strings standing in for the
 * on-screen containers. Timing follows the service: a probe after the
 * swipe, then the 400 ms settle.
 */
class FeedPipelineTest {

    private class Sink : EngineSink {
        val counted = mutableListOf<String>()
        val closed = mutableListOf<EndReason>()
        var overlay = false
        val notes = mutableListOf<String>()
        override fun openSession(token: Long, pkg: String, surface: String, at: Long) {}
        override fun countVideo(token: Long, itemKey: String, at: Long, dwellMs: Long) { counted += itemKey }
        override fun touchSession(token: Long, at: Long, activeMs: Long) {}
        override fun closeSession(token: Long, endedAt: Long, activeMs: Long, reason: EndReason) { closed += reason }
        override fun overlayVisible(visible: Boolean) { overlay = visible }
        override fun note(reason: String) { notes += reason }
    }

    private lateinit var sink: Sink
    private lateinit var engine: CountingEngine
    private lateinit var feed: FeedPipeline<String>
    private var now = 0L

    @Before fun setUp() {
        sink = Sink()
        engine = CountingEngine(EngineConfig(), sink) { "2026-11-21" }
        feed = FeedPipeline(engine)
        now = 0L
        engine.onSurface("com.zhiliaoapp.musically", "tiktok_feed", now)
        feed.onNewSession()
    }

    /** Let time pass and fire the engine's deadlines, as the service's handler does. */
    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val d = engine.nextDeadline() ?: break
            if (d > end) break
            now = maxOf(now, d)
            engine.tick(now)
        }
        now = end
    }

    /** A page settles: the probe sees [page] (and its inner full-screen container) fill the pager. */
    private fun settle(page: String, row: Int? = null) {
        feed.onSettledPage(listOf(page, "$page-inner"), row, now)
        advance(500)
    }

    /** One real swipe from [from] to [to], the way a pager reports it. */
    private fun swipe(from: String, to: String, fromIdx: Int = -1, toIdx: Int = -1, dy: Int = 1800) {
        feed.onPagerScroll(fromIdx, fromIdx + 1, dy / 2, now); advance(40)
        feed.onMidSwipe(from, to, now); advance(40)
        feed.onPagerScroll(toIdx, toIdx, dy / 2, now); advance(150)
        settle(to)
    }

    @Test fun firstVideoCounts() {
        settle("A")
        assertEquals(listOf("p:0"), sink.counted)
    }

    @Test fun tenSwipesGiveTenMoreVideos() {
        settle("V0")
        for (i in 1..10) swipe("V${i - 1}", "V$i", fromIdx = i - 1, toIdx = i)
        assertEquals(11, sink.counted.size) // the first video + one per swipe
        assertEquals((0..10).map { "p:$it" }, sink.counted)
    }

    @Test fun tenSwipesWithoutAdapterIndexesStillCount() {
        // Pagers that report no indexes: direction comes from the scroll delta / mid-swipe page.
        settle("V0")
        for (i in 1..10) swipe("V${i - 1}", "V$i")
        assertEquals(11, sink.counted.size)
    }

    @Test fun recycledContainersStillCount() {
        // Three containers reused in a cycle, as RecyclerView/ViewPager do; indexes tell the pages apart.
        val views = listOf("A", "B", "C")
        settle("A")
        for (i in 1..9) swipe(views[(i - 1) % 3], views[i % 3], fromIdx = i - 1, toIdx = i)
        assertEquals(10, sink.counted.size)
    }

    @Test fun oneSwipeIsCountedOnce() {
        settle("A")
        // A single swipe that sends many scroll events and is probed several times.
        repeat(6) { feed.onPagerScroll(0, 1, 300, now); advance(30) }
        feed.onMidSwipe("A", "B", now); advance(30)
        feed.onPagerScroll(1, 1, 0, now)
        settle("B"); settle("B"); settle("B")
        advance(3_000)
        assertEquals(listOf("p:0", "p:1"), sink.counted)
    }

    @Test fun likeAndCommentDoNotCount() {
        settle("A")
        // Like: the heart animation rebuilds a view inside the page, no swipe.
        feed.onSettledPage(listOf("A", "A-inner-rebuilt"), null, now); advance(600)
        // Comment sheet: the page stays, the comments list scrolls (not the pager, so no onPagerScroll).
        settle("A"); advance(2_000)
        // Share menu / long-press menu: same page.
        settle("A")
        assertEquals(listOf("p:0"), sink.counted)
        assertTrue(sink.notes.any { it.contains("already counted") } || sink.counted.size == 1)
    }

    @Test fun openingAProfileDoesNotCount() {
        settle("A")
        engine.onOffSurface(now); advance(5_000)         // profile opened (short-video screen gone)
        engine.onSurface("com.zhiliaoapp.musically", "tiktok_feed", now)
        settle("A")                                       // back on the same video
        assertEquals(listOf("p:0"), sink.counted)
        assertTrue(sink.closed.isEmpty())                 // within the 30 s grace: same session
    }

    @Test fun sameVideoIsNotCountedAgain() {
        settle("A")
        swipe("A", "B", 0, 1)
        // Swipe back to A, then forward to B again: both already counted.
        feed.onPagerScroll(1, 0, -900, now); advance(40)
        feed.onMidSwipe("A", "B", now); advance(40)
        feed.onPagerScroll(0, 0, -900, now); advance(150)
        settle("A")
        swipe("A", "B", 0, 1)
        assertEquals(listOf("p:0", "p:1"), sink.counted)
    }

    @Test fun bounceBackIsNotANewVideo() {
        settle("A")
        feed.onPagerScroll(0, 1, 200, now); advance(40)
        feed.onPagerScroll(0, 0, -200, now); advance(150)
        settle("A")
        assertEquals(listOf("p:0"), sink.counted)
    }

    @Test fun pageChangeWithoutAnySwipeIsNotCounted() {
        settle("A")
        // The app swapped the page container with no scroll and no mid-swipe (e.g. re-layout).
        settle("Z")
        assertEquals(listOf("p:0"), sink.counted)
    }

    @Test fun leavingTheFeedEndsTheSessionAndHidesTheOverlay() {
        settle("A")
        assertTrue(sink.overlay)
        engine.onOffSurface(now)
        advance(2_000)
        assertFalse(sink.overlay)                         // hidden after the 1.5 s delay
        advance(31_000)
        assertEquals(listOf(EndReason.LEFT_SURFACE), sink.closed)
    }
}

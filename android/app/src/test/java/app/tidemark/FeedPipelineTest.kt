package app.tidemark

import app.tidemark.detect.CountingEngine
import app.tidemark.detect.EndReason
import app.tidemark.detect.EngineConfig
import app.tidemark.detect.EngineSink
import app.tidemark.detect.FeedContext
import app.tidemark.detect.FeedPipeline
import app.tidemark.detect.PageTracker
import app.tidemark.detect.ScrollSource
import app.tidemark.detect.ScrollSource.Relation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The whole counting path the service uses (pager signals -> FeedPipeline ->
 * PageTracker -> CountingEngine), with plain strings standing in for the
 * on-screen containers. Timing follows the service: a probe after the
 * swipe, then the 250 ms settle. The video on screen on arrival is not counted.
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

    /** What the service does with a scroll event: judge the source against the held pager, then feed it. */
    private fun scrollFrom(relation: Relation, from: Int = -1, to: Int = -1, dy: Int = 900, dx: Int = 0, sameSize: Boolean = true): PageTracker.Scroll? {
        val v = ScrollSource.judge(relation, sameSize, horizontalOnly = dx != 0 && dy == 0)
        if (!v.accepted) return null
        return if (v.indexesUsable) feed.onPagerScroll(from, to, dy, now) else feed.onPagerScroll(-1, -1, dy, now)
    }

    /** Order seen on the Realme phone (BUG-006): the new page is up before the scroll event. */
    private fun lateSwipe(to: String) {
        feed.onSettledPage(listOf(to, "$to-inner"), null, now); advance(60)
        val k = feed.onPagerScroll(-1, -1, 900, now)
        if (k.isMotion && feed.pages.pendingSince != null) feed.onSettledPage(listOf(to, "$to-inner"), null, now) // probe at once
        advance(100)
        repeat(3) { feed.onPagerScroll(-1, -1, 300, now); advance(50) } // tail of the same swipe
        settle(to)
    }

    @Test fun arrivalVideoIsNotCounted() {
        settle("A")
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun tenSwipesGiveTenCounts() {
        settle("V0")
        for (i in 1..10) swipe("V${i - 1}", "V$i", fromIdx = i - 1, toIdx = i)
        assertEquals((1..10).map { "p:$it" }, sink.counted) // one per swipe, none for the arrival video
    }

    @Test fun tenSwipesWithoutAdapterIndexesStillCount() {
        // Pagers that report no indexes: direction comes from the scroll delta / mid-swipe page.
        settle("V0")
        for (i in 1..10) swipe("V${i - 1}", "V$i")
        assertEquals(10, sink.counted.size)
    }

    @Test fun recycledContainersStillCount() {
        // Three containers reused in a cycle, as RecyclerView/ViewPager do; indexes tell the pages apart.
        val views = listOf("A", "B", "C")
        settle("A")
        for (i in 1..9) swipe(views[(i - 1) % 3], views[i % 3], fromIdx = i - 1, toIdx = i)
        assertEquals(9, sink.counted.size)
    }

    @Test fun oneSwipeIsCountedOnce() {
        settle("A")
        // A single swipe that sends many scroll events and is probed several times.
        repeat(6) { feed.onPagerScroll(0, 1, 300, now); advance(30) }
        feed.onMidSwipe("A", "B", now); advance(30)
        feed.onPagerScroll(1, 1, 0, now)
        settle("B"); settle("B"); settle("B")
        advance(3_000)
        assertEquals(listOf("p:1"), sink.counted)
    }

    @Test fun likeAndCommentDoNotCount() {
        settle("A")
        // Like: the heart animation rebuilds a view inside the page, no swipe.
        feed.onSettledPage(listOf("A", "A-inner-rebuilt"), null, now); advance(600)
        // Comment sheet: the page stays, the comments list scrolls (not the pager, so no onPagerScroll).
        settle("A"); advance(2_000)
        // Share menu / long-press menu: same page.
        settle("A")
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun openingAProfileDoesNotCount() {
        settle("A")
        engine.onOffSurface(now); advance(5_000)         // profile opened (short-video screen gone)
        engine.onSurface("com.zhiliaoapp.musically", "tiktok_feed", now)
        settle("A")                                       // back on the same video
        assertTrue(sink.counted.isEmpty())
        assertTrue(sink.closed.isEmpty())                 // within the 30 s grace: same session
    }

    @Test fun openingASearchResultDoesNotCount() {
        settle("A")
        engine.onOffSurface(now); advance(40_000)         // searching for a while
        engine.onSurface("com.zhiliaoapp.musically", "tiktok_feed", now)
        feed.onNewSession()
        settle("SearchResult")                            // the tapped result opens full screen
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun forwardAndBackDoNotRecount() {
        settle("A")
        swipe("A", "B", 0, 1)
        // Swipe back to A, then forward to B again: both already seen.
        feed.onPagerScroll(1, 0, -900, now); advance(40)
        feed.onMidSwipe("A", "B", now); advance(40)
        feed.onPagerScroll(0, 0, -900, now); advance(150)
        settle("A")
        swipe("A", "B", 0, 1)
        assertEquals(listOf("p:1"), sink.counted)
    }

    @Test fun bounceBackIsNotANewVideo() {
        settle("A")
        feed.onPagerScroll(0, 1, 200, now); advance(40)
        feed.onPagerScroll(0, 0, -200, now); advance(150)
        settle("A")
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun pageChangeWithoutAnySwipeIsNotCounted() {
        settle("A")
        // The app swapped the page container with no scroll and no mid-swipe (e.g. re-layout).
        settle("Z")
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun wrapperOnTopStillCountsEachSwipe() {
        // The held container is a wrapper: its top levels never change; the page changes deeper down.
        feed.onSettledPage(listOf("Wrap", "Pager", "Page0"), null, now); advance(500)
        for (i in 1..5) {
            feed.onPagerScroll(-1, -1, 900, now); advance(150)
            feed.onSettledPage(listOf("Wrap", "Pager", "Page$i"), null, now); advance(500)
        }
        assertEquals((1..5).map { "p:$it" }, sink.counted)
    }

    /** BUG-005: a probe at the very start of a swipe (old page still on screen) must not erase the swipe. */
    @Test fun earlyProbeDoesNotEraseTheSwipe() {
        settle("A")
        feed.onPagerScroll(-1, -1, 300, now); advance(20)
        feed.onSettledPage(listOf("A", "A-inner"), null, now); advance(80) // early probe: same page
        advance(100)                                                        // swipe ends, no more events
        feed.onSettledPage(listOf("B", "B-inner"), null, now); advance(300)
        assertEquals(listOf("p:1"), sink.counted)
    }

    /** BUG-005: scroll events that move nothing must not hold the count back. */
    @Test fun countLandsWithin260msOfThePageChange() {
        settle("A")
        feed.onPagerScroll(0, 1, 0, now); advance(40)
        feed.onPagerScroll(1, 1, 0, now)
        feed.onSettledPage(listOf("B", "B-inner"), null, now)
        val t0 = now
        repeat(10) { advance(30); feed.onPagerScroll(1, 1, 0, now) } // trailing no-op events
        advance(260 - (now - t0))
        assertEquals(listOf("p:1"), sink.counted)
    }

    // ---- BUG-006: scroll/page correlation on the real phone ----

    @Test fun tenSwipesWithLateScrollEventsGiveTenCounts() {
        settle("V0")
        for (i in 1..10) lateSwipe("V$i")
        assertEquals((1..10).map { "p:$it" }, sink.counted)
    }

    @Test fun lateSwipeIsCountedPromptly() {
        settle("A")
        feed.onSettledPage(listOf("B", "B-inner"), null, now); advance(60)
        feed.onPagerScroll(-1, -1, 900, now)
        feed.onSettledPage(listOf("B", "B-inner"), null, now)
        advance(260)
        assertEquals(listOf("p:1"), sink.counted)
    }

    @Test fun scrollEventFromChildListAndSnapshotFromParentPager() {
        // ViewPager2: the inner RecyclerView sends the scroll events, the probe reads the pager.
        settle("A")
        scrollFrom(Relation.PAGER_LIST, 0, 1); advance(40)
        feed.onMidSwipe("A", "B", now); advance(40)
        scrollFrom(Relation.PAGER_LIST, 1, 1); advance(150)
        settle("B")
        assertEquals(listOf("p:1"), sink.counted)
    }

    @Test fun sameSizeNodeInsideThePagerGivesMovementButNotIndexes() {
        settle("A")
        assertEquals(PageTracker.Scroll.MOVING, scrollFrom(Relation.INSIDE_PAGER, 7, 7)); advance(150)
        settle("B")
        assertEquals(listOf("p:1"), sink.counted)
    }

    @Test fun tabSwipeAndCommentScrollAreNotSwipeEvidence() {
        settle("A")
        assertEquals(null, scrollFrom(Relation.AROUND_PAGER, 0, 1, dy = 0, dx = 1080)) // For You / Following tabs
        assertEquals(null, scrollFrom(Relation.UNRELATED, dy = 400, sameSize = false))   // comment list
        advance(150)
        settle("B")
        assertTrue(sink.counted.isEmpty())
    }

    @Test fun childIdsChangingDuringTheSwipeCountOnce() {
        settle("A")
        feed.onPagerScroll(-1, -1, 300, now); advance(40)
        feed.onMidSwipe("A-cover", "B-cover", now); advance(40)           // mid-swipe nodes differ from the final ones
        feed.onSettledPage(listOf("B", "B-cover"), null, now); advance(100)
        feed.onPagerScroll(-1, -1, 200, now)                              // tail
        feed.onSettledPage(listOf("B", "B-player"), null, now); advance(300) // the player replaces the cover
        feed.onPagerScroll(-1, -1, 200, now); advance(800)                // a straggler after the tail
        feed.onSettledPage(listOf("B", "B-player"), null, now); advance(500)
        assertEquals(listOf("p:1"), sink.counted)
    }

    @Test fun constantWrapperWithChangingChildCountsEachSwipe() {
        feed.onSettledPage(listOf("Wrap", "Page0"), null, now); advance(500)
        for (i in 1..5) {
            feed.onSettledPage(listOf("Wrap", "Page$i"), null, now); advance(60)
            feed.onPagerScroll(-1, -1, 900, now)
            feed.onSettledPage(listOf("Wrap", "Page$i"), null, now); advance(500)
        }
        assertEquals((1..5).map { "p:$it" }, sink.counted)
    }

    @Test fun manyMovingEventsInOneSwipeGiveOneCount() {
        settle("A")
        repeat(8) { feed.onPagerScroll(-1, -1, 250, now); advance(30) }
        feed.onSettledPage(listOf("B", "B-inner"), null, now)
        repeat(5) { advance(40); feed.onPagerScroll(-1, -1, 120, now) }
        settle("B"); advance(2_000)
        assertEquals(listOf("p:1"), sink.counted)
        assertEquals(5L, feed.pages.tailEvents)
    }

    @Test fun searchViewerIsNotTheMainFeed() {
        val fc = FeedContext()
        fc.onReading(0)
        assertEquals(FeedContext.Kind.UNKNOWN, fc.stable) // one reading is not enough
        fc.onReading(1)
        assertEquals(FeedContext.Kind.OTHER, fc.stable)
        val main = FeedContext()
        main.onReading(5); main.onReading(5); main.onReading(0) // one flicker keeps the verdict
        assertEquals(FeedContext.Kind.MAIN_FEED, main.stable)
    }

    @Test fun swipesInTheSearchViewerDoNotCountButTheMainFeedDoes() {
        val fc = FeedContext()
        fc.onReading(0); fc.onReading(0)
        feed.setCountable(fc.stable == FeedContext.Kind.MAIN_FEED, "not the main feed")
        settle("S0")
        for (i in 1..3) swipe("S${i - 1}", "S$i")
        assertTrue(sink.counted.isEmpty())
        // Back to the For You feed.
        engine.onOffSurface(now); advance(3_000)
        engine.onSurface("com.zhiliaoapp.musically", "tiktok_feed", now)
        feed.onEnterSurface()
        fc.reset(); fc.onReading(5); fc.onReading(5)
        feed.setCountable(fc.stable == FeedContext.Kind.MAIN_FEED)
        settle("F0")
        swipe("F0", "F1")
        assertEquals(1, sink.counted.size)
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

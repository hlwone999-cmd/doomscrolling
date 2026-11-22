package app.tidemark

import app.tidemark.detect.PageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTrackerTest {

    @Test fun firstPageIsZeroAndNotAChange() {
        val t = PageTracker<String>()
        val r = t.onSettled(listOf("A"), null, 0)
        assertEquals(0, r.position); assertFalse(r.changed)
    }

    @Test fun adapterIndexGivesStepAndDirection() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), 4, 0)
        t.onScroll(4, 5, 0, 100); t.onScroll(5, 5, 0, 200)
        val r = t.onSettled(listOf("B"), null, 300)
        assertTrue(r.changed); assertEquals(1, r.position)
        t.onScroll(5, 4, 0, 400); t.onScroll(4, 4, 0, 500)
        assertEquals(0, t.onSettled(listOf("A"), null, 600).position)
    }

    @Test fun constantWrapperWithChangingIndexStillMoves() {
        // Signature never changes (one wrapper), but the pager's index does.
        val t = PageTracker<String>()
        t.onSettled(listOf("W"), null, 0)
        t.onScroll(0, 0, 0, 10) // establishes index 0 after the first page
        t.onSettled(listOf("W"), null, 400)
        t.onScroll(0, 1, 0, 500); t.onScroll(1, 1, 0, 600)
        val r = t.onSettled(listOf("W"), null, 700)
        assertTrue(r.changed); assertEquals(1, r.position)
    }

    @Test fun innerContainerChangeCountsWhenWrapperIsConstant() {
        val t = PageTracker<String>()
        t.onSettled(listOf("W", "page1"), null, 0)
        t.onMidSwipe("page1", "page2", 100)
        val r = t.onSettled(listOf("W", "page2"), null, 200)
        assertTrue(r.changed); assertEquals(1, r.position)
    }

    @Test fun midSwipeTellsDirection() {
        val t = PageTracker<String>()
        t.onSettled(listOf("B"), null, 0)
        t.onMidSwipe("A", "B", 100) // A above, B below; we end on A: previous video
        assertEquals(-1, t.onSettled(listOf("A"), null, 200).position)
    }

    @Test fun noMovementNoChange() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), null, 0)
        val r = t.onSettled(listOf("Z"), null, 100)
        assertFalse(r.changed); assertEquals(0, r.position)
        assertTrue(r.reason.contains("without a swipe"))
    }

    @Test fun movementExpiresAfterTheWindow() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), null, 0)
        t.onScroll(-1, -1, 100, 100)
        val r = t.onSettled(listOf("B"), null, 100 + PageTracker.MOTION_WINDOW_MS + 100)
        assertFalse(r.changed)
    }

    /** BUG-005: an early same-page probe does not clear a swipe that is still going. */
    @Test fun earlySamePageProbeKeepsTheSwipe() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), null, 0)
        t.onScroll(-1, -1, 300, 1_000)
        assertFalse(t.onSettled(listOf("A"), null, 1_020).changed) // swipe just started
        val r = t.onSettled(listOf("B"), null, 1_200)              // swipe finished
        assertTrue(r.changed); assertEquals(1, r.position)
    }

    @Test fun noOpScrollIsNoiseAndNewIndexIsReported() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), 3, 0)
        assertEquals(PageTracker.Scroll.NOISE, t.onScroll(3, 3, 0, 10))
        assertEquals(PageTracker.Scroll.MOVING, t.onScroll(3, 4, 0, 20))
        assertEquals(PageTracker.Scroll.NEW_INDEX, t.onScroll(4, 4, 0, 30))
    }

    @Test fun countersSurviveReset() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), null, 0)
        t.onScroll(-1, -1, 100, 10)
        t.onSettled(listOf("B"), null, 20)
        t.reset()
        assertEquals(1L, t.scrollEvents); assertEquals(1, t.pageChanges); assertEquals(0, t.position)
    }
}

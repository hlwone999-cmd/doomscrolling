package app.tidemark

import app.tidemark.detect.PageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTrackerTest {

    @Test fun firstPageIsZeroAndNotAChange() {
        val t = PageTracker<String>()
        val r = t.onSettled(listOf("A"), null)
        assertEquals(0, r.position); assertFalse(r.changed)
    }

    @Test fun adapterIndexGivesStepAndDirection() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), 4)
        t.onScroll(4, 5, 0); t.onScroll(5, 5, 0)
        val r = t.onSettled(listOf("B"), null)
        assertTrue(r.changed); assertEquals(1, r.position)
        t.onScroll(5, 4, 0); t.onScroll(4, 4, 0)
        assertEquals(0, t.onSettled(listOf("A"), null).position)
    }

    @Test fun constantWrapperWithChangingIndexStillMoves() {
        // Signature never changes (one wrapper), but the pager's index does.
        val t = PageTracker<String>()
        t.onSettled(listOf("W"), null)
        t.onScroll(0, 0, 0) // establishes index 0 after the first page
        t.onSettled(listOf("W"), null)
        t.onScroll(0, 1, 0); t.onScroll(1, 1, 0)
        val r = t.onSettled(listOf("W"), null)
        assertTrue(r.changed); assertEquals(1, r.position)
    }

    @Test fun innerContainerChangeCountsWhenWrapperIsConstant() {
        val t = PageTracker<String>()
        t.onSettled(listOf("W", "page1"), null)
        t.onMidSwipe("page1", "page2")
        val r = t.onSettled(listOf("W", "page2"), null)
        assertTrue(r.changed); assertEquals(1, r.position)
    }

    @Test fun midSwipeTellsDirection() {
        val t = PageTracker<String>()
        t.onSettled(listOf("B"), null)
        t.onMidSwipe("A", "B") // A above, B below; we end on A: previous video
        assertEquals(-1, t.onSettled(listOf("A"), null).position)
    }

    @Test fun noMovementNoChange() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), null)
        val r = t.onSettled(listOf("Z"), null)
        assertFalse(r.changed); assertEquals(0, r.position)
        assertTrue(r.reason.contains("without a swipe"))
    }

    @Test fun countersSurviveReset() {
        val t = PageTracker<String>()
        t.onSettled(listOf("A"), null)
        t.onScroll(-1, -1, 100)
        t.onSettled(listOf("B"), null)
        t.reset()
        assertEquals(1L, t.scrollEvents); assertEquals(1, t.pageChanges); assertEquals(0, t.position)
    }
}

package app.tidemark

import app.tidemark.detect.PositionTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Containers are plain strings here; in the app they are AccessibilityNodeInfo. */
class PositionTrackerTest {

    @Test fun firstVideoIsPositionZero() {
        val t = PositionTracker<String>()
        assertEquals(0 to false, t.update("A", listOf("A", "B")))
    }

    @Test fun forwardSwipesCountUp() {
        val t = PositionTracker<String>()
        t.update("A", listOf("A", "B"))
        assertEquals(1 to true, t.update("B", listOf("A", "B", "C")))
        assertEquals(2 to true, t.update("C", listOf("B", "C", "D")))
    }

    @Test fun swipingBackCountsDown() {
        val t = PositionTracker<String>()
        t.update("A", listOf("A", "B"))
        t.update("B", listOf("A", "B", "C"))
        assertEquals(0 to true, t.update("A", listOf("A", "B", "C")))
    }

    @Test fun sameContainerIsNotAMove() {
        val t = PositionTracker<String>()
        t.update("A", listOf("A", "B"))
        val (pos, moved) = t.update("A", listOf("A", "B"))
        assertEquals(0, pos); assertFalse(moved)
    }

    @Test fun recycledContainerUsesPreviousOrder() {
        // A was recycled out of the list; previous order still knows B came after A.
        val t = PositionTracker<String>()
        t.update("A", listOf("A", "B"))
        assertEquals(1 to true, t.update("B", listOf("B", "C")))
    }

    @Test fun flingSkipsSeveral() {
        val t = PositionTracker<String>()
        t.update("A", listOf("A", "B", "C", "D"))
        assertEquals(3 to true, t.update("D", listOf("A", "B", "C", "D")))
    }

    @Test fun unknownJumpIsOneForward() {
        val t = PositionTracker<String>()
        t.update("A", listOf("A"))
        val (pos, moved) = t.update("Z", listOf("Z"))
        assertEquals(1, pos); assertTrue(moved)
    }

    @Test fun resetStartsOver() {
        val t = PositionTracker<String>()
        t.update("A", listOf("A", "B")); t.update("B", listOf("A", "B"))
        t.reset()
        assertEquals(0 to false, t.update("B", listOf("A", "B")))
    }
}

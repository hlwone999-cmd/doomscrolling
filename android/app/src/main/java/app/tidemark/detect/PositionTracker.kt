package app.tidemark.detect

/**
 * Gives each video a position number (0, 1, 2, ...) in the order the user
 * reaches it, using only the identity of on-screen containers. No text,
 * caption or username is read.
 *
 * Input on each probe: [current] identifies the container that fills the
 * pager now; [order] lists the pager's attached children top to bottom.
 * When the current container changes, the move is the difference between
 * the new and old containers' positions in the child order (+1 forward, -1
 * back, more after a fling). If the old one is no longer attached, the order
 * seen at the previous probe is used; failing that, one step forward.
 *
 * Identities are compared with equals(): AccessibilityNodeInfo compares the
 * underlying view and window, so the same on-screen container matches across
 * probes. Pure Kotlin, unit-tested in PositionTrackerTest.
 */
class PositionTracker<T : Any> {
    private var lastCurrent: T? = null
    private var lastOrder: List<T> = emptyList()
    var position: Int = 0
        private set

    fun reset() {
        lastCurrent = null
        lastOrder = emptyList()
        position = 0
    }

    /** Returns the position of [current] and whether it differs from the previous probe's. */
    fun update(current: T, order: List<T>): Pair<Int, Boolean> {
        val prev = lastCurrent
        var moved = false
        if (prev != null && current != prev) {
            val step = stepBetween(prev, current, order).takeIf { it != 0 }
                ?: stepBetween(prev, current, lastOrder).takeIf { it != 0 }
                ?: 1
            position += step
            moved = true
        }
        lastCurrent = current
        if (order.isNotEmpty()) lastOrder = order
        return position to moved
    }

    private fun stepBetween(prev: T, current: T, order: List<T>): Int {
        val a = order.indexOf(prev)
        val b = order.indexOf(current)
        return if (a >= 0 && b >= 0) b - a else 0
    }
}

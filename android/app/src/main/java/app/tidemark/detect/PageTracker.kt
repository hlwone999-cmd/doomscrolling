package app.tidemark.detect

/**
 * Decides when the short-video pager has moved to another video, and gives
 * each video a position number in the session (0, 1, 2, ...). Pure Kotlin,
 * unit-tested in PageTrackerTest and FeedPipelineTest.
 *
 * It never reads text. Signals, all structural:
 *  - adapter position: from the scroll event (fromIndex == toIndex, set by
 *    ViewPager and RecyclerView) or the item's collection row;
 *  - page signature: identities of the full-screen page containers;
 *  - movement evidence: a scroll event from the pager that actually moves
 *    (delta or index change), or a probe that saw two pages mid-swipe.
 *
 * A new video needs BOTH recent movement evidence and a changed page (index
 * or signature). Likes, comment sheets, menus and re-layouts (no movement)
 * never count; a bounce back to the same page never counts.
 *
 * Movement evidence lives for [MOTION_WINDOW_MS] and is only cleared on a
 * "same page" probe once the pager has been quiet for [QUIET_MS]: a probe
 * that lands at the very start of a swipe (old page still on screen) must not
 * erase the evidence the finished swipe needs (BUG-005).
 */
class PageTracker<T : Any> {

    data class Result(val position: Int, val changed: Boolean, val reason: String)

    /** What a scroll event told us. */
    enum class Scroll { NOISE, MOVING, NEW_INDEX }

    var position: Int = 0
        private set
    var previousPosition: Int? = null
        private set

    /** Diagnostics counters; they survive reset() so they cover the whole service run. */
    var scrollEvents: Long = 0
        private set
    var pageChanges: Int = 0
        private set
    var lastMotionAt: Long? = null
        private set
    var lastPageChangeAt: Long? = null
        private set

    private var lastSignature: List<T>? = null
    private var lastIndex: Int? = null
    private var scrollIndex: Int? = null
    private var deltaSum = 0
    private var midUpper: T? = null
    private var midLower: T? = null

    /** New session: positions start again at 0. */
    fun reset() {
        position = 0
        previousPosition = null
        lastSignature = null
        lastIndex = null
        clearMotion()
    }

    /**
     * A scroll event from the pager (or a container of its size). Returns NOISE
     * for events that move nothing (same index, no delta): they must not delay
     * or trigger counting.
     */
    fun onScroll(fromIndex: Int, toIndex: Int, deltaY: Int, now: Long): Scroll {
        scrollEvents++
        val settledIndex = if (fromIndex >= 0 && fromIndex == toIndex) fromIndex else null
        val reference = scrollIndex ?: lastIndex
        val newIndex = settledIndex != null && reference != null && settledIndex != reference
        val moving = deltaY != 0 || (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) || newIndex
        if (settledIndex != null) scrollIndex = settledIndex
        if (!moving) return Scroll.NOISE
        lastMotionAt = now
        deltaSum += deltaY
        return if (newIndex) Scroll.NEW_INDEX else Scroll.MOVING
    }

    /** A probe found two pages sharing the screen: a swipe is in progress. */
    fun onMidSwipe(upper: T, lower: T, now: Long) {
        lastMotionAt = now
        midUpper = upper
        midLower = lower
    }

    /** A probe found one page filling the pager. */
    fun onSettled(signature: List<T>, row: Int?, now: Long): Result {
        val index = row ?: scrollIndex
        val prevSig = lastSignature
        if (prevSig == null) {
            lastSignature = signature
            lastIndex = index
            clearMotion()
            return Result(position, false, "video on entry")
        }
        val prevIndex = lastIndex
        val motionAt = lastMotionAt
        val moved = motionAt != null && now - motionAt <= MOTION_WINDOW_MS
        val sigChanged = signature != prevSig
        val idxChanged = index != null && prevIndex != null && index != prevIndex
        if (!sigChanged && !idxChanged) {
            // Remember the index the pager reported, so the next swipe can be compared with it.
            if (index != null) lastIndex = index
            // Only forget the movement once the pager is quiet: this probe may be the
            // start of a swipe whose new page has not appeared yet.
            if (motionAt == null || now - motionAt >= QUIET_MS) clearMotion()
            return Result(position, false, if (moved) "same video (swipe not finished or bounced back)" else "same video")
        }
        if (!moved) {
            // Something on the page was rebuilt (like animation, menu, re-layout) without a swipe.
            lastSignature = signature
            if (index != null) lastIndex = index
            return Result(position, false, "page structure changed without a swipe: not a new video")
        }
        val (step, how) = when {
            idxChanged -> (index!! - prevIndex!!) to "adapter position $prevIndex → $index"
            deltaSum != 0 -> (if (deltaSum > 0) 1 else -1) to "scroll direction"
            midLower != null && signature.contains(midLower!!) -> 1 to "next page came from below"
            midUpper != null && signature.contains(midUpper!!) -> -1 to "previous page came from above"
            else -> 1 to "direction unknown, taken as next"
        }
        previousPosition = position
        position += step
        pageChanges++
        lastPageChangeAt = now
        lastSignature = signature
        lastIndex = index
        clearMotion()
        return Result(position, true, "page changed: $how")
    }

    private fun clearMotion() {
        lastMotionAt = null
        deltaSum = 0
        midUpper = null
        midLower = null
        scrollIndex = null
    }

    companion object {
        /** How long a swipe counts as evidence for the page that follows it. */
        const val MOTION_WINDOW_MS = 1_500L
        /** A same-page probe forgets the movement only after this much quiet. */
        const val QUIET_MS = 300L
    }
}

/**
 * How the service feeds the pager into the counting engine. Kept free of
 * Android types so the whole path (swipe -> page change -> count) runs in
 * JVM tests with the real engine.
 */
class FeedPipeline<T : Any>(val engine: CountingEngine) {
    val pages = PageTracker<T>()

    /** Keys are positions in the session; the engine dedupes them (no recount). */
    fun key(position: Int) = "p:$position"

    fun onNewSession() = pages.reset()

    /**
     * Scroll event from the pager itself. Smaller scrollers (comments, captions)
     * must not call this. Only real movement counts as a swipe; NEW_INDEX tells
     * the service to probe at once.
     */
    fun onPagerScroll(fromIndex: Int, toIndex: Int, deltaY: Int, now: Long): PageTracker.Scroll {
        val s = pages.onScroll(fromIndex, toIndex, deltaY, now)
        if (s != PageTracker.Scroll.NOISE) engine.onScroll(now)
        return s
    }

    /** Two pages visible: nothing is settled, so nothing counts yet. */
    fun onMidSwipe(upper: T, lower: T, now: Long) {
        pages.onMidSwipe(upper, lower, now)
        engine.onScroll(now)
        engine.onObserve(null, now)
    }

    /** One page fills the pager. Returns the tracker's decision for diagnostics. */
    fun onSettledPage(signature: List<T>, row: Int?, now: Long): PageTracker.Result {
        val r = pages.onSettled(signature, row, now)
        if (r.changed) engine.onScroll(now)
        engine.onObserve(key(r.position), now)
        return r
    }
}

package app.tidemark.detect

/**
 * Decides when the short-video pager has moved to another video, and gives
 * each video a position number in the session (0, 1, 2, ...). Pure Kotlin,
 * unit-tested in PageTrackerTest and FeedPipelineTest.
 *
 * It never reads text. Signals, all structural:
 *  - adapter position: from the scroll event (fromIndex == toIndex, set by
 *    ViewPager and RecyclerView) or the item's collection row;
 *  - page signature: identities of the full-screen page containers (the
 *    active page and its full-screen child, so a constant wrapper still
 *    changes underneath);
 *  - movement evidence: a scroll event from the pager, or a probe that saw
 *    two pages sharing the screen mid-swipe.
 *
 * A new video needs BOTH movement evidence and a changed page (index or
 * signature). So a like, a comment sheet, a share menu or a re-layout (no
 * swipe) never counts, and a swipe that bounces back (same page) never
 * counts. Direction comes from the adapter positions, then the scroll delta,
 * then which mid-swipe page became current; only if none of those is known
 * is a confirmed page change taken as "next".
 */
class PageTracker<T : Any> {

    data class Result(val position: Int, val changed: Boolean, val reason: String)

    var position: Int = 0
        private set
    var previousPosition: Int? = null
        private set

    /** Diagnostics counters; they survive reset() so they cover the whole service run. */
    var scrollEvents: Long = 0
        private set
    var pageChanges: Int = 0
        private set

    private var lastSignature: List<T>? = null
    private var lastIndex: Int? = null
    private var scrollIndex: Int? = null
    private var moved = false
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

    /** A scroll event from the pager (or a container of its size). */
    fun onScroll(fromIndex: Int, toIndex: Int, deltaY: Int) {
        scrollEvents++
        moved = true
        deltaSum += deltaY
        if (fromIndex >= 0 && fromIndex == toIndex) scrollIndex = fromIndex
    }

    /** A probe found two pages sharing the screen: a swipe is in progress. */
    fun onMidSwipe(upper: T, lower: T) {
        moved = true
        midUpper = upper
        midLower = lower
    }

    /** A probe found one page filling the pager. */
    fun onSettled(signature: List<T>, row: Int?): Result {
        val index = row ?: scrollIndex
        val prevSig = lastSignature
        if (prevSig == null) {
            lastSignature = signature
            lastIndex = index
            clearMotion()
            return Result(position, false, "first video on screen")
        }
        val prevIndex = lastIndex
        val sigChanged = signature != prevSig
        val idxChanged = index != null && prevIndex != null && index != prevIndex
        if (!sigChanged && !idxChanged) {
            val why = if (moved) "same video after a scroll (bounce or small drag)" else "same video"
            // Remember the index the pager reported, so the next swipe can be compared with it.
            if (index != null) lastIndex = index
            clearMotion()
            return Result(position, false, why)
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
        lastSignature = signature
        lastIndex = index
        clearMotion()
        return Result(position, true, "page changed: $how")
    }

    private fun clearMotion() {
        moved = false
        deltaSum = 0
        midUpper = null
        midLower = null
        scrollIndex = null
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

    /** Scroll event from the pager itself. Smaller scrollers (comments, captions) must not call this. */
    fun onPagerScroll(fromIndex: Int, toIndex: Int, deltaY: Int, now: Long) {
        pages.onScroll(fromIndex, toIndex, deltaY)
        engine.onScroll(now)
    }

    /** Two pages visible: nothing is settled, so nothing counts yet. */
    fun onMidSwipe(upper: T, lower: T, now: Long) {
        pages.onMidSwipe(upper, lower)
        engine.onScroll(now)
        engine.onObserve(null, now)
    }

    /** One page fills the pager. Returns the tracker's decision for diagnostics. */
    fun onSettledPage(signature: List<T>, row: Int?, now: Long): PageTracker.Result {
        val r = pages.onSettled(signature, row)
        if (r.changed) engine.onScroll(now)
        engine.onObserve(key(r.position), now)
        return r
    }
}

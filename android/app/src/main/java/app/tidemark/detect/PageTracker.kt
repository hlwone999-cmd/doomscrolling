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
 * A new video needs BOTH movement evidence and a changed page (index or
 * signature). Likes, comment sheets, menus and re-layouts (no movement) never
 * count; a bounce back to the same page never counts.
 *
 * The two signals are correlated in a time window, in either order:
 *  - evidence first (the usual order): it lives for [MOTION_WINDOW_MS] and a
 *    "same page" probe does NOT erase it, because that probe may land at the
 *    start of the swipe while the old page still fills the screen (BUG-005);
 *  - page first: some pagers (TikTok on a Realme phone, BUG-006) deliver their
 *    scroll event after the new page is already on screen. The new page is
 *    held as pending for [PENDING_WINDOW_MS]; evidence arriving in that time
 *    confirms it, otherwise it was a rebuild and becomes the new baseline.
 *
 * Scroll events in the [TAIL_MS] after a counted page change are the tail of
 * that same swipe (settle animation): they are not new evidence, so one swipe
 * that sends many events is counted once.
 */
class PageTracker<T : Any> {

    data class Result(val position: Int, val changed: Boolean, val reason: String)

    /** What a scroll event told us. */
    enum class Scroll {
        /** Moves nothing (same index, no delta): ignored. */
        NOISE,
        /** The end of the swipe that was just counted: not new evidence. */
        TAIL,
        /** Real movement. */
        MOVING,
        /** The pager reports a different adapter position: the page has changed. */
        NEW_INDEX;

        val isMotion: Boolean get() = this == MOVING || this == NEW_INDEX
    }

    var position: Int = 0
        private set
    var previousPosition: Int? = null
        private set

    /** Diagnostics counters; they survive reset() so they cover the whole service run. */
    var scrollEvents: Long = 0
        private set
    var tailEvents: Long = 0
        private set
    var pageChanges: Int = 0
        private set
    var lateConfirmations: Int = 0
        private set
    var rejectedChanges: Int = 0
        private set
    var lastMotionAt: Long? = null
        private set
    var lastPageChangeAt: Long? = null
        private set
    /** When a changed page was first seen without movement evidence; null when none is waiting. */
    var pendingSince: Long? = null
        private set

    private var lastSignature: List<T>? = null
    private var lastIndex: Int? = null
    private var scrollIndex: Int? = null
    private var deltaSum = 0
    private var midUpper: T? = null
    private var midLower: T? = null
    private var pendingSignature: List<T>? = null
    private var pendingIndex: Int? = null
    /** A counted change replaced the top-level page: deeper-only changes are then the same video. */
    private var topVaries = false

    /** New session: positions start again at 0. */
    fun reset() {
        position = 0
        previousPosition = null
        rebase()
    }

    /**
     * Arriving on the video screen again (or the app replaced its pager): the
     * next settled page is the arrival page, not a change. Positions are kept.
     */
    fun rebase() {
        lastSignature = null
        lastIndex = null
        topVaries = false
        clearMotion()
        clearPending()
    }

    /**
     * A scroll event from the pager (or a container structurally tied to it).
     * Pass -1 indexes when the source's indexes don't belong to the pager.
     */
    fun onScroll(fromIndex: Int, toIndex: Int, deltaY: Int, now: Long): Scroll {
        scrollEvents++
        val settledIndex = if (fromIndex >= 0 && fromIndex == toIndex) fromIndex else null
        val reference = scrollIndex ?: lastIndex
        val newIndex = settledIndex != null && reference != null && settledIndex != reference
        val moving = deltaY != 0 || (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) || newIndex
        if (settledIndex != null) scrollIndex = settledIndex
        if (!moving) return Scroll.NOISE
        val changedAt = lastPageChangeAt
        if (changedAt != null && now >= changedAt && now - changedAt < TAIL_MS) {
            tailEvents++
            return Scroll.TAIL
        }
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
        if (lastSignature == null) {
            lastSignature = signature
            lastIndex = index
            clearMotion()
            clearPending()
            return Result(position, false, "video on entry")
        }
        val motionAt = lastMotionAt
        val moved = motionAt != null && now - motionAt in 0..MOTION_WINDOW_MS

        // A page seen earlier without evidence: confirmed by evidence that followed it
        // closely, otherwise it was a rebuild and becomes the baseline.
        val since = pendingSince
        var late = false
        var expired = ""
        if (since != null) {
            val followed = motionAt != null && motionAt >= since && motionAt - since <= PENDING_WINDOW_MS
            if (followed) {
                late = true
            } else if (now - since > PENDING_WINDOW_MS) {
                lastSignature = pendingSignature
                pendingIndex?.let { lastIndex = it }
                rejectedChanges++
                clearPending()
                expired = "; an earlier page change had no swipe evidence: not counted"
            }
        }

        val prevSig = lastSignature!!
        val prevIndex = lastIndex
        val sigChanged = signature != prevSig
        val idxChanged = index != null && prevIndex != null && index != prevIndex
        val topChanged = signature.firstOrNull() != prevSig.firstOrNull()
        if (sigChanged && !topChanged && !idxChanged && topVaries) {
            // Same top-level page, something inside it was rebuilt (cover -> player, like
            // animation). This pager's pages are its top-level children, so not a new video.
            lastSignature = signature
            if (index != null) lastIndex = index
            clearPending()
            return Result(position, false, "same video (content inside the page was rebuilt)$expired")
        }
        if (!sigChanged && !idxChanged) {
            // Remember the index the pager reported, so the next swipe can be compared with it.
            if (index != null) lastIndex = index
            clearPending() // back on the baseline page: nothing is waiting
            // Evidence is kept: this probe may be the start of a swipe whose new page is not up yet.
            return Result(position, false, (if (moved) "same video (swipe not finished or bounced back)" else "same video") + expired)
        }
        if (!moved) {
            if (pendingSince == null) pendingSince = now
            pendingSignature = signature
            pendingIndex = index
            val waited = now - pendingSince!!
            return Result(position, false,
                "page structure changed without a swipe: not a new video yet (waiting ${PENDING_WINDOW_MS - waited} ms more for swipe evidence)")
        }
        val (step, how) = when {
            idxChanged -> (index!! - prevIndex!!) to "adapter position $prevIndex → $index"
            deltaSum != 0 -> (if (deltaSum > 0) 1 else -1) to "scroll direction"
            midLower != null && signature.contains(midLower!!) -> 1 to "next page came from below"
            midUpper != null && signature.contains(midUpper!!) -> -1 to "previous page came from above"
            else -> 1 to "direction unknown, taken as next"
        }
        val lateNote = if (late) ", swipe evidence ${motionAt!! - since!!} ms after the new page" else ""
        if (late) lateConfirmations++
        if (topChanged) topVaries = true
        previousPosition = position
        position += step
        pageChanges++
        lastPageChangeAt = now
        lastSignature = signature
        lastIndex = index
        clearMotion()
        clearPending()
        return Result(position, true, "page changed: $how$lateNote")
    }

    private fun clearMotion() {
        lastMotionAt = null
        deltaSum = 0
        midUpper = null
        midLower = null
        scrollIndex = null
    }

    private fun clearPending() {
        pendingSince = null
        pendingSignature = null
        pendingIndex = null
    }

    companion object {
        /** How long a swipe counts as evidence for the page that follows it. */
        const val MOTION_WINDOW_MS = 1_500L
        /** How long a page seen before its swipe evidence waits for that evidence. */
        const val PENDING_WINDOW_MS = 1_000L
        /** Scroll events this soon after a counted change belong to that same swipe. */
        const val TAIL_MS = 300L
    }
}

/**
 * How the service feeds the pager into the counting engine. Kept free of
 * Android types so the whole path (swipe -> page change -> count) runs in
 * JVM tests with the real engine.
 */
class FeedPipeline<T : Any>(val engine: CountingEngine) {
    val pages = PageTracker<T>()

    /**
     * False while the screen is not one whose swipes count (for apps whose
     * rule says so: a video opened from search, a profile or a link). Pages
     * are still tracked, so nothing is counted later for them either.
     */
    var countable: Boolean = true
        private set
    private var notCountableWhy: String = ""

    fun setCountable(allowed: Boolean, why: String = "") {
        countable = allowed
        notCountableWhy = why
    }

    /** Keys are positions in the session; the engine dedupes them (no recount). */
    fun key(position: Int) = "p:$position"

    fun onNewSession() = pages.reset()

    /** Back on the video screen, or a different pager: the next page is an arrival. */
    fun onEnterSurface() = pages.rebase()

    /**
     * Scroll event from the pager (or a container tied to it). Smaller
     * scrollers (comments, captions) must not call this. Only real movement
     * counts as a swipe; NEW_INDEX tells the service to probe at once.
     */
    fun onPagerScroll(fromIndex: Int, toIndex: Int, deltaY: Int, now: Long): PageTracker.Scroll {
        val s = pages.onScroll(fromIndex, toIndex, deltaY, now)
        if (s.isMotion && countable) engine.onScroll(now)
        return s
    }

    /** Two pages visible: nothing is settled, so nothing counts yet. */
    fun onMidSwipe(upper: T, lower: T, now: Long) {
        pages.onMidSwipe(upper, lower, now)
        if (countable) engine.onScroll(now)
        engine.onObserve(null, now)
    }

    /** One page fills the pager. Returns the tracker's decision for diagnostics. */
    fun onSettledPage(signature: List<T>, row: Int?, now: Long): PageTracker.Result {
        var r = pages.onSettled(signature, row, now)
        if (r.changed) {
            if (countable) engine.onScroll(now)
            else r = r.copy(reason = "${r.reason}; not counted: $notCountableWhy")
        }
        engine.onObserve(key(r.position), now)
        return r
    }
}

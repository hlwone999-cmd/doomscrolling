package app.tidemark.detect

/**
 * Which scroll events are swipes of the watched pager (BUG-006). Pure Kotlin.
 *
 * The node that sends TYPE_VIEW_SCROLLED is not always the node the detector
 * holds: ViewPager2 scrolls an inner RecyclerView, some apps wrap their pager
 * in a same-size container, and TikTok also has a full-screen horizontal tab
 * pager around the video pager. The service works out how the event's source
 * relates to the held pager; this decides what the event may be used for.
 * IDs need not match exactly: structurally tied, same-size containers count.
 */
object ScrollSource {

    enum class Relation(val label: String) {
        PAGER("the held pager"),
        PAGER_LIST("the pager's own list"),
        INSIDE_PAGER("inside the pager"),
        AROUND_PAGER("a container around the pager"),
        SIZE_ONLY("unrelated node of pager size"),
        UNRELATED("unrelated smaller scroller"),
    }

    data class Verdict(
        /** Use the event as movement evidence. */
        val accepted: Boolean,
        /** fromIndex/toIndex are the pager's adapter positions. */
        val indexesUsable: Boolean,
        val why: String,
    )

    /**
     * @param sameSize the source covers >= 80 % of the pager's height and >= 90 % of its width
     * @param horizontalOnly the event moved sideways only (tabs, carousels, stories)
     */
    fun judge(relation: Relation, sameSize: Boolean, horizontalOnly: Boolean): Verdict = when {
        horizontalOnly -> Verdict(false, false, "sideways scroll (tabs or carousel): not a video swipe")
        relation == Relation.PAGER || relation == Relation.PAGER_LIST ->
            Verdict(true, true, "from ${relation.label}")
        relation == Relation.UNRELATED || !sameSize ->
            Verdict(false, false, "from ${relation.label}: comments, captions or menus, not a swipe")
        // Same size and tied to the pager (or of pager size): movement yes, but its
        // indexes count something else (pages of a carousel, tabs), so not used.
        else -> Verdict(true, false, "from ${relation.label} (same size): movement only, indexes ignored")
    }
}

/**
 * Main feed or not, from the bottom navigation bar (BUG-006). One reading
 * can be wrong (the bar is laid out a moment after the window, or hidden
 * mid-animation), so the kind only changes after two readings in a row agree.
 */
class FeedContext {
    enum class Kind { UNKNOWN, MAIN_FEED, OTHER }

    var stable: Kind = Kind.UNKNOWN
        private set
    var lastItems: Int = -1
        private set
    var readings: Int = 0
        private set
    private var last: Kind? = null

    fun reset() {
        stable = Kind.UNKNOWN
        last = null
        lastItems = -1
        readings = 0
    }

    /** [navItems]: clickable items in a row along the bottom edge, or -1 if the window was unreadable. */
    fun onReading(navItems: Int): Kind {
        if (navItems < 0) return stable
        readings++
        lastItems = navItems
        val k = if (navItems >= MIN_NAV_ITEMS) Kind.MAIN_FEED else Kind.OTHER
        if (k == last) stable = k
        last = k
        return stable
    }

    fun describe(): String = when (stable) {
        Kind.UNKNOWN -> "checking (bottom navigation items: ${if (lastItems < 0) "?" else lastItems})"
        Kind.MAIN_FEED -> "main feed (bottom navigation, $lastItems items)"
        Kind.OTHER -> "not the main feed: no bottom navigation ($lastItems items; search, profile or detail view)"
    }

    companion object {
        const val MIN_NAV_ITEMS = 4
    }
}

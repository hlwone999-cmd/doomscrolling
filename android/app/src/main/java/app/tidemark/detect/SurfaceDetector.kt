package app.tidemark.detect

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/**
 * Finds the short-video pager on screen and the video currently filling it.
 *
 * Reads structure only: bounds, class names, view ids, scroll actions and
 * collection positions. It never reads text, content descriptions, captions,
 * usernames or messages.
 *
 * A short-video screen is a container that
 *   - fills the window (>= 80 % height, >= 90 % width),
 *   - is pager-like (scrollable, has vertical scroll/page actions, or is a
 *     RecyclerView/ViewPager), and not horizontal-only (Stories, carousels),
 *   - has a current child that fills it (>= 95 % of its height): one video per
 *     page. A feed (Instagram Home, YouTube Home) fails this: its items are
 *     shorter than the screen.
 * A view-id hint from the rules (e.g. "clips_viewer") makes a candidate win,
 * but the structure must still match, so a wrong id guess can't count.
 */
class SurfaceDetector {

    class Match(
        val pager: AccessibilityNodeInfo,
        val bounds: Rect,
        val byHint: Boolean,
        val className: String,
        val viewId: String?,
    ) {
        /** For diagnostics only: class and id entry, never text. */
        fun describe(): String =
            "${className.substringAfterLast('.')}${viewId?.let { " #" + it.substringAfter(":id/") } ?: ""}" +
                if (byHint) " (id hint)" else " (structure)"
    }

    /** What a probe sees in the pager. Structure only. */
    sealed interface Snapshot {
        /** One page fills the pager: identities of its full-screen containers, and its adapter row if exposed. */
        class Settled(val signature: List<AccessibilityNodeInfo>, val row: Int?) : Snapshot
        /** Two pages share the screen: a swipe is in progress. */
        class MidSwipe(val upper: AccessibilityNodeInfo, val lower: AccessibilityNodeInfo) : Snapshot
    }

    fun find(root: AccessibilityNodeInfo, rule: AppRule): Match? {
        val window = Rect().also { root.getBoundsInScreen(it) }
        if (window.isEmpty) return null
        var hinted: Match? = null
        var best: Match? = null
        var bestScore = Int.MIN_VALUE
        walk(root, MAX_NODES) { node, bounds, depth ->
            if (!fillsWindow(bounds, window)) return@walk true
            val hint = rule.hintMatches(node.viewIdResourceName)
            if (!hint && !rule.genericFallback) return@walk true
            if (!isPagerLike(node) || isHorizontalOnly(node)) return@walk true
            if (!hasFullPageChild(node)) return@walk true
            val m = Match(node, bounds, hint, node.className?.toString() ?: "?", node.viewIdResourceName)
            if (hint && hinted == null) hinted = m
            // Prefer the innermost pager, and one holding several pages (current + preloaded
            // neighbours) over a wrapper with a single full-size child.
            val score = depth * 10 + (if (listNode(node).childCount >= 2) 100 else 0)
            if (score > bestScore) { bestScore = score; best = m }
            true
        }
        return hinted ?: best
    }

    /** The scroll event's source covers the pager (>= 80 % height, >= 90 % width). */
    fun sameSize(source: AccessibilityNodeInfo?, match: Match): Boolean {
        if (source == null) return false
        val b = Rect().also { source.getBoundsInScreen(it) }
        return b.height() >= match.bounds.height() * 0.8f && b.width() >= match.bounds.width() * 0.9f
    }

    /**
     * How a scroll event's source relates to the held pager (BUG-006): the pager
     * itself, its inner list (ViewPager2's RecyclerView), a node inside it, a
     * container around it, or unrelated. Compares node identity (window and
     * node id) by walking parents a few levels; reads no text.
     */
    fun scrollRelation(source: AccessibilityNodeInfo?, match: Match): ScrollSource.Relation {
        if (source == null) return ScrollSource.Relation.UNRELATED
        if (source == match.pager) return ScrollSource.Relation.PAGER
        if (source == listNode(match.pager)) return ScrollSource.Relation.PAGER_LIST
        var p = source.parent
        var steps = 0
        while (p != null && steps < PARENT_STEPS) {
            if (p == match.pager) return ScrollSource.Relation.INSIDE_PAGER
            p = p.parent; steps++
        }
        var q = match.pager.parent
        steps = 0
        while (q != null && steps < PARENT_STEPS) {
            if (q == source) return ScrollSource.Relation.AROUND_PAGER
            q = q.parent; steps++
        }
        return if (sameSize(source, match)) ScrollSource.Relation.SIZE_ONLY else ScrollSource.Relation.UNRELATED
    }

    /** Class and view-id entry of a node, for diagnostics. Never text. */
    fun describeNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return "no source"
        return "${node.className?.toString()?.substringAfterLast('.') ?: "?"}" +
            (node.viewIdResourceName?.let { " #" + it.substringAfter(":id/") } ?: "") +
            " @" + Integer.toHexString(node.hashCode())
    }

    /**
     * The page state, or null when nothing readable is on screen (loading).
     * Signature: the visible child that fills most of the pager, plus that
     * child's own full-screen child if it has one (so a constant wrapper still
     * changes underneath). Small views (like buttons, captions) never enter
     * the signature, so likes and comments can't change it.
     */
    fun snapshot(match: Match): Snapshot? {
        if (!match.pager.refresh()) return null
        val list = listNode(match.pager)
        val lb = Rect().also { list.getBoundsInScreen(it) }
        if (lb.height() <= 0) return null
        val visible = mutableListOf<Triple<AccessibilityNodeInfo, Int, Int>>() // node, visible height, top
        val r = Rect()
        for (i in 0 until list.childCount) {
            val child = list.getChild(i) ?: continue
            child.getBoundsInScreen(r)
            if (!r.intersect(lb) || r.height() <= 0) continue
            visible += Triple(child, r.height(), r.top)
        }
        if (visible.isEmpty()) return null
        val best = visible.maxByOrNull { it.second }!!
        if (best.second < lb.height() * 0.85f) {
            val two = visible.filter { it.second >= lb.height() * 0.10f }.sortedBy { it.third }
            return if (two.size >= 2) Snapshot.MidSwipe(two.first().first, two.last().first) else null
        }
        val page = best.first
        // Chain of full-screen containers under the page (up to 4 levels): if the
        // pager we hold is a wrapper, the real page change shows up further down.
        val signature = mutableListOf(page)
        var level = page
        repeat(SIGNATURE_DEPTH - 1) {
            val lvb = Rect().also { level.getBoundsInScreen(it) }
            if (lvb.height() <= 0) return@repeat
            var inner: AccessibilityNodeInfo? = null
            var innerH = 0
            for (i in 0 until level.childCount) {
                val c = level.getChild(i) ?: continue
                c.getBoundsInScreen(r)
                if (!r.intersect(lvb)) continue
                if (r.height() > innerH) { innerH = r.height(); inner = c }
            }
            val next = inner ?: return@repeat
            if (innerH < lvb.height() * 0.85f) return@repeat
            signature += next
            level = next
        }
        val row = page.collectionItemInfo?.rowIndex?.takeIf { it >= 0 }
        return Snapshot.Settled(signature, row)
    }

    /**
     * Clickable items in a row along the bottom 12 % of the window: the app's
     * bottom navigation bar. Main feeds (TikTok For You, the Reels tab, the
     * Shorts tab) show one; videos opened from search, a profile or a link
     * usually don't. -1 when the window can't be read. Structure only, never
     * text. FeedContext turns readings into a stable verdict.
     */
    fun bottomNavItems(root: AccessibilityNodeInfo): Int {
        val window = Rect().also { root.getBoundsInScreen(it) }
        if (window.isEmpty) return -1
        val band = window.bottom - (window.height() * 0.12f).toInt()
        val centres = mutableSetOf<Int>()
        walk(root, MAX_NODES) { node, b, _ ->
            if (node.isClickable && b.top >= band && b.height() in 1..(window.height() / 8) &&
                b.width() < window.width() / 3
            ) centres += (b.centerX() * 10 / window.width()) // tenth of the width the item sits in
            true
        }
        return centres.size
    }

    /**
     * For the diagnostic log when no short-video screen is found in a tracked
     * app: the largest containers with class, view-id entry, size share,
     * scroll directions and how much their biggest child fills. No text.
     */
    fun describeCandidates(root: AccessibilityNodeInfo): String {
        val window = Rect().also { root.getBoundsInScreen(it) }
        if (window.isEmpty) return "window has no bounds"
        val rows = mutableListOf<Pair<Int, String>>()
        walk(root, MAX_NODES) { node, b, _ ->
            if (b.height() < window.height() * 0.5f || node.childCount == 0) return@walk true
            val area = b.width() * b.height()
            val dirs = buildString {
                val ids = node.actionList.map { it.id }.toSet()
                if (AccessibilityAction.ACTION_SCROLL_UP.id in ids || AccessibilityAction.ACTION_SCROLL_DOWN.id in ids) append("V")
                if (AccessibilityAction.ACTION_SCROLL_LEFT.id in ids || AccessibilityAction.ACTION_SCROLL_RIGHT.id in ids) append("H")
                if (AccessibilityAction.ACTION_SCROLL_FORWARD.id in ids) append("F")
                if (AccessibilityAction.ACTION_SCROLL_BACKWARD.id in ids) append("B")
                if (node.isScrollable) append("s")
            }
            val ci = node.collectionInfo?.let { " rows=${it.rowCount} cols=${it.columnCount}" } ?: ""
            rows += area to "${node.className?.toString()?.substringAfterLast('.')}" +
                (node.viewIdResourceName?.let { " #" + it.substringAfter(":id/") } ?: "") +
                " h=${b.height() * 100 / window.height()}% w=${b.width() * 100 / window.width()}%" +
                " child=${maxChildFillPct(listNode(node))}% dirs=$dirs$ci"
            true
        }
        return rows.sortedByDescending { it.first }.take(6).joinToString(" | ") { it.second }.ifEmpty { "no large containers" }
    }

    // ---- structure helpers ----

    /** Breadth-first walk over visible nodes; [visit] returns false to stop. */
    private inline fun walk(root: AccessibilityNodeInfo, limit: Int, visit: (AccessibilityNodeInfo, Rect, Int) -> Boolean) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser) continue
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (!visit(node, b, depth)) return
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
    }

    private fun fillsWindow(b: Rect, window: Rect): Boolean =
        b.height() >= window.height() * 0.8f && b.width() >= window.width() * 0.9f

    private fun isPagerLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isScrollable) return true
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("RecyclerView") || cls.contains("ViewPager")) return true
        val ids = node.actionList.map { it.id }.toSet()
        return verticalActions().any { it in ids } ||
            AccessibilityAction.ACTION_SCROLL_FORWARD.id in ids || AccessibilityAction.ACTION_SCROLL_BACKWARD.id in ids
    }

    /** Stories and carousels: only left/right movement, or one row of many columns. */
    private fun isHorizontalOnly(node: AccessibilityNodeInfo): Boolean {
        node.collectionInfo?.let { ci -> if (ci.columnCount > 1 && ci.rowCount <= 1) return true }
        val ids = node.actionList.map { it.id }.toSet()
        val horizontal = horizontalActions().any { it in ids }
        val vertical = verticalActions().any { it in ids }
        return horizontal && !vertical
    }

    private fun hasFullPageChild(node: AccessibilityNodeInfo): Boolean = maxChildFillPct(listNode(node)) >= 95

    private fun maxChildFillPct(list: AccessibilityNodeInfo): Int {
        val lb = Rect().also { list.getBoundsInScreen(it) }
        if (lb.height() <= 0) return 0
        var best = 0
        val r = Rect()
        for (i in 0 until list.childCount) {
            val c = list.getChild(i) ?: continue
            c.getBoundsInScreen(r)
            if (!r.intersect(lb)) continue
            best = maxOf(best, r.height() * 100 / lb.height())
        }
        return best
    }

    /** ViewPager2 wraps a RecyclerView of the same size: descend through such wrappers. */
    private fun listNode(pager: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var node = pager
        repeat(3) {
            if (node.childCount != 1) return node
            val child = node.getChild(0) ?: return node
            val nb = Rect().also { node.getBoundsInScreen(it) }
            val cb = Rect().also { child.getBoundsInScreen(it) }
            if (cb.height() < nb.height() * 0.95f) return node
            val cls = child.className?.toString().orEmpty()
            val isList = child.isScrollable || child.collectionInfo != null ||
                cls.contains("RecyclerView") || cls.contains("ViewPager")
            if (!isList) return node
            node = child
        }
        return node
    }

    private fun verticalActions(): Set<Int> {
        val s = mutableSetOf(AccessibilityAction.ACTION_SCROLL_UP.id, AccessibilityAction.ACTION_SCROLL_DOWN.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s += setOf(AccessibilityAction.ACTION_PAGE_UP.id, AccessibilityAction.ACTION_PAGE_DOWN.id)
        return s
    }

    private fun horizontalActions(): Set<Int> {
        val s = mutableSetOf(AccessibilityAction.ACTION_SCROLL_LEFT.id, AccessibilityAction.ACTION_SCROLL_RIGHT.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) s += setOf(AccessibilityAction.ACTION_PAGE_LEFT.id, AccessibilityAction.ACTION_PAGE_RIGHT.id)
        return s
    }

    private companion object {
        const val MAX_NODES = 800
        const val MAX_DEPTH = 35
        const val SIGNATURE_DEPTH = 4
        const val PARENT_STEPS = 8
    }
}

package app.tidemark.detect

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import app.tidemark.core.ItemKeys

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

    /** The item on screen: its key and the container identity used by PositionTracker. */
    class CurrentItem(val node: AccessibilityNodeInfo, val order: List<AccessibilityNodeInfo>, val row: Int?)

    fun find(root: AccessibilityNodeInfo, rule: AppRule): Match? {
        val window = Rect().also { root.getBoundsInScreen(it) }
        if (window.isEmpty) return null
        var best: Match? = null
        walk(root, MAX_NODES) { node, bounds ->
            if (!fillsWindow(bounds, window)) return@walk true
            val hint = rule.hintMatches(node.viewIdResourceName)
            if (!hint && !rule.genericFallback) return@walk true
            if (!isPagerLike(node) || isHorizontalOnly(node)) return@walk true
            if (!hasFullPageChild(node)) return@walk true
            val m = Match(node, bounds, hint, node.className?.toString() ?: "?", node.viewIdResourceName)
            if (hint) { best = m; return@walk false } // stop: hinted structural match
            if (best == null) best = m               // keep the outermost structural match
            true
        }
        return best
    }

    /** True when a scroll event came from the pager or a container of the same size. */
    fun isPagerScroll(source: AccessibilityNodeInfo?, match: Match): Boolean {
        if (source == null) return false
        val b = Rect().also { source.getBoundsInScreen(it) }
        return b.height() >= match.bounds.height() * 0.8f && b.width() >= match.bounds.width() * 0.9f
    }

    /** The child filling the pager, with the children order for PositionTracker. Null mid-swipe. */
    fun currentItem(match: Match): CurrentItem? {
        if (!match.pager.refresh()) return null
        val list = listNode(match.pager)
        val listBounds = Rect().also { list.getBoundsInScreen(it) }
        val children = (0 until list.childCount).mapNotNull { list.getChild(it) }
        var best: AccessibilityNodeInfo? = null
        var bestVisible = 0
        val r = Rect()
        for (child in children) {
            child.getBoundsInScreen(r)
            if (!r.intersect(listBounds)) continue
            if (r.height() > bestVisible) { bestVisible = r.height(); best = child }
        }
        // Mid-swipe two pages share the screen; wait until one fills >= 85 %.
        if (best == null || bestVisible < listBounds.height() * 0.85f) return null
        val row = best.collectionItemInfo?.rowIndex?.takeIf { it >= 0 }
        return CurrentItem(best, children, row)
    }

    /** Key for the engine: adapter row when the app exposes it, else the tracked position. */
    fun keyFor(item: CurrentItem, tracker: PositionTracker<AccessibilityNodeInfo>): Pair<String, Boolean> {
        val row = item.row
        if (row != null) return ItemKeys.index(row) to false
        val (pos, moved) = tracker.update(item.node, item.order)
        return "p:$pos" to moved
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
        walk(root, MAX_NODES) { node, b ->
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
    private inline fun walk(root: AccessibilityNodeInfo, limit: Int, visit: (AccessibilityNodeInfo, Rect) -> Boolean) {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < limit) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser) continue
            val b = Rect().also { node.getBoundsInScreen(it) }
            if (!visit(node, b)) return
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
    }
}

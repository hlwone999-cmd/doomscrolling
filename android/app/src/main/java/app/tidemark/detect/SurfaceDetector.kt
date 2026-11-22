package app.tidemark.detect

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import app.tidemark.core.ItemKeys

/**
 * Finds the short-video pager on screen and the video currently filling it.
 * Matches structure only (ids, class names, sizes, scroll direction).
 *
 * Detection order:
 *   1. a node whose view id is in the app's bundled pagerIds and fills the window
 *   2. generic fallback: a vertical RecyclerView/ViewPager that fills the window
 *      and whose current child fills the pager (a feed's posts don't)
 *
 * Every threshold here is a first guess to tune on real devices (accuracy.md).
 */
class SurfaceDetector {

    class Match(val pager: AccessibilityNodeInfo, val bounds: Rect, val byId: Boolean)

    fun find(root: AccessibilityNodeInfo, rule: AppRule): Match? {
        val window = Rect().also { root.getBoundsInScreen(it) }
        if (window.isEmpty) return null
        var generic: Match? = null
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            if (!node.isVisibleToUser) continue
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val id = node.viewIdResourceName
            if (id != null && id in rule.pagerIds && fillsWindow(bounds, window)) {
                return Match(node, bounds, byId = true)
            }
            if (generic == null && rule.genericFallback && isGenericPager(node, bounds, window)) {
                generic = Match(node, bounds, byId = false) // keep looking for an id match
            }
            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
        return generic
    }

    /** True when a scroll event came from the pager itself (not a caption or a chip row inside it). */
    fun isPagerScroll(source: AccessibilityNodeInfo?, match: Match): Boolean {
        if (source == null) return false
        val b = Rect().also { source.getBoundsInScreen(it) }
        return b.height() >= match.bounds.height() * 0.8f && b.width() >= match.bounds.width() * 0.9f
    }

    /**
     * Dedupe key of the video filling the pager, or null when it cannot tell
     * (mid-swipe, still loading). Null never counts: undercount beats double count.
     */
    fun currentItemKey(match: Match, pkg: String, salt: ByteArray): String? {
        if (!match.pager.refresh()) return null
        val list = listNode(match.pager)
        val listBounds = Rect().also { list.getBoundsInScreen(it) }
        val item = currentItem(list, listBounds) ?: return null

        val row = item.collectionItemInfo?.rowIndex ?: -1
        if (row >= 0) return ItemKeys.index(row)

        val parts = collectStableText(item)
        if (parts.isEmpty()) return null
        return ItemKeys.hash(salt, pkg + "\u0000" + parts.joinToString("\u0001"))
    }

    private fun fillsWindow(b: Rect, window: Rect): Boolean =
        b.height() >= window.height() * 0.8f && b.width() >= window.width() * 0.9f

    private fun isGenericPager(node: AccessibilityNodeInfo, bounds: Rect, window: Rect): Boolean {
        if (!node.isScrollable) return false
        val cls = node.className?.toString() ?: return false
        if (!cls.contains("RecyclerView") && !cls.contains("ViewPager")) return false
        if (!fillsWindow(bounds, window)) return false
        if (!isVertical(node)) return false
        val list = listNode(node)
        val listBounds = Rect().also { list.getBoundsInScreen(it) }
        val item = currentItem(list, listBounds) ?: return false
        val itemBounds = Rect().also { item.getBoundsInScreen(it) }
        return itemBounds.height() >= listBounds.height() * 0.9f
    }

    private fun isVertical(node: AccessibilityNodeInfo): Boolean {
        node.collectionInfo?.let { ci ->
            if (ci.rowCount > 1 && ci.columnCount <= 1) return true
            if (ci.columnCount > 1 && ci.rowCount <= 1) return false
        }
        val ids = node.actionList.map { it.id }.toSet()
        val vertical = mutableSetOf(AccessibilityAction.ACTION_SCROLL_UP.id, AccessibilityAction.ACTION_SCROLL_DOWN.id)
        val horizontal = mutableSetOf(AccessibilityAction.ACTION_SCROLL_LEFT.id, AccessibilityAction.ACTION_SCROLL_RIGHT.id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vertical += setOf(AccessibilityAction.ACTION_PAGE_UP.id, AccessibilityAction.ACTION_PAGE_DOWN.id)
            horizontal += setOf(AccessibilityAction.ACTION_PAGE_LEFT.id, AccessibilityAction.ACTION_PAGE_RIGHT.id)
        }
        if (ids.any { it in vertical }) return true
        return false // horizontal or unknown: Stories-style pagers must not count
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
            // Only descend into another list container, never into a single video item.
            val cls = child.className?.toString().orEmpty()
            val isList = child.isScrollable || child.collectionInfo != null ||
                cls.contains("RecyclerView") || cls.contains("ViewPager")
            if (!isList) return node
            node = child
        }
        return node
    }

    /** The child that fills most of the list, if it fills at least 60% of it. */
    private fun currentItem(list: AccessibilityNodeInfo, listBounds: Rect): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestHeight = 0
        val r = Rect()
        for (i in 0 until list.childCount) {
            val child = list.getChild(i) ?: continue
            child.getBoundsInScreen(r)
            if (!r.intersect(listBounds)) continue
            if (r.height() > bestHeight) {
                bestHeight = r.height(); best = child
            }
        }
        return if (bestHeight >= listBounds.height() * 0.6f) best else null
    }

    /**
     * The three longest description/text strings in the item that look stable:
     * at least 12 characters and no digits (like counts, timers and view counts
     * change while you watch). Short labels ("Like", "Follow") are skipped
     * because they toggle. Used only to build a hash; never stored.
     */
    private fun collectStableText(item: AccessibilityNodeInfo): List<String> {
        val found = mutableListOf<String>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(item to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_ITEM_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++
            val s = (node.contentDescription ?: node.text)?.toString()?.trim()
            if (s != null && s.length >= 12 && s.none { it.isDigit() }) found += s
            if (depth < MAX_ITEM_DEPTH) {
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
        }
        return found.distinct().sortedWith(compareByDescending<String> { it.length }.thenBy { it }).take(3)
    }

    private companion object {
        const val MAX_NODES = 600
        const val MAX_DEPTH = 30
        const val MAX_ITEM_NODES = 150
        const val MAX_ITEM_DEPTH = 10
    }
}

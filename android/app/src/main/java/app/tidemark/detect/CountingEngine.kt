package app.tidemark.detect

/**
 * Pure counting state machine. No Android types, so it runs in JVM unit tests.
 *
 * The service feeds it signals, all on one thread:
 *   onSurface    the short-video screen of a tracked app is visible
 *   onOffSurface anything else is visible (feed, DMs, another app, home)
 *   onScroll     the pager scrolled (the user moved)
 *   onObserve    the settled current video has this key (null = mid-swipe)
 *   tick         time passed; call at nextDeadline()
 *
 * Counting rules (architecture.md, "When a video counts"):
 *   1. a video counts once it stays the current video for settleMs
 *   2. the video on screen when the user arrives (opening the app's feed, a
 *      search result, a profile video, coming back from comments) is marked
 *      seen but NOT counted; counting starts with the first swipe (BUG-005)
 *   3. the same key never counts twice in one session
 *   4. every count needs a swipe since the last settled video, so a UI change
 *      that alters the key (rotation, a like, the comments sheet) cannot
 *      produce a count
 *   5. one session per screen visit; leaving for less than graceMs keeps it
 *   6. keys of one kind per session (all "i:" or all "h:"), never mixed
 */
class CountingEngine(
    private val config: EngineConfig,
    private val sink: EngineSink,
    private val dayKeyOf: (Long) -> String,
) {
    private class Open(
        val token: Long,
        val pkg: String,
        val surface: String,
        val dayKey: String,
        val startedAt: Long,
    ) {
        val counted = HashSet<String>()
        var keyKind: Char? = null
        var activeMs = 0L
        var onSurfaceSince: Long? = null
        var leftAt: Long? = null
        var lastTouchAt: Long = startedAt
        var mayCount = false   // rule 4: true only after a swipe
        var entryUnseen = true // rule 2: the next settled video is the arrival video
    }

    private var open: Open? = null
    private var nextToken = 1L
    private var pendingKey: String? = null
    private var pendingSince = 0L
    private var overlayShown = false
    private var hideAt: Long? = null

    val isOnSurface: Boolean get() = open?.onSurfaceSince != null
    val currentPackage: String? get() = open?.pkg
    val sessionCount: Int get() = open?.counted?.size ?: 0

    /** Active (on-screen) time of the open session up to [now]; 0 when none. */
    fun sessionActiveMs(now: Long): Long = open?.let { activeMsAt(it, now) } ?: 0L

    private fun activeMsAt(o: Open, now: Long): Long = o.activeMs + (o.onSurfaceSince?.let { now - it } ?: 0L)

    fun onSurface(pkg: String, surface: String, now: Long) {
        var o = open
        if (o != null && o.pkg != pkg) {
            close(o, now, EndReason.APP_SWITCH)
            o = null
        }
        val left = o?.leftAt
        if (o != null && o.onSurfaceSince == null && left != null && now - left >= config.graceMs) {
            close(o, left, EndReason.LEFT_SURFACE) // grace ran out before tick() got to it
            o = null
        }
        if (o != null && o.dayKey != dayKeyOf(now)) {
            // Away during the grace period: the old session ended when the user left.
            close(o, if (o.onSurfaceSince == null) o.leftAt ?: now else now, EndReason.DAY_ROLLOVER)
            o = null
        }
        if (o == null) {
            o = Open(nextToken++, pkg, surface, dayKeyOf(now), now)
            open = o
            sink.openSession(o.token, pkg, surface, now)
        }
        if (o.onSurfaceSince == null) {
            o.onSurfaceSince = now
            o.leftAt = null
            o.mayCount = false   // arriving is not a swipe
            o.entryUnseen = true // the video shown on arrival is marked seen, not counted
        }
        hideAt = null
        setOverlay(true)
    }

    fun onOffSurface(now: Long) {
        val o = open
        if (o != null) {
            val since = o.onSurfaceSince
            if (since != null) {
                o.activeMs += now - since
                o.onSurfaceSince = null
                o.leftAt = now
            }
        }
        pendingKey = null
        if (overlayShown && hideAt == null) hideAt = now + config.hideDelayMs
    }

    fun onScroll(now: Long) {
        val o = open ?: return
        if (o.onSurfaceSince == null) return
        o.mayCount = true
        pendingKey = null // mid-swipe: the next settled key starts a fresh dwell
    }

    fun onObserve(key: String?, now: Long) {
        val o = open ?: return
        if (o.onSurfaceSince == null) return
        if (key == null) {
            pendingKey = null
            return
        }
        val kind = o.keyKind
        if (kind != null && key.firstOrNull() != kind) { // rule 6
            sink.note("ignored $key: this session uses ${kind}: keys")
            return
        }
        if (key == pendingKey) return
        pendingKey = key
        pendingSince = now
    }

    /** Screen off ends the session at once (no grace). */
    fun onScreenOff(now: Long) {
        onOffSurface(now)
        open?.let { close(it, it.leftAt ?: now, EndReason.SCREEN_OFF) }
        hideOverlayNow()
    }

    /** Service stopping, tracking paused, disclosure withdrawn. */
    fun stop(now: Long, reason: EndReason) {
        open?.let {
            if (it.onSurfaceSince != null) onOffSurface(now)
            close(it, it.leftAt ?: now, reason)
        }
        hideOverlayNow()
    }

    fun tick(now: Long) {
        hideAt?.let { if (now >= it) hideOverlayNow() }

        val o = open ?: return
        val left = o.leftAt
        if (o.onSurfaceSince == null && left != null && now - left >= config.graceMs) {
            close(o, left, EndReason.LEFT_SURFACE)
            return
        }
        if (o.onSurfaceSince == null) return

        if (o.dayKey != dayKeyOf(now)) {
            // Midnight (reset hour) while watching: close and continue in a new session.
            val pkg = o.pkg; val surface = o.surface
            onOffSurface(now)
            close(o, now, EndReason.DAY_ROLLOVER)
            onSurface(pkg, surface, now)
            return
        }

        val key = pendingKey
        if (key != null && now - pendingSince >= config.settleMs) {
            pendingKey = null
            commit(o, key, now)
        }

        if (now - o.lastTouchAt >= config.touchEveryMs) {
            o.lastTouchAt = now
            sink.touchSession(o.token, now, activeMsAt(o, now))
        }
    }

    /** The earliest time tick() has something to do, or null. */
    fun nextDeadline(): Long? {
        val o = open
        var best: Long? = hideAt
        fun consider(t: Long) { best = best?.let { minOf(it, t) } ?: t }
        if (pendingKey != null) consider(pendingSince + config.settleMs)
        if (o != null) {
            val left = o.leftAt
            if (o.onSurfaceSince == null && left != null) consider(left + config.graceMs)
            if (o.onSurfaceSince != null) consider(o.lastTouchAt + config.touchEveryMs)
        }
        return best
    }

    private fun commit(o: Open, key: String, now: Long) {
        if (!o.mayCount) {
            if (o.entryUnseen) {
                o.entryUnseen = false
                if (o.counted.add(key) && o.keyKind == null) o.keyKind = key.first()
                sink.note("not counted $key: video shown on arrival (counting starts with the first swipe)")
            } else {
                sink.note("not counted $key: no swipe since the last count")
            }
            return
        }
        o.entryUnseen = false
        // A settled video uses up the scroll, even one already counted (swipe back),
        // so a later key change on that same video cannot count it again.
        o.mayCount = false
        if (!o.counted.add(key)) {
            sink.note("not counted $key: already counted in this session")
            return
        }
        if (o.keyKind == null) o.keyKind = key.first()
        sink.note("counted $key after ${now - pendingSince} ms on screen")
        sink.countVideo(o.token, key, now, now - pendingSince)
    }

    private fun close(o: Open, endedAt: Long, reason: EndReason) {
        val since = o.onSurfaceSince
        if (since != null) {
            o.activeMs += endedAt - since
            o.onSurfaceSince = null
        }
        sink.closeSession(o.token, endedAt, o.activeMs, reason)
        if (open === o) open = null
        pendingKey = null
    }

    private fun setOverlay(visible: Boolean) {
        if (overlayShown == visible) return
        overlayShown = visible
        sink.overlayVisible(visible)
    }

    private fun hideOverlayNow() {
        hideAt = null
        setOverlay(false)
    }
}

data class EngineConfig(
    val settleMs: Long = 250,
    val graceMs: Long = 30_000,
    val hideDelayMs: Long = 1_500,
    val touchEveryMs: Long = 5_000,
)

enum class EndReason(val db: String) {
    LEFT_SURFACE("left_surface"),
    APP_SWITCH("app_switch"),
    SCREEN_OFF("screen_off"),
    GOAL_BLOCK("goal_block"),
    SERVICE_STOPPED("service_stopped"),
    PAUSED("paused"),
    DAY_ROLLOVER("day_rollover"),
}

interface EngineSink {
    fun openSession(token: Long, pkg: String, surface: String, at: Long)
    fun countVideo(token: Long, itemKey: String, at: Long, dwellMs: Long)
    fun touchSession(token: Long, at: Long, activeMs: Long)
    fun closeSession(token: Long, endedAt: Long, activeMs: Long, reason: EndReason)
    fun overlayVisible(visible: Boolean)

    /** Why the last observation did or didn't count. Diagnostics only; no content. */
    fun note(reason: String) {}
}

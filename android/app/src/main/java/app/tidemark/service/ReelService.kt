package app.tidemark.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import app.tidemark.MainActivity
import app.tidemark.app
import app.tidemark.core.GoalDecision
import app.tidemark.core.Nudge
import app.tidemark.core.OverlayMode
import app.tidemark.core.OverlayPolicy
import app.tidemark.core.StatsLogic
import app.tidemark.data.DiagLog
import app.tidemark.data.DisclosureState
import app.tidemark.data.GoalRow
import app.tidemark.data.OverlaySettings
import app.tidemark.data.ReminderSettings
import app.tidemark.detect.AppRule
import app.tidemark.detect.CountingEngine
import app.tidemark.detect.EndReason
import app.tidemark.detect.EngineConfig
import app.tidemark.detect.EngineSink
import app.tidemark.detect.FeedPipeline
import app.tidemark.detect.PageTracker
import app.tidemark.detect.SurfaceDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The counting service. Everything runs on the main thread except database
 * writes, which go to the single writer.
 *
 * Flow per event:
 *   window state / content change in a tracked app -> look for the pager
 *     found     -> engine.onSurface, then probe the current video
 *     not found -> engine.onOffSurface (counter hides after the delay)
 *   window state change in any other app -> engine.onOffSurface
 *   scroll from the pager -> engine.onScroll, then probe after it settles
 *
 * On top of the engine: counter display mode (S14), the counter card (S07),
 * the block screen (S12), the in-session nudge, pause, and app on/off (S04).
 */
class ReelService : AccessibilityService(), EngineSink {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = SurfaceDetector()
    private lateinit var engine: CountingEngine
    private lateinit var overlay: OverlayController
    private lateinit var card: CounterCard
    private lateinit var block: BlockScreen

    // Settings mirrored from DataStore / Room.
    private lateinit var pipeline: FeedPipeline<AccessibilityNodeInfo>
    private var lastProbeRequest = 0L
    private var lastRejectLog = 0L
    private val lastCandidateLog = HashMap<String, Long>()
    private var consent = false
    private var pausedUntil = 0L
    private var enabledApps: Set<String> = emptySet()
    private var overlaySettings = OverlaySettings()
    private var reminders = ReminderSettings()
    private var todayTotal = 0
    private var reelsGoal: GoalRow? = null
    private var blocking: List<GoalDecision> = emptyList()
    private var appNames: Map<String, String> = emptyMap()

    // Screen state.
    private var match: SurfaceDetector.Match? = null
    private var matchPkg: String? = null
    private var foregroundTracked: String? = null
    private var engineWantsOverlay = false
    private var hiddenUntilLeave = false
    private var lastContentCheck = 0L
    private var isShutDown = false

    // Per-session nudge state.
    private var nudgeToken = -1L
    private var nudgeVideos = 0
    private var nudgeSent = false

    private val tickRunnable = Runnable { tick() }
    private val probeRunnable = Runnable { probe() }
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!::engine.isInitialized) return
            val now = now()
            if (overlay.isShown && overlay.wantsTimer) {
                overlay.setTimer(StatsLogic.timer(engine.sessionActiveMs(now)))
                handler.postDelayed(this, 1_000)
            }
        }
    }
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            scope.launch { app.prefs.heartbeat(System.currentTimeMillis()) }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF && ::engine.isInitialized) {
                match = null
                foregroundTracked = null
                card.dismiss()
                engine.onScreenOff(now())
                refreshOverlay()
                schedule()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val a = app
        // Anything the previous process left open ends at its last activity.
        a.writer.closeDangling()
        DiagLog.add(this, "service connected (rules v${a.rules.version})")
        DiagnosticsState.update {
            DiagnosticsState.Snapshot(connected = true, connectedAt = System.currentTimeMillis())
        }
        Notifications.cancelStopped(this)
        engine = CountingEngine(EngineConfig(settleMs = a.rules.settleMs), sink = this, dayKeyOf = { a.dayKey(it) })
        pipeline = FeedPipeline(engine)
        overlay = OverlayController(
            this,
            onMoved = { x, y -> scope.launch { a.prefs.setOverlayPosition(x, y) } },
            onTap = { showCard() },
        )
        card = CounterCard(
            this,
            onPause = { scope.launch { a.prefs.setPausedUntil(System.currentTimeMillis() + 60 * 60_000L) } },
            onHideUntilLeave = { hiddenUntilLeave = true; refreshOverlay() },
            onOpenApp = { openApp("today") },
        )
        block = BlockScreen(
            this,
            onCloseApp = { block.dismiss(); performGlobalAction(GLOBAL_ACTION_HOME) },
            onSnooze = { snoozeCurrent() },
            onBreathe = { block.dismiss(); performGlobalAction(GLOBAL_ACTION_HOME); openApp("breathe") },
        )

        scope.launch {
            a.prefs.disclosure.collectLatest {
                consent = it == DisclosureState.ACCEPTED
                if (!consent) stopCounting(EndReason.PAUSED)
            }
        }
        scope.launch { a.prefs.pausedUntil.collectLatest { pausedUntil = it; if (it > System.currentTimeMillis()) stopCounting(EndReason.PAUSED) } }
        scope.launch { a.prefs.overlayPosition.collectLatest { overlay.setPosition(it) } }
        scope.launch {
            a.prefs.overlay.collectLatest {
                overlaySettings = it
                overlay.applyAppearance(it.size, it.opacityPct, it.showTimer)
                refreshOverlay()
            }
        }
        scope.launch { a.prefs.reminders.collectLatest { reminders = it } }
        scope.launch {
            a.db.trackedApps().all().collectLatest { list ->
                enabledApps = list.filter { it.enabled }.map { it.packageName }.toSet()
                appNames = list.associate { it.packageName to it.displayName }
                val cur = matchPkg
                if (cur != null && cur !in enabledApps) leaveSurface(now())
            }
        }
        scope.launch { a.writer.todayTotal.collectLatest { todayTotal = it; updatePill() } }
        scope.launch { a.writer.reelsGoal.collectLatest { reelsGoal = it; updatePill() } }
        scope.launch { a.writer.blocking.collectLatest { blocking = it; applyBlock() } }
        a.writer.refreshToday()

        ContextCompat.registerReceiver(
            this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.post(heartbeatRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !::engine.isInitialized) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // our own overlays and screens
        recordEvent(event.eventType, pkg)
        if (!counting()) return
        val rule = app.rules.forPackage(pkg)?.takeIf { pkg in enabledApps }
        val now = now()
        if (block.isShown && rule != null) {
            // Blocked: nothing counts behind the block screen. Leaving the app dismisses it.
            foregroundTracked = pkg
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (rule == null) {
                    foregroundTracked = null
                    hiddenUntilLeave = false
                    leaveSurface(now)
                } else {
                    foregroundTracked = pkg
                    evaluate(rule, now)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (rule == null) return
                foregroundTracked = pkg
                val m = match
                if (m != null && matchPkg == pkg) {
                    // On the video screen: a cheap probe of the pager's children,
                    // often enough to see a swipe in progress.
                    if (now - lastProbeRequest < PROBE_THROTTLE_MS) return
                    lastProbeRequest = now
                    if (keepMatch(m, pkg)) scheduleProbe(0) else evaluate(rule, now)
                } else {
                    // Playing videos change content constantly, so the full walk is throttled.
                    if (now - lastContentCheck < CONTENT_THROTTLE_MS) return
                    lastContentCheck = now
                    // Tab switches inside an app often only change content: full walk.
                    evaluate(rule, now)
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (rule == null) return
                val m = match
                if (m != null && matchPkg == pkg) {
                    if (detector.isPagerScroll(event.source, m)) {
                        val dy = if (Build.VERSION.SDK_INT >= 28) event.scrollDeltaY else 0
                        val kind = pipeline.onPagerScroll(event.fromIndex, event.toIndex, dy, now)
                        DiagnosticsState.update { it.copy(lastScrollAt = now, lastScrollKind = kind.name) }
                        publishPages()
                        // The pager reported a new adapter position: the page has changed, look now.
                        if (kind == PageTracker.Scroll.NEW_INDEX) { scheduleProbe(0); return }
                    }
                    // Smaller scrollers (comments, captions, menus) are not a swipe;
                    // the probe still checks which page is on screen.
                    scheduleProbe(PROBE_AFTER_SCROLL_MS)
                } else if (m == null && now - lastContentCheck >= CONTENT_THROTTLE_MS) {
                    lastContentCheck = now
                    evaluate(rule, now)
                }
            }
        }
        refreshOverlay()
        schedule()
    }

    override fun onInterrupt() = Unit

    /** Rotation or a resized window: put the counter back at its (default or saved) place. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlay.isInitialized) overlay.relayout()
        if (::card.isInitialized) card.dismiss()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // ---- EngineSink: called by the engine on the main thread ----

    override fun openSession(token: Long, pkg: String, surface: String, at: Long) {
        app.writer.open(token, pkg, surface, at, app.rules.version, versionCodeOf(pkg))
        nudgeToken = token; nudgeVideos = 0; nudgeSent = false
        pipeline.onNewSession()
    }

    override fun countVideo(token: Long, itemKey: String, at: Long, dwellMs: Long) {
        app.writer.count(token, itemKey, at, dwellMs)
        if (token == nudgeToken) nudgeVideos++
        DiagnosticsState.update { it.copy(countedSinceConnect = it.countedSinceConnect + 1) }
        DiagnosticsState.update { it.copy(lastCountAt = at) }
        DiagLog.add(this, "counted ${matchPkg?.let { appNames[it] } ?: "?"} $itemKey (${dwellMs} ms on screen)")
        checkNudge(at)
    }

    override fun touchSession(token: Long, at: Long, activeMs: Long) {
        app.writer.touch(token, at, activeMs)
        checkNudge(at)
    }

    override fun closeSession(token: Long, endedAt: Long, activeMs: Long, reason: EndReason) {
        app.writer.close(token, endedAt, activeMs, reason.db)
    }

    override fun note(reason: String) = setReason(reason)

    override fun overlayVisible(visible: Boolean) {
        engineWantsOverlay = visible
        if (!visible) { hiddenUntilLeave = false; card.dismiss() }
        refreshOverlay()
    }

    // ---- display ----

    private fun refreshOverlay() {
        if (!::overlay.isInitialized) return
        val show = OverlayPolicy.visible(
            overlaySettings.mode, engineWantsOverlay, foregroundTracked != null && counting(),
            hiddenUntilLeave, block.isShown,
        )
        if (show) {
            if (!overlay.isShown) {
                overlay.show()
                handler.removeCallbacks(timerRunnable)
                handler.post(timerRunnable)
            }
        } else {
            overlay.hide()
            handler.removeCallbacks(timerRunnable)
        }
    }

    private fun updatePill() {
        if (!::overlay.isInitialized) return
        overlay.update(todayTotal, reelsGoal?.value, reelsGoal?.warnAtPct ?: 80)
    }

    private fun showCard() {
        val now = now()
        card.show(engine.sessionCount, StatsLogic.timer(engine.sessionActiveMs(now)), todayTotal, anchorY = overlay.anchorBottom())
    }

    /** Show the block screen when a blocking goal covers the app on screen. */
    private fun applyBlock() {
        if (!::block.isInitialized) return
        val pkg = matchPkg
        val hit = if (pkg != null && engine.isOnSurface) blocking.firstOrNull { it.pkg == null || it.pkg == pkg } else null
        if (hit == null) {
            if (block.isShown) block.dismiss()
            refreshOverlay()
            return
        }
        if (block.isShown) return
        card.dismiss()
        scope.launch {
            val goal = app.db.goals().byId(hit.goalId) ?: return@launch
            val snoozed = app.db.goals().countKind(goal.id, app.dayKey(now()), "snoozed")
            block.show(hit.used, hit.target, goal.metric == GoalRow.METRIC_MINUTES, hit.pkg?.let { appNames[it] },
                canSnooze = snoozed < goal.snoozesPerDay)
            app.writer.recordBlocked(goal.id)
            engine.stop(now(), EndReason.GOAL_BLOCK)
            match = null
            refreshOverlay()
            schedule()
        }
    }

    private fun snoozeCurrent() {
        val pkg = matchPkg ?: foregroundTracked
        val hit = blocking.firstOrNull { it.pkg == null || it.pkg == pkg } ?: blocking.firstOrNull() ?: return
        scope.launch {
            val ok = app.writer.snooze(hit.goalId)
            if (ok) block.dismiss()
            refreshOverlay()
        }
    }

    private fun checkNudge(now: Long) {
        if (nudgeSent) return
        val active = engine.sessionActiveMs(now)
        if (Nudge.due(active, nudgeVideos, reminders.nudgeMinutes, reminders.nudgeVideos, nudgeSent)) {
            nudgeSent = true
            Notifications.nudge(this, (active / 60_000L).toInt(), nudgeVideos)
        }
    }

    private fun openApp(route: String) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(Notifications.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    // ---- detection ----

    private fun counting(): Boolean {
        if (!consent) { setReason("not counting: data disclosure not accepted"); return false }
        if (pausedUntil > System.currentTimeMillis()) { setReason("not counting: paused"); return false }
        return true
    }

    private fun recordEvent(type: Int, pkg: String) {
        DiagnosticsState.update {
            it.copy(
                foregroundPackage = if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) pkg else it.foregroundPackage ?: pkg,
                lastEvent = "${AccessibilityEvent.eventTypeToString(type)} · $pkg",
                lastEventAt = System.currentTimeMillis(),
                eventsSinceConnect = it.eventsSinceConnect + 1,
            )
        }
    }

    private fun setReason(reason: String) {
        DiagnosticsState.update { it.copy(lastReason = reason, lastReasonAt = System.currentTimeMillis()) }
    }

    /**
     * The tracked app's window. rootInActiveWindow can be null or point at
     * another window on some phones (overlays, split screen, OEM launchers),
     * so fall back to the interactive window list.
     */
    private fun rootFor(pkg: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }?.let { return it }
        return try {
            windows.firstOrNull {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName?.toString() == pkg
            }?.root
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun evaluate(rule: AppRule, now: Long) {
        // Keep the pager we already have while it is still on screen in the same
        // window. Re-running find() mid-swipe would fail (no page fills the screen
        // while two are half visible), drop the surface and lose the swipe.
        val current = match
        if (current != null && matchPkg == rule.packageName && engine.isOnSurface && keepMatch(current, rule.packageName)) {
            engine.onSurface(rule.packageName, rule.surface, now)
            scheduleProbe(PROBE_AFTER_SCROLL_MS)
            return
        }
        val root = rootFor(rule.packageName)
        if (root == null) {
            DiagnosticsState.update { it.copy(surface = "${rule.name}: window not readable") }
            leaveSurface(now)
            return
        }
        val found = detector.find(root, rule)
        if (found == null) {
            DiagnosticsState.update { it.copy(surface = "none in ${rule.name}") }
            val last = lastCandidateLog[rule.packageName] ?: 0L
            if (now - last >= CANDIDATE_LOG_MS) {
                lastCandidateLog[rule.packageName] = now
                // Structure only (class, view-id entry, size, scroll directions): never text.
                DiagLog.add(this, "no short-video screen in ${rule.name}: ${detector.describeCandidates(root)}")
            }
            leaveSurface(now)
            return
        }
        DiagnosticsState.update { it.copy(surface = "${rule.name}: ${found.describe()}") }
        val entering = match == null || matchPkg != rule.packageName || !engine.isOnSurface
        match = found
        matchPkg = rule.packageName
        engine.onSurface(rule.packageName, rule.surface, now)
        if (entering) {
            val context = try { detector.feedContext(root, found) } catch (e: RuntimeException) { "unknown" }
            DiagnosticsState.update { it.copy(feedContext = context) }
            DiagLog.add(this, "surface ${rule.name}: ${found.describe()}, $context, " +
                "app v${versionCodeOf(rule.packageName) ?: "?"}, verified=${rule.isVerified(versionCodeOf(rule.packageName))}")
            scheduleProbe(0)
            applyBlock()
        } else {
            scheduleProbe(PROBE_AFTER_SCROLL_MS)
        }
    }

    /**
     * The matched pager is still the one on screen: visible, and in the app's
     * current window (a new activity such as a profile or settings page has a
     * different window id).
     */
    private fun keepMatch(m: SurfaceDetector.Match, pkg: String): Boolean = try {
        stillVisible(m) && rootFor(pkg)?.windowId == m.pager.windowId
    } catch (e: RuntimeException) {
        false
    }

    private fun stillVisible(m: SurfaceDetector.Match): Boolean = try {
        m.pager.refresh() && m.pager.isVisibleToUser
    } catch (e: RuntimeException) {
        false
    }

    private fun leaveSurface(now: Long) {
        match = null
        matchPkg = null
        handler.removeCallbacks(probeRunnable)
        engine.onOffSurface(now)
        if (block.isShown && foregroundTracked == null) block.dismiss()
    }

    private fun probe() {
        val m = match ?: return
        val now = now()
        val snap = try {
            detector.snapshot(m)
        } catch (e: RuntimeException) {
            null // node went stale mid-read
        }
        when (snap) {
            null -> engine.onObserve(null, now) // loading: never counts
            is SurfaceDetector.Snapshot.MidSwipe -> {
                pipeline.onMidSwipe(snap.upper, snap.lower, now)
                DiagnosticsState.update { it.copy(pageDecision = "swipe in progress (two pages on screen)") }
            }
            is SurfaceDetector.Snapshot.Settled -> {
                val r = pipeline.onSettledPage(snap.signature, snap.row, now)
                val appName = matchPkg?.let { appNames[it] } ?: "?"
                if (r.changed) {
                    DiagnosticsState.update { it.copy(lastPageChangeAt = now) }
                    DiagLog.add(this, "page ${pipeline.pages.previousPosition} → ${r.position} in $appName: ${r.reason}")
                } else if (r.reason.contains("without a swipe") && now - lastRejectLog >= REJECT_LOG_MS) {
                    lastRejectLog = now
                    DiagLog.add(this, "page not counted in $appName: ${r.reason} " +
                        "(pager scroll events so far: ${pipeline.pages.scrollEvents}, pager: ${m.describe()})")
                }
                DiagnosticsState.update { it.copy(pageDecision = r.reason) }
            }
        }
        publishPages()
        schedule()
    }

    private fun publishPages() {
        val p = pipeline.pages
        DiagnosticsState.update {
            it.copy(
                scrollEvents = p.scrollEvents,
                pageChanges = p.pageChanges,
                previousPosition = p.previousPosition,
                position = p.position,
            )
        }
    }

    private fun scheduleProbe(delayMs: Long) {
        handler.removeCallbacks(probeRunnable)
        handler.postDelayed(probeRunnable, delayMs)
    }

    private fun tick() {
        if (!::engine.isInitialized) return
        engine.tick(now())
        schedule()
    }

    /** Wake up exactly when the engine next has something to do. */
    private fun schedule() {
        handler.removeCallbacks(tickRunnable)
        val deadline = engine.nextDeadline() ?: return
        handler.postDelayed(tickRunnable, (deadline - now()).coerceAtLeast(0))
    }

    private fun stopCounting(reason: EndReason) {
        if (!::engine.isInitialized) return
        match = null
        engine.stop(now(), reason)
        card.dismiss()
        refreshOverlay()
        schedule()
    }

    private fun shutdown() {
        if (!::engine.isInitialized || isShutDown) return
        isShutDown = true
        DiagLog.add(this, "service stopping")
        DiagnosticsState.update { it.copy(connected = false, surface = null) }
        handler.removeCallbacksAndMessages(null)
        engine.stop(now(), EndReason.SERVICE_STOPPED)
        overlay.destroy()
        card.dismiss()
        block.dismiss()
        try { unregisterReceiver(screenOffReceiver) } catch (_: IllegalArgumentException) {}
        scope.cancel()
    }

    private fun versionCodeOf(pkg: String): Long? = try {
        val info = packageManager.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Wall-clock time: stored in the database and used for day keys. */
    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val CONTENT_THROTTLE_MS = 600L
        const val PROBE_AFTER_SCROLL_MS = 150L
        const val HEARTBEAT_MS = 5 * 60_000L
        const val CANDIDATE_LOG_MS = 30_000L
        const val PROBE_THROTTLE_MS = 250L
        const val REJECT_LOG_MS = 10_000L
    }
}

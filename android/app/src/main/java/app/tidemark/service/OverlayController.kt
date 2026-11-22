package app.tidemark.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import app.tidemark.R
import app.tidemark.core.OverlayPolicy
import app.tidemark.core.Severities
import app.tidemark.core.Severity
import app.tidemark.design.DarkAppColors
import app.tidemark.design.Motion
import app.tidemark.design.OverlayColors
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared bits for the three overlay windows. Colours only from design tokens. */
private class Kit(val context: Context) {
    val dm = context.resources.displayMetrics
    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
    fun dpi(v: Float) = dp(v).roundToInt()

    /** Inter (bundled, OFL); weight set through the variable font's wght axis. */
    private val inter: Typeface by lazy {
        try { ResourcesCompat.getFont(context, R.font.inter_variable) } catch (_: Exception) { null } ?: Typeface.DEFAULT
    }

    fun text(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = inter
        fontVariationSettings = if (bold) "'wght' 700" else "'wght' 500"
        fontFeatureSettings = "tnum"
    }

    fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        typeface = inter
        fontVariationSettings = "'wght' 600"
        minHeight = dpi(48f)
        setTextColor(if (primary) DarkAppColors.onAccent.toArgb() else OverlayColors.text.toArgb())
        background = GradientDrawable().apply {
            cornerRadius = dp(12f)
            if (primary) setColor(DarkAppColors.accent.toArgb())
            else { setColor(0); setStroke(dpi(1f), DarkAppColors.borderInput.toArgb()) }
        }
        setOnClickListener { onClick() }
    }

    fun reducedMotion(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    fun withAlpha(argb: Int, alpha: Float): Int = ((alpha * 255).roundToInt() shl 24) or (argb and 0x00FFFFFF)
}

/**
 * The floating counter pill (S06), drawn as an accessibility overlay by the
 * service: no "Display over other apps" permission, and it exists only while
 * the service runs. Plain Views (not Compose) to keep it light.
 *
 * Never steals swipes: the window is wrap-content, not focusable, and touch
 * outside it goes straight to the app underneath. Tap opens the counter card;
 * long-press then drag moves it.
 */
class OverlayController(
    private val context: Context,
    private val onMoved: (x: Float, y: Float) -> Unit,
    private val onTap: () -> Unit,
) {
    private val kit = Kit(context)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dm = kit.dm

    private val dot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    }
    private val label = kit.text(18f, OverlayColors.text.toArgb(), bold = true).apply { includeFontPadding = false }
    private val timer = kit.text(13f, OverlayColors.textMuted.toArgb()).apply { includeFontPadding = false; visibility = View.GONE }
    private val bg = GradientDrawable()
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = bg
        elevation = kit.dp(4f)
        alpha = 0f
        addView(dot)
        addView(label)
        addView(timer)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var attached = false
    private var visible = false
    private var dragging = false
    private var scale = 1f
    private var showTimer = false
    private var lastPos: Pair<Float, Float>? = null

    init {
        applyAppearance(1, 88, false)
        attachDragHandler()
        // Keep the pill centred when its width changes (more digits, timer on/off).
        pill.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && !dragging) setPosition(lastPos)
        }
    }

    /** S14 counter settings: size 0/1/2, opacity 60-100 %, timer on/off. */
    fun applyAppearance(size: Int, opacityPct: Int, showTimer: Boolean) {
        scale = OverlayPolicy.scale(size)
        this.showTimer = showTimer
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f * scale)
        timer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f * scale)
        timer.visibility = if (showTimer) View.VISIBLE else View.GONE
        pill.minimumHeight = kit.dpi(36f * scale)
        pill.setPadding(kit.dpi(12f * scale), 0, kit.dpi(14f * scale), 0)
        dot.layoutParams = LinearLayout.LayoutParams(kit.dpi(8f * scale), kit.dpi(8f * scale)).apply { marginEnd = kit.dpi(8f) }
        timer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = kit.dpi(6f) }
        bg.cornerRadius = kit.dp(18f * scale)
        bg.setColor(kit.withAlpha(OverlayColors.bg.toArgb(), opacityPct / 100f))
        bg.setStroke(kit.dpi(1f), OverlayColors.border.toArgb())
        if (attached) wm.updateViewLayout(pill, params)
        setPosition(lastPos)
    }

    /**
     * Normalised position (x 0..1 across the free width, y 0..1 of the height);
     * null = the default: horizontally centred, below the status bar / notch and
     * the app's own header row. Recomputed on every call, so rotation and
     * different screen sizes stay right.
     */
    fun setPosition(pos: Pair<Float, Float>?) {
        lastPos = pos
        val w = screenWidth()
        val h = screenHeight()
        val top = safeTop()
        val minY = top + kit.dpi(8f)
        val maxY = (h - kit.dpi(96f)).coerceAtLeast(minY)
        val nx = pos?.first ?: 0.5f
        params.x = (nx * (w - pillWidth())).roundToInt().coerceIn(0, (w - pillWidth()).coerceAtLeast(0))
        params.y = if (pos == null) (top + kit.dpi(DEFAULT_TOP_OFFSET_DP)).coerceIn(minY, maxY)
        else (pos.second * h).roundToInt().coerceIn(minY, maxY)
        if (attached) wm.updateViewLayout(pill, params)
    }

    /** Re-apply the saved (or default) position after a rotation or resize. */
    fun relayout() = setPosition(lastPos)

    /** Screen y just below the pill, for the counter card. */
    fun anchorBottom(): Int = params.y + (if (pill.height > 0) pill.height else kit.dpi(36f * scale))

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    /** Status bar and display cutout (notch) height at the top. */
    private fun safeTop(): Int {
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
            )
            return insets.top
        }
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else kit.dpi(24f)
    }

    /** Count shown is today's total; the dot follows the all-apps video goal if there is one. */
    fun update(todayTotal: Int, goal: Int?, warnAtPct: Int) {
        val changed = label.text?.toString() != todayTotal.toString()
        label.text = todayTotal.toString()
        pill.contentDescription = context.getString(R.string.overlay_cd, todayTotal)
        val color = when (Severities.of(todayTotal, goal, warnAtPct)) {
            Severity.LOW -> OverlayColors.sevLow
            Severity.MID -> OverlayColors.sevMid
            Severity.HIGH -> OverlayColors.sevHigh
            Severity.OVER -> OverlayColors.sevOver
        }
        (dot.background as GradientDrawable).setColor(color.toArgb())
        if (changed && visible && !kit.reducedMotion()) {
            label.animate().cancel()
            label.scaleX = 1.12f; label.scaleY = 1.12f
            label.animate().scaleX(1f).scaleY(1f).setDuration(Motion.BASE_MS.toLong()).start()
        }
    }

    fun setTimer(text: String) {
        if (showTimer) timer.text = text
    }

    val isShown: Boolean get() = visible
    val wantsTimer: Boolean get() = showTimer

    fun show() {
        if (visible) return
        visible = true
        if (!attached) {
            try {
                wm.addView(pill, params)
                attached = true
                pill.post { setPosition(lastPos) } // centre again once the real width is known
            } catch (e: Exception) {
                visible = false
                return
            }
        }
        pill.animate().cancel()
        if (kit.reducedMotion()) {
            pill.alpha = 1f; pill.translationY = 0f
        } else {
            pill.translationY = -kit.dp(4f)
            pill.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
    }

    fun hide() {
        if (!visible) return
        visible = false
        pill.animate().cancel()
        if (kit.reducedMotion()) pill.alpha = 0f
        else pill.animate().alpha(0f).setDuration(120).start()
    }

    fun destroy() {
        visible = false
        if (attached) {
            try { wm.removeViewImmediate(pill) } catch (_: Exception) {}
            attached = false
        }
    }

    // Tap = card. Long-press, then drag; on release, snap to the nearest side edge.
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var downAt = 0L; var moved = false
        pill.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    downAt = e.eventTime; dragging = false; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && e.eventTime - downAt >= longPress) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).roundToInt().coerceIn(0, dm.widthPixels - pillWidth())
                        params.y = (startY + dy).roundToInt().coerceIn(0, dm.heightPixels - pill.height)
                        if (attached) wm.updateViewLayout(pill, params)
                    } else if (abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        downAt = e.eventTime // moved too early: restart the long-press wait
                        downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        // Snap to the nearest of left, centre and right.
                        val w = screenWidth()
                        val centre = params.x + pillWidth() / 2f
                        val nx = when {
                            centre < w / 3f -> 0f
                            centre > w * 2f / 3f -> 1f
                            else -> 0.5f
                        }
                        val ny = params.y.toFloat() / screenHeight()
                        dragging = false
                        setPosition(nx to ny)
                        onMoved(nx, ny)
                    } else if (!moved && e.eventTime - downAt < longPress) {
                        pill.performClick()
                        onTap()
                    }
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; true }
                else -> false
            }
        }
    }

    private fun pillWidth(): Int = if (pill.width > 0) pill.width else kit.dpi(72f * scale)

    private companion object {
        /** Below the status bar: clears the header row of TikTok, Reels and Shorts. */
        const val DEFAULT_TOP_OFFSET_DP = 64f
    }
}

/** S07: small card under the pill. Never focusable; closes on its own after 6 s. */
class CounterCard(
    private val context: Context,
    private val onPause: () -> Unit,
    private val onHideUntilLeave: () -> Unit,
    private val onOpenApp: () -> Unit,
) {
    private val kit = Kit(context)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val sessionLine = kit.text(16f, OverlayColors.text.toArgb(), bold = true)
    private val todayLine = kit.text(14f, OverlayColors.textMuted.toArgb())
    private var root: View? = null
    private val autoClose = Runnable { dismiss() }

    val isShown: Boolean get() = root != null

    fun show(sessionVideos: Int, sessionTime: String, today: Int, anchorY: Int) {
        dismiss()
        sessionLine.text = context.getString(R.string.card_session, sessionVideos, sessionTime)
        todayLine.text = context.getString(R.string.card_today, today)
        val pad = kit.dpi(16f)
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = kit.dp(20f)
                setColor(OverlayColors.bg.toArgb())
                setStroke(kit.dpi(1f), OverlayColors.border.toArgb())
            }
            elevation = kit.dp(8f)
            addView(sessionLine)
            addView(todayLine)
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, kit.dpi(12f), 0, 0) }
            row.addView(kit.button(context.getString(R.string.card_pause), primary = false) { onPause(); dismiss() })
            row.addView(View(context), LinearLayout.LayoutParams(kit.dpi(8f), 1))
            row.addView(kit.button(context.getString(R.string.card_hide), primary = false) { onHideUntilLeave(); dismiss() })
            addView(row)
            addView(kit.button(context.getString(R.string.card_open), primary = true) { onOpenApp(); dismiss() },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = kit.dpi(8f) })
            contentDescription = context.getString(R.string.card_cd)
        }
        val lp = WindowManager.LayoutParams(
            kit.dpi(300f), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = anchorY + kit.dpi(8f)
        }
        view.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismiss(); true } else { v.performClick(); false }
        }
        try {
            wm.addView(view, lp)
            root = view
            view.postDelayed(autoClose, 6_000)
        } catch (_: Exception) {
        }
    }

    fun dismiss() {
        val r = root ?: return
        r.removeCallbacks(autoClose)
        try { wm.removeViewImmediate(r) } catch (_: Exception) {}
        root = null
    }
}

/**
 * S12: full-screen interrupt when a blocking goal is reached. It consumes
 * touches (that is the point). "Close app" goes to the home screen; a snooze
 * is offered while the goal has snoozes left today.
 */
class BlockScreen(
    private val context: Context,
    private val onCloseApp: () -> Unit,
    private val onSnooze: () -> Unit,
    private val onBreathe: () -> Unit,
) {
    private val kit = Kit(context)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    val isShown: Boolean get() = root != null

    fun show(used: Int, target: Int, minutes: Boolean, appName: String?, canSnooze: Boolean) {
        dismiss()
        val what = appName ?: context.getString(R.string.all_apps)
        val unit = context.getString(if (minutes) R.string.unit_minutes else R.string.unit_videos)
        val pad = kit.dpi(24f)
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(kit.withAlpha(DarkAppColors.bg.toArgb(), 0.96f))
            addView(kit.text(56f, OverlayColors.text.toArgb(), bold = true).apply { text = used.toString(); gravity = Gravity.CENTER })
            addView(kit.text(22f, OverlayColors.text.toArgb(), bold = true).apply {
                text = context.getString(R.string.block_title); gravity = Gravity.CENTER; setPadding(0, kit.dpi(8f), 0, kit.dpi(8f))
            })
            addView(kit.text(16f, OverlayColors.textMuted.toArgb()).apply {
                text = context.getString(R.string.block_body, target, unit, what); gravity = Gravity.CENTER
            })
            val full = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = kit.dpi(12f) }
            addView(kit.button(context.getString(R.string.block_close), primary = true) { onCloseApp() }, full)
            addView(kit.button(context.getString(R.string.block_breathe), primary = false) { onBreathe() },
                LinearLayout.LayoutParams(full))
            if (canSnooze) {
                addView(kit.button(context.getString(R.string.block_snooze), primary = false) { onSnooze() },
                    LinearLayout.LayoutParams(full))
            }
            isClickable = true // swallow touches meant for the video underneath
            contentDescription = context.getString(R.string.block_title)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            wm.addView(view, lp)
            root = view
            view.announceForAccessibility(context.getString(R.string.block_title))
        } catch (_: Exception) {
        }
    }

    fun dismiss() {
        val r = root ?: return
        try { wm.removeViewImmediate(r) } catch (_: Exception) {}
        root = null
    }
}

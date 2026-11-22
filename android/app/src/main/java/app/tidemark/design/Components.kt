package app.tidemark.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeoSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tidemark.R
import app.tidemark.core.Severity

// Primitives from replica/design/components.md. Colours come only from
// LocalAppColors / OverlayColors, sizes only from Space / Radius / Size.

enum class ButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(Radius.md)
    val content: @Composable () -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(Size.iconSm), strokeWidth = 2.dp, color = LocalContentColorFor(variant))
            Spacer(Modifier.size(Space.xs))
        }
        Text(text, style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold))
    }
    val m = modifier.heightIn(min = Size.buttonMd).semantics { if (loading) contentDescription = "$text, loading" }
    val click = { if (!loading) onClick() }
    when (variant) {
        ButtonVariant.PRIMARY -> Button(click, m, enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) { content() }
        ButtonVariant.DANGER -> Button(click, m, enabled, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onDanger)) { content() }
        ButtonVariant.SECONDARY -> OutlinedButton(click, m, enabled, shape = shape,
            border = BorderStroke(1.dp, c.borderInput),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.text)) { content() }
        ButtonVariant.GHOST -> TextButton(click, m, enabled, shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = c.accent)) { content() }
    }
}

@Composable
private fun LocalContentColorFor(variant: ButtonVariant): Color {
    val c = LocalAppColors.current
    return when (variant) {
        ButtonVariant.PRIMARY -> c.onAccent
        ButtonVariant.DANGER -> c.onDanger
        ButtonVariant.SECONDARY -> c.text
        ButtonVariant.GHOST -> c.accent
    }
}

@Composable
fun AppCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalAppColors.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        color = c.surface,
        border = if (c.isDark) BorderStroke(1.dp, c.border) else null,
        shadowElevation = if (c.isDark) 0.dp else 1.dp,
    ) {
        Box(Modifier.padding(Space.md)) { content() }
    }
}

fun severityColor(c: AppColors, s: Severity): Color = when (s) {
    Severity.LOW -> c.sevLow
    Severity.MID -> c.sevMid
    Severity.HIGH -> c.sevHigh
    Severity.OVER -> c.sevOver
}

@Composable
fun severityLabel(s: Severity, hasGoal: Boolean): String = stringResource(
    when (s) {
        Severity.LOW -> R.string.severity_low
        Severity.MID -> R.string.severity_mid
        Severity.HIGH -> R.string.severity_high
        Severity.OVER -> if (hasGoal) R.string.severity_over_goal else R.string.severity_over
    }
)

/** Colour dot + word: severity is never colour alone. */
@Composable
fun SeverityChip(severity: Severity, hasGoal: Boolean, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    val color = severityColor(c, severity)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.size(6.dp))
        Text(severityLabel(severity, hasGoal), style = AppType.labelSm.copy(fontWeight = FontWeight.SemiBold), color = color)
    }
}

/**
 * Ring meter. progress 0..1 (with a goal: count/goal; without: count/300).
 * One accessibility node carrying the whole sentence.
 */
@Composable
fun MeterRing(
    count: Int,
    progress: Float,
    severity: Severity,
    caption: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val fill = severityColor(c, severity)
    Box(modifier.size(200.dp).semantics(mergeDescendants = true) { contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arcSize = GeoSize(size.width - stroke, size.height - stroke)
            drawArc(c.surface2, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(fill, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(count.toString(), style = AppType.display, color = c.text)
            Text(caption, style = AppType.bodySm, color = c.textMuted)
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, delta: String? = null, deltaGood: Boolean? = null) {
    val c = LocalAppColors.current
    AppCard(modifier.semantics(mergeDescendants = true) {}) {
        Column {
            Text(label, style = AppType.bodySm, color = c.textMuted)
            Text(value, style = AppType.stat, color = c.text)
            if (delta != null) {
                Text(
                    delta,
                    style = AppType.labelSm.copy(fontWeight = FontWeight.SemiBold),
                    color = when (deltaGood) { true -> c.sevLow; false -> c.sevHigh; null -> c.textMuted },
                )
            }
        }
    }
}

enum class BannerKind { INFO, WARNING, ERROR }

@Composable
fun AppBanner(kind: BannerKind, text: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = LocalAppColors.current
    val stripe = when (kind) { BannerKind.INFO -> c.accent; BannerKind.WARNING -> c.warning; BannerKind.ERROR -> c.danger }
    val icon = when (kind) { BannerKind.INFO -> AppIcons.Check; BannerKind.WARNING -> AppIcons.Warning; BannerKind.ERROR -> AppIcons.Error }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).background(c.surface2)
            .heightIn(min = Size.touchMin),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 4.dp, height = Size.touchMin).background(stripe))
        Icon(icon, contentDescription = null, tint = stripe, modifier = Modifier.padding(start = Space.sm).size(Size.icon))
        Text(text, style = AppType.bodySm, color = c.text, modifier = Modifier.weight(1f).padding(Space.sm))
        if (actionLabel != null && onAction != null) {
            AppButton(actionLabel, onAction, variant = ButtonVariant.GHOST)
        }
    }
}

@Composable
fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    grantedLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    Row(modifier.fillMaxWidth().heightIn(min = Size.listRow), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (granted) AppIcons.Check else AppIcons.Warning,
            contentDescription = null,
            tint = if (granted) c.success else c.warning,
            modifier = Modifier.size(Size.icon),
        )
        Column(Modifier.weight(1f).padding(horizontal = Space.sm)) {
            Text(title, style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Text(body, style = AppType.bodySm, color = c.textMuted)
        }
        if (granted) Text(grantedLabel, style = AppType.bodySm, color = c.textMuted)
        else AppButton(actionLabel, onAction)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Column(modifier.widthIn(max = 320.dp).padding(Space.lg), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Icon(icon, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(48.dp))
        Text(title, style = AppType.title, color = c.text, textAlign = TextAlign.Center)
        Text(body, style = AppType.bodySm, color = c.textMuted, textAlign = TextAlign.Center)
    }
}

/** Compose twin of the overlay pill, for the design screen only. */
@Composable
fun CounterPillPreview(count: Int, severity: Severity, timer: String? = null) {
    val dot = when (severity) {
        Severity.LOW -> OverlayColors.sevLow
        Severity.MID -> OverlayColors.sevMid
        Severity.HIGH -> OverlayColors.sevHigh
        Severity.OVER -> OverlayColors.sevOver
    }
    Row(
        Modifier.height(Size.overlayPillH).clip(RoundedCornerShape(Radius.pill))
            .background(OverlayColors.bg.copy(alpha = 0.88f))
            .border(1.dp, OverlayColors.border, RoundedCornerShape(Radius.pill))
            .padding(start = Space.sm, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.size(Space.xs))
        Text(count.toString(), style = AppType.overlay, color = OverlayColors.text)
        if (timer != null) Text(" · $timer", style = AppType.bodySm, color = OverlayColors.textMuted)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = AppType.labelSm.copy(fontWeight = FontWeight.SemiBold), color = LocalAppColors.current.textMuted,
        modifier = modifier.padding(top = Space.sm))
}

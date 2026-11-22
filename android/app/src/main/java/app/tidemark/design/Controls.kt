package app.tidemark.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.tidemark.R

/** Day / Week / Month and similar. Tabs semantics, 48 dp touch height. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.pill)).background(c.surface2).padding(4.dp).selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.pill))
                    .background(if (on) c.surface else c.surface2)
                    .selectable(selected = on, onClick = { onSelect(i) }, role = Role.Tab),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.text else c.textMuted)
            }
        }
    }
}

/**
 * Bar chart drawn on Canvas (no chart library). Optional dashed goal line,
 * tap a bar to select it. One accessibility node with a summary; screens
 * offer a "view as list" alternative with the same numbers.
 */
@Composable
fun BarChart(
    values: List<Int>,
    labels: List<String>,
    summary: String,
    modifier: Modifier = Modifier,
    goal: Int? = null,
    selected: Int? = null,
    onSelect: (Int) -> Unit = {},
) {
    val c = LocalAppColors.current
    val max = maxOf(values.maxOrNull() ?: 0, goal ?: 0, 1)
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = summary }) {
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).pointerInput(values.size) {
                detectTapGestures { pos ->
                    if (values.isNotEmpty()) onSelect((pos.x / (size.width / values.size)).toInt().coerceIn(0, values.size - 1))
                }
            },
        ) {
            val slot = size.width / values.size.coerceAtLeast(1)
            val barW = (slot * 0.6f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = size.height * v / max
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(
                    color = if (selected == null || selected == i) c.accent else c.accent.copy(alpha = 0.45f),
                    topLeft = Offset(x, size.height - h),
                    size = androidx.compose.ui.geometry.Size(barW, h),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
            }
            if (goal != null && goal > 0) {
                val y = size.height - size.height * goal / max
                drawLine(
                    color = c.textMuted, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())),
                )
            }
            drawLine(c.border, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
        }
        if (labels.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                labels.forEach {
                    Text(it, style = AppType.labelSm, color = c.textMuted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/** Title + subtitle + M3 Switch; the whole row toggles. */
@Composable
fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    val c = LocalAppColors.current
    Row(
        modifier.fillMaxWidth().heightIn(min = Size.listRow)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(title, style = AppType.body, color = if (enabled) c.text else c.textMuted)
            if (subtitle != null) Text(subtitle, style = AppType.bodySm, color = c.textMuted)
        }
        Switch(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent, uncheckedBorderColor = c.borderInput),
        )
    }
}

/** − value + with a unit; long values never truncate. Adjustable for TalkBack via the buttons. */
@Composable
fun StepperRow(title: String, value: Int, valueText: String, onChange: (Int) -> Unit, min: Int, max: Int, step: Int, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    Row(modifier.fillMaxWidth().heightIn(min = Size.listRow), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.body, color = c.text, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((value - step).coerceAtLeast(min)) }, enabled = value > min,
            modifier = Modifier.semantics { contentDescription = "−$step" }) {
            Text("−", style = AppType.title, color = if (value > min) c.accent else c.textMuted)
        }
        Text(valueText, style = AppType.title.copy(fontWeight = FontWeight.Bold), color = c.text,
            modifier = Modifier.semantics { stateDescription = valueText })
        IconButton(onClick = { onChange((value + step).coerceAtMost(max)) }, enabled = value < max,
            modifier = Modifier.semantics { contentDescription = "+$step" }) {
            Text("+", style = AppType.title, color = if (value < max) c.accent else c.textMuted)
        }
    }
}

@Composable
fun SliderRow(title: String, value: Int, valueText: String, range: IntRange, steps: Int, onChange: (Int) -> Unit) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = AppType.body, color = c.text, modifier = Modifier.weight(1f))
            Text(valueText, style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
        }
        Slider(
            value = value.toFloat(), onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface2),
        )
    }
}

/** Radio list in a card. */
@Composable
fun <T> RadioGroup(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val c = LocalAppColors.current
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = Size.touchMin)
                    .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(selected = value == selected, onClick = null)
                Text(label, style = AppType.body, color = c.text, modifier = Modifier.padding(start = Space.sm))
            }
        }
    }
}

/** A row that navigates somewhere. */
@Composable
fun LinkRow(title: String, onClick: () -> Unit, subtitle: String? = null) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = Size.listRow).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AppType.body, color = c.text)
            if (subtitle != null) Text(subtitle, style = AppType.bodySm, color = c.textMuted)
        }
        Icon(AppIcons.Chevron, contentDescription = null, tint = c.textMuted, modifier = Modifier.size(Size.iconSm))
    }
}

/** Destructive confirm: explicit second tap, no swipe-to-confirm. */
@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = AppType.title, color = c.text) },
        text = { Text(body, style = AppType.body, color = c.text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = c.danger, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = c.text) } },
        containerColor = c.surface,
    )
}

/** Back + title header for sub-screens. */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)?) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = Size.touchMin), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.back), tint = c.text) }
        }
        Text(title, style = AppType.headline, color = c.text, modifier = Modifier.semantics { androidx.compose.ui.semantics.heading() })
    }
}

@Composable
fun DayChips(mask: Int, labels: List<String>, onChange: (Int) -> Unit) {
    val c = LocalAppColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            val on = mask and (1 shl i) != 0
            Box(
                Modifier.weight(1f).heightIn(min = Size.touchMin).clip(RoundedCornerShape(Radius.md))
                    .background(if (on) c.accent else c.surface2)
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = {
                        val next = if (on) mask and (1 shl i).inv() else mask or (1 shl i)
                        if (next != 0) onChange(next) // at least one day
                    }),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.onAccent else c.text)
            }
        }
    }
}

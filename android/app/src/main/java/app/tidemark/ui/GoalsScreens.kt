package app.tidemark.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tidemark.R
import app.tidemark.app
import app.tidemark.core.GoalLevel
import app.tidemark.core.GoalLogic
import app.tidemark.core.UsageTotals
import app.tidemark.data.GoalRow
import app.tidemark.data.Usage
import app.tidemark.design.AppButton
import app.tidemark.design.AppCard
import app.tidemark.design.AppIcons
import app.tidemark.design.AppType
import app.tidemark.design.ButtonVariant
import app.tidemark.design.ConfirmDialog
import app.tidemark.design.DayChips
import app.tidemark.design.EmptyState
import app.tidemark.design.LocalAppColors
import app.tidemark.design.RadioGroup
import app.tidemark.design.ScreenHeader
import app.tidemark.design.SectionTitle
import app.tidemark.design.SegmentedControl
import app.tidemark.design.SliderRow
import app.tidemark.design.Space
import app.tidemark.design.StepperRow
import app.tidemark.design.SwitchRow
import app.tidemark.design.severityColor
import app.tidemark.core.Severities
import app.tidemark.service.ServiceStatus
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** S11: every goal with today's progress, last 7 days and streak. All free. */
@Composable
fun GoalsScreen(onEdit: (Long) -> Unit) {
    val graph = LocalContext.current.app
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val goals by graph.db.goals().all().collectAsStateWithLifecycle(emptyList())
    val apps by graph.db.trackedApps().all().collectAsStateWithLifecycle(emptyList())
    val names = apps.associate { it.packageName to it.displayName }
    val totals by produceState(UsageTotals()) {
        while (true) {
            value = Usage.totals(graph.db, graph.dayKey(System.currentTimeMillis()))
            delay(5_000)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(stringResource(R.string.goals_intro), style = AppType.bodySm, color = c.textMuted)
        if (goals.isEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(AppIcons.Goals, stringResource(R.string.goals_empty_title), stringResource(R.string.goals_empty_body))
            }
        }
        goals.forEach { g ->
            GoalCard(
                g, names, totals,
                onEdit = { onEdit(g.id) },
                onToggle = { on -> scope.launch { graph.db.goals().setEnabled(g.id, on, System.currentTimeMillis()); graph.writer.refreshToday() } },
            )
        }
        AppButton(stringResource(R.string.goal_add), { onEdit(0L) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun GoalCard(g: GoalRow, names: Map<String, String>, totals: UsageTotals, onEdit: () -> Unit, onToggle: (Boolean) -> Unit) {
    val c = LocalAppColors.current
    val graph = LocalContext.current.app
    val today = graph.dayKey(System.currentTimeMillis())
    val results by produceState(emptyList<Pair<String, Boolean>>(), g.id) {
        value = graph.db.goals().dayResults(g.id, 60).map { it.dayKey to (it.kind == "met_day") }
    }
    val used = GoalLogic.used(g, totals)
    val level = GoalLogic.level(used, g.value, g.warnAtPct)
    val sev = Severities.of(used, g.value, g.warnAtPct)
    val streak = GoalLogic.streak(results, today)
    val unit = stringResource(if (g.metric == GoalRow.METRIC_MINUTES) R.string.unit_minutes else R.string.unit_videos)
    val scopeName = g.packageName?.let { names[it] ?: it } ?: stringResource(R.string.all_apps)

    AppCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onEdit)) {
                    Text("$scopeName · ${g.value} $unit", style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                    Text(goalSubtitle(g), style = AppType.bodySm, color = c.textMuted)
                }
                androidx.compose.material3.Switch(checked = g.enabled, onCheckedChange = onToggle,
                    modifier = Modifier.semantics { contentDescription = scopeName })
            }
            // Progress bar
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(999.dp)).background(c.surface2)) {
                Box(Modifier.fillMaxWidth((used.toFloat() / g.value).coerceIn(0f, 1f)).height(10.dp)
                    .clip(RoundedCornerShape(999.dp)).background(severityColor(c, sev)))
            }
            Text(
                when (level) {
                    GoalLevel.REACHED -> stringResource(R.string.goal_today_over, used, g.value, unit)
                    else -> stringResource(R.string.goal_today_progress, used, g.value, unit)
                },
                style = AppType.bodySm, color = c.text,
            )
            // Last 7 days, oldest left. Each dot has a word for screen readers.
            val last7 = (7 downTo 1).map { LocalDate.parse(today).minusDays(it.toLong()).toString() }
            val byDay = results.toMap()
            val metLabel = stringResource(R.string.goal_day_met)
            val missLabel = stringResource(R.string.goal_day_missed)
            val noneLabel = stringResource(R.string.goal_day_none)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {}) {
                last7.forEach { d ->
                    val met = byDay[d]
                    Box(Modifier.size(12.dp).clip(CircleShape).background(
                        when (met) { true -> c.sevLow; false -> c.sevOver; null -> c.surface2 }
                    ).semantics { contentDescription = "${shortDate(LocalDate.parse(d))}: " + when (met) { true -> metLabel; false -> missLabel; null -> noneLabel } })
                }
                Text(stringResource(R.string.goal_streak, streak), style = AppType.bodySm, color = c.textMuted, modifier = Modifier.padding(start = Space.xs))
            }
        }
    }
}

@Composable
private fun goalSubtitle(g: GoalRow): String {
    val parts = mutableListOf<String>()
    parts += stringResource(if (g.action == GoalRow.ACTION_BLOCK) R.string.goal_blocks else R.string.goal_notifies)
    if (g.daysMask != 127) parts += (1..7).filter { g.daysMask and (1 shl (it - 1)) != 0 }.joinToString(" ") { weekdayNarrow(it) }
    val from = g.activeFromMin; val to = g.activeToMin
    if (from != null && to != null) parts += "${clockOfMinute(from)}–${clockOfMinute(to)}"
    val step = g.stepPct; val floor = g.floorValue
    if (step != null && floor != null) parts += stringResource(R.string.goal_reduction_short, step, floor)
    return parts.joinToString(" · ")
}

/** S11 editor. id 0 = new goal. */
@Composable
fun GoalEditorScreen(id: Long, onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()
    val apps by graph.db.trackedApps().all().collectAsStateWithLifecycle(emptyList())
    val now = System.currentTimeMillis()
    var g by remember { mutableStateOf(GoalRow(value = 100, createdAt = now, updatedAt = now)) }
    var loaded by remember { mutableStateOf(id == 0L) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        if (id != 0L) graph.db.goals().byId(id)?.let { g = it }
        loaded = true
    }
    if (!loaded) return
    val minutes = g.metric == GoalRow.METRIC_MINUTES
    val unit = stringResource(if (minutes) R.string.unit_minutes else R.string.unit_videos)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(if (id == 0L) R.string.goal_new else R.string.goal_edit), onDone)

        SectionTitle(stringResource(R.string.goal_scope))
        AppCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                listOf<Pair<String?, String>>(null to stringResource(R.string.all_apps)) +
                    apps.filter { it.enabled && (ServiceStatus.isInstalled(context, it.packageName) || it.packageName == g.packageName) }
                        .map { it.packageName to it.displayName },
                g.packageName,
            ) { g = g.copy(packageName = it) }
        }

        SectionTitle(stringResource(R.string.goal_metric))
        SegmentedControl(listOf(stringResource(R.string.unit_videos), stringResource(R.string.unit_minutes)), if (minutes) 1 else 0, {
            val m = if (it == 1) GoalRow.METRIC_MINUTES else GoalRow.METRIC_REELS
            if (m != g.metric) g = g.copy(metric = m, value = if (it == 1) 30 else 100, floorValue = g.floorValue?.let { _ -> if (it == 1) 10 else 30 })
        })
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                StepperRow(stringResource(R.string.goal_target), g.value, "${g.value} $unit",
                    { g = g.copy(value = it, floorValue = g.floorValue?.coerceAtMost(it)) },
                    min = if (minutes) 5 else 10, max = if (minutes) 600 else 2000, step = if (minutes) 5 else 10)
                SliderRow(stringResource(R.string.goal_warn_at), g.warnAtPct, "${g.warnAtPct}%", 50..95, steps = 8) { g = g.copy(warnAtPct = it) }
            }
        }

        SectionTitle(stringResource(R.string.goal_when_reached))
        SegmentedControl(listOf(stringResource(R.string.goal_action_notify), stringResource(R.string.goal_action_block)),
            if (g.action == GoalRow.ACTION_BLOCK) 1 else 0, { g = g.copy(action = if (it == 1) GoalRow.ACTION_BLOCK else GoalRow.ACTION_NOTIFY) })
        if (g.action == GoalRow.ACTION_BLOCK) {
            AppCard(Modifier.fillMaxWidth()) {
                StepperRow(stringResource(R.string.goal_snoozes), g.snoozesPerDay, g.snoozesPerDay.toString(),
                    { g = g.copy(snoozesPerDay = it) }, min = 0, max = 5, step = 1)
            }
        }

        SectionTitle(stringResource(R.string.goal_days))
        DayChips(g.daysMask, (1..7).map { weekdayNarrow(it) }) { g = g.copy(daysMask = it) }

        AppCard(Modifier.fillMaxWidth()) {
            Column {
                val windowOn = g.activeFromMin != null && g.activeToMin != null
                SwitchRow(stringResource(R.string.goal_window), windowOn, { on ->
                    g = if (on) g.copy(activeFromMin = 22 * 60, activeToMin = 6 * 60) else g.copy(activeFromMin = null, activeToMin = null)
                }, subtitle = stringResource(R.string.goal_window_hint))
                val from = g.activeFromMin; val to = g.activeToMin
                if (from != null && to != null) {
                    StepperRow(stringResource(R.string.goal_from), from / 60, clockOfMinute(from), { g = g.copy(activeFromMin = it * 60) }, 0, 23, 1)
                    StepperRow(stringResource(R.string.goal_to), to / 60, clockOfMinute(to), { g = g.copy(activeToMin = it * 60) }, 0, 23, 1)
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column {
                val reduceOn = g.stepPct != null
                SwitchRow(stringResource(R.string.goal_reduce), reduceOn, { on ->
                    g = if (on) g.copy(stepPct = 10, floorValue = (g.value / 3).coerceAtLeast(if (minutes) 5 else 10))
                    else g.copy(stepPct = null, floorValue = null, nextStepDay = null)
                }, subtitle = stringResource(R.string.goal_reduce_hint))
                val step = g.stepPct; val floorV = g.floorValue
                if (step != null && floorV != null) {
                    StepperRow(stringResource(R.string.goal_reduce_step), step, "$step%", { g = g.copy(stepPct = it) }, 5, 30, 5)
                    StepperRow(stringResource(R.string.goal_reduce_floor), floorV, "$floorV $unit", { g = g.copy(floorValue = it) },
                        if (minutes) 5 else 10, g.value, if (minutes) 5 else 10)
                }
            }
        }

        AppButton(stringResource(R.string.save), {
            scope.launch {
                val t = System.currentTimeMillis()
                val today = LocalDate.parse(graph.dayKey(t))
                val toSave = g.copy(
                    updatedAt = t,
                    nextStepDay = if (g.stepPct != null) g.nextStepDay ?: today.plusDays(7).toString() else null,
                )
                graph.db.goals().save(toSave)
                graph.writer.refreshToday()
                onDone()
            }
        }, modifier = Modifier.fillMaxWidth())
        if (id != 0L) {
            AppButton(stringResource(R.string.goal_delete), { confirmDelete = true }, variant = ButtonVariant.DANGER, modifier = Modifier.fillMaxWidth())
        }
        if (confirmDelete) {
            ConfirmDialog(
                stringResource(R.string.goal_delete), stringResource(R.string.goal_delete_body), stringResource(R.string.delete),
                onConfirm = { scope.launch { graph.db.goals().delete(id); graph.writer.refreshToday(); confirmDelete = false; onDone() } },
                onDismiss = { confirmDelete = false },
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(Space.lg))
    }
}

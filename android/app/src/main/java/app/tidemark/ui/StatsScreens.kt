package app.tidemark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tidemark.R
import app.tidemark.app
import app.tidemark.core.DayRange
import app.tidemark.core.Period
import app.tidemark.core.StatsLogic
import app.tidemark.data.GoalRow
import app.tidemark.data.SessionRow
import app.tidemark.design.AppButton
import app.tidemark.design.AppCard
import app.tidemark.design.AppIcons
import app.tidemark.design.AppType
import app.tidemark.design.BarChart
import app.tidemark.design.ButtonVariant
import app.tidemark.design.EmptyState
import app.tidemark.design.LocalAppColors
import app.tidemark.design.ScreenHeader
import app.tidemark.design.SectionTitle
import app.tidemark.design.SegmentedControl
import app.tidemark.design.Size
import app.tidemark.design.Space
import app.tidemark.design.StatCard
import java.time.LocalDate
import java.time.ZoneId

/** S08: Day / Week / Month with previous-period comparison, chart or list, per-app split. */
@Composable
fun StatsScreen(onOpenApp: (String) -> Unit, onHistory: () -> Unit) {
    val graph = LocalContext.current.app
    var periodIdx by remember { mutableIntStateOf(1) }
    var offset by remember { mutableIntStateOf(0) }
    var asList by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val period = Period.entries[periodIdx]
    val today = LocalDate.parse(graph.dayKey(System.currentTimeMillis()))
    val range = StatsLogic.rangeFor(period, today, offset)
    val prev = StatsLogic.rangeFor(period, today, offset + 1)

    val rows by remember(range) { graph.db.stats().range(range.fromKey, range.toKey) }.collectAsStateWithLifecycle(emptyList())
    val prevRows by remember(prev) { graph.db.stats().range(prev.fromKey, prev.toKey) }.collectAsStateWithLifecycle(emptyList())
    val times by remember(range) { graph.db.reelEvents().timesForDay(range.fromKey, null) }.collectAsStateWithLifecycle(emptyList())
    val apps by graph.db.trackedApps().all().collectAsStateWithLifecycle(emptyList())
    val goals by graph.db.goals().all().collectAsStateWithLifecycle(emptyList())
    val names = apps.associate { it.packageName to it.displayName }
    val reelsGoal = goals.firstOrNull { it.enabled && it.packageName == null && it.metric == GoalRow.METRIC_REELS }

    val series = StatsLogic.dailySeries(rows, range)
    val summary = StatsLogic.summary(series, today)
    val prevTotal = prevRows.sumOf { it.reels }
    val delta = StatsLogic.deltaPct(summary.total, prevTotal)
    val c = LocalAppColors.current

    val values: List<Int>
    val labels: List<String>
    if (period == Period.DAY) {
        values = StatsLogic.hourly(times, ZoneId.systemDefault()).toList()
        labels = (0 until 24).map { if (it % 6 == 0) it.toString() else "" }
    } else {
        values = series.map { it.reels }
        labels = series.mapIndexed { i, d ->
            if (period == Period.WEEK) weekdayShort(d.day) else if (i % 7 == 0) d.day.dayOfMonth.toString() else ""
        }
    }
    val chartSummary = stringResource(R.string.chart_summary, summary.total, rangeLabel(period, range, offset))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        SegmentedControl(
            listOf(stringResource(R.string.period_day), stringResource(R.string.period_week), stringResource(R.string.period_month)),
            periodIdx, onSelect = { periodIdx = it; offset = 0; selected = null },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { offset++; selected = null }) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.previous_period), tint = c.text)
            }
            Text(rangeLabel(period, range, offset), style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = { if (offset > 0) { offset--; selected = null } }, enabled = offset > 0) {
                Icon(AppIcons.Back, contentDescription = stringResource(R.string.next_period),
                    tint = if (offset > 0) c.text else c.textMuted, modifier = Modifier.rotate(180f))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatCard(stringResource(R.string.stat_total), summary.total.toString(), Modifier.weight(1f),
                delta = delta?.let { deltaText(it, period) }, deltaGood = delta?.let { it <= 0 })
            StatCard(stringResource(if (period == Period.DAY) R.string.stat_minutes else R.string.stat_avg_day),
                if (period == Period.DAY) (summary.activeMs / 60_000L).toString() else summary.avgPerDay.toString(), Modifier.weight(1f))
        }
        if (period != Period.DAY) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                StatCard(stringResource(R.string.stat_minutes), (summary.activeMs / 60_000L).toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.stat_sessions), summary.sessions.toString(), Modifier.weight(1f))
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (summary.total == 0) {
                    EmptyState(AppIcons.Stats, stringResource(R.string.stats_empty_title), stringResource(R.string.stats_empty_body))
                } else if (asList) {
                    val listRows = if (period == Period.DAY) values.mapIndexed { h, v -> "%02d:00".format(h) to v }.filter { it.second > 0 }
                    else series.map { shortDate(it.day) to it.reels }
                    listRows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp)) {
                            Text(k, style = AppType.bodySm, color = c.text, modifier = Modifier.weight(1f))
                            Text(v.toString(), style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        }
                    }
                } else {
                    BarChart(
                        values, labels, chartSummary,
                        goal = if (period != Period.DAY) reelsGoal?.value else null,
                        selected = selected, onSelect = { selected = it },
                    )
                    selected?.let { i ->
                        val text = if (period == Period.DAY) stringResource(R.string.chart_hour_detail, i, values.getOrElse(i) { 0 })
                        else stringResource(R.string.chart_day_detail, shortDate(series[i].day), series[i].reels, (series[i].activeMs / 60_000L).toInt())
                        Text(text, style = AppType.bodySm, color = c.text)
                    }
                }
                AppButton(stringResource(if (asList) R.string.view_chart else R.string.view_list), { asList = !asList }, variant = ButtonVariant.GHOST)
            }
        }

        if (period != Period.DAY && summary.total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                summary.best?.let { StatCard(stringResource(R.string.stat_best), "${it.reels}", Modifier.weight(1f), delta = shortDate(it.day)) }
                summary.worst?.let { StatCard(stringResource(R.string.stat_worst), "${it.reels}", Modifier.weight(1f), delta = shortDate(it.day)) }
            }
        }

        val perApp = StatsLogic.perApp(rows)
        if (perApp.isNotEmpty()) {
            SectionTitle(stringResource(R.string.today_by_app))
            AppCard(Modifier.fillMaxWidth()) {
                Column {
                    perApp.forEachIndexed { i, (pkg, n) ->
                        if (i > 0) HorizontalDivider(color = c.border)
                        Row(Modifier.fillMaxWidth().heightIn(min = Size.touchMin).clickable(role = Role.Button) { onOpenApp(pkg) },
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(names[pkg] ?: pkg, style = AppType.body, color = c.text, modifier = Modifier.weight(1f))
                            Text(n.toString(), style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                        }
                    }
                }
            }
        }
        AppButton(stringResource(R.string.open_history), onHistory, variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun rangeLabel(period: Period, range: DayRange, offset: Int): String = when {
    offset == 0 && period == Period.DAY -> stringResource(R.string.today)
    offset == 0 && period == Period.WEEK -> stringResource(R.string.this_week)
    offset == 0 && period == Period.MONTH -> stringResource(R.string.this_month)
    period == Period.DAY -> shortDate(range.from)
    else -> "${shortDate(range.from)} – ${shortDate(range.to)}"
}

@Composable
private fun deltaText(pct: Int, period: Period): String {
    val vs = stringResource(
        when (period) { Period.DAY -> R.string.vs_yesterday; Period.WEEK -> R.string.vs_last_week; Period.MONTH -> R.string.vs_last_month }
    )
    return if (pct <= 0) "▼ ${-pct}% $vs" else "▲ $pct% $vs"
}

/** S09: one app over the current week, plus its recent sessions. */
@Composable
fun AppDetailScreen(pkg: String, onBack: () -> Unit) {
    val graph = LocalContext.current.app
    val c = LocalAppColors.current
    val today = LocalDate.parse(graph.dayKey(System.currentTimeMillis()))
    var periodIdx by remember { mutableIntStateOf(1) }
    val period = if (periodIdx == 0) Period.WEEK else Period.MONTH
    val range = StatsLogic.rangeFor(period, today)
    val rows by remember(range) { graph.db.stats().range(range.fromKey, range.toKey) }.collectAsStateWithLifecycle(emptyList())
    val sessions by remember(pkg) { graph.db.sessions().recentForApp(pkg, 50) }.collectAsStateWithLifecycle(emptyList())
    val apps by graph.db.trackedApps().all().collectAsStateWithLifecycle(emptyList())
    val name = apps.firstOrNull { it.packageName == pkg }?.displayName ?: pkg
    val series = StatsLogic.dailySeries(rows, range, pkg)
    val summary = StatsLogic.summary(series, today)
    val avgSessionMin = if (sessions.isEmpty()) 0 else (sessions.sumOf { it.activeMs } / sessions.size / 60_000L).toInt()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(name, onBack)
        SegmentedControl(listOf(stringResource(R.string.period_week), stringResource(R.string.period_month)), periodIdx, { periodIdx = it })
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatCard(stringResource(R.string.stat_total), summary.total.toString(), Modifier.weight(1f))
            StatCard(stringResource(R.string.stat_avg_session), stringResource(R.string.minutes_short, avgSessionMin), Modifier.weight(1f))
        }
        AppCard(Modifier.fillMaxWidth()) {
            BarChart(
                series.map { it.reels },
                series.mapIndexed { i, d -> if (period == Period.WEEK) weekdayShort(d.day) else if (i % 7 == 0) d.day.dayOfMonth.toString() else "" },
                stringResource(R.string.chart_summary, summary.total, name),
            )
        }
        SectionTitle(stringResource(R.string.recent_sessions))
        if (sessions.isEmpty()) {
            EmptyState(AppIcons.Video, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
        } else {
            AppCard(Modifier.fillMaxWidth()) { Column { sessions.take(20).forEachIndexed { i, s -> if (i > 0) HorizontalDivider(color = c.border); SessionLine(s, name) } } }
        }
    }
}

/** S10: every closed session, newest first, grouped by day. */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.app
    val c = LocalAppColors.current
    var limit by remember { mutableIntStateOf(100) }
    val sessions by remember(limit) { graph.db.sessions().recent(limit) }.collectAsStateWithLifecycle(emptyList())
    val apps by graph.db.trackedApps().all().collectAsStateWithLifecycle(emptyList())
    val names = apps.associate { it.packageName to it.displayName }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        ScreenHeader(stringResource(R.string.history_title), onBack)
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(AppIcons.Video, stringResource(R.string.history_empty_title), stringResource(R.string.history_empty_body))
            }
        }
        sessions.groupBy { it.dayKey }.forEach { (day, list) ->
            SectionTitle("${shortDate(LocalDate.parse(day))} · ${stringResource(R.string.videos_count, list.sumOf { it.reelCount })}")
            AppCard(Modifier.fillMaxWidth()) {
                Column { list.forEachIndexed { i, s -> if (i > 0) HorizontalDivider(color = c.border); SessionLine(s, names[s.packageName] ?: s.packageName) } }
            }
        }
        if (sessions.size >= limit) {
            AppButton(stringResource(R.string.load_more), { limit += 100 }, variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SessionLine(s: SessionRow, appName: String) {
    val c = LocalAppColors.current
    val mins = (s.activeMs / 60_000L).toInt()
    Row(Modifier.fillMaxWidth().heightIn(min = Size.listRow), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$appName · ${clock(s.startedAt)}", style = AppType.body, color = c.text)
            Text(stringResource(R.string.minutes_short, mins), style = AppType.bodySm, color = c.textMuted)
        }
        Text(stringResource(R.string.videos_count, s.reelCount), style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
    }
}

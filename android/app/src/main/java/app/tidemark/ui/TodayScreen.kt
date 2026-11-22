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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tidemark.R
import app.tidemark.app
import app.tidemark.core.Severities
import app.tidemark.data.DisclosureState
import app.tidemark.data.GoalRow
import app.tidemark.design.AppBanner
import app.tidemark.design.AppCard
import app.tidemark.design.AppIcons
import app.tidemark.design.AppType
import app.tidemark.design.BannerKind
import app.tidemark.design.EmptyState
import app.tidemark.design.LocalAppColors
import app.tidemark.design.MeterRing
import app.tidemark.design.SectionTitle
import app.tidemark.design.SeverityChip
import app.tidemark.design.Size
import app.tidemark.design.Space
import app.tidemark.design.severityLabel
import app.tidemark.service.ServiceStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** One app's row on Today: closed sessions plus the open session's live time. */
private data class TodayRow(val pkg: String, val name: String, val reels: Int, val activeMs: Long, val sessions: Int)

/** S05. Today's totals, live: daily_stat + the open session (fixes C-6). */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun TodayScreen(
    onFixService: () -> Unit,
    onReviewDisclosure: () -> Unit,
    onOpenApp: (String) -> Unit,
    onGoals: () -> Unit,
) {
    val context = LocalContext.current
    val graph = context.app
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()

    val rows by remember {
        dayKeyTicker { graph.dayKey(it) }.flatMapLatest { day ->
            combine(graph.db.stats().byApp(day), graph.db.sessions().openActive(day), graph.db.trackedApps().all()) { closed, open, apps ->
                val names = apps.associate { it.packageName to it.displayName }
                val byPkg = closed.associateBy { it.packageName }.toMutableMap()
                val live = open.associate { it.packageName to it.activeMs }
                (byPkg.keys + live.keys).map { pkg ->
                    val r = byPkg[pkg]
                    TodayRow(pkg, r?.displayName ?: names[pkg] ?: pkg, r?.reels ?: 0, (r?.activeMs ?: 0L) + (live[pkg] ?: 0L), r?.sessions ?: 0)
                }.filter { it.reels > 0 || it.activeMs > 0 }.sortedByDescending { it.reels }
            }
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    val goals by graph.db.goals().all().collectAsStateWithLifecycle(initialValue = emptyList())
    val disclosure by graph.prefs.disclosure.collectAsStateWithLifecycle(initialValue = DisclosureState.ACCEPTED)
    val pausedUntil by graph.prefs.pausedUntil.collectAsStateWithLifecycle(initialValue = 0L)

    var serviceOn by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        serviceOn = ServiceStatus.isAccessibilityEnabled(context)
        onPauseOrDispose { }
    }

    val total = rows.sumOf { it.reels }
    val minutes = (rows.sumOf { it.activeMs } / 60_000L).toInt()
    val sessions = rows.sumOf { it.sessions }
    val goal = goals.firstOrNull { it.enabled && it.packageName == null && it.metric == GoalRow.METRIC_REELS }
    val severity = Severities.of(total, goal?.value, goal?.warnAtPct ?: 80)
    val scaleMax = (goal?.value ?: Severities.NO_GOAL_THRESHOLDS.last()).toFloat()
    val paused = pausedUntil > System.currentTimeMillis()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        when {
            disclosure == DisclosureState.DECLINED -> AppBanner(BannerKind.WARNING, stringResource(R.string.banner_tracking_off),
                actionLabel = stringResource(R.string.banner_review), onAction = onReviewDisclosure)
            !serviceOn -> AppBanner(BannerKind.ERROR, stringResource(R.string.banner_service_off),
                actionLabel = stringResource(R.string.banner_fix), onAction = onFixService)
            paused -> AppBanner(BannerKind.INFO, stringResource(R.string.banner_paused, clock(pausedUntil)),
                actionLabel = stringResource(R.string.resume), onAction = { scope.launch { graph.prefs.setPausedUntil(0) } })
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                val caption = if (goal != null) stringResource(R.string.of_goal, goal.value) else stringResource(R.string.today_count_label)
                val word = severityLabel(severity, hasGoal = goal != null)
                MeterRing(
                    count = total,
                    progress = total / scaleMax,
                    severity = severity,
                    caption = caption,
                    description = "$total. $caption. $word.",
                )
                SeverityChip(severity, hasGoal = goal != null)
                Text(stringResource(R.string.today_minutes_sessions, minutes, sessions), style = AppType.bodySm, color = c.textMuted)
                if (goal == null) {
                    Text(stringResource(R.string.today_set_goal), style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold),
                        color = c.accent, modifier = Modifier.clickable(role = Role.Button, onClick = onGoals).padding(Space.xs))
                }
            }
        }

        if (rows.isEmpty()) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(AppIcons.Video, stringResource(R.string.today_empty_title), stringResource(R.string.today_empty_body))
            }
        } else {
            SectionTitle(stringResource(R.string.today_by_app))
            rows.forEach { AppRow(it, onClick = { onOpenApp(it.pkg) }) }
        }
    }
}

@Composable
private fun AppRow(row: TodayRow, onClick: () -> Unit) {
    val c = LocalAppColors.current
    AppCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = Size.touchMin), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(row.name, style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                Text(stringResource(R.string.today_app_row, row.reels, (row.activeMs / 60_000L).toInt()),
                    style = AppType.bodySm, color = c.textMuted)
            }
            Text(row.reels.toString(), style = AppType.title, color = c.text)
        }
    }
}

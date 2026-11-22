package app.tidemark.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tidemark.R
import app.tidemark.app
import app.tidemark.core.CsvFormat
import app.tidemark.core.OemFamily
import app.tidemark.core.OemGuides
import app.tidemark.core.OverlayMode
import app.tidemark.core.Retention
import app.tidemark.core.Severity
import app.tidemark.data.DiagLog
import app.tidemark.data.OverlaySettings
import app.tidemark.data.ReminderSettings
import app.tidemark.design.AppBanner
import app.tidemark.design.AppButton
import app.tidemark.design.AppCard
import app.tidemark.design.AppType
import app.tidemark.design.BannerKind
import app.tidemark.design.ButtonVariant
import app.tidemark.design.ConfirmDialog
import app.tidemark.design.CounterPillPreview
import app.tidemark.design.LinkRow
import app.tidemark.design.LocalAppColors
import app.tidemark.design.PermissionRow
import app.tidemark.design.RadioGroup
import app.tidemark.design.ScreenHeader
import app.tidemark.design.SectionTitle
import app.tidemark.design.SegmentedControl
import app.tidemark.design.SliderRow
import app.tidemark.design.Space
import app.tidemark.design.StepperRow
import app.tidemark.design.SwitchRow
import app.tidemark.design.ThemeMode
import app.tidemark.service.DiagnosticsState
import app.tidemark.service.ServiceStatus
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** S14 hub. */
@Composable
fun SettingsScreen(theme: ThemeMode, onTheme: (ThemeMode) -> Unit, go: (String) -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val resetHour by graph.prefs.resetHour.collectAsStateWithLifecycle(4)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        SectionTitle(stringResource(R.string.settings_theme))
        AppCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                listOf(ThemeMode.SYSTEM to stringResource(R.string.theme_system), ThemeMode.LIGHT to stringResource(R.string.theme_light),
                    ThemeMode.DARK to stringResource(R.string.theme_dark)),
                theme, onTheme,
            )
        }
        SectionTitle(stringResource(R.string.settings_counting))
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                LinkRow(stringResource(R.string.settings_counter), { go(Routes.COUNTER) }, stringResource(R.string.settings_counter_sub))
                HorizontalDivider(color = LocalAppColors.current.border)
                LinkRow(stringResource(R.string.settings_apps), { go(Routes.APPS) }, stringResource(R.string.settings_apps_sub))
                HorizontalDivider(color = LocalAppColors.current.border)
                StepperRow(stringResource(R.string.settings_reset_hour), resetHour, clockOfMinute(resetHour * 60),
                    { scope.launch { graph.prefs.setResetHour(it) } }, 0, 6, 1)
            }
        }
        SectionTitle(stringResource(R.string.settings_notifications))
        AppCard(Modifier.fillMaxWidth()) {
            LinkRow(stringResource(R.string.settings_reminders), { go(Routes.REMINDERS) }, stringResource(R.string.settings_reminders_sub))
        }
        SectionTitle(stringResource(R.string.settings_privacy_section))
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                LinkRow(stringResource(R.string.settings_privacy), { go(Routes.PRIVACY) }, stringResource(R.string.settings_privacy_sub))
                HorizontalDivider(color = LocalAppColors.current.border)
                LinkRow(stringResource(R.string.settings_disclosure), { go(Routes.DISCLOSURE) })
            }
        }
        SectionTitle(stringResource(R.string.settings_help))
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                LinkRow(stringResource(R.string.settings_permissions), { go(Routes.PERMISSIONS) })
                HorizontalDivider(color = LocalAppColors.current.border)
                LinkRow(stringResource(R.string.recovery_title), { go(Routes.RECOVERY) })
                HorizontalDivider(color = LocalAppColors.current.border)
                LinkRow(stringResource(R.string.diag_title), { go(Routes.DIAGNOSTICS) }, stringResource(R.string.diag_sub))
                HorizontalDivider(color = LocalAppColors.current.border)
                LinkRow(stringResource(R.string.settings_licenses), { go(Routes.LICENSES) })
                HorizontalDivider(color = LocalAppColors.current.border)
                LinkRow(stringResource(R.string.settings_design), { go(Routes.DESIGN) })
            }
        }
        Column(Modifier.fillMaxWidth().padding(vertical = Space.md), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.app_name), style = AppType.title, color = LocalAppColors.current.text)
            Text(stringResource(R.string.about_tagline), style = AppType.bodySm, color = LocalAppColors.current.textMuted)
        }
    }
}

/** Open-source licences: Inter's OFL ships with the app, as the licence requires. */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalAppColors.current
    val text by produceState("") {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/inter_OFL.txt").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.settings_licenses), onBack)
        Text(stringResource(R.string.licenses_intro), style = AppType.body, color = c.text)
        AppCard(Modifier.fillMaxWidth()) {
            Text(text, style = AppType.labelSm.copy(fontFamily = FontFamily.Monospace), color = c.text)
        }
    }
}

/** S14 > Counter: when it shows, size, opacity, timer. */
@Composable
fun CounterSettingsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val s by graph.prefs.overlay.collectAsStateWithLifecycle(OverlaySettings())
    fun save(n: OverlaySettings) = scope.launch { graph.prefs.setOverlay(n) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.settings_counter), onBack)
        Box(Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(LocalAppColors.current.surface2).padding(Space.lg), contentAlignment = Alignment.Center) {
            Box(Modifier.scale(app.tidemark.core.OverlayPolicy.scale(s.size))) {
                CounterPillPreview(128, Severity.MID, if (s.showTimer) "4:07" else null)
            }
        }
        SectionTitle(stringResource(R.string.counter_show))
        AppCard(Modifier.fillMaxWidth()) {
            RadioGroup(
                listOf(OverlayMode.AUTO to stringResource(R.string.counter_auto), OverlayMode.ALWAYS to stringResource(R.string.counter_always),
                    OverlayMode.OFF to stringResource(R.string.counter_off)),
                s.mode, { save(s.copy(mode = it)) },
            )
        }
        SectionTitle(stringResource(R.string.counter_size))
        SegmentedControl(listOf(stringResource(R.string.size_s), stringResource(R.string.size_m), stringResource(R.string.size_l)), s.size, { save(s.copy(size = it)) })
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                SliderRow(stringResource(R.string.counter_opacity), s.opacityPct, "${s.opacityPct}%", 60..100, steps = 7) { save(s.copy(opacityPct = it)) }
                SwitchRow(stringResource(R.string.counter_timer), s.showTimer, { save(s.copy(showTimer = it)) }, subtitle = stringResource(R.string.counter_timer_sub))
            }
        }
        AppButton(stringResource(R.string.counter_reset_position), { scope.launch { graph.prefs.clearOverlayPosition() } },
            variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}

/** S14 > Reminders. Changing times re-plans the background jobs (App.kt). */
@Composable
fun RemindersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    val r by graph.prefs.reminders.collectAsStateWithLifecycle(ReminderSettings())
    var allowed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) { allowed = ServiceStatus.notificationsAllowed(context); onPauseOrDispose { } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = ServiceStatus.notificationsAllowed(context) }
    fun save(n: ReminderSettings) = scope.launch { graph.prefs.setReminders(n) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.settings_reminders), onBack)
        if (!allowed) {
            AppBanner(BannerKind.WARNING, stringResource(R.string.reminders_blocked), actionLabel = stringResource(R.string.perm_turn_on), onAction = {
                if (Build.VERSION.SDK_INT >= 33) launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(ServiceStatus.appDetailsIntent(context))
            })
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                SwitchRow(stringResource(R.string.rem_daily), r.dailySummary, { save(r.copy(dailySummary = it)) }, subtitle = stringResource(R.string.rem_daily_sub))
                if (r.dailySummary) {
                    StepperRow(stringResource(R.string.rem_time), r.dailySummaryMinute / 60, clockOfMinute(r.dailySummaryMinute),
                        { save(r.copy(dailySummaryMinute = it * 60)) }, 0, 23, 1)
                }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                SwitchRow(stringResource(R.string.rem_weekly), r.weeklyReport, { save(r.copy(weeklyReport = it)) }, subtitle = stringResource(R.string.rem_weekly_sub))
                if (r.weeklyReport) {
                    StepperRow(stringResource(R.string.rem_day), r.weeklyReportDay, weekdayFull(r.weeklyReportDay),
                        { save(r.copy(weeklyReportDay = it)) }, 1, 7, 1)
                }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Text(stringResource(R.string.rem_nudge_title), style = AppType.body.copy(fontWeight = FontWeight.SemiBold), color = LocalAppColors.current.text)
                Text(stringResource(R.string.rem_nudge_sub), style = AppType.bodySm, color = LocalAppColors.current.textMuted)
                StepperRow(stringResource(R.string.rem_nudge_minutes), r.nudgeMinutes,
                    if (r.nudgeMinutes == 0) stringResource(R.string.off) else stringResource(R.string.minutes_short, r.nudgeMinutes),
                    { save(r.copy(nudgeMinutes = it)) }, 0, 120, 5)
                StepperRow(stringResource(R.string.rem_nudge_videos), r.nudgeVideos,
                    if (r.nudgeVideos == 0) stringResource(R.string.off) else r.nudgeVideos.toString(),
                    { save(r.copy(nudgeVideos = it)) }, 0, 500, 25)
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            SwitchRow(stringResource(R.string.rem_goals), r.goalNotices, { save(r.copy(goalNotices = it)) }, subtitle = stringResource(R.string.rem_goals_sub))
        }
        Text(stringResource(R.string.rem_inexact), style = AppType.bodySm, color = LocalAppColors.current.textMuted)
    }
}

/** S04: which apps are counted. Unverified detection is said plainly. */
@Composable
fun AppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    val apps by graph.db.trackedApps().all().collectAsStateWithLifecycle(emptyList())
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.settings_apps), onBack)
        Text(stringResource(R.string.apps_intro), style = AppType.bodySm, color = LocalAppColors.current.textMuted)
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                apps.forEachIndexed { i, a ->
                    if (i > 0) HorizontalDivider(color = LocalAppColors.current.border)
                    val installed = ServiceStatus.isInstalled(context, a.packageName)
                    val rule = graph.rules.forPackage(a.packageName)
                    val verified = rule?.isVerified(ServiceStatus.versionCode(context, a.packageName)) == true
                    val sub = buildString {
                        append(surfaceName(a.surfaceKind))
                        append(" · ")
                        append(if (!installed) stringResource(R.string.apps_not_installed)
                        else if (verified) stringResource(R.string.apps_verified) else stringResource(R.string.apps_unverified))
                    }
                    SwitchRow(a.displayName, a.enabled, { on -> scope.launch { graph.db.trackedApps().setEnabled(a.packageName, on, System.currentTimeMillis()) } },
                        subtitle = sub, enabled = installed || a.enabled)
                }
            }
        }
    }
}

@Composable
private fun surfaceName(kind: String): String = stringResource(
    when (kind) {
        "reels" -> R.string.surface_reels
        "shorts" -> R.string.surface_shorts
        "fb_reels" -> R.string.surface_fb_reels
        "spotlight" -> R.string.surface_spotlight
        else -> R.string.surface_tiktok
    }
)

/** S15: pause, retention, export, import, delete. Nothing here uses the network. */
@Composable
fun PrivacyScreen(onBack: () -> Unit, onDisclosure: () -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val pausedUntil by graph.prefs.pausedUntil.collectAsStateWithLifecycle(0L)
    val retention by graph.prefs.retentionDays.collectAsStateWithLifecycle(Retention.DEFAULT_DAYS)
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // "today" | "week" | "old" | "all"

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = CsvFormat.export(graph.db.stats().all(), graph.db.sessions().allClosed())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: error("no stream")
                }.isSuccess
            }
            message = context.getString(if (ok) R.string.export_done else R.string.export_failed)
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            }?.let { CsvFormat.parseDaily(it, System.currentTimeMillis()) }
            message = if (parsed == null || (parsed.rows.isEmpty() && parsed.skipped > 0)) context.getString(R.string.import_failed)
            else context.getString(R.string.import_done, graph.writer.importDaily(parsed.rows), parsed.skipped)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.settings_privacy), onBack)
        AppCard(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.privacy_statement), style = AppType.bodySm, color = c.text)
        }
        message?.let {
            Text(it, style = AppType.bodySm.copy(fontWeight = FontWeight.SemiBold), color = c.text,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }

        SectionTitle(stringResource(R.string.privacy_pause))
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                val paused = pausedUntil > System.currentTimeMillis()
                Text(if (paused) stringResource(R.string.banner_paused, clock(pausedUntil)) else stringResource(R.string.privacy_counting_on),
                    style = AppType.body, color = c.text)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    AppButton(stringResource(R.string.pause_1h), { scope.launch { graph.prefs.setPausedUntil(System.currentTimeMillis() + 3_600_000L) } },
                        variant = ButtonVariant.SECONDARY)
                    AppButton(stringResource(R.string.pause_tomorrow), {
                        scope.launch {
                            val tomorrow = LocalDate.parse(graph.dayKey(System.currentTimeMillis())).plusDays(1)
                            val at = tomorrow.atStartOfDay(java.time.ZoneId.systemDefault()).plusHours(graph.resetHour.toLong()).toInstant().toEpochMilli()
                            graph.prefs.setPausedUntil(at)
                        }
                    }, variant = ButtonVariant.SECONDARY)
                }
                if (paused) AppButton(stringResource(R.string.resume), { scope.launch { graph.prefs.setPausedUntil(0) } })
            }
        }

        SectionTitle(stringResource(R.string.privacy_retention))
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Text(stringResource(R.string.privacy_retention_sub), style = AppType.bodySm, color = c.textMuted)
                RadioGroup(Retention.CHOICES.map { it to stringResource(R.string.days_n, it) }, retention, { scope.launch { graph.prefs.setRetentionDays(it) } })
            }
        }

        SectionTitle(stringResource(R.string.privacy_your_data))
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.export_sub), style = AppType.bodySm, color = c.textMuted)
                AppButton(stringResource(R.string.export_csv), { exportLauncher.launch("tidemark-${LocalDate.now()}.csv") },
                    variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
                AppButton(stringResource(R.string.import_csv), { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) },
                    variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
            }
        }

        SectionTitle(stringResource(R.string.privacy_delete))
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                AppButton(stringResource(R.string.delete_today), { confirm = "today" }, variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
                AppButton(stringResource(R.string.delete_week), { confirm = "week" }, variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
                AppButton(stringResource(R.string.delete_old), { confirm = "old" }, variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
                AppButton(stringResource(R.string.delete_all), { confirm = "all" }, variant = ButtonVariant.DANGER, modifier = Modifier.fillMaxWidth())
            }
        }
        LinkRow(stringResource(R.string.settings_disclosure), onDisclosure)
    }

    confirm?.let { what ->
        val today = LocalDate.parse(graph.dayKey(System.currentTimeMillis()))
        val (from, to) = when (what) {
            "today" -> today to today
            "week" -> today.minusDays(6) to today
            "old" -> LocalDate.of(2000, 1, 1) to today.minusDays(31)
            else -> null to null
        }
        ConfirmDialog(
            title = stringResource(if (what == "all") R.string.delete_all else R.string.delete_period_title),
            body = if (what == "all") stringResource(R.string.delete_all_body)
            else stringResource(R.string.delete_period_body, shortDate(from!!), shortDate(to!!)),
            confirmLabel = stringResource(R.string.delete),
            onConfirm = {
                confirm = null
                scope.launch {
                    if (what == "all") {
                        graph.writer.deleteAll()
                        graph.prefs.resetAfterDeleteAll()
                        DiagLog.clear(context)
                        message = context.getString(R.string.deleted_all)
                    } else {
                        graph.writer.deleteDays(from!!.toString(), to!!.toString())
                        message = context.getString(R.string.deleted_period)
                    }
                }
            },
            onDismiss = { confirm = null },
        )
    }
}

/** S16: why counting stopped and how to fix it on this phone. */
@Composable
fun RecoveryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val c = LocalAppColors.current
    var a11y by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(false) }
    var notif by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        a11y = ServiceStatus.isAccessibilityEnabled(context)
        battery = ServiceStatus.isIgnoringBatteryOptimizations(context)
        notif = ServiceStatus.notificationsAllowed(context)
        onPauseOrDispose { }
    }
    val family = OemGuides.familyOf(Build.MANUFACTURER)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.recovery_title), onBack)
        Text(stringResource(if (a11y) R.string.recovery_ok else R.string.recovery_body), style = AppType.body, color = c.text)
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                PermissionRow(stringResource(R.string.perm_a11y_title), stringResource(R.string.perm_a11y_body), a11y,
                    stringResource(R.string.perm_turn_on), stringResource(R.string.perm_on), { context.startActivity(ServiceStatus.accessibilitySettingsIntent()) })
                HorizontalDivider(color = c.border)
                PermissionRow(stringResource(R.string.perm_battery_title), stringResource(R.string.perm_battery_body), battery,
                    stringResource(R.string.perm_turn_on), stringResource(R.string.perm_on), { context.startActivity(ServiceStatus.batterySettingsIntent()) })
                HorizontalDivider(color = c.border)
                PermissionRow(stringResource(R.string.perm_notif_title), stringResource(R.string.perm_notif_body), notif,
                    stringResource(R.string.perm_turn_on), stringResource(R.string.perm_on), { context.startActivity(ServiceStatus.appDetailsIntent(context)) })
            }
        }
        SectionTitle(stringResource(R.string.oem_title, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }))
        AppCard(Modifier.fillMaxWidth()) {
            Text(oemSteps(family), style = AppType.bodySm, color = c.text)
        }
        AppButton(stringResource(R.string.open_app_info), { context.startActivity(ServiceStatus.appDetailsIntent(context)) },
            variant = ButtonVariant.SECONDARY, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun oemSteps(family: OemFamily): String = stringResource(
    when (family) {
        OemFamily.XIAOMI -> R.string.oem_xiaomi
        OemFamily.SAMSUNG -> R.string.oem_samsung
        OemFamily.OPPO -> R.string.oem_oppo
        OemFamily.VIVO -> R.string.oem_vivo
        OemFamily.HUAWEI -> R.string.oem_huawei
        OemFamily.OTHER -> R.string.oem_other
    }
)

/** Settings > Diagnostics: detection status per app and the local log. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = context.app
    val c = LocalAppColors.current
    var refresh by remember { mutableIntStateOf(0) }
    val lines by produceState(emptyList<String>(), refresh) {
        value = withContext(Dispatchers.IO) { DiagLog.read(context) }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ScreenHeader(stringResource(R.string.diag_title), onBack)

        // Live status from the running service (memory only, technical metadata).
        val live by DiagnosticsState.state.collectAsStateWithLifecycle()
        val realTotal by graph.db.reelEvents().countAll().collectAsStateWithLifecycle(0)
        val none = stringResource(R.string.diag_none)
        SectionTitle(stringResource(R.string.diag_live))
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                DiagLine(stringResource(R.string.diag_connected),
                    if (live.connected) stringResource(R.string.diag_yes_since, clock(live.connectedAt)) else stringResource(R.string.diag_no),
                    warn = !live.connected)
                DiagLine(stringResource(R.string.diag_foreground), live.foregroundPackage ?: none)
                DiagLine(stringResource(R.string.diag_last_event),
                    live.lastEvent?.let { "$it · ${clock(live.lastEventAt)}" } ?: none)
                DiagLine(stringResource(R.string.diag_surface), live.surface ?: none, warn = live.surface?.startsWith("none") == true)
                DiagLine(stringResource(R.string.diag_last_reason),
                    live.lastReason?.let { "$it · ${clock(live.lastReasonAt)}" } ?: none)
                DiagLine(stringResource(R.string.diag_scroll_events), live.scrollEvents.toString())
                DiagLine(stringResource(R.string.diag_page_changes), live.pageChanges.toString())
                DiagLine(stringResource(R.string.diag_positions),
                    "${live.previousPosition?.toString() ?: "–"} → ${live.position}")
                DiagLine(stringResource(R.string.diag_page_decision), live.pageDecision ?: none)
                DiagLine(stringResource(R.string.diag_counted_since), live.countedSinceConnect.toString())
                DiagLine(stringResource(R.string.diag_total_real), realTotal.toString())
                Text(stringResource(R.string.diag_live_note), style = AppType.bodySm, color = c.textMuted)
            }
        }

        SectionTitle(stringResource(R.string.diag_detection))
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(stringResource(R.string.diag_rules_version, graph.rules.version), style = AppType.bodySm, color = c.textMuted)
                graph.rules.apps.values.forEach { rule ->
                    val v = ServiceStatus.versionCode(context, rule.packageName)
                    val status = when {
                        v == null -> stringResource(R.string.apps_not_installed)
                        rule.isVerified(v) -> stringResource(R.string.diag_verified, v)
                        else -> stringResource(R.string.diag_unverified, v)
                    }
                    Text("${rule.name} (${rule.packageName}): $status", style = AppType.bodySm,
                        color = if (v != null && !rule.isVerified(v)) c.warning else c.text)
                }
            }
        }
        SectionTitle(stringResource(R.string.diag_log))
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                Text(stringResource(R.string.diag_log_sub), style = AppType.bodySm, color = c.textMuted)
                val shown = lines.takeLast(100).reversed()
                if (shown.isEmpty()) Text(stringResource(R.string.diag_log_empty), style = AppType.bodySm, color = c.textMuted)
                shown.forEach { Text(it, style = AppType.labelSm.copy(fontFamily = FontFamily.Monospace), color = c.text) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            AppButton(stringResource(R.string.diag_share), {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                context.startActivity(Intent.createChooser(send, context.getString(R.string.diag_share)))
            }, variant = ButtonVariant.SECONDARY, enabled = lines.isNotEmpty())
            AppButton(stringResource(R.string.diag_clear), { DiagLog.clear(context); refresh++ }, variant = ButtonVariant.GHOST)
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String, warn: Boolean = false) {
    val c = LocalAppColors.current
    Column {
        Text(label, style = AppType.labelSm, color = c.textMuted)
        Text(value, style = AppType.bodySm.copy(fontFamily = FontFamily.Monospace), color = if (warn) c.warning else c.text)
    }
}

/** Breathing break (could-have): 30 s of slow in/out, offered from the block screen. */
@Composable
fun BreatheScreen(onDone: () -> Unit) {
    val c = LocalAppColors.current
    var secondsLeft by remember { mutableIntStateOf(30) }
    LaunchedEffect(Unit) { while (secondsLeft > 0) { delay(1_000); secondsLeft-- } }
    val t = rememberInfiniteTransition(label = "breathe")
    val s by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(4_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    val inhale = (secondsLeft / 4) % 2 == 1
    Column(
        Modifier.fillMaxSize().padding(Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.lg, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(200.dp).scale(s).clip(CircleShape).background(c.accent.copy(alpha = 0.25f)))
        Text(stringResource(if (secondsLeft == 0) R.string.breathe_done else if (inhale) R.string.breathe_in else R.string.breathe_out),
            style = AppType.headline, color = c.text, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(R.string.breathe_left, secondsLeft), style = AppType.bodySm, color = c.textMuted)
        AppButton(stringResource(R.string.done), onDone, variant = if (secondsLeft == 0) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY)
    }
}

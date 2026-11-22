package app.tidemark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.tidemark.R
import app.tidemark.app
import app.tidemark.data.DisclosureState
import app.tidemark.design.AppIcons
import app.tidemark.design.AppTheme
import app.tidemark.design.AppType
import app.tidemark.design.LocalAppColors
import app.tidemark.design.ThemeMode
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Routes for every screen in the recon inventory (S13 and S17 removed in revision 2). */
object Routes {
    const val TODAY = "today"             // S05
    const val STATS = "stats"             // S08
    const val APP_DETAIL = "app/{pkg}"    // S09
    const val HISTORY = "history"         // S10
    const val GOALS = "goals"             // S11
    const val GOAL_EDIT = "goal/{id}"     // S11 editor
    const val SETTINGS = "settings"       // S14
    const val COUNTER = "counter"         // S14 > counter
    const val REMINDERS = "reminders"     // S14 > reminders
    const val APPS = "apps"               // S04
    const val PRIVACY = "privacy"         // S15
    const val RECOVERY = "recovery"       // S16
    const val DIAGNOSTICS = "diagnostics"
    const val PERMISSIONS = "permissions" // S03 from settings
    const val DISCLOSURE = "disclosure"   // S02 from settings / banner
    const val BREATHE = "breathe"         // breathing break
    const val DESIGN = "design"           // developer primitives
    const val LICENSES = "licenses"       // open-source licences (Inter OFL)

    fun appDetail(pkg: String) = "app/$pkg"
    fun goalEdit(id: Long) = "goal/$id"

    /** Routes a notification or the block screen may open. */
    val EXTERNAL = setOf(TODAY, STATS, GOALS, RECOVERY, BREATHE)
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.TODAY, R.string.nav_today, AppIcons.Today),
    Tab(Routes.STATS, R.string.nav_stats, AppIcons.Stats),
    Tab(Routes.GOALS, R.string.nav_goals, AppIcons.Goals),
    Tab(Routes.SETTINGS, R.string.nav_settings, AppIcons.Settings),
)

@Composable
fun AppRoot(pendingRoute: StateFlow<String?>, onRouteHandled: () -> Unit) {
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val disclosure by graph.prefs.disclosure.collectAsStateWithLifecycle(initialValue = null)
    val permissionsSeen by graph.prefs.permissionsSeen.collectAsStateWithLifecycle(initialValue = null)
    val theme by graph.prefs.theme.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)

    AppTheme(theme) {
        Surface(Modifier.fillMaxSize(), color = LocalAppColors.current.bg) {
            val d = disclosure
            val p = permissionsSeen
            when {
                d == null || p == null -> Box(Modifier.fillMaxSize()) // first DataStore read, a few ms
                d == DisclosureState.UNSET -> DisclosureScreen(
                    onAgree = { scope.launch { graph.prefs.setDisclosure(DisclosureState.ACCEPTED) } },
                    onDecline = {
                        scope.launch {
                            graph.prefs.setDisclosure(DisclosureState.DECLINED)
                            graph.prefs.setPermissionsSeen()
                        }
                    },
                )
                d == DisclosureState.ACCEPTED && !p -> PermissionsScreen(
                    onDone = { scope.launch { graph.prefs.setPermissionsSeen() } },
                )
                else -> MainScaffold(theme, onTheme = { scope.launch { graph.prefs.setTheme(it) } }, pendingRoute, onRouteHandled)
            }
        }
    }
}

@Composable
private fun MainScaffold(theme: ThemeMode, onTheme: (ThemeMode) -> Unit, pendingRoute: StateFlow<String?>, onRouteHandled: () -> Unit) {
    val nav = rememberNavController()
    val graph = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val c = LocalAppColors.current
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBar = TABS.any { it.route == current }
    val pending by pendingRoute.collectAsStateWithLifecycle()

    // Opened from a notification, the counter card or the block screen.
    LaunchedEffect(pending) {
        val r = pending ?: return@LaunchedEffect
        if (r in Routes.EXTERNAL) {
            if (TABS.any { it.route == r }) nav.navigateTab(r) else nav.navigate(r)
        }
        onRouteHandled()
    }

    Scaffold(
        containerColor = c.bg,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = c.surface) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = current == tab.route,
                            onClick = { nav.navigateTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(stringResource(tab.label), style = AppType.labelSm) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = c.accent,
                                selectedTextColor = c.accent,
                                indicatorColor = c.surface2,
                                unselectedIconColor = c.textMuted,
                                unselectedTextColor = c.textMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        val back: () -> Unit = { nav.popBackStack() }
        NavHost(nav, startDestination = Routes.TODAY, modifier = Modifier.padding(padding)) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onFixService = { nav.navigate(Routes.RECOVERY) },
                    onReviewDisclosure = { nav.navigate(Routes.DISCLOSURE) },
                    onOpenApp = { nav.navigate(Routes.appDetail(it)) },
                    onGoals = { nav.navigateTab(Routes.GOALS) },
                )
            }
            composable(Routes.STATS) {
                StatsScreen(onOpenApp = { nav.navigate(Routes.appDetail(it)) }, onHistory = { nav.navigate(Routes.HISTORY) })
            }
            composable(Routes.APP_DETAIL, arguments = listOf(navArgument("pkg") { type = NavType.StringType })) { entry ->
                AppDetailScreen(entry.arguments?.getString("pkg").orEmpty(), back)
            }
            composable(Routes.HISTORY) { HistoryScreen(back) }
            composable(Routes.GOALS) { GoalsScreen(onEdit = { nav.navigate(Routes.goalEdit(it)) }) }
            composable(Routes.GOAL_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                GoalEditorScreen(entry.arguments?.getLong("id") ?: 0L, onDone = back)
            }
            composable(Routes.SETTINGS) { SettingsScreen(theme, onTheme, go = { nav.navigate(it) }) }
            composable(Routes.COUNTER) { CounterSettingsScreen(back) }
            composable(Routes.REMINDERS) { RemindersScreen(back) }
            composable(Routes.APPS) { AppsScreen(back) }
            composable(Routes.PRIVACY) { PrivacyScreen(back, onDisclosure = { nav.navigate(Routes.DISCLOSURE) }) }
            composable(Routes.RECOVERY) { RecoveryScreen(back) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(back) }
            composable(Routes.PERMISSIONS) { PermissionsScreen(onDone = back) }
            composable(Routes.DISCLOSURE) {
                DisclosureScreen(
                    onAgree = { scope.launch { graph.prefs.setDisclosure(DisclosureState.ACCEPTED) }; nav.popBackStack() },
                    onDecline = { scope.launch { graph.prefs.setDisclosure(DisclosureState.DECLINED) }; nav.popBackStack() },
                )
            }
            composable(Routes.BREATHE) { BreatheScreen(onDone = back) }
            composable(Routes.DESIGN) { DesignScreen(onBack = back) }
            composable(Routes.LICENSES) { LicensesScreen(back) }
        }
    }
}

private fun NavHostController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

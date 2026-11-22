package app.tidemark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import app.tidemark.R
import app.tidemark.app
import app.tidemark.core.Severity
import app.tidemark.design.AppBanner
import app.tidemark.design.AppButton
import app.tidemark.design.AppCard
import app.tidemark.design.AppIcons
import app.tidemark.design.AppType
import app.tidemark.design.BannerKind
import app.tidemark.design.ButtonVariant
import app.tidemark.design.CounterPillPreview
import app.tidemark.design.EmptyState
import app.tidemark.design.LocalAppColors
import app.tidemark.design.MeterRing
import app.tidemark.design.PermissionRow
import app.tidemark.design.SectionTitle
import app.tidemark.design.SeverityChip
import app.tidemark.design.Size
import app.tidemark.design.Space
import app.tidemark.design.StatCard
import app.tidemark.design.ThemeMode
import kotlinx.coroutines.launch

// S08, S11 and S14 moved to StatsScreens.kt, GoalsScreens.kt and SettingsScreens.kt.
// This file keeps the developer design screen.

/**
 * Developer screen: every UI primitive in the current theme. The numbers here
 * are fixed layout samples for checking the components; they are never
 * written to the database and never appear in statistics.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DesignScreen(onBack: () -> Unit) {
    val c = LocalAppColors.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton(stringResource(R.string.back), onBack, variant = ButtonVariant.GHOST)
            Text(stringResource(R.string.design_title), style = AppType.title.copy(fontWeight = FontWeight.Bold), color = c.text)
        }
        AppBanner(BannerKind.INFO, stringResource(R.string.design_samples_note))

        SectionTitle("Buttons")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            AppButton("Primary", {})
            AppButton("Secondary", {}, variant = ButtonVariant.SECONDARY)
            AppButton("Ghost", {}, variant = ButtonVariant.GHOST)
            AppButton("Danger", {}, variant = ButtonVariant.DANGER)
            AppButton("Disabled", {}, enabled = false)
            AppButton("Loading", {}, loading = true)
        }

        SectionTitle("Meter and severity")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            MeterRing(128, 128 / 200f, Severity.MID, "of 200", "128 of 200, rising")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            Severity.entries.forEach { SeverityChip(it, hasGoal = true) }
        }

        SectionTitle("Counter pill (overlay)")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            CounterPillPreview(12, Severity.LOW)
            CounterPillPreview(164, Severity.MID, "21:07")
            CounterPillPreview(310, Severity.OVER)
        }

        SectionTitle("Stat cards")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            StatCard("Avg per day", "96", Modifier.weight(1f), "▼ 18% vs last week", deltaGood = true)
            StatCard("This week", "672", Modifier.weight(1f), "▲ 12% vs last week", deltaGood = false)
        }

        SectionTitle("Banners")
        AppBanner(BannerKind.INFO, "Tracking paused until 21:00", actionLabel = "Resume", onAction = {})
        AppBanner(BannerKind.WARNING, "Instagram updated. Detection not yet verified for this version.")
        AppBanner(BannerKind.ERROR, "Counting stopped. Android turned the service off.", actionLabel = "Fix", onAction = {})

        SectionTitle("Permission rows")
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                PermissionRow("Accessibility service", "Needed to count videos", false, "Turn on", "On", {})
                PermissionRow("Notifications", "Reminders and goals", true, "Turn on", "On", {})
            }
        }

        SectionTitle("Empty state")
        EmptyState(AppIcons.Stats, "No videos counted in this period", "Data appears after your first session.")

    }
}

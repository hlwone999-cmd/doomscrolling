package app.tidemark.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.tidemark.R
import app.tidemark.design.AppButton
import app.tidemark.design.AppCard
import app.tidemark.design.AppIcons
import app.tidemark.design.AppType
import app.tidemark.design.ButtonVariant
import app.tidemark.design.LocalAppColors
import app.tidemark.design.PermissionRow
import app.tidemark.design.SectionTitle
import app.tidemark.design.Space
import app.tidemark.design.Size
import app.tidemark.service.ServiceStatus

/**
 * S02. Play's prominent disclosure: its own screen, in the normal flow,
 * affirmative button, "Not now" equally visible and the app still opens.
 */
@Composable
fun DisclosureScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    val c = LocalAppColors.current
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(stringResource(R.string.disclosure_title), style = AppType.headline, color = c.text,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.disclosure_intro), style = AppType.body, color = c.text)

        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionTitle(stringResource(R.string.disclosure_reads_title))
                listOf(R.string.disclosure_read_1, R.string.disclosure_read_2, R.string.disclosure_read_3, R.string.disclosure_read_4)
                    .forEach { Bullet(AppIcons.Check, c.success, stringResource(it)) }
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                SectionTitle(stringResource(R.string.disclosure_never_title))
                listOf(R.string.disclosure_never_1, R.string.disclosure_never_2, R.string.disclosure_never_3)
                    .forEach { Bullet(AppIcons.Close, c.danger, stringResource(it)) }
            }
        }
        Text(stringResource(R.string.disclosure_where), style = AppType.bodySm, color = c.textMuted)

        Spacer(Modifier.height(Space.sm))
        AppButton(stringResource(R.string.disclosure_agree), onAgree, Modifier.fillMaxWidth().height(Size.buttonLg))
        AppButton(stringResource(R.string.disclosure_decline), onDecline, Modifier.fillMaxWidth().height(Size.buttonLg),
            variant = ButtonVariant.SECONDARY)
    }
}

@Composable
private fun Bullet(icon: ImageVector, tint: Color, text: String) {
    Row {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Size.iconSm))
        Text(text, style = AppType.bodySm, color = LocalAppColors.current.text, modifier = Modifier.padding(start = Space.sm))
    }
}

/** S03. Status refreshes every time the user comes back from system settings. */
@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val c = LocalAppColors.current
    val context = LocalContext.current
    var a11y by remember { mutableStateOf(false) }
    var notif by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(false) }
    fun refresh() {
        a11y = ServiceStatus.isAccessibilityEnabled(context)
        notif = ServiceStatus.notificationsAllowed(context)
        battery = ServiceStatus.isIgnoringBatteryOptimizations(context)
    }
    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose { }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Text(stringResource(R.string.perm_title), style = AppType.headline, color = c.text,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.perm_intro), style = AppType.body, color = c.textMuted)
        AppCard(Modifier.fillMaxWidth()) {
            Column {
                PermissionRow(
                    stringResource(R.string.perm_a11y_title), stringResource(R.string.perm_a11y_body), a11y,
                    stringResource(R.string.perm_turn_on), stringResource(R.string.perm_on),
                    onAction = { context.startActivity(ServiceStatus.accessibilitySettingsIntent()) },
                )
                HorizontalDivider(color = c.border)
                PermissionRow(
                    stringResource(R.string.perm_notif_title), stringResource(R.string.perm_notif_body), notif,
                    stringResource(R.string.perm_turn_on), stringResource(R.string.perm_on),
                    onAction = {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else context.startActivity(ServiceStatus.appDetailsIntent(context))
                    },
                )
                HorizontalDivider(color = c.border)
                PermissionRow(
                    stringResource(R.string.perm_battery_title), stringResource(R.string.perm_battery_body), battery,
                    stringResource(R.string.perm_turn_on), stringResource(R.string.perm_on),
                    onAction = { context.startActivity(ServiceStatus.batterySettingsIntent()) },
                )
            }
        }
        // Phones known to stop background services get their own steps up front (prevention beats recovery).
        val family = app.tidemark.core.OemGuides.familyOf(Build.MANUFACTURER)
        if (family != app.tidemark.core.OemFamily.OTHER) {
            SectionTitle(stringResource(R.string.oem_title, Build.MANUFACTURER.replaceFirstChar { it.uppercase() }))
            AppCard(Modifier.fillMaxWidth()) {
                Text(oemSteps(family), style = AppType.bodySm, color = c.text)
            }
        }
        AppButton(
            stringResource(if (a11y) R.string.perm_continue else R.string.perm_later),
            onDone,
            Modifier.fillMaxWidth().height(Size.buttonLg),
            variant = if (a11y) ButtonVariant.PRIMARY else ButtonVariant.SECONDARY,
        )
    }
}

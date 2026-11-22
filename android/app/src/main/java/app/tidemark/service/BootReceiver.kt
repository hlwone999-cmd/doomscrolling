package app.tidemark.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.tidemark.app
import app.tidemark.data.DiagLog
import app.tidemark.data.DisclosureState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * After a reboot or an app update: close any session the old process left
 * open (at its last activity), and if counting should be on but the
 * accessibility service is off, tell the user right away instead of waiting
 * for the 15-minute health check. Android rebinds an enabled service itself.
 *
 * goAsync() keeps the receiver alive until the database write has finished
 * (fixes "to check" C-7).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val a = context.app
        a.appScope.launch {
            try {
                val closed = a.db.sessions().closeDangling(System.currentTimeMillis())
                DiagLog.add(context, "${intent.action?.substringAfterLast('.')}: closed $closed open session(s)")
                val wanted = a.prefs.disclosure.first() == DisclosureState.ACCEPTED &&
                    a.prefs.pausedUntil.first() <= System.currentTimeMillis()
                if (wanted && !ServiceStatus.isAccessibilityEnabled(context)) {
                    Notifications.serviceStopped(context)
                    a.prefs.setStoppedNoticeAt(System.currentTimeMillis())
                }
                a.widgets.refresh()
            } finally {
                pending.finish()
            }
        }
    }
}

package app.tidemark.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.tidemark.MainActivity
import app.tidemark.R

/**
 * Three channels so the user can mute each kind in system settings:
 * counting status, goals, reminders. Every notice opens the relevant screen.
 */
object Notifications {
    const val CH_STATUS = "status"
    const val CH_GOALS = "goals"
    const val CH_REMINDERS = "reminders"
    const val EXTRA_ROUTE = "route"

    private const val ID_STOPPED = 1
    private const val ID_SUMMARY = 2
    private const val ID_WEEKLY = 3
    private const val ID_NUDGE = 4
    private const val ID_GOAL_BASE = 1000

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, context.getString(R.string.ch_status), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_GOALS, context.getString(R.string.ch_goals), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_REMINDERS, context.getString(R.string.ch_reminders), NotificationManager.IMPORTANCE_LOW),
            )
        )
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun serviceStopped(context: Context) = post(
        context, ID_STOPPED, CH_STATUS, context.getString(R.string.n_stopped_title),
        context.getString(R.string.n_stopped_body), route = "recovery",
    )

    fun dailySummary(context: Context, videos: Int, minutes: Int) = post(
        context, ID_SUMMARY, CH_REMINDERS, context.getString(R.string.n_summary_title),
        context.getString(R.string.n_summary_body, videos, minutes), route = "stats",
    )

    fun weeklyReport(context: Context, total: Int, deltaPct: Int?) = post(
        context, ID_WEEKLY, CH_REMINDERS, context.getString(R.string.n_weekly_title),
        when {
            deltaPct == null -> context.getString(R.string.n_weekly_body_first, total)
            deltaPct <= 0 -> context.getString(R.string.n_weekly_body_down, total, -deltaPct)
            else -> context.getString(R.string.n_weekly_body_up, total, deltaPct)
        },
        route = "stats",
    )

    fun nudge(context: Context, minutes: Int, videos: Int) = post(
        context, ID_NUDGE, CH_REMINDERS, context.getString(R.string.n_nudge_title),
        context.getString(R.string.n_nudge_body, videos, minutes), route = "today",
    )

    /** kind: warned | reached | met_day */
    fun goal(context: Context, goalId: Long, kind: String, used: Int, target: Int, minutes: Boolean, appName: String?) {
        val what = appName ?: context.getString(R.string.all_apps)
        val unit = context.getString(if (minutes) R.string.unit_minutes else R.string.unit_videos)
        val (title, body) = when (kind) {
            "warned" -> context.getString(R.string.n_goal_warn_title) to context.getString(R.string.n_goal_warn_body, used, target, unit, what)
            "reached" -> context.getString(R.string.n_goal_reached_title) to context.getString(R.string.n_goal_reached_body, target, unit, what)
            else -> context.getString(R.string.n_goal_met_title) to context.getString(R.string.n_goal_met_body, used, target, unit, what)
        }
        val slot = when (kind) { "warned" -> 0; "reached" -> 1; else -> 2 }
        post(context, ID_GOAL_BASE + goalId.toInt() * 4 + slot, CH_GOALS, title, body, route = "goals")
    }

    fun cancelStopped(context: Context) = NotificationManagerCompat.from(context).cancel(ID_STOPPED)

    @Suppress("MissingPermission") // checked in canPost()
    private fun post(context: Context, id: Int, channel: String, title: String, body: String, route: String) {
        if (!canPost(context)) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_counter)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

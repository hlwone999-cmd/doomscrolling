package app.tidemark.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.tidemark.app
import app.tidemark.core.GoalLogic
import app.tidemark.core.Period
import app.tidemark.core.Retention
import app.tidemark.core.Schedule
import app.tidemark.core.StatsLogic
import app.tidemark.core.UsageTotals
import app.tidemark.data.DiagLog
import app.tidemark.data.DisclosureState
import app.tidemark.data.GoalEventRow
import app.tidemark.data.GoalRow
import app.tidemark.data.ReminderSettings
import app.tidemark.data.Usage
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/** All background work. Local only: none of these touch the network. */
object Work {
    private const val HEALTH = "health"
    private const val PRUNE = "prune"
    private const val ROLLOVER = "rollover"
    private const val SUMMARY = "daily_summary"
    private const val WEEKLY = "weekly_report"

    /**
     * [replaceTimed]: re-plan the clock-time jobs (after the user changes a
     * reminder time or the reset hour). Otherwise existing schedules are kept.
     */
    fun schedule(context: Context, reminders: ReminderSettings, resetHour: Int, replaceTimed: Boolean) {
        val wm = WorkManager.getInstance(context)
        val timed = if (replaceTimed) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        val now = ZonedDateTime.now()

        wm.enqueueUniquePeriodicWork(HEALTH, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HealthCheckWorker>(15, TimeUnit.MINUTES).build())
        wm.enqueueUniquePeriodicWork(PRUNE, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PruneWorker>(7, TimeUnit.DAYS).build())
        wm.enqueueUniquePeriodicWork(ROLLOVER, timed,
            PeriodicWorkRequestBuilder<DayRolloverWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(Schedule.delayToNext(now, resetHour * 60 + 5), TimeUnit.MILLISECONDS).build())

        if (reminders.dailySummary) {
            wm.enqueueUniquePeriodicWork(SUMMARY, timed,
                PeriodicWorkRequestBuilder<DailySummaryWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(Schedule.delayToNext(now, reminders.dailySummaryMinute), TimeUnit.MILLISECONDS).build())
        } else wm.cancelUniqueWork(SUMMARY)

        if (reminders.weeklyReport) {
            wm.enqueueUniquePeriodicWork(WEEKLY, timed,
                PeriodicWorkRequestBuilder<WeeklyReportWorker>(7, TimeUnit.DAYS)
                    .setInitialDelay(Schedule.delayToNext(now, reminders.dailySummaryMinute, reminders.weeklyReportDay), TimeUnit.MILLISECONDS)
                    .build())
        } else wm.cancelUniqueWork(WEEKLY)
    }
}

/** F07: if counting should be on but the service is not enabled, say so (at most every 6 h). */
class HealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = ctx.app.prefs
        val now = System.currentTimeMillis()
        val wanted = prefs.disclosure.first() == DisclosureState.ACCEPTED && prefs.pausedUntil.first() <= now
        if (!wanted) return Result.success()
        if (ServiceStatus.isAccessibilityEnabled(ctx)) {
            Notifications.cancelStopped(ctx)
            return Result.success()
        }
        if (now - prefs.lastStoppedNoticeAt() >= 6 * 3_600_000L) {
            DiagLog.add(ctx, "health: service not enabled, notice posted")
            Notifications.serviceStopped(ctx)
            prefs.setStoppedNoticeAt(now)
        }
        return Result.success()
    }
}

/** Retention: detailed events older than the chosen days go; daily totals stay. */
class PruneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val a = applicationContext.app
        val days = a.prefs.retentionDays.first()
        val n = a.db.reelEvents().pruneBefore(Retention.cutoff(System.currentTimeMillis(), days))
        DiagLog.add(applicationContext, "prune: $n events older than $days days")
        return Result.success()
    }
}

/**
 * After the reset hour: record whether yesterday met each goal (and notify a
 * met day), then apply any gradual-reduction step that is due.
 */
class DayRolloverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val a = ctx.app
        val now = System.currentTimeMillis()
        DayRollover.run(ctx, now)
        a.widgets.refresh()
        return Result.success()
    }
}

object DayRollover {
    suspend fun run(ctx: Context, now: Long) {
        val a = ctx.app
        val today = a.dayKey(now)
        val yesterday = LocalDate.parse(today).minusDays(1)
        val yKey = yesterday.toString()
        val rows = a.db.stats().rangeOnce(yKey, yKey)
        val totals = UsageTotals(rows.associate { it.packageName to it.reels }, rows.associate { it.packageName to it.activeMs })
        val names = a.db.trackedApps().enabled().associate { it.packageName to it.displayName }
        val notices = a.prefs.reminders.first().goalNotices
        for (g in a.db.goals().enabledOnce()) {
            if (g.daysMask and (1 shl (yesterday.dayOfWeek.value - 1)) != 0) {
                val used = GoalLogic.used(g, totals)
                val met = GoalLogic.dayMet(used, g.value)
                val id = a.db.goals().insertEvent(
                    GoalEventRow(goalId = g.id, dayKey = yKey, at = now, kind = if (met) "met_day" else "missed_day", valueAtEvent = used)
                )
                if (id != -1L && met && notices) {
                    Notifications.goal(ctx, g.id, "met_day", used, g.value, g.metric == GoalRow.METRIC_MINUTES, g.packageName?.let { names[it] })
                }
            }
            GoalLogic.applyDueSteps(g, today, now)?.let { stepped ->
                a.db.goals().update(stepped)
                a.db.goals().insertEvent(GoalEventRow(goalId = g.id, dayKey = today, at = now, kind = "stepped_down", valueAtEvent = stepped.value))
                DiagLog.add(ctx, "goal ${g.id}: target ${g.value} -> ${stepped.value}")
            }
        }
    }
}

class DailySummaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val a = applicationContext.app
        if (!a.prefs.reminders.first().dailySummary) return Result.success()
        val t = Usage.totals(a.db, a.dayKey(System.currentTimeMillis()))
        Notifications.dailySummary(applicationContext, t.reels(null), t.minutes(null))
        return Result.success()
    }
}

class WeeklyReportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val a = applicationContext.app
        if (!a.prefs.reminders.first().weeklyReport) return Result.success()
        val today = LocalDate.parse(a.dayKey(System.currentTimeMillis()))
        val week = StatsLogic.rangeFor(Period.WEEK, today)
        val prev = StatsLogic.rangeFor(Period.WEEK, today, 1)
        val cur = a.db.stats().rangeOnce(week.fromKey, week.toKey).sumOf { it.reels }
        val before = a.db.stats().rangeOnce(prev.fromKey, prev.toKey).sumOf { it.reels }
        Notifications.weeklyReport(applicationContext, cur, StatsLogic.deltaPct(cur, before))
        return Result.success()
    }
}

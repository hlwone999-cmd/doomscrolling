package app.tidemark.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.tidemark.App
import app.tidemark.MainActivity
import app.tidemark.R
import app.tidemark.app
import app.tidemark.core.Severities
import app.tidemark.core.Severity
import kotlinx.coroutines.launch

/** Home-screen widget (S18): today's count and a severity word. Updates locally, no network. */
class CounterWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        context.app.widgets.refresh()
    }
}

class Widgets(private val app: App) {
    fun refresh() {
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(ComponentName(app, CounterWidget::class.java))
        if (ids.isEmpty()) return
        app.appScope.launch {
            val total = app.db.stats().totalFor(app.dayKey(System.currentTimeMillis()))
            val goal = app.db.goals().enabledOnce().firstOrNull { it.packageName == null && it.metric == "reels" }
            val serviceOn = ServiceStatus.isAccessibilityEnabled(app)
            val severity = Severities.of(total, goal?.value, goal?.warnAtPct ?: 80)
            val label = when {
                !serviceOn -> app.getString(R.string.widget_off)
                goal != null -> app.getString(R.string.widget_of_goal, goal.value)
                else -> app.getString(R.string.today_count_label)
            }
            val word = app.getString(
                when (severity) {
                    Severity.LOW -> R.string.severity_low
                    Severity.MID -> R.string.severity_mid
                    Severity.HIGH -> R.string.severity_high
                    Severity.OVER -> if (goal != null) R.string.severity_over_goal else R.string.severity_over
                }
            )
            val open = PendingIntent.getActivity(
                app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val views = RemoteViews(app.packageName, R.layout.widget_counter).apply {
                setTextViewText(R.id.widget_count, total.toString())
                setTextViewText(R.id.widget_label, label)
                setTextViewText(R.id.widget_severity, word)
                setContentDescription(R.id.widget_root, "$total. $label. $word.")
                setOnClickPendingIntent(R.id.widget_root, open)
            }
            manager.updateAppWidget(ids, views)
        }
    }
}

package app.tidemark

import android.app.Application
import android.content.Context
import app.tidemark.core.DayKeys
import app.tidemark.data.AppDatabase
import app.tidemark.data.Prefs
import app.tidemark.data.SessionWriter
import app.tidemark.detect.Rules
import app.tidemark.service.Notifications
import app.tidemark.service.Widgets
import app.tidemark.service.Work
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Small hand-written object graph (instead of Hilt, to keep the build simple).
 * One database, one prefs store, one writer for the whole process.
 */
class App : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val prefs: Prefs by lazy { Prefs(this) }
    val rules: Rules by lazy { Rules.load(this) }
    val widgets: Widgets by lazy { Widgets(this) }

    /** Day reset hour from Settings, kept in memory for the counting path. */
    @Volatile var resetHour: Int = DayKeys.DEFAULT_RESET_HOUR
        private set

    fun dayKey(epochMs: Long): String = DayKeys.of(epochMs, resetHour = resetHour)

    /** Single-writer: its consumer coroutine runs on one IO thread at a time. */
    val writer: SessionWriter by lazy {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        val oneThread = Dispatchers.IO.limitedParallelism(1)
        SessionWriter(this, db, CoroutineScope(SupervisorJob() + oneThread), dayKeyOf = { dayKey(it) })
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        appScope.launch {
            prefs.resetHour.collect { resetHour = it }
        }
        appScope.launch {
            // First run keeps existing schedules; later changes to reminder times
            // or the reset hour re-plan the clock-time jobs.
            val first = prefs.reminders.first()
            Work.schedule(this@App, first, prefs.resetHour.first(), replaceTimed = false)
            combine(prefs.reminders, prefs.resetHour) { r, h -> r to h }.drop(1).collect { (r, h) ->
                Work.schedule(this@App, r, h, replaceTimed = true)
            }
        }
    }
}

val Context.app: App get() = applicationContext as App

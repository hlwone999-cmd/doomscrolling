package app.tidemark.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.tidemark.core.DayKeys
import app.tidemark.core.OverlayMode
import app.tidemark.core.Retention
import app.tidemark.design.ThemeMode
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class DisclosureState { UNSET, ACCEPTED, DECLINED }

/** Counter appearance (S14 "Counter"). size: 0 small, 1 medium, 2 large. opacity 60-100 %. */
data class OverlaySettings(
    val mode: OverlayMode = OverlayMode.AUTO,
    val size: Int = 1,
    val opacityPct: Int = 88,
    val showTimer: Boolean = false,
)

/** Reminders (S14 "Reminders"). Minutes of day are local time; weekly day is ISO (7 = Sunday). */
data class ReminderSettings(
    val dailySummary: Boolean = true,
    val dailySummaryMinute: Int = 21 * 60,
    val weeklyReport: Boolean = true,
    val weeklyReportDay: Int = 7,
    val nudgeMinutes: Int = 20,
    val nudgeVideos: Int = 0,
    val goalNotices: Boolean = true,
)

/** Everything that is a preference, not a record. Never leaves the device. */
class Prefs(context: Context) {
    private val store = context.applicationContext.settingsStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    // ---- consent and onboarding ----

    val disclosure: Flow<DisclosureState> = data.map { p ->
        p[DISCLOSURE]?.let { runCatching { DisclosureState.valueOf(it) }.getOrNull() } ?: DisclosureState.UNSET
    }

    suspend fun setDisclosure(state: DisclosureState, now: Long = System.currentTimeMillis()) {
        store.edit {
            it[DISCLOSURE] = state.name
            if (state == DisclosureState.ACCEPTED) it[DISCLOSURE_AT] = now
        }
    }

    val permissionsSeen: Flow<Boolean> = data.map { it[PERMISSIONS_SEEN] ?: false }
    suspend fun setPermissionsSeen() = store.edit { it[PERMISSIONS_SEEN] = true }

    // ---- appearance ----

    val theme: Flow<ThemeMode> = data.map { p ->
        p[THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }
    suspend fun setTheme(mode: ThemeMode) = store.edit { it[THEME] = mode.name }

    val overlay: Flow<OverlaySettings> = data.map { p ->
        OverlaySettings(
            mode = p[OVERLAY_MODE]?.let { runCatching { OverlayMode.valueOf(it) }.getOrNull() } ?: OverlayMode.AUTO,
            size = (p[OVERLAY_SIZE] ?: 1).coerceIn(0, 2),
            opacityPct = (p[OVERLAY_OPACITY] ?: 88).coerceIn(60, 100),
            showTimer = p[OVERLAY_TIMER] ?: false,
        )
    }.distinctUntilChanged()

    suspend fun setOverlay(s: OverlaySettings) = store.edit {
        it[OVERLAY_MODE] = s.mode.name
        it[OVERLAY_SIZE] = s.size.coerceIn(0, 2)
        it[OVERLAY_OPACITY] = s.opacityPct.coerceIn(60, 100)
        it[OVERLAY_TIMER] = s.showTimer
    }

    /** Normalised 0..1 position of the floating counter; null until the user moves it. */
    val overlayPosition: Flow<Pair<Float, Float>?> = data.map { p ->
        val x = p[OVERLAY_X]; val y = p[OVERLAY_Y]
        if (x != null && y != null) x to y else null
    }
    suspend fun setOverlayPosition(x: Float, y: Float) = store.edit {
        it[OVERLAY_X] = x.coerceIn(0f, 1f); it[OVERLAY_Y] = y.coerceIn(0f, 1f)
    }

    // ---- day and counting ----

    val resetHour: Flow<Int> = data.map { (it[RESET_HOUR] ?: DayKeys.DEFAULT_RESET_HOUR).coerceIn(0, 6) }.distinctUntilChanged()
    suspend fun setResetHour(h: Int) = store.edit { it[RESET_HOUR] = h.coerceIn(0, 6) }

    /** 0 = not paused. */
    val pausedUntil: Flow<Long> = data.map { it[PAUSED_UNTIL] ?: 0L }
    suspend fun setPausedUntil(t: Long) = store.edit { it[PAUSED_UNTIL] = t }

    // ---- reminders ----

    val reminders: Flow<ReminderSettings> = data.map { p ->
        ReminderSettings(
            dailySummary = p[DAILY_ON] ?: true,
            dailySummaryMinute = (p[DAILY_MIN] ?: 21 * 60).coerceIn(0, 1439),
            weeklyReport = p[WEEKLY_ON] ?: true,
            weeklyReportDay = (p[WEEKLY_DAY] ?: 7).coerceIn(1, 7),
            nudgeMinutes = (p[NUDGE_MIN] ?: 20).coerceIn(0, 240),
            nudgeVideos = (p[NUDGE_VIDEOS] ?: 0).coerceIn(0, 1000),
            goalNotices = p[GOAL_NOTICES] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun setReminders(r: ReminderSettings) = store.edit {
        it[DAILY_ON] = r.dailySummary
        it[DAILY_MIN] = r.dailySummaryMinute.coerceIn(0, 1439)
        it[WEEKLY_ON] = r.weeklyReport
        it[WEEKLY_DAY] = r.weeklyReportDay.coerceIn(1, 7)
        it[NUDGE_MIN] = r.nudgeMinutes.coerceIn(0, 240)
        it[NUDGE_VIDEOS] = r.nudgeVideos.coerceIn(0, 1000)
        it[GOAL_NOTICES] = r.goalNotices
    }

    // ---- privacy ----

    val retentionDays: Flow<Int> = data.map { it[RETENTION] ?: Retention.DEFAULT_DAYS }.distinctUntilChanged()
    suspend fun setRetentionDays(d: Int) = store.edit { it[RETENTION] = d }

    // ---- service health ----

    val heartbeatAt: Flow<Long> = data.map { it[HEARTBEAT] ?: 0L }
    suspend fun heartbeat(now: Long) = store.edit { it[HEARTBEAT] = now }

    suspend fun lastStoppedNoticeAt(): Long = data.first()[STOPPED_NOTICE] ?: 0L
    suspend fun setStoppedNoticeAt(t: Long) = store.edit { it[STOPPED_NOTICE] = t }

    /** Per-install random salt for item hashes. Created on first use; rotated by "delete all". */
    suspend fun salt(): ByteArray {
        data.first()[SALT]?.let { return Base64.decode(it, Base64.NO_WRAP) }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var result = fresh
        store.edit { p ->
            val existing = p[SALT]
            if (existing == null) p[SALT] = Base64.encodeToString(fresh, Base64.NO_WRAP)
            else result = Base64.decode(existing, Base64.NO_WRAP)
        }
        return result
    }

    /**
     * "Delete all data": every preference goes except the consent decision and
     * onboarding progress (the user keeps the app set up), and a new salt is made.
     */
    suspend fun resetAfterDeleteAll() {
        store.edit { p ->
            val disclosure = p[DISCLOSURE]; val at = p[DISCLOSURE_AT]; val seen = p[PERMISSIONS_SEEN]
            p.clear()
            disclosure?.let { p[DISCLOSURE] = it }
            at?.let { p[DISCLOSURE_AT] = it }
            seen?.let { p[PERMISSIONS_SEEN] = it }
            p[SALT] = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        }
    }

    private companion object {
        val DISCLOSURE = stringPreferencesKey("disclosure_state")
        val DISCLOSURE_AT = longPreferencesKey("disclosure_accepted_at")
        val PERMISSIONS_SEEN = booleanPreferencesKey("permissions_seen")
        val THEME = stringPreferencesKey("theme")
        val PAUSED_UNTIL = longPreferencesKey("tracking_paused_until")
        val OVERLAY_X = floatPreferencesKey("overlay_x")
        val OVERLAY_Y = floatPreferencesKey("overlay_y")
        val OVERLAY_MODE = stringPreferencesKey("overlay_mode")
        val OVERLAY_SIZE = intPreferencesKey("overlay_size")
        val OVERLAY_OPACITY = intPreferencesKey("overlay_opacity")
        val OVERLAY_TIMER = booleanPreferencesKey("overlay_show_timer")
        val RESET_HOUR = intPreferencesKey("reset_hour")
        val DAILY_ON = booleanPreferencesKey("reminders_daily_on")
        val DAILY_MIN = intPreferencesKey("reminders_daily_minute")
        val WEEKLY_ON = booleanPreferencesKey("reminders_weekly_on")
        val WEEKLY_DAY = intPreferencesKey("reminders_weekly_day")
        val NUDGE_MIN = intPreferencesKey("reminders_nudge_minutes")
        val NUDGE_VIDEOS = intPreferencesKey("reminders_nudge_videos")
        val GOAL_NOTICES = booleanPreferencesKey("reminders_goal_notices")
        val RETENTION = intPreferencesKey("retention_days")
        val HEARTBEAT = longPreferencesKey("service_heartbeat_at")
        val STOPPED_NOTICE = longPreferencesKey("stopped_notice_at")
        val SALT = stringPreferencesKey("install_salt")
    }
}

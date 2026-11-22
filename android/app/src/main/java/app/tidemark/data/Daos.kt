package app.tidemark.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackedAppDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(apps: List<TrackedApp>)

    @Query("SELECT * FROM tracked_app WHERE enabled = 1")
    suspend fun enabled(): List<TrackedApp>

    @Query("SELECT * FROM tracked_app ORDER BY display_name, package")
    fun all(): Flow<List<TrackedApp>>

    @Query("SELECT package FROM tracked_app WHERE enabled = 1")
    fun enabledPackages(): Flow<List<String>>

    @Query("UPDATE tracked_app SET enabled = :enabled, updated_at = :now WHERE package = :pkg")
    suspend fun setEnabled(pkg: String, enabled: Boolean, now: Long)

    @Query("UPDATE tracked_app SET last_seen_version_code = :versionCode, updated_at = :now WHERE package = :pkg")
    suspend fun setVersion(pkg: String, versionCode: Long, now: Long)
}

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(row: SessionRow): Long

    @Query(
        """UPDATE session
           SET ended_at = :endedAt, ended_reason = :reason, active_ms = :activeMs,
               last_event_at = MAX(last_event_at, :endedAt), is_open = NULL, updated_at = :now
           WHERE id = :id AND is_open = 1"""
    )
    suspend fun close(id: Long, endedAt: Long, reason: String, activeMs: Long, now: Long): Int

    /** Keeps last_event_at and the live active time of the open session current (every ~5 s). */
    @Query(
        """UPDATE session SET last_event_at = MAX(last_event_at, :at), active_ms = MAX(active_ms, :activeMs),
               updated_at = :at
           WHERE id = :id AND is_open = 1"""
    )
    suspend fun touch(id: Long, at: Long, activeMs: Long)

    /** Demo data only: closes a session that was inserted with is_open = NULL. */
    @Query(
        """UPDATE session SET ended_at = :endedAt, ended_reason = 'left_surface', active_ms = :activeMs,
               last_event_at = :endedAt, updated_at = :endedAt
           WHERE id = :id AND ended_at IS NULL"""
    )
    suspend fun finishDemo(id: Long, endedAt: Long, activeMs: Long)

    /**
     * Sessions left open by a crash, a kill or a reboot end at their last
     * activity. Active time is the last value written by touch(); if none was
     * written yet, the time between start and last activity.
     */
    @Query(
        """UPDATE session
           SET ended_at = last_event_at, ended_reason = 'service_stopped',
               active_ms = CASE WHEN active_ms > 0 THEN MIN(active_ms, last_event_at - started_at)
                                ELSE last_event_at - started_at END,
               is_open = NULL, updated_at = :now
           WHERE is_open = 1"""
    )
    suspend fun closeDangling(now: Long): Int

    @Query("SELECT package, active_ms FROM session WHERE is_open = 1 AND day_key = :dayKey")
    fun openActive(dayKey: String): Flow<List<OpenActive>>

    @Query("SELECT * FROM session WHERE is_open = 1 LIMIT 1")
    suspend fun openSession(): SessionRow?

    @Query("SELECT * FROM session WHERE ended_at IS NOT NULL ORDER BY started_at DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SessionRow>>

    @Query("SELECT * FROM session WHERE ended_at IS NOT NULL AND package = :pkg ORDER BY started_at DESC LIMIT :limit")
    fun recentForApp(pkg: String, limit: Int): Flow<List<SessionRow>>

    @Query("SELECT * FROM session WHERE ended_at IS NOT NULL ORDER BY started_at")
    suspend fun allClosed(): List<SessionRow>

    @Query("DELETE FROM session WHERE day_key BETWEEN :fromDay AND :toDay AND is_open IS NULL")
    suspend fun deleteDays(fromDay: String, toDay: String): Int

    @Query("DELETE FROM session WHERE is_open IS NULL")
    suspend fun deleteAllClosed(): Int
}

/** A (day, app) pair whose daily total must be rebuilt. */
data class DayPackage(
    @androidx.room.ColumnInfo(name = "day_key") val dayKey: String,
    @androidx.room.ColumnInfo(name = "package") val packageName: String,
)

/**
 * Removes the sample rows an earlier build's developer "Insert demo data"
 * button wrote (rules_version 0, keys "i:demo-..."), then rebuilds the daily
 * totals of the affected days from real sessions only.
 */
@Dao
interface DemoPurgeDao {
    @Query(
        """SELECT DISTINCT s.id FROM session s
           WHERE s.rules_version = 0
              OR EXISTS (SELECT 1 FROM reel_event r WHERE r.session_id = s.id AND r.item_key LIKE 'i:demo-%')"""
    )
    suspend fun demoSessionIds(): List<Long>

    @Query("SELECT DISTINCT day_key, package FROM session WHERE id IN (:ids)")
    suspend fun daysOf(ids: List<Long>): List<DayPackage>

    @Query("DELETE FROM session WHERE id IN (:ids)")
    suspend fun deleteSessions(ids: List<Long>): Int

    @Query(
        """INSERT OR REPLACE INTO daily_stat (day_key, package, reels, active_ms, sessions, updated_at)
           SELECT :day, :pkg,
                  (SELECT COUNT(*) FROM reel_event r JOIN session s ON s.id = r.session_id
                    WHERE s.day_key = :day AND s.package = :pkg),
                  (SELECT COALESCE(SUM(active_ms), 0) FROM session
                    WHERE day_key = :day AND package = :pkg AND ended_at IS NOT NULL),
                  (SELECT COUNT(*) FROM session WHERE day_key = :day AND package = :pkg),
                  :now"""
    )
    suspend fun rebuildDay(day: String, pkg: String, now: Long)

    @Query("DELETE FROM daily_stat WHERE reels = 0 AND active_ms = 0 AND sessions = 0")
    suspend fun dropEmptyDays(): Int
}

@Dao
interface ReelEventDao {
    /** Returns -1 when the (session_id, item_key) pair already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: ReelEventRow): Long

    /** Retention: detailed events go, daily totals stay. The open session is never touched. */
    @Query(
        """DELETE FROM reel_event WHERE at < :cutoff
           AND session_id NOT IN (SELECT id FROM session WHERE is_open = 1)"""
    )
    suspend fun pruneBefore(cutoff: Long): Int

    /** Every video counted from real activity (demo rows are purged on start). */
    @Query("SELECT COUNT(*) FROM reel_event")
    fun countAll(): Flow<Int>

    /** Event times for a day's hourly chart (times only, never keys). */
    @Query(
        """SELECT r.at FROM reel_event r JOIN session s ON s.id = r.session_id
           WHERE s.day_key = :dayKey AND (:pkg IS NULL OR s.package = :pkg)
           ORDER BY r.at"""
    )
    fun timesForDay(dayKey: String, pkg: String?): Flow<List<Long>>
}

@Dao
interface StatsDao {
    @Query("SELECT COALESCE(SUM(reels), 0) FROM daily_stat WHERE day_key = :dayKey")
    suspend fun totalFor(dayKey: String): Int

    @Query("SELECT COALESCE(SUM(reels), 0) FROM daily_stat WHERE day_key = :dayKey AND package = :pkg")
    suspend fun totalForApp(dayKey: String, pkg: String): Int

    @Query(
        """SELECT d.package AS package, t.display_name AS display_name, d.reels AS reels,
                  d.active_ms AS active_ms, d.sessions AS sessions
           FROM daily_stat d JOIN tracked_app t ON t.package = d.package
           WHERE d.day_key = :dayKey AND (d.reels > 0 OR d.active_ms > 0 OR d.sessions > 0)
           ORDER BY d.reels DESC"""
    )
    fun byApp(dayKey: String): Flow<List<AppDayTotal>>

    @Query("SELECT * FROM daily_stat WHERE day_key BETWEEN :fromDay AND :toDay ORDER BY day_key")
    fun range(fromDay: String, toDay: String): Flow<List<DailyStatRow>>

    @Query("SELECT * FROM daily_stat WHERE day_key BETWEEN :fromDay AND :toDay ORDER BY day_key")
    suspend fun rangeOnce(fromDay: String, toDay: String): List<DailyStatRow>

    @Query("SELECT * FROM daily_stat ORDER BY day_key, package")
    suspend fun all(): List<DailyStatRow>

    @Query("SELECT DISTINCT day_key FROM daily_stat WHERE reels > 0 ORDER BY day_key")
    suspend fun daysWithData(): List<String>

    @Query("DELETE FROM daily_stat WHERE day_key BETWEEN :fromDay AND :toDay")
    suspend fun deleteDays(fromDay: String, toDay: String): Int

    @Query("DELETE FROM daily_stat")
    suspend fun deleteAll(): Int

    /** Import: adds a day only where none exists, so an import never inflates live data. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfMissing(rows: List<DailyStatRow>): List<Long>
}

@Dao
abstract class GoalDao {
    @Query("SELECT * FROM goal ORDER BY (package IS NOT NULL), package, metric")
    abstract fun all(): Flow<List<GoalRow>>

    @Query("SELECT * FROM goal WHERE enabled = 1")
    abstract suspend fun enabledOnce(): List<GoalRow>

    @Query("SELECT * FROM goal")
    abstract suspend fun allOnce(): List<GoalRow>

    @Query("SELECT * FROM goal WHERE id = :id")
    abstract suspend fun byId(id: Long): GoalRow?

    @Query("SELECT * FROM goal WHERE package IS :pkg AND metric = :metric LIMIT 1")
    abstract suspend fun byScope(pkg: String?, metric: String): GoalRow?

    @Insert
    abstract suspend fun insert(goal: GoalRow): Long

    @Update
    abstract suspend fun update(goal: GoalRow)

    @Query("DELETE FROM goal WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("UPDATE goal SET enabled = :enabled, updated_at = :now WHERE id = :id")
    abstract suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    /** One goal per (scope, metric): editing a scope that exists replaces it. Returns the id. */
    @Transaction
    open suspend fun save(goal: GoalRow): Long {
        val clash = byScope(goal.packageName, goal.metric)
        return when {
            goal.id != 0L && (clash == null || clash.id == goal.id) -> { update(goal); goal.id }
            goal.id != 0L && clash != null -> { delete(clash.id); update(goal); goal.id }
            clash != null -> { update(goal.copy(id = clash.id, createdAt = clash.createdAt)); clash.id }
            else -> insert(goal)
        }
    }

    /** -1 when this notice already exists for the goal and day. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEvent(event: GoalEventRow): Long

    @Query("SELECT COUNT(*) FROM goal_event WHERE goal_id = :goalId AND day_key = :dayKey AND kind = :kind")
    abstract suspend fun countKind(goalId: Long, dayKey: String, kind: String): Int

    @Query("SELECT * FROM goal_event WHERE day_key BETWEEN :fromDay AND :toDay ORDER BY at")
    abstract fun eventsBetween(fromDay: String, toDay: String): Flow<List<GoalEventRow>>

    @Query("SELECT * FROM goal_event WHERE goal_id = :goalId AND kind IN ('met_day','missed_day') ORDER BY day_key DESC LIMIT :limit")
    abstract suspend fun dayResults(goalId: Long, limit: Int): List<GoalEventRow>

    @Query("DELETE FROM goal_event WHERE day_key BETWEEN :fromDay AND :toDay")
    abstract suspend fun deleteEventDays(fromDay: String, toDay: String): Int

    @Query("DELETE FROM goal_event")
    abstract suspend fun deleteAllEvents(): Int

    @Query("DELETE FROM goal")
    abstract suspend fun deleteAllGoals(): Int
}

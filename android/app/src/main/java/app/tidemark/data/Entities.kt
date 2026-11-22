package app.tidemark.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Mirrors replica/schema.sql. Room creates the tables; unique indexes are real,
// triggers are added in AppDatabase. CHECK constraints from schema.sql are
// enforced by the writer (Room cannot declare them).

@Entity(tableName = "tracked_app")
data class TrackedApp(
    @PrimaryKey @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "surface_kind") val surfaceKind: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "last_seen_version_code") val lastSeenVersionCode: Long? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "session",
    foreignKeys = [ForeignKey(
        entity = TrackedApp::class,
        parentColumns = ["package"],
        childColumns = ["package"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["package", "started_at"]),
        Index(value = ["day_key"]),
        Index(value = ["started_at"]),
        // is_open is 1 while open and NULL when closed. NULLs never collide in a
        // unique index, so at most one session can be open at a time.
        Index(value = ["is_open"], unique = true),
    ],
)
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "surface_kind") val surfaceKind: String,
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "tz_offset_min") val tzOffsetMin: Int,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "last_event_at") val lastEventAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "reel_count") val reelCount: Int = 0,
    @ColumnInfo(name = "active_ms") val activeMs: Long = 0,
    @ColumnInfo(name = "ended_reason") val endedReason: String? = null,
    @ColumnInfo(name = "rules_version") val rulesVersion: Int,
    @ColumnInfo(name = "target_version_code") val targetVersionCode: Long? = null,
    @ColumnInfo(name = "is_open") val isOpen: Int? = 1,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "reel_event",
    foreignKeys = [ForeignKey(
        entity = SessionRow::class,
        parentColumns = ["id"],
        childColumns = ["session_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["at"]),
        // Same video never counted twice in one session. Also serves session_id lookups.
        Index(value = ["session_id", "item_key"], unique = true),
    ],
)
data class ReelEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "item_key") val itemKey: String,
    @ColumnInfo(name = "dwell_ms") val dwellMs: Long? = null,
)

@Entity(
    tableName = "daily_stat",
    primaryKeys = ["day_key", "package"],
    foreignKeys = [ForeignKey(
        entity = TrackedApp::class,
        parentColumns = ["package"],
        childColumns = ["package"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["package", "day_key"])],
)
data class DailyStatRow(
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "reels") val reels: Int = 0,
    @ColumnInfo(name = "active_ms") val activeMs: Long = 0,
    @ColumnInfo(name = "sessions") val sessions: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Read model for the Today screen. */
data class AppDayTotal(
    @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "reels") val reels: Int,
    @ColumnInfo(name = "active_ms") val activeMs: Long,
    @ColumnInfo(name = "sessions") val sessions: Int,
)

/**
 * Reduction goal (S11). package NULL = all tracked apps. One goal per
 * (scope, metric) is enforced by GoalDao.save, since Room cannot declare an
 * index on COALESCE(package, '*').
 */
@Entity(
    tableName = "goal",
    foreignKeys = [ForeignKey(
        entity = TrackedApp::class,
        parentColumns = ["package"],
        childColumns = ["package"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["package", "metric"])],
)
data class GoalRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package") val packageName: String? = null,
    @ColumnInfo(name = "metric") val metric: String = METRIC_REELS,          // reels | minutes
    @ColumnInfo(name = "value") val value: Int,
    @ColumnInfo(name = "warn_at_pct") val warnAtPct: Int = 80,
    @ColumnInfo(name = "action") val action: String = ACTION_NOTIFY,         // notify | block
    @ColumnInfo(name = "days_mask") val daysMask: Int = 127,                 // bit 0 = Monday
    @ColumnInfo(name = "active_from_min") val activeFromMin: Int? = null,     // optional window
    @ColumnInfo(name = "active_to_min") val activeToMin: Int? = null,
    @ColumnInfo(name = "snoozes_per_day") val snoozesPerDay: Int = 1,
    @ColumnInfo(name = "step_pct") val stepPct: Int? = null,                 // gradual reduction
    @ColumnInfo(name = "floor_value") val floorValue: Int? = null,
    @ColumnInfo(name = "next_step_day") val nextStepDay: String? = null,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val METRIC_REELS = "reels"
        const val METRIC_MINUTES = "minutes"
        const val ACTION_NOTIFY = "notify"
        const val ACTION_BLOCK = "block"
    }
}

/**
 * What happened to a goal on a day. seq is 0 for every kind except
 * 'snoozed' (1, 2, ...), so the unique index makes each notice fire once
 * per goal per day while still allowing several snoozes.
 */
@Entity(
    tableName = "goal_event",
    foreignKeys = [
        ForeignKey(entity = GoalRow::class, parentColumns = ["id"], childColumns = ["goal_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["goal_id", "day_key", "kind", "seq"], unique = true), Index(value = ["day_key"])],
)
data class GoalEventRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "goal_id") val goalId: Long,
    @ColumnInfo(name = "day_key") val dayKey: String,
    @ColumnInfo(name = "at") val at: Long,
    @ColumnInfo(name = "kind") val kind: String,   // warned | reached | blocked | snoozed | met_day | missed_day | stepped_down
    @ColumnInfo(name = "seq") val seq: Int = 0,
    @ColumnInfo(name = "value_at_event") val valueAtEvent: Int,
)

/** Live time of the open session, for Today (BUG C-6). */
data class OpenActive(
    @ColumnInfo(name = "package") val packageName: String,
    @ColumnInfo(name = "active_ms") val activeMs: Long,
)

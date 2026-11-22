package app.tidemark.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import app.tidemark.app
import app.tidemark.core.DayKeys
import app.tidemark.core.GoalDecision
import app.tidemark.core.GoalLevel
import app.tidemark.core.GoalLogic
import app.tidemark.service.Notifications
import java.time.LocalDateTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The single writer. Every database write from the counting path, and every
 * delete or import from the UI, goes through one channel processed in order
 * on one coroutine, so "close the old session" always lands before "open the
 * new one" and a delete never races a count. Engine tokens are mapped to row
 * ids here, so the engine never waits for the database.
 *
 * After each count and each touch (~5 s) it evaluates goals: records
 * warned/reached once per day, posts goal notices, and publishes which apps
 * are blocked right now.
 */
class SessionWriter(
    private val context: Context,
    private val db: AppDatabase,
    scope: CoroutineScope,
    private val dayKeyOf: (Long) -> String = { DayKeys.of(it) },
    private val offsetOf: (Long) -> Int = { DayKeys.offsetMinutes(it) },
) {
    private sealed interface Cmd {
        data class Open(val token: Long, val pkg: String, val surface: String, val at: Long,
                        val rulesVersion: Int, val versionCode: Long?) : Cmd
        data class Count(val token: Long, val itemKey: String, val at: Long, val dwellMs: Long?) : Cmd
        data class Touch(val token: Long, val at: Long, val activeMs: Long) : Cmd
        data class Close(val token: Long, val at: Long, val activeMs: Long, val reason: String) : Cmd
        data class CloseDangling(val now: Long) : Cmd
        data class Refresh(val now: Long) : Cmd
        data class Snooze(val goalId: Long, val now: Long, val done: CompletableDeferred<Boolean>) : Cmd
        data class RecordBlocked(val goalId: Long, val now: Long) : Cmd
        data class DeleteDays(val fromDay: String, val toDay: String, val done: CompletableDeferred<Unit>) : Cmd
        data class DeleteAll(val done: CompletableDeferred<Unit>) : Cmd
        data class Import(val rows: List<DailyStatRow>, val done: CompletableDeferred<Int>) : Cmd
        data object PurgeDemo : Cmd
    }

    /** What an engine token needs to (re)create its row after a delete. */
    private data class Meta(val pkg: String, val surface: String, val startedAt: Long, val rulesVersion: Int, val versionCode: Long?)

    private val channel = Channel<Cmd>(Channel.UNLIMITED)
    private val rowIds = HashMap<Long, Long>()
    private val meta = HashMap<Long, Meta>()
    private val snoozedUntil = HashMap<Long, Long>()
    private var lastWidgetAt = 0L

    private val _todayTotal = MutableStateFlow(0)
    private val _blocking = MutableStateFlow<List<GoalDecision>>(emptyList())
    private val _reelsGoal = MutableStateFlow<GoalRow?>(null)

    /** Today's total across tracked apps, updated after every counted video. */
    val todayTotal: StateFlow<Int> = _todayTotal

    /** Goals that block right now (reached, action = block, not snoozed). */
    val blocking: StateFlow<List<GoalDecision>> = _blocking

    /** The all-apps video goal, if any: the counter's colour follows it. */
    val reelsGoal: StateFlow<GoalRow?> = _reelsGoal

    init {
        scope.launch {
            for (cmd in channel) {
                try {
                    handle(cmd)
                } catch (e: Exception) {
                    // Never let one bad write stop counting. Logged locally only.
                    Log.w(TAG, "write failed: ${cmd::class.simpleName}", e)
                    DiagLog.add(context, "write failed: ${cmd::class.simpleName}: ${e.javaClass.simpleName}")
                    when (cmd) {
                        is Cmd.Snooze -> cmd.done.complete(false)
                        is Cmd.DeleteDays -> cmd.done.complete(Unit)
                        is Cmd.DeleteAll -> cmd.done.complete(Unit)
                        is Cmd.Import -> cmd.done.complete(0)
                        else -> Unit
                    }
                }
            }
        }
    }

    // ---- counting path (called from the service, main thread, never blocks) ----

    fun open(token: Long, pkg: String, surface: String, at: Long, rulesVersion: Int, versionCode: Long?) =
        send(Cmd.Open(token, pkg, surface, at, rulesVersion, versionCode))
    fun count(token: Long, itemKey: String, at: Long, dwellMs: Long?) = send(Cmd.Count(token, itemKey, at, dwellMs))
    fun touch(token: Long, at: Long, activeMs: Long) = send(Cmd.Touch(token, at, activeMs))
    fun close(token: Long, at: Long, activeMs: Long, reason: String) = send(Cmd.Close(token, at, activeMs, reason))
    fun closeDangling(now: Long = System.currentTimeMillis()) = send(Cmd.CloseDangling(now))
    fun refreshToday(now: Long = System.currentTimeMillis()) = send(Cmd.Refresh(now))
    fun recordBlocked(goalId: Long, now: Long = System.currentTimeMillis()) = send(Cmd.RecordBlocked(goalId, now))

    /** Remove sample rows left by an older build's demo button; statistics then come from real activity only. */
    fun purgeDemoData() = send(Cmd.PurgeDemo)

    // ---- user actions (suspend until done) ----

    /** True if a snooze was left for today. */
    suspend fun snooze(goalId: Long, now: Long = System.currentTimeMillis()): Boolean =
        CompletableDeferred<Boolean>().also { send(Cmd.Snooze(goalId, now, it)) }.await()

    suspend fun deleteDays(fromDay: String, toDay: String) =
        CompletableDeferred<Unit>().also { send(Cmd.DeleteDays(fromDay, toDay, it)) }.await()

    suspend fun deleteAll() = CompletableDeferred<Unit>().also { send(Cmd.DeleteAll(it)) }.await()

    /** Returns how many days were added (existing days are never overwritten). */
    suspend fun importDaily(rows: List<DailyStatRow>): Int =
        CompletableDeferred<Int>().also { send(Cmd.Import(rows, it)) }.await()

    private fun send(cmd: Cmd) {
        channel.trySend(cmd)
    }

    private suspend fun handle(cmd: Cmd) {
        when (cmd) {
            is Cmd.Open -> {
                meta[cmd.token] = Meta(cmd.pkg, cmd.surface, cmd.at, cmd.rulesVersion, cmd.versionCode)
                insertRow(cmd.token, cmd.at)
                cmd.versionCode?.let { db.trackedApps().setVersion(cmd.pkg, it, cmd.at) }
                refreshTotal(cmd.at)
                evaluateGoals(cmd.at)
            }
            is Cmd.Count -> {
                val id = rowIds[cmd.token] ?: ensureRow(cmd.token, cmd.at) ?: return
                val inserted = db.reelEvents().insert(
                    ReelEventRow(sessionId = id, at = cmd.at, itemKey = cmd.itemKey, dwellMs = cmd.dwellMs)
                )
                if (inserted != -1L) {
                    refreshTotal(cmd.at)
                    evaluateGoals(cmd.at)
                    if (cmd.at - lastWidgetAt > 15_000L) {
                        lastWidgetAt = cmd.at
                        context.app.widgets.refresh()
                    }
                }
            }
            is Cmd.Touch -> {
                val id = rowIds[cmd.token] ?: return
                db.sessions().touch(id, cmd.at, cmd.activeMs)
                evaluateGoals(cmd.at)
            }
            is Cmd.Close -> {
                meta.remove(cmd.token)
                val id = rowIds.remove(cmd.token) ?: return
                db.sessions().close(id, cmd.at, cmd.reason, cmd.activeMs.coerceAtLeast(0), System.currentTimeMillis())
                context.app.widgets.refresh()
            }
            is Cmd.CloseDangling -> {
                rowIds.clear()
                db.sessions().closeDangling(cmd.now)
            }
            is Cmd.Refresh -> {
                refreshTotal(cmd.now)
                evaluateGoals(cmd.now)
            }
            is Cmd.Snooze -> {
                val goal = db.goals().byId(cmd.goalId)
                val day = dayKeyOf(cmd.now)
                val used = db.goals().countKind(cmd.goalId, day, "snoozed")
                val ok = goal != null && used < goal.snoozesPerDay
                if (ok) {
                    db.goals().insertEvent(GoalEventRow(goalId = cmd.goalId, dayKey = day, at = cmd.now, kind = "snoozed", seq = used + 1, valueAtEvent = 0))
                    snoozedUntil[cmd.goalId] = cmd.now + GoalLogic.SNOOZE_MS
                    evaluateGoals(cmd.now)
                }
                cmd.done.complete(ok)
            }
            is Cmd.RecordBlocked -> {
                db.goals().insertEvent(GoalEventRow(goalId = cmd.goalId, dayKey = dayKeyOf(cmd.now), at = cmd.now, kind = "blocked", valueAtEvent = 0))
            }
            is Cmd.DeleteDays -> {
                detachOpenRow()
                db.withTransaction {
                    db.sessions().deleteDays(cmd.fromDay, cmd.toDay)
                    db.stats().deleteDays(cmd.fromDay, cmd.toDay)
                    db.goals().deleteEventDays(cmd.fromDay, cmd.toDay)
                }
                refreshTotal(System.currentTimeMillis())
                context.app.widgets.refresh()
                cmd.done.complete(Unit)
            }
            is Cmd.DeleteAll -> {
                detachOpenRow()
                db.withTransaction {
                    db.sessions().deleteAllClosed()
                    db.stats().deleteAll()
                    db.goals().deleteAllEvents()
                    db.goals().deleteAllGoals()
                }
                snoozedUntil.clear()
                _todayTotal.value = 0
                _blocking.value = emptyList()
                _reelsGoal.value = null
                context.app.widgets.refresh()
                cmd.done.complete(Unit)
            }
            is Cmd.PurgeDemo -> {
                val ids = db.demoPurge().demoSessionIds()
                if (ids.isNotEmpty()) {
                    db.withTransaction {
                        val days = db.demoPurge().daysOf(ids)
                        db.demoPurge().deleteSessions(ids)
                        val now = System.currentTimeMillis()
                        days.forEach { db.demoPurge().rebuildDay(it.dayKey, it.packageName, now) }
                        db.demoPurge().dropEmptyDays()
                    }
                    DiagLog.add(context, "removed ${ids.size} sample session(s) from an older build")
                    refreshTotal(System.currentTimeMillis())
                    context.app.widgets.refresh()
                }
            }
            is Cmd.Import -> {
                val known = db.trackedApps().enabled().map { it.packageName }.toSet() +
                    KNOWN_PACKAGES
                val ids = db.stats().insertIfMissing(cmd.rows.filter { it.packageName in known })
                refreshTotal(System.currentTimeMillis())
                cmd.done.complete(ids.count { it != -1L })
            }
        }
    }

    /**
     * Before a delete: close the open row so it can be removed too. The engine
     * still holds its token; the next count re-creates a fresh row for it
     * (ensureRow), so counting carries on without leaving the screen.
     */
    private suspend fun detachOpenRow() {
        db.sessions().closeDangling(System.currentTimeMillis())
        rowIds.clear()
    }

    private suspend fun ensureRow(token: Long, at: Long): Long? {
        meta[token] ?: return null
        return insertRow(token, at)
    }

    private suspend fun insertRow(token: Long, at: Long): Long {
        val m = meta.getValue(token)
        // On open, at == the engine's start. After a delete, the new row starts now.
        val start = at
        val row = SessionRow(
            packageName = m.pkg, surfaceKind = m.surface,
            dayKey = dayKeyOf(start), tzOffsetMin = offsetOf(start),
            startedAt = start, lastEventAt = start,
            rulesVersion = m.rulesVersion, targetVersionCode = m.versionCode,
            isOpen = 1, createdAt = at, updatedAt = at,
        )
        val id = try {
            db.sessions().insert(row)
        } catch (e: SQLiteConstraintException) {
            // A session survived a crash: close it, then open ours.
            db.sessions().closeDangling(at)
            db.sessions().insert(row)
        }
        rowIds[token] = id
        return id
    }

    private suspend fun refreshTotal(now: Long) {
        _todayTotal.value = db.stats().totalFor(dayKeyOf(now))
    }

    private suspend fun evaluateGoals(now: Long) {
        val goals = db.goals().enabledOnce()
        _reelsGoal.value = goals.firstOrNull { it.packageName == null && it.metric == GoalRow.METRIC_REELS }
        if (goals.isEmpty()) {
            _blocking.value = emptyList()
            return
        }
        val day = dayKeyOf(now)
        val totals = Usage.totals(db, day)
        val clock = LocalDateTime.now()
        val decisions = GoalLogic.evaluate(goals, totals, clock.dayOfWeek.value, clock.hour * 60 + clock.minute)
        val notices = context.app.prefs.reminders.first().goalNotices
        var names: Map<String, String>? = null
        for (d in decisions) {
            if (d.level == GoalLevel.NONE) continue
            val goal = goals.first { it.id == d.goalId }
            val minutes = goal.metric == GoalRow.METRIC_MINUTES
            val warnedNew = db.goals().insertEvent(GoalEventRow(goalId = d.goalId, dayKey = day, at = now, kind = "warned", valueAtEvent = d.used)) != -1L
            val reachedNew = d.level == GoalLevel.REACHED &&
                db.goals().insertEvent(GoalEventRow(goalId = d.goalId, dayKey = day, at = now, kind = "reached", valueAtEvent = d.used)) != -1L
            if (!notices || (!warnedNew && !reachedNew)) continue
            val nameMap = names ?: db.trackedApps().enabled().associate { it.packageName to it.displayName }.also { names = it }
            val appName = d.pkg?.let { nameMap[it] }
            // Straight from under the warning line to the target: one notice, not two.
            val kind = if (reachedNew) "reached" else "warned"
            Notifications.goal(context, d.goalId, kind, d.used, d.target, minutes, appName)
        }
        _blocking.value = decisions.filter { it.blocks && (snoozedUntil[it.goalId] ?: 0L) <= now }
    }

    private companion object {
        const val TAG = "SessionWriter"
        /** Apps the bundled rules know, even if the user switched them off. */
        val KNOWN_PACKAGES = setOf(
            "com.instagram.android", "com.google.android.youtube", "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill", "com.facebook.katana", "com.snapchat.android",
        )
    }
}

package app.tidemark.core

import app.tidemark.data.GoalRow
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Today's usage, per app, as goals see it (closed sessions + the open one). */
data class UsageTotals(
    val reelsByApp: Map<String, Int> = emptyMap(),
    val activeMsByApp: Map<String, Long> = emptyMap(),
) {
    fun reels(pkg: String?): Int = if (pkg == null) reelsByApp.values.sum() else reelsByApp[pkg] ?: 0
    fun minutes(pkg: String?): Int =
        ((if (pkg == null) activeMsByApp.values.sum() else activeMsByApp[pkg] ?: 0L) / 60_000L).toInt()
}

enum class GoalLevel { NONE, WARN, REACHED }

data class GoalDecision(val goalId: Long, val pkg: String?, val level: GoalLevel, val used: Int, val target: Int, val blocks: Boolean)

/** Pure goal rules. All dates are day keys ("YYYY-MM-DD"), days of week ISO (1 = Monday). */
object GoalLogic {

    /** Is the goal in force at this moment? Disabled, wrong weekday or outside its window: no. */
    fun isActive(goal: GoalRow, isoDayOfWeek: Int, minuteOfDay: Int): Boolean {
        if (!goal.enabled) return false
        if (goal.daysMask and (1 shl (isoDayOfWeek - 1)) == 0) return false
        val from = goal.activeFromMin
        val to = goal.activeToMin
        if (from == null || to == null || from == to) return true
        return if (from < to) minuteOfDay in from until to
        else minuteOfDay >= from || minuteOfDay < to // window over midnight, e.g. 22:00-06:00
    }

    fun used(goal: GoalRow, totals: UsageTotals): Int =
        if (goal.metric == GoalRow.METRIC_MINUTES) totals.minutes(goal.packageName) else totals.reels(goal.packageName)

    fun level(used: Int, target: Int, warnAtPct: Int): GoalLevel {
        if (target <= 0) return GoalLevel.NONE
        return when {
            used >= target -> GoalLevel.REACHED
            used * 100 >= target * warnAtPct -> GoalLevel.WARN
            else -> GoalLevel.NONE
        }
    }

    fun evaluate(goals: List<GoalRow>, totals: UsageTotals, isoDayOfWeek: Int, minuteOfDay: Int): List<GoalDecision> =
        goals.filter { isActive(it, isoDayOfWeek, minuteOfDay) }.map { g ->
            val u = used(g, totals)
            val lvl = level(u, g.value, g.warnAtPct)
            GoalDecision(g.id, g.packageName, lvl, u, g.value, blocks = lvl == GoalLevel.REACHED && g.action == GoalRow.ACTION_BLOCK)
        }

    /** A day is met when usage stayed at or under the target. */
    fun dayMet(used: Int, target: Int): Boolean = used <= target

    /** Next target after one reduction step: at least 1 lower, never below the floor. */
    fun nextReducedValue(value: Int, stepPct: Int, floorValue: Int): Int {
        if (value <= floorValue) return floorValue
        val reduced = floor(value * (100 - stepPct) / 100.0).toInt()
        return max(floorValue, min(value - 1, reduced))
    }

    /**
     * Applies every reduction step that is due by [today] (several if the
     * phone was off for weeks). Returns null when nothing changes.
     */
    fun applyDueSteps(goal: GoalRow, today: String, now: Long): GoalRow? {
        val step = goal.stepPct ?: return null
        val floorV = goal.floorValue ?: return null
        var next = goal.nextStepDay ?: return null
        var value = goal.value
        var changed = false
        while (next <= today) {
            value = nextReducedValue(value, step, floorV)
            next = LocalDate.parse(next).plusDays(7).toString()
            changed = true
        }
        return if (changed) goal.copy(value = value, nextStepDay = next, updatedAt = now) else null
    }

    /**
     * Consecutive met days ending yesterday (today is not final yet), from
     * day results newest first. A gap (a day with no result) ends the streak.
     */
    fun streak(resultsNewestFirst: List<Pair<String, Boolean>>, today: String): Int {
        var expected = LocalDate.parse(today).minusDays(1)
        var n = 0
        for ((day, met) in resultsNewestFirst) {
            val d = LocalDate.parse(day)
            if (d >= LocalDate.parse(today)) continue
            if (d != expected || !met) break
            n++
            expected = expected.minusDays(1)
        }
        return n
    }

    /** Snooze lengths are fixed; the count per day is the goal's snoozes_per_day. */
    const val SNOOZE_MS = 5 * 60_000L
}

/** In-session nudge: once per session, after N minutes or N videos (0 = off). */
object Nudge {
    fun due(activeMs: Long, videos: Int, minutes: Int, videoLimit: Int, alreadySent: Boolean): Boolean {
        if (alreadySent) return false
        if (minutes > 0 && activeMs >= minutes * 60_000L) return true
        if (videoLimit > 0 && videos >= videoLimit) return true
        return false
    }
}

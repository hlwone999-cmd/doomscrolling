package app.tidemark.core

import app.tidemark.data.DailyStatRow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

enum class Period { DAY, WEEK, MONTH }

data class DayRange(val from: LocalDate, val to: LocalDate) {
    val fromKey: String get() = from.toString()
    val toKey: String get() = to.toString()
    val days: Int get() = (ChronoUnit.DAYS.between(from, to) + 1).toInt()
    fun dates(): List<LocalDate> = (0 until days).map { from.plusDays(it.toLong()) }
}

data class DayTotal(val day: LocalDate, val reels: Int, val activeMs: Long, val sessions: Int)

data class PeriodSummary(
    val total: Int,
    val activeMs: Long,
    val sessions: Int,
    val avgPerDay: Int,
    val best: DayTotal?,   // fewest videos among days that had any data or have passed
    val worst: DayTotal?,  // most videos
)

/** Pure statistics helpers for S08/S09. Weeks start on Monday. */
object StatsLogic {

    /** The period containing [today], or [offset] periods before it. */
    fun rangeFor(period: Period, today: LocalDate, offset: Int = 0): DayRange = when (period) {
        Period.DAY -> today.minusDays(offset.toLong()).let { DayRange(it, it) }
        Period.WEEK -> {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(offset.toLong())
            DayRange(monday, monday.plusDays(6))
        }
        Period.MONTH -> {
            val first = today.withDayOfMonth(1).minusMonths(offset.toLong())
            DayRange(first, first.with(TemporalAdjusters.lastDayOfMonth()))
        }
    }

    /** One entry per day of the range (zeros included), optionally for one app. */
    fun dailySeries(rows: List<DailyStatRow>, range: DayRange, pkg: String? = null): List<DayTotal> {
        val byDay = rows.filter { pkg == null || it.packageName == pkg }.groupBy { it.dayKey }
        return range.dates().map { d ->
            val r = byDay[d.toString()].orEmpty()
            DayTotal(d, r.sumOf { it.reels }, r.sumOf { it.activeMs }, r.sumOf { it.sessions })
        }
    }

    /**
     * Totals and average for a period. Only days up to [today] count toward the
     * average, so a week in progress isn't diluted by future days.
     */
    fun summary(series: List<DayTotal>, today: LocalDate): PeriodSummary {
        val elapsed = series.filter { !it.day.isAfter(today) }
        val total = series.sumOf { it.reels }
        val avg = if (elapsed.isEmpty()) 0 else (total.toDouble() / elapsed.size).roundToInt()
        val pastDays = elapsed.filter { it.day.isBefore(today) || it.reels > 0 }
        return PeriodSummary(
            total = total,
            activeMs = series.sumOf { it.activeMs },
            sessions = series.sumOf { it.sessions },
            avgPerDay = avg,
            best = pastDays.minByOrNull { it.reels },
            worst = pastDays.filter { it.reels > 0 }.maxByOrNull { it.reels },
        )
    }

    /** Percentage change, or null when there is nothing to compare against. */
    fun deltaPct(current: Int, previous: Int): Int? =
        if (previous <= 0) null else ((current - previous) * 100.0 / previous).roundToInt()

    /** Videos per clock hour (0-23, local time) for a day's event times. */
    fun hourly(times: List<Long>, zone: ZoneId = ZoneId.systemDefault()): IntArray {
        val out = IntArray(24)
        times.forEach { out[Instant.ofEpochMilli(it).atZone(zone).hour]++ }
        return out
    }

    /** Videos per app over the rows, most first. */
    fun perApp(rows: List<DailyStatRow>): List<Pair<String, Int>> =
        rows.groupBy { it.packageName }.map { (pkg, r) -> pkg to r.sumOf { it.reels } }
            .filter { it.second > 0 }.sortedByDescending { it.second }

    /** "38 min", "1 h 05 min". */
    fun minutesLabel(ms: Long): Pair<Int, Int> {
        val totalMin = (ms / 60_000L).toInt()
        return totalMin / 60 to totalMin % 60
    }

    /** Counter timer text: "4:07" or "1:02:09". */
    fun timer(ms: Long): String {
        val s = (ms / 1000L).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
    }
}

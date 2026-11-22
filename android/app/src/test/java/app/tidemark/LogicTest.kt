package app.tidemark

import app.tidemark.core.CsvFormat
import app.tidemark.core.GoalLevel
import app.tidemark.core.GoalLogic
import app.tidemark.core.Nudge
import app.tidemark.core.OemFamily
import app.tidemark.core.OemGuides
import app.tidemark.core.OverlayMode
import app.tidemark.core.OverlayPolicy
import app.tidemark.core.Period
import app.tidemark.core.StatsLogic
import app.tidemark.core.UsageTotals
import app.tidemark.data.DailyStatRow
import app.tidemark.data.GoalRow
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as replica/tests/logic_sim.py (run there with Python). */
class LogicTest {

    private fun goal(
        id: Long = 1, pkg: String? = null, metric: String = GoalRow.METRIC_REELS, value: Int = 100,
        warn: Int = 80, action: String = GoalRow.ACTION_NOTIFY, days: Int = 127, from: Int? = null, to: Int? = null,
        step: Int? = null, floor: Int? = null, nextStep: String? = null, enabled: Boolean = true,
    ) = GoalRow(id, pkg, metric, value, warn, action, days, from, to, 1, step, floor, nextStep, enabled, 0, 0)

    // ---- GoalLogic ----

    @Test fun activeRules() {
        assertTrue(GoalLogic.isActive(goal(), 3, 600))
        assertFalse(GoalLogic.isActive(goal(enabled = false), 3, 600))
        assertFalse(GoalLogic.isActive(goal(days = 0b0011111), 6, 600))
        assertTrue(GoalLogic.isActive(goal(from = 1320, to = 360), 1, 1380))
        assertTrue(GoalLogic.isActive(goal(from = 1320, to = 360), 1, 300))
        assertFalse(GoalLogic.isActive(goal(from = 1320, to = 360), 1, 720))
        assertTrue(GoalLogic.isActive(goal(from = 540, to = 1020), 1, 540))
        assertFalse(GoalLogic.isActive(goal(from = 540, to = 1020), 1, 1020))
    }

    @Test fun levels() {
        assertEquals(listOf(GoalLevel.NONE, GoalLevel.WARN, GoalLevel.REACHED), listOf(79, 80, 100).map { GoalLogic.level(it, 100, 80) })
        assertEquals(GoalLevel.NONE, GoalLogic.level(5, 0, 80))
    }

    @Test fun evaluateScopesAndMetrics() {
        val totals = UsageTotals(mapOf("ig" to 70, "yt" to 40), mapOf("ig" to 30 * 60_000L, "yt" to 15 * 60_000L + 59_000L))
        val d = GoalLogic.evaluate(
            listOf(goal(1), goal(2, pkg = "ig", value = 60, action = GoalRow.ACTION_BLOCK), goal(3, metric = GoalRow.METRIC_MINUTES, value = 50)),
            totals, 1, 600,
        )
        assertEquals(GoalLevel.REACHED, d[0].level); assertEquals(110, d[0].used); assertFalse(d[0].blocks)
        assertEquals(GoalLevel.REACHED, d[1].level); assertEquals(70, d[1].used); assertTrue(d[1].blocks)
        assertEquals(GoalLevel.WARN, d[2].level); assertEquals(45, d[2].used)
    }

    @Test fun reductionSteps() {
        assertEquals(90, GoalLogic.nextReducedValue(100, 10, 30))
        assertEquals(30, GoalLogic.nextReducedValue(32, 10, 30))
        assertEquals(4, GoalLogic.nextReducedValue(5, 1, 1))
        assertEquals(30, GoalLogic.nextReducedValue(30, 10, 30))
        val g = goal(step = 10, floor = 30, nextStep = "2026-11-21")
        assertNull(GoalLogic.applyDueSteps(g, "2026-11-20", 0))
        GoalLogic.applyDueSteps(g, "2026-11-21", 0)!!.let { assertEquals(90, it.value); assertEquals("2026-11-28", it.nextStepDay) }
        GoalLogic.applyDueSteps(g, "2026-12-06", 0)!!.let { assertEquals(72, it.value); assertEquals("2026-12-12", it.nextStepDay) }
    }

    @Test fun streaks() {
        val res = listOf("2026-11-21" to true, "2026-11-20" to true, "2026-11-19" to true, "2026-11-18" to false, "2026-11-17" to true)
        assertEquals(2, GoalLogic.streak(res, "2026-11-21"))
        assertEquals(1, GoalLogic.streak(listOf("2026-11-20" to true, "2026-11-18" to true), "2026-11-21"))
        assertEquals(0, GoalLogic.streak(emptyList(), "2026-11-21"))
    }

    @Test fun nudges() {
        assertTrue(Nudge.due(20 * 60_000L, 3, 20, 0, false))
        assertTrue(Nudge.due(1000, 100, 0, 100, false))
        assertFalse(Nudge.due(99 * 60_000L, 999, 20, 100, true))
        assertFalse(Nudge.due(99 * 60_000L, 999, 0, 0, false))
    }

    // ---- StatsLogic ----

    private val today = LocalDate.of(2026, 11, 21) // Saturday

    @Test fun ranges() {
        StatsLogic.rangeFor(Period.WEEK, today).let { assertEquals("2026-11-16", it.fromKey); assertEquals("2026-11-22", it.toKey) }
        StatsLogic.rangeFor(Period.WEEK, today, 1).let { assertEquals("2026-11-09", it.fromKey); assertEquals("2026-11-15", it.toKey) }
        StatsLogic.rangeFor(Period.MONTH, today).let { assertEquals("2026-11-01", it.fromKey); assertEquals("2026-11-30", it.toKey) }
        StatsLogic.rangeFor(Period.MONTH, LocalDate.of(2026, 1, 15), 1).let { assertEquals("2025-12-01", it.fromKey); assertEquals("2025-12-31", it.toKey) }
        assertEquals("2028-02-29", StatsLogic.rangeFor(Period.MONTH, LocalDate.of(2028, 2, 10)).toKey)
    }

    @Test fun seriesAndSummary() {
        val rows = listOf(
            DailyStatRow("2026-11-16", "ig", 40, 600_000, 2, 0), DailyStatRow("2026-11-16", "yt", 10, 60_000, 1, 0),
            DailyStatRow("2026-11-18", "ig", 5, 1, 1, 0), DailyStatRow("2026-11-21", "ig", 30, 0, 1, 0),
            DailyStatRow("2026-11-23", "ig", 99, 0, 1, 0),
        )
        val week = StatsLogic.rangeFor(Period.WEEK, today)
        val series = StatsLogic.dailySeries(rows, week)
        assertEquals(7, series.size); assertEquals(50, series[0].reels); assertEquals(0, series[1].reels)
        val s = StatsLogic.summary(series, today)
        assertEquals(85, s.total); assertEquals(14, s.avgPerDay)
        assertEquals(0, s.best!!.reels); assertTrue(s.best!!.day.isBefore(today))
        assertEquals(LocalDate.of(2026, 11, 16), s.worst!!.day); assertEquals(50, s.worst!!.reels)
        assertEquals(listOf(10, 0, 0, 0, 0, 0, 0), StatsLogic.dailySeries(rows, week, "yt").map { it.reels })
        assertEquals(-18, StatsLogic.deltaPct(82, 100)); assertNull(StatsLogic.deltaPct(5, 0))
        assertEquals("4:07", StatsLogic.timer(247_000)); assertEquals("1:02:09", StatsLogic.timer(3_729_000))
    }

    // ---- CsvFormat ----

    @Test fun csvRoundTripAndRejects() {
        val daily = listOf(
            DailyStatRow("2026-11-21", "com.instagram.android", 42, 1_830_000, 3, 0),
            DailyStatRow("2026-11-20", "com.zhiliaoapp.musically", 0, 0, 1, 0),
        )
        val text = CsvFormat.export(daily, emptyList())
        val back = CsvFormat.parseDaily(text, 0)
        assertEquals(daily, back.rows); assertEquals(0, back.skipped)
        val bad = CsvFormat.MAGIC + "\n" + CsvFormat.HEADER +
            "\ndaily,2026-13-x,ig,1,1,1\ndaily,2026-11-01,ig,-1,0,0\ndaily,2026-11-01,a b,1,1,1\ndaily,2026-11-02,ok.pkg,3,4,5,,,\n"
        val r = CsvFormat.parseDaily(bad, 0)
        assertEquals(listOf(DailyStatRow("2026-11-02", "ok.pkg", 3, 4000, 5, 0)), r.rows); assertEquals(3, r.skipped)
        assertTrue(CsvFormat.parseDaily("day,count\n2026-11-01,3\n", 0).rows.isEmpty())
    }

    // ---- Policies ----

    @Test fun overlayPolicy() {
        assertTrue(OverlayPolicy.visible(OverlayMode.AUTO, true, true, false, false))
        assertFalse(OverlayPolicy.visible(OverlayMode.AUTO, false, true, false, false))
        assertTrue(OverlayPolicy.visible(OverlayMode.ALWAYS, false, true, false, false))
        assertFalse(OverlayPolicy.visible(OverlayMode.OFF, true, true, false, false))
        assertFalse(OverlayPolicy.visible(OverlayMode.ALWAYS, true, true, true, false))
        assertFalse(OverlayPolicy.visible(OverlayMode.AUTO, true, true, false, true))
    }

    @Test fun scheduleDelays() {
        val zone = java.time.ZoneId.of("Asia/Jakarta")
        val sat2000 = java.time.ZonedDateTime.of(2026, 11, 21, 20, 0, 0, 0, zone) // Saturday 20:00
        assertEquals(60 * 60_000L, app.tidemark.core.Schedule.delayToNext(sat2000, 21 * 60))
        assertEquals(24 * 60 * 60_000L, app.tidemark.core.Schedule.delayToNext(sat2000, 20 * 60)) // exactly now: tomorrow
        assertEquals(25 * 60 * 60_000L, app.tidemark.core.Schedule.delayToNext(sat2000, 21 * 60, isoDay = 7)) // Sunday 21:00
        assertEquals((6 * 24 + 23) * 60 * 60_000L, app.tidemark.core.Schedule.delayToNext(sat2000, 19 * 60, isoDay = 6)) // next Saturday
    }

    @Test fun oemFamilies() {
        assertEquals(
            listOf(OemFamily.XIAOMI, OemFamily.XIAOMI, OemFamily.SAMSUNG, OemFamily.OPPO, OemFamily.OPPO, OemFamily.VIVO, OemFamily.HUAWEI, OemFamily.OTHER, OemFamily.OTHER),
            listOf("Xiaomi", "POCO", "samsung", "realme", "OnePlus", "vivo", "HONOR", "Google", null).map { OemGuides.familyOf(it) },
        )
    }
}

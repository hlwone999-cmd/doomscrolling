package app.tidemark

import app.tidemark.core.DayKeys
import app.tidemark.core.ItemKeys
import app.tidemark.core.Severities
import app.tidemark.core.Severity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val jakarta = ZoneId.of("Asia/Jakarta")
    private fun ms(text: String, zone: ZoneId = jakarta) =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test fun dayKeyRespectsResetHour() {
        assertEquals("2026-11-21", DayKeys.of(ms("2026-11-22T01:30"), jakarta, 4))
        assertEquals("2026-11-22", DayKeys.of(ms("2026-11-22T04:00"), jakarta, 4))
        assertEquals("2026-11-22", DayKeys.of(ms("2026-11-22T01:30"), jakarta, 0))
    }

    @Test fun dayKeySurvivesDst() {
        val ny = ZoneId.of("America/New_York")
        // DST ends 2026-11-01 02:00 local; with a 04:00 reset the day is not split.
        assertEquals("2026-10-31", DayKeys.of(ms("2026-11-01T01:30", ny), ny, 4))
        assertEquals("2026-11-01", DayKeys.of(ms("2026-11-01T05:00", ny), ny, 4))
    }

    @Test fun offsetMinutes() {
        assertEquals(420, DayKeys.offsetMinutes(ms("2026-11-21T12:00"), jakarta))
    }

    @Test fun hashIsStableSaltedAndShort() {
        val salt1 = ByteArray(32) { 1 }
        val salt2 = ByteArray(32) { 2 }
        val a = ItemKeys.hash(salt1, "pkg\u0000caption text here")
        assertEquals(a, ItemKeys.hash(salt1, "pkg\u0000caption text here"))
        assertNotEquals(a, ItemKeys.hash(salt2, "pkg\u0000caption text here"))
        assertTrue(a.startsWith("h:"))
        assertEquals(18, a.length)
        assertEquals("i:12", ItemKeys.index(12))
    }

    @Test fun severityWithAndWithoutGoal() {
        assertEquals(Severity.LOW, Severities.of(10))
        assertEquals(Severity.MID, Severities.of(50))
        assertEquals(Severity.HIGH, Severities.of(150))
        assertEquals(Severity.OVER, Severities.of(300))
        assertEquals(Severity.LOW, Severities.of(79, goal = 100))
        assertEquals(Severity.MID, Severities.of(80, goal = 100))
        assertEquals(Severity.HIGH, Severities.of(95, goal = 100))
        assertEquals(Severity.OVER, Severities.of(100, goal = 100))
    }
}

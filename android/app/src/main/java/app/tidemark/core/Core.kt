package app.tidemark.core

import java.time.Instant
import java.time.ZoneId
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Local calendar day for a timestamp, shifted by the reset hour.
 * With resetHour = 4, 01:30 on the 22nd belongs to the 21st.
 * Computed once at write time and stored, never recomputed (travel-safe).
 */
object DayKeys {
    const val DEFAULT_RESET_HOUR = 4

    fun of(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), resetHour: Int = DEFAULT_RESET_HOUR): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).minusHours(resetHour.toLong()).toLocalDate().toString()

    fun offsetMinutes(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        zone.rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds / 60
}

/**
 * Dedupe keys for a video. Either the pager position ("i:12") or a salted
 * one-way hash of a structural fingerprint ("h:" + 16 hex). The fingerprint
 * text only exists in memory for the duration of this call.
 */
object ItemKeys {
    fun index(position: Int): String = "i:$position"

    fun hash(salt: ByteArray, fingerprint: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val digest = mac.doFinal(fingerprint.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder("h:")
        for (i in 0 until 8) sb.append(String.format("%02x", digest[i]))
        return sb.toString()
    }
}

enum class Severity { LOW, MID, HIGH, OVER }

/** Severity scale from design/components.md. Thresholds without a goal are placeholders to tune. */
object Severities {
    val NO_GOAL_THRESHOLDS = intArrayOf(50, 150, 300)

    fun of(count: Int, goal: Int? = null, warnAtPct: Int = 80): Severity {
        if (goal != null && goal > 0) {
            val pct = count * 100 / goal
            return when {
                pct >= 100 -> Severity.OVER
                pct >= 95 -> Severity.HIGH
                pct >= warnAtPct -> Severity.MID
                else -> Severity.LOW
            }
        }
        val (mid, high, over) = NO_GOAL_THRESHOLDS.let { Triple(it[0], it[1], it[2]) }
        return when {
            count >= over -> Severity.OVER
            count >= high -> Severity.HIGH
            count >= mid -> Severity.MID
            else -> Severity.LOW
        }
    }
}

package app.tidemark.core

import app.tidemark.data.DailyStatRow
import app.tidemark.data.SessionRow

/**
 * The user's own export (S15). One file, two kinds of rows, so a single
 * share or save covers everything. Contains counts and times only: no item
 * keys, no content. Import reads the "daily" rows back.
 *
 *   # Tidemark export v1
 *   kind,day,package,videos,active_seconds,sessions,started_at,ended_at,end_reason
 *   daily,2026-11-21,com.instagram.android,42,1830,3,,,
 *   session,2026-11-21,com.instagram.android,12,410,,2026-11-21T20:14:03Z,2026-11-21T20:20:53Z,left_surface
 */
object CsvFormat {
    const val MAGIC = "# Tidemark export v1"
    const val HEADER = "kind,day,package,videos,active_seconds,sessions,started_at,ended_at,end_reason"

    private val DAY = Regex("""\d{4}-\d{2}-\d{2}""")
    private val PKG = Regex("""[A-Za-z0-9_.]+""")

    fun export(daily: List<DailyStatRow>, sessions: List<SessionRow>): String {
        val sb = StringBuilder()
        sb.append(MAGIC).append('\n').append(HEADER).append('\n')
        daily.forEach {
            sb.append("daily,").append(it.dayKey).append(',').append(it.packageName).append(',')
                .append(it.reels).append(',').append(it.activeMs / 1000).append(',').append(it.sessions).append(",,,\n")
        }
        sessions.forEach {
            sb.append("session,").append(it.dayKey).append(',').append(it.packageName).append(',')
                .append(it.reelCount).append(',').append(it.activeMs / 1000).append(",,")
                .append(java.time.Instant.ofEpochMilli(it.startedAt)).append(',')
                .append(it.endedAt?.let { e -> java.time.Instant.ofEpochMilli(e).toString() } ?: "").append(',')
                .append(it.endedReason ?: "").append('\n')
        }
        return sb.toString()
    }

    data class ImportResult(val rows: List<DailyStatRow>, val skipped: Int)

    /** Reads "daily" rows; anything malformed is skipped and counted, never guessed. */
    fun parseDaily(text: String, now: Long): ImportResult {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty() || lines.first() != MAGIC) return ImportResult(emptyList(), lines.size)
        val rows = mutableListOf<DailyStatRow>()
        var skipped = 0
        for (line in lines.drop(1)) {
            if (line == HEADER || line.startsWith("session,")) continue
            val f = line.split(',')
            val ok = f.size >= 6 && f[0] == "daily" && DAY.matches(f[1]) && PKG.matches(f[2])
            val videos = f.getOrNull(3)?.toIntOrNull()
            val seconds = f.getOrNull(4)?.toLongOrNull()
            val sessions = f.getOrNull(5)?.toIntOrNull()
            if (!ok || videos == null || seconds == null || sessions == null || videos < 0 || seconds < 0 || sessions < 0) {
                skipped++
                continue
            }
            rows += DailyStatRow(f[1], f[2], videos, seconds * 1000, sessions, now)
        }
        return ImportResult(rows, skipped)
    }
}

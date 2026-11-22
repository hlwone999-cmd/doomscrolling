package app.tidemark.data

import app.tidemark.core.UsageTotals

/** Today's usage for goals and notices: closed sessions from daily_stat plus the open session's live time. */
object Usage {
    suspend fun totals(db: AppDatabase, dayKey: String): UsageTotals {
        val rows = db.stats().rangeOnce(dayKey, dayKey)
        val reels = rows.associate { it.packageName to it.reels }
        val ms = rows.associate { it.packageName to it.activeMs }.toMutableMap()
        db.sessions().openSession()?.takeIf { it.dayKey == dayKey }?.let { open ->
            ms[open.packageName] = (ms[open.packageName] ?: 0L) + open.activeMs
        }
        return UsageTotals(reels, ms)
    }
}

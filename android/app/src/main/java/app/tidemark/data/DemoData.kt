package app.tidemark.data

import androidx.room.withTransaction
import app.tidemark.core.DayKeys

/**
 * Seed data for the design screen and screenshots. Clearly marked demo keys
 * ("i:demo-…"), closed sessions only, so it never collides with real counting.
 */
object DemoData {
    suspend fun insertToday(db: AppDatabase, now: Long = System.currentTimeMillis()) {
        val plan = listOf(
            Triple("com.instagram.android", "reels", 42),
            Triple("com.google.android.youtube", "shorts", 17),
            Triple("com.zhiliaoapp.musically", "tiktok_feed", 29),
        )
        db.withTransaction {
            plan.forEachIndexed { i, (pkg, surface, videos) ->
                val start = now - (i + 1) * 45 * 60_000L
                val id = db.sessions().insert(
                    SessionRow(
                        packageName = pkg, surfaceKind = surface,
                        dayKey = DayKeys.of(start), tzOffsetMin = DayKeys.offsetMinutes(start),
                        startedAt = start, lastEventAt = start,
                        rulesVersion = 0, isOpen = null, createdAt = start, updatedAt = start,
                    )
                )
                repeat(videos) { n ->
                    db.reelEvents().insert(
                        ReelEventRow(sessionId = id, at = start + n * 20_000L, itemKey = "i:demo-$id-$n", dwellMs = 20_000L)
                    )
                }
                val end = start + videos * 20_000L
                db.sessions().finishDemo(id, end, end - start)
            }
        }
    }
}

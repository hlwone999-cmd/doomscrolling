package app.tidemark.core

/** When the floating counter is on screen (S06, S14 "Show counter"). */
enum class OverlayMode { AUTO, ALWAYS, OFF }

object OverlayPolicy {
    /**
     * AUTO: on the short-video screen (the engine's decision, with its hide delay).
     * ALWAYS: also anywhere inside a tracked app.
     * OFF: never (counting continues).
     * "Hide until I leave" from the counter card wins until the user leaves.
     */
    fun visible(mode: OverlayMode, engineWantsVisible: Boolean, inTrackedApp: Boolean, hiddenUntilLeave: Boolean, blocked: Boolean): Boolean {
        if (blocked || hiddenUntilLeave) return false
        return when (mode) {
            OverlayMode.OFF -> false
            OverlayMode.AUTO -> engineWantsVisible
            OverlayMode.ALWAYS -> engineWantsVisible || inTrackedApp
        }
    }

    /** Pill size factor from the S/M/L setting. */
    fun scale(size: Int): Float = when (size) { 0 -> 0.85f; 2 -> 1.25f; else -> 1f }
}

/** Phone makers whose background limits need extra steps (S03, S16). */
enum class OemFamily { XIAOMI, SAMSUNG, OPPO, VIVO, HUAWEI, OTHER }

object OemGuides {
    fun familyOf(manufacturer: String?): OemFamily {
        val m = manufacturer?.lowercase()?.trim().orEmpty()
        return when {
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> OemFamily.XIAOMI
            m.contains("samsung") -> OemFamily.SAMSUNG
            m.contains("oppo") || m.contains("realme") || m.contains("oneplus") -> OemFamily.OPPO
            m.contains("vivo") || m.contains("iqoo") -> OemFamily.VIVO
            m.contains("huawei") || m.contains("honor") -> OemFamily.HUAWEI
            else -> OemFamily.OTHER
        }
    }
}

/** Retention choices for detailed events (S15). Daily totals are always kept. */
object Retention {
    val CHOICES = listOf(30, 90, 365)
    const val DEFAULT_DAYS = 90
    fun cutoff(now: Long, days: Int): Long = now - days * 86_400_000L
}

/** Delays for WorkManager jobs that should run at a local clock time. */
object Schedule {
    /**
     * Milliseconds from [now] until the next [minuteOfDay] local time, on ISO day
     * [isoDay] if given (weekly). Never 0: if it is exactly that time, the next one.
     */
    fun delayToNext(now: java.time.ZonedDateTime, minuteOfDay: Int, isoDay: Int? = null): Long {
        var t = now.toLocalDate().atStartOfDay(now.zone).plusMinutes(minuteOfDay.toLong())
        if (!t.isAfter(now)) t = t.plusDays(1)
        if (isoDay != null) {
            while (t.dayOfWeek.value != isoDay) t = t.plusDays(1)
        }
        return java.time.Duration.between(now, t).toMillis()
    }
}

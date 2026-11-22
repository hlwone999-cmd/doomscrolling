package app.tidemark.ui

import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/** Emits today's day key, re-checking every minute so screens roll over at the reset hour. */
fun dayKeyTicker(dayKeyOf: (Long) -> String): Flow<String> = flow {
    while (true) {
        emit(dayKeyOf(System.currentTimeMillis()))
        delay(60_000)
    }
}.distinctUntilChanged()

/** Local clock time ("21:00" / "9:00 PM") in the phone's locale. */
fun clock(epochMs: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(epochMs))

fun clockOfMinute(minuteOfDay: Int): String =
    java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

fun shortDate(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

fun weekdayShort(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

fun weekdayNarrow(isoDay: Int): String =
    java.time.DayOfWeek.of(isoDay).getDisplayName(TextStyle.NARROW, Locale.getDefault())

fun weekdayFull(isoDay: Int): String =
    java.time.DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.getDefault())

fun minutesText(ms: Long): Int = (ms / 60_000L).toInt()

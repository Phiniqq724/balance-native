package id.sndyy.balancenative.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Number and date text, matching src/lib/format.ts in the Expo app. */
object Format {
  private val zone: ZoneId get() = ZoneId.systemDefault()

  /** 1284500 -> "1.284.500" (Indonesian grouping, which is how both banks show Rupiah). */
  fun digits(value: Long): String {
    val s = abs(value).toString()
    val out = StringBuilder()
    s.forEachIndexed { i, c ->
      if (i > 0 && (s.length - i) % 3 == 0) out.append('.')
      out.append(c)
    }
    return out.toString()
  }

  fun digits(value: Double): String = digits(value.roundToLong())

  fun rupiah(value: Long): String = "Rp ${digits(value)}"

  fun signed(value: Long, direction: Direction): String = (if (direction == Direction.IN) "+" else "−") + digits(value)

  /** 1_250_000 -> "1,3jt", 45_000 -> "45rb". For tight spaces (chart readouts, widgets). */
  fun compact(value: Long): String {
    val v = abs(value)
    return when {
      v >= 1_000_000_000 -> one(v / 1_000_000_000.0) + "M"
      v >= 1_000_000 -> one(v / 1_000_000.0) + "jt"
      v >= 1_000 -> (v / 1_000.0).roundToLong().toString() + "rb"
      else -> v.toString()
    }
  }

  private fun one(x: Double): String {
    val r = (x * 10).roundToLong()
    return if (r % 10 == 0L) (r / 10).toString() else "${r / 10},${r % 10}"
  }

  fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

  fun dateTime(ms: Long): LocalDateTime = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

  fun startOfDayMs(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

  fun shortDay(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

  fun shortMonth(date: LocalDate): String = date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

  fun monthName(date: LocalDate): String = date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

  /** "Mon 3 Nov". */
  fun shortDate(date: LocalDate): String = "${shortDay(date)} ${date.dayOfMonth} ${shortMonth(date)}"

  fun dayLabel(date: LocalDate, today: LocalDate = LocalDate.now(zone)): String =
    when (ChronoUnit.DAYS.between(date, today)) {
      0L -> "Today"
      1L -> "Yesterday"
      else -> shortDate(date)
    }

  fun time(ms: Long): String {
    val t = dateTime(ms)
    return "%02d:%02d".format(t.hour, t.minute)
  }
}

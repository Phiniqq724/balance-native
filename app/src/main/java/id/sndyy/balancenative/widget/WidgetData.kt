package id.sndyy.balancenative.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import id.sndyy.balancenative.core.Account
import id.sndyy.balancenative.core.BalanceDb
import id.sndyy.balancenative.core.ReviewNotifier
import id.sndyy.balancenative.core.WidgetBridge
import id.sndyy.balancenative.core.WidgetCharts
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.max

/** Everything every widget shows, read from the ledger in one pass. Widgets render from this, never from SQL. */
internal data class Snapshot(
  val ready: Boolean,
  val today: LocalDate,
  val spent: Map<LocalDate, Long>,
  val received: Map<LocalDate, Long>,
  val toCheck: List<ReviewNotifier.Item>,
  val toCheckCount: Int,
) {
  fun spentBetween(from: LocalDate, toInclusive: LocalDate) = spent.entries.sumOf { (d, v) -> if (d in from..toInclusive) v else 0L }
  fun receivedBetween(from: LocalDate, toInclusive: LocalDate) = received.entries.sumOf { (d, v) -> if (d in from..toInclusive) v else 0L }

  companion object {
    /** The grid can be 53 weeks wide and the Year view needs January, so read whichever reaches further back. */
    fun load(context: Context): Snapshot {
      val zone = ZoneId.systemDefault()
      val today = LocalDate.now(zone)
      val owner = Account.userId(context)
      if (owner == null) return Snapshot(false, today, emptyMap(), emptyMap(), emptyList(), 0)
      val from = minOf(today.withDayOfYear(1), today.minusWeeks(54))
      val db = BalanceDb.get(context)
      val spent = HashMap<LocalDate, Long>()
      val received = HashMap<LocalDate, Long>()
      // Summed per local day by SQLite (as BalanceCore.dailyTotals does): one row per day, not per payment.
      db.readableDatabase.rawQuery(
        """SELECT date(occurred_at / 1000, 'unixepoch', 'localtime') AS day,
                  SUM(CASE WHEN direction = 'in' THEN 0 ELSE amount END), SUM(CASE WHEN direction = 'in' THEN amount ELSE 0 END)
           FROM transactions WHERE is_transfer = 0 AND occurred_at >= ? GROUP BY day""",
        arrayOf(from.atStartOfDay(zone).toInstant().toEpochMilli().toString()),
      ).use { c ->
        while (c.moveToNext()) {
          val day = LocalDate.parse(c.getString(0))
          if (c.getLong(1) != 0L) spent[day] = c.getLong(1)
          if (c.getLong(2) != 0L) received[day] = c.getLong(2)
        }
      }
      val items = db.readableDatabase.rawQuery(
        """
        SELECT r.id, r.package, r.title, r.body, r.posted_at, a.state, a.kind, a.direction, a.amount, a.counterparty
        FROM raw_notifications r LEFT JOIN ai_reviews a ON a.raw_id = r.id
        WHERE r.status = 'unparsed' ORDER BY r.posted_at DESC LIMIT 3
        """.trimIndent(),
        null,
      ).use { c ->
        buildList {
          while (c.moveToNext()) add(
            ReviewNotifier.Item(
              c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5), c.getString(6),
              c.getString(7), if (c.isNull(8)) null else c.getLong(8), c.getString(9),
            )
          )
        }
      }
      return Snapshot(true, today, spent, received, items, ReviewNotifier.openCount(db))
    }
  }
}

internal object WidgetDraw {
  private val HEAT = intArrayOf(0xFF2B2420.toInt(), 0xFF5C2D1C.toInt(), 0xFF93401F.toInt(), 0xFFD2582B.toInt(), 0xFFFF7444.toInt())
  private const val GAP_RATIO = 0.25f

  /** How a grid of a given size is laid out: seven rows fill the height, as many weeks as fit the width. */
  data class GridLayout(val weeks: Int, val cellDp: Float)

  fun gridLayout(widthDp: Float, heightDp: Float): GridLayout {
    val cell = heightDp / (7 + 6 * GAP_RATIO)
    val weeks = ((widthDp + cell * GAP_RATIO) / (cell * (1 + GAP_RATIO))).toInt().coerceIn(2, 53)
    return GridLayout(weeks, cell)
  }

  /** Monday-first columns ending with this week; future days stay empty. Drawn at the exact widget size, never scaled. */
  fun grid(context: Context, s: Snapshot, widthDp: Float, heightDp: Float): Pair<Bitmap, Int> {
    val layout = gridLayout(widthDp, heightDp)
    val start = s.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((layout.weeks - 1).toLong())
    val days = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(s.today) }.toList()
    val levels = WidgetBridge.levelsFor(days.map { s.spent[it] ?: 0L })

    val density = context.resources.displayMetrics.density
    val cell = layout.cellDp * density
    val gap = cell * GAP_RATIO
    val radius = cell * 0.22f
    val width = max(1, (layout.weeks * cell + (layout.weeks - 1) * gap).toInt())
    val height = max(1, (7 * cell + 6 * gap).toInt())
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    levels.forEachIndexed { i, level ->
      val x = (i / 7) * (cell + gap)
      val y = (i % 7) * (cell + gap)
      paint.color = HEAT[level]
      canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), radius, radius, paint)
    }
    return bitmap to layout.weeks
  }

  fun daily(context: Context, s: Snapshot, widthDp: Float, heightDp: Float): Pair<Bitmap, Long> {
    val days = (0 until 30).map { s.today.minusDays((29 - it).toLong()) }
    val values = days.map { (s.spent[it] ?: 0L).toDouble() }.toDoubleArray()
    return WidgetCharts.drawLine(context, values, 30, widthDp, heightDp) to values.sum().toLong()
  }

  fun week(context: Context, s: Snapshot, widthDp: Float, heightDp: Float): Pair<Bitmap, Long> {
    val days = (0 until 7).map { s.today.minusDays((6 - it).toLong()) }
    val values = days.map { (s.spent[it] ?: 0L).toDouble() }.toDoubleArray()
    val initials = days.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).take(1) }.toTypedArray()
    return WidgetCharts.drawBars(context, values, initials, widthDp, heightDp) to values.sum().toLong()
  }
}

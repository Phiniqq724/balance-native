package id.sndyy.balancenative.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.data.AtlasView
import id.sndyy.balancenative.data.Book
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.Selection
import id.sndyy.balancenative.ui.kit.Haptic
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Satoshi
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

private val LABEL_ROW = 16.dp
private const val HOLD_MS = 150L

private fun gapOf(view: AtlasView): Dp = when (view) { AtlasView.WEEKS -> 2.5.dp; AtlasView.DAYS -> 4.dp; AtlasView.YEAR -> 4.dp }

/**
 * Where each cell sits in time. Weeks and days: columns are weeks, rows are weekdays. Year: columns
 * are the 12 months, rows are its five runs of days (1-7, 8-14, 15-21, 22-28, 29-end), so a year
 * keeps big, tappable cells and roughly the same height as the other views instead of 53 slivers.
 */
private class Layout(val view: AtlasView, lastWeek: LocalDate, val lastMonth: LocalDate) {
  val cols = if (view == AtlasView.YEAR) 12 else view.weeks
  val rows = if (view == AtlasView.YEAR) 5 else 7
  val firstWeek: LocalDate = lastWeek.minusWeeks((cols - 1).toLong())
  val firstMonth: LocalDate = lastMonth.minusMonths(11)

  fun start(col: Int, row: Int): LocalDate? {
    if (view != AtlasView.YEAR) return firstWeek.plusWeeks(col.toLong()).plusDays(row.toLong())
    val m = firstMonth.plusMonths(col.toLong())
    return if (row * 7 < m.lengthOfMonth()) m.plusDays(row * 7L) else null
  }

  fun end(col: Int, row: Int): LocalDate? {
    val s = start(col, row) ?: return null
    if (view != AtlasView.YEAR) return s
    return if (row == 4) s.withDayOfMonth(s.lengthOfMonth()) else s.plusDays(6)
  }

  fun columnStart(col: Int): LocalDate = if (view == AtlasView.YEAR) firstMonth.plusMonths(col.toLong()) else firstWeek.plusWeeks(col.toLong())
}

/** Heat for the year view's day runs: quartiles of the runs themselves, so a busy week reads hot. */
private fun runLevels(book: Book, layout: Layout): Array<IntArray> {
  val totals = Array(layout.cols) { col -> LongArray(layout.rows) { row ->
    val s = layout.start(col, row)
    if (s == null) -1 else book.spent(s, layout.end(col, row)!!)
  } }
  val spending = totals.flatMap { it.toList() }.filter { it > 0 }.sorted()
  fun q(p: Double) = spending[((spending.size - 1) * p).toInt()]
  return Array(layout.cols) { col -> IntArray(layout.rows) { row ->
    val s = layout.start(col, row)
    val v = totals[col][row]
    when {
      s == null -> -3
      s.isAfter(book.today) -> -1
      book.firstData == null || layout.end(col, row)!!.isBefore(book.firstData) -> -2
      v <= 0 || spending.isEmpty() -> 0
      v <= q(0.25) -> 1
      v <= q(0.5) -> 2
      v <= q(0.75) -> 3
      else -> 4
    }
  } }
}

/**
 * The Atlas: the heat grid as navigation. Weeks are columns ending with [lastWeek], Monday on top;
 * the year view is months by runs of days. Tap selects a week (20W), a day (8W) or a month (year).
 * Hold 150ms, then drag, for a range: until then the touch is left for the page and the tab pager,
 * so flicks still scroll. One Canvas.
 */
@Composable
fun AtlasGrid(
  book: Book,
  selection: Selection,
  view: AtlasView,
  lastWeek: LocalDate,
  cursor: LocalDate?,
  onTap: (LocalDate) -> Unit,
  onDrag: (from: LocalDate, to: LocalDate, done: Boolean) -> Unit,
  onHold: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hostView = LocalView.current
  val measurer = rememberTextMeasurer()
  val tap by rememberUpdatedState(onTap)
  val drag by rememberUpdatedState(onDrag)
  val hold by rememberUpdatedState(onHold)
  val labelStyle = remember { TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = Ember.muted) }
  val dayStyle = remember { TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 11.sp) }

  // The year shows the 12 months ending this month, or ending 11 months after an older selection.
  val nowMonth = book.today.withDayOfMonth(1)
  val selMonth = selection.start.withDayOfMonth(1)
  val lastMonth = if (selMonth.isBefore(nowMonth.minusMonths(11))) selMonth.plusMonths(11) else nowMonth
  val layout = remember(view, lastWeek, lastMonth) { Layout(view, lastWeek, lastMonth) }
  val runs = remember(book, layout) { if (view == AtlasView.YEAR) runLevels(book, layout) else null }

  BoxWithConstraints(modifier.fillMaxWidth()) {
    val cols = layout.cols
    val rows = layout.rows
    val gap = gapOf(view)
    val cell = (maxWidth - gap * (cols - 1)) / cols
    val height = LABEL_ROW + cell * rows + gap * (rows - 1) + 4.dp

    Canvas(
      Modifier
        .fillMaxWidth()
        .height(height)
        .semantics { contentDescription = "Spending grid. Selected ${selectionLabel(selection)}." }
        .pointerInput(layout, cell) {
          val pitch = (cell + gap).toPx()
          val top = LABEL_ROW.toPx()
          fun dayAt(o: Offset): LocalDate {
            val c = floor((o.x + gap.toPx() / 2) / pitch).toInt().coerceIn(0, cols - 1)
            var r = floor((o.y - top + gap.toPx() / 2) / pitch).toInt().coerceIn(0, rows - 1)
            while (layout.start(c, r) == null) r--
            return layout.start(c, r)!!
          }
          fun sameUnit(a: LocalDate, b: LocalDate) = when (view) {
            AtlasView.WEEKS -> weekOf(a) == weekOf(b)
            AtlasView.DAYS -> a == b
            AtlasView.YEAR -> a.year == b.year && a.month == b.month
          }
          awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = dayAt(down.position)
            // Before the hold: watch only. Moving past slop hands the gesture to the page or pager.
            var lifted: Offset? = null
            val waited = withTimeoutOrNull(HOLD_MS) {
              while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                if (!change.pressed) { lifted = change.position; return@withTimeoutOrNull true }
                if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
              }
              @Suppress("UNREACHABLE_CODE") false
            }
            when (waited) {
              true -> { lifted?.let { if (sameUnit(start, dayAt(it))) tap(start) }; return@awaitEachGesture }
              false -> return@awaitEachGesture
              null -> Unit
            }
            // Held: the grid owns the touch now, so the page and pager stay put.
            hold()
            var last = start
            var dragging = false
            while (true) {
              val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
              change.consume()
              val here = dayAt(change.position)
              if (!change.pressed) {
                if (dragging) { drag(start, last, true); Haptic.confirm(hostView) }
                else if (sameUnit(start, here)) tap(start)
                break
              }
              if (here == last) continue
              // A range starts only once the finger crosses into another day (another month on the year view).
              val moved = if (view == AtlasView.YEAR) !sameUnit(start, here) else here != start
              if (!dragging && !moved) continue
              if (here.month != last.month || here.year != last.year) Haptic.month(hostView)
              else if (weekOf(here) != weekOf(last)) Haptic.week(hostView)
              dragging = true
              last = here
              drag(start, here, false)
            }
          }
        }
    ) {
      val c = cell.toPx()
      val g = gap.toPx()
      val top = LABEL_ROW.toPx()
      val pitch = c + g
      val radius = CornerRadius(min(if (view == AtlasView.YEAR) 3.dp.toPx() else 2.dp.toPx(), c * 0.25f))
      fun x(col: Int) = col * pitch
      fun y(row: Int) = top + row * pitch

      // Month names: every column on the year view, else over the column where each month starts.
      var lastM = -1
      var lastLabel = -9
      for (col in 0 until cols) {
        val d = layout.columnStart(col)
        if (view == AtlasView.YEAR) {
          drawText(measurer.measure(Format.shortMonth(d), labelStyle), topLeft = Offset(x(col), 0f))
        } else if (d.monthValue != lastM) {
          if (col - lastLabel >= 3) { drawText(measurer.measure(Format.shortMonth(d), labelStyle), topLeft = Offset(x(col), 0f)); lastLabel = col }
          lastM = d.monthValue
        }
      }

      // The selection is one soft shape: the union of each column's selected run, rounded at every
      // corner. A faint ink wash fills it (it shows in the gaps), then a rounded ink line rims it.
      val shape = selectionShape(selection, book.today, layout, c, g, top, 1.5.dp.toPx())
      val corner = android.graphics.CornerPathEffect(if (view == AtlasView.YEAR) 6.dp.toPx() else 4.dp.toPx())
      shape?.let { p ->
        drawContext.canvas.nativeCanvas.drawPath(p, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
          style = android.graphics.Paint.Style.FILL
          color = Ember.text.copy(alpha = 0.16f).toArgb()
          pathEffect = corner
        })
      }

      for (col in 0 until cols) for (row in 0 until rows) {
        val d = layout.start(col, row) ?: continue
        val level = runs?.get(col)?.get(row) ?: book.level(d)
        val at = Offset(x(col), y(row))
        when (level) {
          -3, -2 -> Unit
          -1 -> drawCircle(Ember.selected, radius = (c * 0.1f).coerceIn(0.8.dp.toPx(), 1.6.dp.toPx()), center = at + Offset(c / 2, c / 2))
          else -> {
            drawRoundRect(Ember.heat[level], at, Size(c, c), radius)
            if (view == AtlasView.DAYS) {
              val t = measurer.measure(d.dayOfMonth.toString(), dayStyle.copy(color = if (level >= 3) Ember.bg else Ember.muted))
              drawText(t, topLeft = at + Offset(5.dp.toPx(), 4.dp.toPx()))
            }
          }
        }
        val end = layout.end(col, row)!!
        // Today is a solid dot inside its cell: the bright end of the faint dots that mark the days
        // still to come. Ink on dark cells, charcoal on hot ones, so it reads on every heat step.
        if (!book.today.isBefore(d) && !book.today.isAfter(end)) {
          val dot = (c * 0.14f).coerceIn(1.6.dp.toPx(), 2.6.dp.toPx())
          val centre = if (view == AtlasView.DAYS) at + Offset(c - 7.dp.toPx(), c - 7.dp.toPx()) else at + Offset(c / 2, c / 2)
          drawCircle(if (level >= 3) Ember.bg else Ember.text, radius = dot, center = centre)
        }
        if (cursor != null && d == cursor && view != AtlasView.YEAR) drawRoundRect(Ember.text, at, Size(c, c), radius)
      }

      shape?.let { p ->
        drawContext.canvas.nativeCanvas.drawPath(p, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
          style = android.graphics.Paint.Style.STROKE
          strokeWidth = 1.5.dp.toPx()
          color = Ember.text.copy(alpha = 0.92f).toArgb()
          pathEffect = corner
        })
      }
    }
  }
}

/**
 * Union of one rectangle per column (a contiguous range is one run per column), pushed out past the
 * gap by [pad] so the rim never touches a cell. Plain rectangles union cleanly; the corner effect then
 * rounds convex and concave corners alike.
 */
private fun selectionShape(sel: Selection, today: LocalDate, layout: Layout, c: Float, g: Float, top: Float, pad: Float): android.graphics.Path? {
  val o = g / 2 + pad
  val pitch = c + g
  var out: android.graphics.Path? = null
  for (col in 0 until layout.cols) {
    var first = -1
    var last = -1
    for (row in 0 until layout.rows) {
      val s = layout.start(col, row) ?: continue
      val e = layout.end(col, row)!!
      if (!s.isAfter(sel.end) && !e.isBefore(sel.start) && !s.isAfter(today)) { if (first < 0) first = row; last = row }
    }
    if (first < 0) continue
    val r = android.graphics.Path().apply {
      addRect(col * pitch - o, top + first * pitch - o, col * pitch + c + o, top + last * pitch + c + o, android.graphics.Path.Direction.CW)
    }
    out = out?.apply { op(r, android.graphics.Path.Op.UNION) } ?: r
  }
  return out
}

private fun weekOf(d: LocalDate) = d.minusDays(d.dayOfWeek.value - 1L)

fun selectionLabel(sel: Selection): String {
  val a = sel.start
  val b = sel.end
  if (a == b) return Format.shortDate(a)
  val year = if (b.year != LocalDate.now().year) " ${b.year}" else ""
  return if (a.month == b.month && a.year == b.year) "${a.dayOfMonth}–${b.dayOfMonth} ${Format.shortMonth(b)}$year"
  else "${a.dayOfMonth} ${Format.shortMonth(a)} – ${b.dayOfMonth} ${Format.shortMonth(b)}$year"
}

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

private fun gapOf(view: AtlasView): Dp = when (view) { AtlasView.WEEKS -> 2.5.dp; AtlasView.DAYS -> 4.dp; AtlasView.YEAR -> 1.dp }

/**
 * The Atlas: the heat grid as navigation. Weeks are columns ending with [lastWeek], Monday on top.
 * Tap selects a week (20W), a day (8W) or a month (year). Hold 150ms, then drag, for a range: until
 * then the touch is left for the page and the tab pager, so flicks still scroll. One Canvas.
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

  BoxWithConstraints(modifier.fillMaxWidth()) {
    val cols = view.weeks
    val gap = gapOf(view)
    val cell = (maxWidth - gap * (cols - 1)) / cols
    val height = LABEL_ROW + cell * 7 + gap * 6 + 4.dp
    val firstWeek = lastWeek.minusWeeks((cols - 1).toLong())

    Canvas(
      Modifier
        .fillMaxWidth()
        .height(height)
        .semantics { contentDescription = "Spending grid. Selected ${selectionLabel(selection)}." }
        .pointerInput(view, firstWeek, cell) {
          val pitch = (cell + gap).toPx()
          val top = LABEL_ROW.toPx()
          fun dayAt(o: Offset): LocalDate {
            val c = floor((o.x + gap.toPx() / 2) / pitch).toInt().coerceIn(0, cols - 1)
            val r = floor((o.y - top + gap.toPx() / 2) / pitch).toInt().coerceIn(0, 6)
            return firstWeek.plusWeeks(c.toLong()).plusDays(r.toLong())
          }
          fun sameUnit(a: LocalDate, b: LocalDate) = when (view) {
            AtlasView.WEEKS -> a.minusDays(a.dayOfWeek.value - 1L) == b.minusDays(b.dayOfWeek.value - 1L)
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
      val radius = CornerRadius(min(2.dp.toPx(), c * 0.25f))
      fun x(col: Int) = col * pitch
      fun y(row: Int) = top + row * pitch

      // Month names over the column where each month starts.
      var lastMonth = -1
      var lastLabel = -9
      for (col in 0 until cols) {
        val m = firstWeek.plusWeeks(col.toLong()).monthValue
        if (m != lastMonth) {
          if (col - lastLabel >= 3) {
            val name = Format.shortMonth(firstWeek.plusWeeks(col.toLong()))
            drawText(measurer.measure(if (view == AtlasView.YEAR) name.take(1) else name, labelStyle), topLeft = Offset(x(col), 0f))
            lastLabel = col
          }
          lastMonth = m
        }
      }

      for (col in 0 until cols) for (row in 0 until 7) {
        val d = firstWeek.plusWeeks(col.toLong()).plusDays(row.toLong())
        val level = book.level(d)
        val at = Offset(x(col), y(row))
        when (level) {
          -2 -> Unit
          -1 -> drawCircle(Ember.selected, radius = (c * 0.1f).coerceIn(0.8.dp.toPx(), 1.6.dp.toPx()), center = at + Offset(c / 2, c / 2))
          else -> {
            drawRoundRect(Ember.heat[level], at, Size(c, c), radius)
            if (view == AtlasView.DAYS) {
              val t = measurer.measure(d.dayOfMonth.toString(), dayStyle.copy(color = if (level >= 3) Ember.bg else Ember.muted))
              drawText(t, topLeft = at + Offset(5.dp.toPx(), 4.dp.toPx()))
            }
          }
        }
        if (d == book.today) drawRect(Ember.muted, at + Offset(c * 0.2f, c + min(g * 0.5f, 1.2.dp.toPx()) - 0.75.dp.toPx()), Size(c * 0.6f, 1.5.dp.toPx()))
        if (cursor != null && d == cursor && view != AtlasView.YEAR) drawRoundRect(Ember.text, at, Size(c, c), radius)
      }

      val stroke = 1.5.dp.toPx()
      if (view == AtlasView.YEAR) {
        // Cells are too small to outline: a bracket over the selected weeks instead.
        val a = ((selection.start.toEpochDay() - firstWeek.toEpochDay()) / 7).toInt().coerceIn(0, cols - 1)
        val b = ((selection.end.toEpochDay() - firstWeek.toEpochDay()) / 7).toInt().coerceIn(0, cols - 1)
        val path = Path().apply {
          moveTo(x(a) + stroke / 2, top - 1.dp.toPx())
          lineTo(x(a) + stroke / 2, top - 4.5.dp.toPx())
          lineTo(x(b) + c - stroke / 2, top - 4.5.dp.toPx())
          lineTo(x(b) + c - stroke / 2, top - 1.dp.toPx())
        }
        drawPath(path, Ember.text, style = Stroke(stroke))
      } else {
        // Trace the outer edge of the selection on true day boundaries, through the middle of the gaps.
        val o = g / 2
        fun inSel(d: LocalDate) = d in selection && !d.isAfter(book.today)
        val path = Path()
        for (col in 0 until cols) for (row in 0 until 7) {
          val d = firstWeek.plusWeeks(col.toLong()).plusDays(row.toLong())
          if (!inSel(d)) continue
          val x0 = x(col) - o; val y0 = y(row) - o; val x1 = x(col) + c + o; val y1 = y(row) + c + o
          if (row == 0 || !inSel(d.minusDays(1))) { path.moveTo(x0, y0); path.lineTo(x1, y0) }
          if (row == 6 || !inSel(d.plusDays(1))) { path.moveTo(x0, y1); path.lineTo(x1, y1) }
          if (col == 0 || !inSel(d.minusDays(7))) { path.moveTo(x0, y0); path.lineTo(x0, y1) }
          if (col == cols - 1 || !inSel(d.plusDays(7))) { path.moveTo(x1, y0); path.lineTo(x1, y1) }
        }
        drawPath(path, Ember.text, style = Stroke(stroke, cap = StrokeCap.Square))
      }
    }
  }
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

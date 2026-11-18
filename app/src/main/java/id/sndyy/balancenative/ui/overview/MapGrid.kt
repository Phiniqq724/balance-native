package id.sndyy.balancenative.ui.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.data.Book
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.monday
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Satoshi
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.min

/** How many weeks the map shows at once. What's in view is what's counted. */
const val MAP_WEEKS = 13
private val MAP_GAP = 3.dp
private val MAP_LABELS = 16.dp

/** Monday of the first visible week when the map's right edge is [back] whole weeks before this week. */
fun mapStart(book: Book, back: Int): LocalDate = monday(book.today).minusWeeks((back + MAP_WEEKS - 1).toLong())

/** Furthest the map can travel back: until its first column reaches the start of the book. */
fun mapMaxBack(book: Book): Int =
  maxOf(0, ((monday(book.today).toEpochDay() - monday(book.first).toEpochDay()) / 7).toInt() - (MAP_WEEKS - 1))

/**
 * The map: [MAP_WEEKS] columns of days that slide through time. [back] is how many weeks the right
 * edge sits behind this week, fractional while a drag or fling is moving it. Horizontal drags belong
 * to the map (vertical ones still scroll the page); a tap picks the week under the finger.
 * One Canvas; it draws one extra column so the edges slide in and out smoothly.
 */
@Composable
fun MapGrid(
  book: Book,
  back: () -> Float,
  focus: LocalDate?,
  cursor: LocalDate?,
  onDrag: (weeks: Float) -> Unit,
  onRelease: (velocityWeeksPerSecond: Float) -> Unit,
  onTap: (week: LocalDate) -> Unit,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer()
  val labelStyle = remember { TextStyle(fontFamily = Satoshi, fontWeight = FontWeight.Medium, fontSize = 10.sp, color = Ember.muted) }
  val drag by rememberUpdatedState(onDrag)
  val release by rememberUpdatedState(onRelease)
  val tap by rememberUpdatedState(onTap)
  val now = monday(book.today)

  BoxWithConstraints(modifier.fillMaxWidth()) {
    val cell = (maxWidth - MAP_GAP * (MAP_WEEKS - 1)) / MAP_WEEKS
    val pitchDp = cell + MAP_GAP
    val height = MAP_LABELS + cell * 7 + MAP_GAP * 6
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val state = rememberDraggableState { px -> drag(px / (pitchDp.value * density)) }

    Canvas(
      Modifier
        .fillMaxWidth()
        .height(height)
        .semantics { contentDescription = "Spending map, $MAP_WEEKS weeks. Drag sideways to travel through time." }
        // Dragging right reveals the past, so a rightward drag adds weeks of "back".
        .draggable(state, Orientation.Horizontal, onDragStopped = { v -> release(v / (pitchDp.value * density)) })
        .pointerInput(book) {
          detectTapGestures { o ->
            val pitch = pitchDp.toPx()
            val backNow = back()
            val frac = backNow - floor(backNow)
            val k = floor(o.x / pitch - frac).toInt()
            val week = now.minusWeeks((floor(backNow).toInt() + MAP_WEEKS - 1 - k).toLong())
            if (!week.isAfter(book.today) && !week.plusDays(6).isBefore(book.first)) tap(week)
          }
        },
    ) {
      val c = cell.toPx()
      val g = MAP_GAP.toPx()
      val pitch = c + g
      val top = MAP_LABELS.toPx()
      // Read here, in the draw phase, so a moving map redraws without recomposing the screen.
      val b = back()
      val base = floor(b).toInt()
      val frac = b - base
      val radius = CornerRadius(3.dp.toPx())

      fun mondayOf(k: Int): LocalDate = now.minusWeeks((base + MAP_WEEKS - 1 - k).toLong())
      fun xOf(k: Int) = (k + frac) * pitch

      clipRect(0f, 0f, size.width, size.height) {
        // Month names where a month's first week starts.
        for (k in -1..MAP_WEEKS) {
          val m = mondayOf(k)
          if (m.dayOfMonth <= 7) drawText(measurer.measure(Format.shortMonth(m), labelStyle), topLeft = Offset(xOf(k), 0f))
        }
        for (k in -1..MAP_WEEKS) {
          val m = mondayOf(k)
          for (row in 0 until 7) {
            val d = m.plusDays(row.toLong())
            val at = Offset(xOf(k), top + row * pitch)
            when (val level = book.level(d)) {
              -2 -> Unit
              -1 -> drawCircle(Ember.selected, radius = 1.5.dp.toPx(), center = at + Offset(c / 2, c / 2))
              else -> {
                drawRoundRect(Ember.heat[level], at, Size(c, c), radius)
                if (d == book.today) drawCircle(if (level >= 3) Ember.bg else Ember.text, radius = 2.6.dp.toPx(), center = at + Offset(c / 2, c / 2))
                if (d == cursor) drawRoundRect(Ember.text, at, Size(c, c), radius)
              }
            }
          }
          // A focused week gets one soft rim around its column.
          if (focus != null && m == focus) {
            val o = g / 2 + 1.5.dp.toPx()
            drawRoundRect(
              Ember.text.copy(alpha = 0.92f),
              Offset(xOf(k) - o, top - o),
              Size(c + 2 * o, 7 * pitch - g + 2 * o),
              CornerRadius(5.dp.toPx()),
              style = Stroke(1.5.dp.toPx()),
            )
          }
        }
      }

      // Soft edges say "there's more this way"; the right edge fades only when the future isn't next.
      val fade = 22.dp.toPx()
      drawRect(Brush.horizontalGradient(listOf(Ember.bg, Color.Transparent), 0f, fade), Offset(0f, top - 4f), Size(fade, size.height))
      if (b > 0.01f) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Ember.bg), size.width - fade, size.width), Offset(size.width - fade, top - 4f), Size(fade, size.height))
    }
  }
}

/**
 * Every week in the book as one thin row of heat cells, with a rim around the stretch in view. Tap or
 * drag along it to jump far without flicking the map a hundred times.
 */
@Composable
fun MapRuler(book: Book, back: () -> Float, onJump: (back: Float, done: Boolean) -> Unit, modifier: Modifier = Modifier) {
  val jump by rememberUpdatedState(onJump)
  val now = monday(book.today)
  val first = monday(book.first)
  val weeks = ((now.toEpochDay() - first.toEpochDay()) / 7).toInt() + 1
  // Weekly totals, ranked into the same five steps as the days.
  val levels = remember(book) {
    val sums = LongArray(weeks) { w -> first.plusWeeks(w.toLong()).let { book.spent(it, it.plusDays(6)) } }
    val sorted = sums.filter { it > 0 }.sorted()
    fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
    IntArray(weeks) { w ->
      val v = sums[w]
      when {
        v <= 0 || sorted.isEmpty() -> 0
        v <= q(0.25) -> 1
        v <= q(0.5) -> 2
        v <= q(0.75) -> 3
        else -> 4
      }
    }
  }
  val maxBack = mapMaxBack(book)
  Canvas(
    modifier
      .fillMaxWidth()
      .height(22.dp)
      .semantics { contentDescription = "All weeks. Tap to jump." }
      .pointerInput(book) {
        fun backAt(x: Float): Float {
          val w = (x / size.width * weeks).coerceIn(0f, weeks - 1f)
          // Centre the window on the touched week.
          return (weeks - 1 - w - (MAP_WEEKS - 1) / 2f).coerceIn(0f, maxBack.toFloat())
        }
        detectTapGestures { o -> jump(backAt(o.x), true) }
      }
      .pointerInput(book, "drag") {
        fun backAt(x: Float): Float {
          val w = (x / size.width * weeks).coerceIn(0f, weeks - 1f)
          return (weeks - 1 - w - (MAP_WEEKS - 1) / 2f).coerceIn(0f, maxBack.toFloat())
        }
        var last = 0f
        detectHorizontalDragGestures(
          onDragStart = { o -> last = backAt(o.x); jump(last, false) },
          onDragEnd = { jump(last, true) },
        ) { change, _ -> change.consume(); last = backAt(change.position.x); jump(last, false) }
      },
  ) {
    val gap = 1.dp.toPx()
    val c = (size.width - gap * (weeks - 1)) / weeks
    val h = 10.dp.toPx()
    val y = (size.height - h) / 2
    for (w in 0 until weeks) drawRoundRect(Ember.heat[levels[w]], Offset(w * (c + gap), y), Size(c, h), CornerRadius(min(1.5.dp.toPx(), c / 2)))
    // The window: the MAP_WEEKS weeks in view.
    val right = weeks - 1 - back()
    val left = right - (MAP_WEEKS - 1)
    val pad = 3.dp.toPx()
    drawRoundRect(
      Ember.text.copy(alpha = 0.92f),
      Offset(left * (c + gap) - pad, y - pad),
      Size((MAP_WEEKS) * (c + gap) - gap + 2 * pad, h + 2 * pad),
      CornerRadius(4.dp.toPx()),
      style = Stroke(1.5.dp.toPx()),
    )
  }
}

fun selectionLabel(sel: id.sndyy.balancenative.data.Selection): String {
  val a = sel.start
  val b = sel.end
  if (a == b) return Format.shortDate(a)
  val year = if (b.year != LocalDate.now().year) " ${b.year}" else ""
  return if (a.month == b.month && a.year == b.year) "${a.dayOfMonth}–${b.dayOfMonth} ${Format.shortMonth(b)}$year"
  else "${a.dayOfMonth} ${Format.shortMonth(a)} – ${b.dayOfMonth} ${Format.shortMonth(b)}$year"
}

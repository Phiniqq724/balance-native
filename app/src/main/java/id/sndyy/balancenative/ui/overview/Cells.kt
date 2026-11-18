package id.sndyy.balancenative.ui.overview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.Share
import id.sndyy.balancenative.ui.kit.Haptic
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.theme.Ember
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Every cell figure sits on the Atlas pitch: 20 columns across the page, the same 2.5dp gap. */
const val PITCH_COLS = 20
val CELL_GAP = 2.5.dp

/** Category ranks are neutral: orange intensity belongs to heat. Two tones, then one hatched "rest". */
val RANK_1 = Color(0xFFCFC5BA)
val RANK_2 = Color(0xFF8F8378)

private fun DrawScope.cell(x: Float, y: Float, c: Float, color: Color) =
  drawRoundRect(color, Offset(x, y), Size(c, c), CornerRadius(min(2.dp.toPx(), c * 0.25f)))

private fun DrawScope.hatched(x: Float, y: Float, c: Float) {
  cell(x, y, c, Ember.line)
  clipRect(x, y, x + c, y + c) {
    val step = 4.dp.toPx()
    var t = -c
    while (t < c * 2) {
      drawLine(RANK_2, Offset(x + t, y + c), Offset(x + t + c, y), strokeWidth = 1.3.dp.toPx())
      t += step
    }
  }
}

@Composable
private fun pitchCell(maxWidth: Dp): Dp = (maxWidth - CELL_GAP * (PITCH_COLS - 1)) / PITCH_COLS

/**
 * In and out as one row of 20 cells, 20 = the larger flow. The smaller flow comes first in its own
 * hue, then one empty slot, then the difference in the larger flow's hue: sage is a surplus, orange
 * an overspend. Any non-zero part keeps at least one cell.
 */
@Composable
fun InOutRow(received: Long, spent: Long, modifier: Modifier = Modifier) {
  val large = maxOf(received, spent)
  val small = minOf(received, spent)
  val largeHue = if (received >= spent) Ember.income else Ember.accent
  val smallHue = if (received >= spent) Ember.accent else Ember.income
  val cells: List<Color?> = when {
    large == 0L -> List(PITCH_COLS) { Ember.heat[0] }
    small == 0L -> List(PITCH_COLS) { largeHue }
    large == small -> List(PITCH_COLS) { Ember.accent }
    else -> {
      val n = (small.toDouble() / large * (PITCH_COLS - 1)).roundToInt().coerceIn(1, PITCH_COLS - 2)
      List(n) { smallHue } + listOf(null) + List(PITCH_COLS - 1 - n) { largeHue }
    }
  }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val c = pitchCell(maxWidth)
    Canvas(Modifier.fillMaxWidth().height(c).semantics { contentDescription = "In ${Format.rupiah(received)}, out ${Format.rupiah(spent)}" }) {
      val cp = c.toPx()
      val p = cp + CELL_GAP.toPx()
      cells.forEachIndexed { i, color -> if (color != null) cell(i * p, 0f, cp, color) }
    }
  }
}

/** Largest-remainder split of 100 cells, each non-zero part keeping at least one. */
private fun hundred(amounts: List<Long>): List<Int> {
  val total = amounts.sum().toDouble()
  if (total <= 0) return amounts.map { 0 }
  val raw = amounts.map { it / total * 100 }
  val cells = raw.map { maxOf(1, floor(it).toInt()) }.toMutableList()
  var left = 100 - cells.sum()
  val order = raw.indices.sortedByDescending { raw[it] - floor(raw[it]) }
  var k = 0
  while (left > 0) { cells[order[k % order.size]]++; left--; k++ }
  while (left < 0) { val i = cells.indices.maxBy { cells[it] }; cells[i]--; left++ }
  return cells
}

/**
 * Where it went as a 20×5 field (100 cells = 100%) on the Atlas pitch, filled column by column so
 * each category is a block read left to right. The top two get their own tone; the rest is one
 * hatched block. The highlighted block turns ink.
 */
@Composable
fun ShareField(categories: List<Share>, highlight: String?, modifier: Modifier = Modifier) {
  val top = categories.take(2)
  val rest = categories.drop(2).sumOf { it.amount }
  data class Part(val key: String, val amount: Long, val tone: Color?)
  val parts = top.mapIndexed { i, s -> Part(s.key, s.amount, if (i == 0) RANK_1 else RANK_2) } +
    (if (rest > 0) listOf(Part(REST, rest, null)) else emptyList())
  val counts = hundred(parts.map { it.amount })
  val seq = parts.flatMapIndexed { i, p -> List(counts[i]) { p } }
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val c = pitchCell(maxWidth)
    Canvas(
      Modifier
        .fillMaxWidth()
        .height(c * 5 + CELL_GAP * 4)
        .clipToBounds()
        .semantics { contentDescription = parts.mapIndexed { i, p -> "${if (p.key == REST) "Rest" else top[i].name} ${counts[i]}%" }.joinToString() },
    ) {
      val cp = c.toPx()
      val p = cp + CELL_GAP.toPx()
      var k = 0
      for (col in 0 until PITCH_COLS) for (row in 0 until 5) {
        val part = seq.getOrNull(k++)
        val x = col * p
        val y = row * p
        when {
          part == null -> cell(x, y, cp, Ember.heat[0])
          part.key == highlight || (highlight == REST && part.key == REST) -> cell(x, y, cp, Ember.text)
          part.tone == null -> hatched(x, y, cp)
          else -> cell(x, y, cp, part.tone)
        }
      }
    }
  }
}

const val REST = "rest"

/** Ranked rows under the field. Each leads with its swatch; a tap rings it and inks its cells, and offers the jump to Activity. */
@Composable
fun RankedRows(categories: List<Share>, highlight: String?, onHighlight: (String?) -> Unit, onOpen: (Share) -> Unit) {
  val view = LocalView.current
  Column {
    categories.take(5).forEachIndexed { i, s ->
      val key = if (i < 2) s.key else REST
      val on = highlight == key
      Column(
        Modifier
          .fillMaxWidth()
          .clickable {
            Haptic.key(view)
            onHighlight(if (on) null else key)
          }
          .padding(vertical = 10.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          Swatch(i, on)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Label(s.name, weight = FontWeight.Medium, maxLines = 1)
            Label("${(s.share * 100).roundToInt()}%  ·  ${s.count}×", size = 12.sp, tone = Tone.MUTED)
          }
          Label(Format.digits(s.amount), weight = FontWeight.Medium)
        }
        AnimatedVisibility(on && i < 2, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
          Label(
            "View in Activity ›",
            Modifier.padding(start = 24.dp, top = 8.dp).clickable { onOpen(s) },
            size = 13.sp,
            weight = FontWeight.Bold,
          )
        }
      }
    }
  }
}

@Composable
private fun Swatch(rank: Int, on: Boolean) {
  Canvas(Modifier.size(12.dp)) {
    val c = size.minDimension
    when {
      on -> cell(0f, 0f, c, Ember.text)
      rank == 0 -> cell(0f, 0f, c, RANK_1)
      rank == 1 -> cell(0f, 0f, c, RANK_2)
      else -> hatched(0f, 0f, c)
    }
  }
}

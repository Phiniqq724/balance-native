package id.sndyy.balancenative.ui.kit

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Satoshi
import id.sndyy.balancenative.ui.theme.Space
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState

enum class Tone(val color: Color) { TEXT(Ember.text), MUTED(Ember.muted), INCOME(Ember.income), ACCENT(Ember.accent), DANGER(Ember.danger) }

/** The one text primitive: Satoshi at a size and weight, colored by meaning. */
@Composable
fun Label(
  text: String,
  modifier: Modifier = Modifier,
  size: TextUnit = 16.sp,
  weight: FontWeight = FontWeight.Normal,
  tone: Tone = Tone.TEXT,
  color: Color = tone.color,
  maxLines: Int = Int.MAX_VALUE,
) {
  Text(
    text = text,
    modifier = modifier,
    color = color,
    fontFamily = Satoshi,
    fontSize = size,
    fontWeight = weight,
    lineHeight = size * 1.3f,
    maxLines = maxLines,
    overflow = TextOverflow.Ellipsis,
  )
}

/** Hairline rule: an instrument reads as one panel, not a stack of tiles. */
@Composable
fun Rule(modifier: Modifier = Modifier) {
  Box(modifier.fillMaxWidth().height(1.dp).background(Ember.line))
}

/**
 * Big Satoshi Bold digits with a small muted "Rp": the digits are the focal point. The value counts
 * up to its new number, so a change reads as motion rather than a jump.
 */
@Composable
fun Money(value: Long, modifier: Modifier = Modifier, size: TextUnit = 46.sp, sign: String = "", color: Color = Ember.text, animate: Boolean = true) {
  val anim = remember { Animatable(value.toFloat()) }
  // While a finger drives the value (dragging a range) it is a direct readout: no count-up.
  LaunchedEffect(value, animate) {
    if (animate) anim.animateTo(value.toFloat(), tween(650, easing = FastOutSlowInEasing)) else anim.snapTo(value.toFloat())
  }
  // Floats lose rupiah above ~16 million, so the settled number is always the exact value.
  val shown = if (anim.isRunning) anim.value.toLong() else value
  Text(
    text = buildAnnotatedString {
      withStyle(SpanStyle(color = Ember.muted, fontSize = size * 0.42f, fontWeight = FontWeight.Medium)) { append("Rp ") }
      withStyle(SpanStyle(color = color)) { append(sign + Format.digits(shown)) }
    },
    modifier = modifier,
    fontFamily = Satoshi,
    fontWeight = FontWeight.Bold,
    fontSize = size,
    lineHeight = size * 1.1f,
    maxLines = 1,
  )
}

@Composable
fun SectionHead(title: String, detail: String? = null, modifier: Modifier = Modifier) {
  Column(modifier.fillMaxWidth().padding(top = 40.dp, bottom = 16.dp)) {
    Rule()
    Row(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
      Label(title, Modifier.weight(1f), weight = FontWeight.Bold)
      if (detail != null) Label(detail, size = 13.sp, tone = Tone.MUTED)
    }
  }
}

@Composable
fun Loading() {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(color = Ember.muted, strokeWidth = 2.dp)
  }
}

@Composable
fun Failed(message: String, onRetry: () -> Unit) {
  Column(Modifier.fillMaxSize().padding(Space.page), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
    Label(message, tone = Tone.MUTED)
    TextButton(onClick = onRetry) { Label("Try again", weight = FontWeight.Bold) }
  }
}

/** Presses sink a little and spring back: tactile without being loud. */
@Composable
fun rememberPressScale(source: MutableInteractionSource, pressed: Float = 0.96f): Float {
  val isPressed by source.collectIsPressedAsState()
  val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
  return scale
}

/** Warm creams from bright to dim, so category ranks read without spending the accent. */
fun shade(i: Int, n: Int): Color {
  val from = floatArrayOf(0xF3 / 255f, 0xEB / 255f, 0xE2 / 255f)
  val to = floatArrayOf(0x4A / 255f, 0x40 / 255f, 0x3A / 255f)
  val t = if (n <= 1) 0f else i.toFloat() / (n - 1)
  return Color(from[0] + (to[0] - from[0]) * t, from[1] + (to[1] - from[1]) * t, from[2] + (to[2] - from[2]) * t)
}

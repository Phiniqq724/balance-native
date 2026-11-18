package id.sndyy.balancenative.ui.kit

import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.sndyy.balancenative.ui.theme.Ember

/**
 * The haptic budget: detents only. Control haptics fire on release and are dropped within 80ms of a
 * detent so they never stack. Each falls back to a constant the phone's API level has.
 */
object Haptic {
  private var lastDetent = 0L

  private fun fire(view: View, constant: Int) { view.performHapticFeedback(constant) }

  /** A drag crossing a week. */
  fun week(view: View) { lastDetent = SystemClock.uptimeMillis(); fire(view, HapticFeedbackConstants.CLOCK_TICK) }

  /** A drag crossing a month: a firmer click. */
  fun month(view: View) { lastDetent = SystemClock.uptimeMillis(); fire(view, HapticFeedbackConstants.CONTEXT_CLICK) }

  /** Finger lifted on a finished range or step. */
  fun confirm(view: View) {
    lastDetent = SystemClock.uptimeMillis()
    fire(view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
  }

  /** A key or cell control released. */
  fun key(view: View) {
    if (SystemClock.uptimeMillis() - lastDetent < 80) return
    fire(view, if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_TAP else HapticFeedbackConstants.VIRTUAL_KEY)
  }
}

/** A cell's corner: the heat cell's own ratio (2dp on 13.5dp), so controls read as large cells. */
fun cellShape(height: Dp) = RoundedCornerShape(height * 0.15f)

private val squeeze = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)

/**
 * A row whose items share its width by weight, Counted-style: the pressed item swells and its
 * neighbours give way, then everything springs back. The chosen item can hold a little extra width.
 * Pure layout weight, so it costs one measure per frame of a handful of children.
 */
@Composable
fun SqueezeRow(
  count: Int,
  modifier: Modifier = Modifier,
  spacing: Dp = 2.5.dp,
  chosen: Int = -1,
  chosenExtra: Float = 0f,
  grow: Float = 0.32f,
  item: @Composable RowScope.(index: Int, source: MutableInteractionSource, weight: Modifier) -> Unit,
) {
  val sources = remember(count) { List(count) { MutableInteractionSource() } }
  val pressed = sources.map { it.collectIsPressedAsState().value }
  val down = pressed.indexOf(true)
  Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically) {
    for (i in 0 until count) {
      val base = 1f + if (i == chosen) chosenExtra else 0f
      val target = when {
        down < 0 -> base
        down == i -> base + grow
        else -> base - grow / (count - 1).coerceAtLeast(1)
      }
      val w by animateFloatAsState(target.coerceAtLeast(0.2f), squeeze, label = "squeeze")
      item(i, sources[i], Modifier.weight(w))
    }
  }
}

/**
 * Tessera's control: a flat large cell. Chosen fills with ink. A press sinks it a dp and darkens the
 * face; the key haptic fires on release.
 */
@Composable
fun CellButton(
  on: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  source: MutableInteractionSource = remember { MutableInteractionSource() },
  height: Dp = 40.dp,
  face: Color = Ember.surface,
  description: String? = null,
  content: @Composable (fg: Color) -> Unit,
) {
  val view = LocalView.current
  val isPressed by source.collectIsPressedAsState()
  val press by animateFloatAsState(if (isPressed) 1f else 0f, spring(stiffness = Spring.StiffnessHigh), label = "press")
  val bg by animateColorAsState(if (on) Ember.text else face, spring(stiffness = Spring.StiffnessMedium), label = "cellBg")
  val fg by animateColorAsState(if (on) Ember.bg else Ember.text, spring(stiffness = Spring.StiffnessMedium), label = "cellFg")
  val sink = with(LocalDensity.current) { 1.dp.toPx() }
  Box(
    modifier
      .height(height)
      .graphicsLayer { translationY = press * sink }
      .clip(cellShape(height))
      .background(lerp(bg, Color.Black, press * 0.06f))
      .semantics { selected = on }
      .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = description) {
        Haptic.key(view)
        onClick()
      },
    contentAlignment = Alignment.Center,
  ) { content(fg) }
}

package id.sndyy.balancenative.ui.kit

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import id.sndyy.balancenative.ui.theme.Ember
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TRACK_W = 52.dp
private val TRACK_H = 32.dp
private val THUMB = 24.dp
private val INSET = 4.dp

/**
 * A switch you can tap or slide. The thumb follows the finger, the track warms from charcoal to
 * cream as it travels, and it snaps (with a tick) to whichever side it's closer to on release.
 * Material's Compose switch only toggles on tap.
 */
@Composable
fun EmberSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  val view = LocalView.current
  val scope = rememberCoroutineScope()
  val travel = with(LocalDensity.current) { (TRACK_W - THUMB - INSET * 2).toPx() }
  // 0 = off, 1 = on. Follows the finger while dragging.
  val position = remember { Animatable(if (checked) 1f else 0f) }
  var dragging by remember { mutableStateOf(false) }
  val snap = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium)

  LaunchedEffect(checked) { if (!dragging) position.animateTo(if (checked) 1f else 0f, snap) }

  fun settle(target: Boolean) {
    scope.launch { position.animateTo(if (target) 1f else 0f, snap) }
    if (target != checked) {
      view.performHapticFeedback(if (target) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF)
      onCheckedChange(target)
    }
  }

  val p = position.value
  val track = lerp(Ember.raised, Ember.text, p)
  val thumb = lerp(Ember.muted, Ember.bg, p)
  Box(
    modifier
      .size(TRACK_W, TRACK_H)
      .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
      .clip(CircleShape)
      .background(track)
      .border(1.dp, lerp(Ember.line, Ember.text, p), CircleShape)
      .semantics {
        role = Role.Switch
        stateDescription = if (checked) "On" else "Off"
      }
      .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { settle(!checked) }
      .draggable(
        enabled = enabled,
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta ->
          scope.launch { position.snapTo((position.value + delta / travel).coerceIn(0f, 1f)) }
        },
        onDragStarted = { dragging = true },
        onDragStopped = { velocity ->
          dragging = false
          // A flick decides on its own; a slow drag settles on the nearer side.
          val target = when {
            velocity > 600f -> true
            velocity < -600f -> false
            else -> position.value >= 0.5f
          }
          settle(target)
        },
      )
      .padding(INSET),
    contentAlignment = Alignment.CenterStart,
  ) {
    // The thumb stretches a little mid-drag, like it's being pulled.
    val stretch = if (dragging) 1.15f else 1f
    Box(
      Modifier
        .offset { IntOffset((travel * p).roundToInt(), 0) }
        .graphicsLayer { scaleX = stretch }
        .size(THUMB)
        .clip(CircleShape)
        .background(thumb)
    )
  }
}

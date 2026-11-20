package id.sndyy.balancenative.ui.signin

import id.sndyy.balancenative.ui.profile.findActivity

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.rememberPressScale
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun SignInScreen() {
  val context = LocalContext.current
  val account = context.balance.account
  val scope = rememberCoroutineScope()
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  Box(Modifier.fillMaxSize()) {
    // A spending grid warming up behind the title: what the app will show, before there's data.
    EmberField(Modifier.fillMaxWidth().height(320.dp))

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.page)) {
      Spacer(Modifier.weight(1f))
      Label("Balance", size = 15.sp, weight = FontWeight.Bold, tone = Tone.MUTED)
      Spacer(Modifier.height(8.dp))
      Label("Money that\nlogs itself.", size = 36.sp, weight = FontWeight.Bold)
      Spacer(Modifier.height(32.dp))

      val source = remember { MutableInteractionSource() }
      val scale = rememberPressScale(source)
      Button(
        onClick = {
          if (busy) return@Button
          busy = true
          error = null
          scope.launch {
            error = context.findActivity()?.let { account.signInWithGoogle(it) } ?: "Couldn’t reach Google."
            busy = false
          }
        },
        enabled = !busy,
        interactionSource = source,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Ember.text, contentColor = Ember.bg),
        modifier = Modifier.fillMaxWidth().height(56.dp).graphicsLayer { scaleX = scale; scaleY = scale },
      ) {
        Label(if (busy) "Working…" else "Continue with Google", weight = FontWeight.Bold, color = Ember.bg)
      }
      AnimatedVisibility(error != null, enter = fadeIn(), exit = fadeOut()) {
        Label(error.orEmpty(), Modifier.padding(top = 12.dp), size = 14.sp, tone = Tone.DANGER)
      }
      Spacer(Modifier.height(8.dp))
      TextButton(
        onClick = {
          if (busy) return@TextButton
          busy = true
          scope.launch {
            error = account.chooseLocal()
            busy = false
          }
        },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Label("Use on this phone only", weight = FontWeight.Medium, tone = Tone.MUTED)
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

/** Heat cells fading up one by one, brightest toward the bottom-right, then holding still. */
@Composable
private fun EmberField(modifier: Modifier) {
  val levels = remember { List(7 * 14) { i -> val col = i / 7; (Random(i).nextFloat() * 4.2f * (col + 2) / 16f).toInt().coerceIn(0, 4) } }
  val progress = remember { Animatable(0f) }
  LaunchedEffect(Unit) { progress.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
  BoxWithConstraints(modifier) {
    Canvas(Modifier.fillMaxSize().padding(Space.page)) {
      val cols = 14
      val gap = 4.dp.toPx()
      val cell = (size.width - gap * (cols - 1)) / cols
      for (i in levels.indices) {
        val c = i / 7
        val r = i % 7
        val t = ((progress.value * (cols + 6)) - c).coerceIn(0f, 1f)
        val color = Ember.heat[levels[i]].copy(alpha = 0.55f * t)
        drawRoundRect(color, Offset(c * (cell + gap), r * (cell + gap)), Size(cell, cell), CornerRadius(4.dp.toPx()))
      }
    }
  }
}

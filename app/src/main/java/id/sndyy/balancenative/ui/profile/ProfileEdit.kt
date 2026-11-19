package id.sndyy.balancenative.ui.profile

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.AvatarStyle
import id.sndyy.balancenative.core.PhotoChange
import id.sndyy.balancenative.core.ProfileView
import id.sndyy.balancenative.ui.activity.Sheet
import id.sndyy.balancenative.ui.activity.primaryButtonColors
import id.sndyy.balancenative.ui.kit.Field
import id.sndyy.balancenative.ui.kit.Haptic
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.SqueezeRow
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.theme.Ember
import kotlinx.coroutines.launch

private enum class Choice(val label: String) { GOOGLE("Google"), OWN("Choose"), MOSAIC("Mosaic") }

/**
 * Edit sheet: big preview, a squeeze row picking where the picture comes from, the username, and the
 * email (read only). Nothing is written until Save.
 */
@Composable
fun ProfileSheet(view: ProfileView, email: String?, onClose: () -> Unit) {
  val context = LocalContext.current
  val profiles = context.balance.profiles
  val scope = rememberCoroutineScope()

  val choices = remember(view.google) { listOfNotNull(Choice.GOOGLE.takeIf { view.google != null }, Choice.OWN, Choice.MOSAIC) }
  val initial = when {
    view.photo != null -> Choice.OWN
    view.style == AvatarStyle.GOOGLE && view.google != null -> Choice.GOOGLE
    else -> Choice.MOSAIC
  }
  var choice by remember { mutableStateOf(initial) }
  var fresh by remember { mutableStateOf<Pair<ByteArray, ImageBitmap>?>(null) }
  var cropping by remember { mutableStateOf<Bitmap?>(null) }
  var username by remember { mutableStateOf(view.username.orEmpty()) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
    uri ?: return@rememberLauncherForActivityResult
    scope.launch { decodePicked(context, uri)?.let { cropping = it } ?: run { error = "Couldn’t open that photo." } }
  }
  fun choosePhoto() = pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  val hasOwn = fresh != null || view.photo != null

  val change: PhotoChange? = when {
    choice == Choice.OWN -> fresh?.let { PhotoChange.Upload(it.first) }
    choice == initial -> null
    else -> PhotoChange.Use(if (choice == Choice.GOOGLE) AvatarStyle.GOOGLE else AvatarStyle.MOSAIC)
  }
  val nameChanged = username.trim().lowercase() != view.username.orEmpty()
  val dirty = change != null || nameChanged

  Sheet(onClose) {
    Label("Profile", size = 22.sp, weight = FontWeight.Bold)

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      when (choice) {
        Choice.OWN -> Avatar(view.photo, view.seed, 104.dp, bitmap = fresh?.second)
        Choice.GOOGLE -> Avatar(view.google, view.seed, 104.dp)
        Choice.MOSAIC -> Avatar(null, view.seed, 104.dp)
      }
    }

    SqueezeRow(choices.size, Modifier.fillMaxWidth(), spacing = 6.dp, chosen = choices.indexOf(choice), chosenExtra = 0.35f) { i, source, weight ->
      val c = choices[i]
      SourceTile(
        label = if (c == Choice.OWN && hasOwn) "Yours" else c.label,
        on = c == choice,
        source = source,
        modifier = weight,
        onClick = {
          error = null
          // Own photo: the first tap selects it when there is one, any other tap picks a new one.
          if (c == Choice.OWN && (choice == Choice.OWN || !hasOwn)) choosePhoto() else choice = c
        },
      ) {
        when (c) {
          Choice.GOOGLE -> Avatar(view.google, view.seed, 30.dp)
          Choice.MOSAIC -> Avatar(null, view.seed, 30.dp)
          Choice.OWN -> if (hasOwn) Avatar(view.photo, view.seed, 30.dp, bitmap = fresh?.second) else {
            Box(Modifier.size(30.dp).clip(avatarShape(30.dp)).background(Ember.bg), contentAlignment = Alignment.Center) {
              Label("+", size = 18.sp, tone = Tone.MUTED)
            }
          }
        }
      }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Field(username, { username = it.lowercase().filter { ch -> !ch.isWhitespace() }; error = null }, "Username", Modifier.fillMaxWidth())
      Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Label(if (email != null) "Email" else "Account", size = 13.sp, tone = Tone.MUTED)
        Label(email ?: "On this phone only", size = 13.sp, maxLines = 1)
      }
    }

    error?.let { Label(it, size = 14.sp, tone = Tone.DANGER) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      Button(
        enabled = dirty && !busy,
        onClick = {
          busy = true
          error = null
          scope.launch {
            val message = profiles.save(username.takeIf { nameChanged }, change)
            busy = false
            if (message == null) onClose() else error = message
          }
        },
        colors = primaryButtonColors(),
      ) { Label(if (busy) "Saving…" else "Save", weight = FontWeight.Medium, color = Ember.bg) }
      TextButton(onClick = onClose) { Label("Cancel", weight = FontWeight.Medium, tone = Tone.MUTED) }
    }
  }

  cropping?.let { bitmap ->
    CropScreen(bitmap, onCancel = { cropping = null }) { jpeg, preview ->
      fresh = jpeg to preview
      choice = Choice.OWN
      cropping = null
    }
  }
}

/** One picture source: a soft tile, filled when chosen, sinking a dp under the finger. */
@Composable
private fun SourceTile(label: String, on: Boolean, source: MutableInteractionSource, modifier: Modifier, onClick: () -> Unit, thumb: @Composable () -> Unit) {
  val view = LocalView.current
  val pressed by source.collectIsPressedAsState()
  val press by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = Spring.StiffnessHigh), label = "tilePress")
  val bg by animateColorAsState(if (on) Ember.selected else Ember.raised, label = "tileBg")
  val fg by animateColorAsState(if (on) Ember.text else Ember.muted, label = "tileFg")
  val sink = with(LocalDensity.current) { 1.dp.toPx() }
  Column(
    modifier
      .graphicsLayer { translationY = press * sink }
      .clip(RoundedCornerShape(14.dp))
      .background(lerp(bg, Color.Black, press * 0.06f))
      .semantics { selected = on }
      .clickable(interactionSource = source, indication = null, role = Role.Button) { Haptic.key(view); onClick() }
      .padding(vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    thumb()
    Label(label, size = 13.sp, weight = FontWeight.Medium, color = fg, maxLines = 1)
  }
}

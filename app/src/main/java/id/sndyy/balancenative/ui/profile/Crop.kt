package id.sndyy.balancenative.ui.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.sndyy.balancenative.ui.activity.primaryButtonColors
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.theme.Ember
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saved photos are this many pixels square: sharp in the 104dp preview, far under the bucket's 1 MB cap. */
private const val OUTPUT_PX = 512
private const val MAX_DECODE_PX = 2048
private val Backdrop = Color(0xFF0E0C0B)

/** Decodes a picked image upright and no larger than [MAX_DECODE_PX] on its long side. */
suspend fun decodePicked(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
  runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
      ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        var sample = 1
        while (max(info.size.width, info.size.height) / sample > MAX_DECODE_PX) sample *= 2
        if (sample > 1) decoder.setTargetSampleSize(sample)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      }
    } else {
      val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
      var sample = 1
      while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_PX) sample *= 2
      val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?: return@runCatching null
      val degrees = context.contentResolver.openInputStream(uri)?.use {
        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
          ExifInterface.ORIENTATION_ROTATE_90 -> 90f
          ExifInterface.ORIENTATION_ROTATE_180 -> 180f
          ExifInterface.ORIENTATION_ROTATE_270 -> 270f
          else -> 0f
        }
      } ?: 0f
      if (degrees == 0f) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }
  }.getOrNull()
}

/**
 * Full-screen cropper. The window is the avatar's own rounded square, so what's inside is exactly
 * what the tile shows. Pinch to zoom, drag to move; the photo always covers the window.
 */
@Composable
fun CropScreen(source: Bitmap, onCancel: () -> Unit, onDone: (jpeg: ByteArray, preview: ImageBitmap) -> Unit) {
  val image = remember(source) { source.asImageBitmap() }
  val scope = rememberCoroutineScope()
  var zoom by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  var busy by remember { mutableStateOf(false) }
  // The window in the image box's pixels, and the scale that makes the photo just cover it.
  var window by remember { mutableFloatStateOf(0f) }
  var center by remember { mutableStateOf(Offset.Zero) }
  val inset = with(LocalDensity.current) { 28.dp.toPx() }

  fun base() = window / min(source.width, source.height).toFloat()

  fun clamp(o: Offset, z: Float): Offset {
    val s = base() * z
    val maxX = max(0f, (source.width * s - window) / 2)
    val maxY = max(0f, (source.height * s - window) / 2)
    return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
  }

  Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
    Column(Modifier.fillMaxSize().background(Backdrop)) {
      Box(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Label("Cancel", Modifier.align(Alignment.CenterStart).clickable(onClick = onCancel).padding(12.dp), weight = FontWeight.Medium, tone = Tone.MUTED)
        Label("Fit your photo", Modifier.align(Alignment.Center), size = 16.sp, weight = FontWeight.Bold)
      }

      BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        window = min(w, h) - inset * 2
        center = Offset(w / 2, h / 2)
        Canvas(
          Modifier.fillMaxSize().pointerInput(source) {
            detectTransformGestures { centroid, pan, change, _ ->
              val next = (zoom * change).coerceIn(1f, 5f)
              val ratio = next / zoom
              // Keep the point under the fingers still while zooming, then follow the pan.
              val fromCenter = centroid - center
              val moved = fromCenter - (fromCenter - offset) * ratio + pan
              zoom = next
              offset = clamp(moved, next)
            }
          },
        ) {
          val s = base() * zoom
          val dw = source.width * s
          val dh = source.height * s
          drawImage(
            image,
            dstOffset = IntOffset((center.x + offset.x - dw / 2).toInt(), (center.y + offset.y - dh / 2).toInt()),
            dstSize = IntSize(dw.toInt(), dh.toInt()),
          )
          val hole = Path().apply {
            addRoundRect(RoundRect(center.x - window / 2, center.y - window / 2, center.x + window / 2, center.y + window / 2, CornerRadius(window / 3.4f)))
          }
          clipPath(hole, ClipOp.Difference) { drawRect(Backdrop.copy(alpha = 0.72f)) }
        }
        Label(
          "Pinch to zoom · drag to move",
          Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
          size = 13.sp,
          tone = Tone.MUTED,
        )
      }

      Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Label("Saved at $OUTPUT_PX px", size = 13.sp, tone = Tone.MUTED)
        Button(
          enabled = !busy,
          onClick = {
            busy = true
            scope.launch {
              val (jpeg, preview) = withContext(Dispatchers.Default) { crop(source, base() * zoom, offset, window) }
              onDone(jpeg, preview)
            }
          },
          colors = primaryButtonColors(),
        ) { Label(if (busy) "Saving…" else "Use photo", weight = FontWeight.Medium, color = Ember.bg) }
      }
    }
  }
}

/** The window's square in the photo's own pixels, scaled to [OUTPUT_PX] and encoded as JPEG. */
private fun crop(source: Bitmap, scale: Float, offset: Offset, window: Float): Pair<ByteArray, ImageBitmap> {
  val side = (window / scale).toInt().coerceIn(1, min(source.width, source.height))
  val left = (source.width / 2f - offset.x / scale - side / 2f).toInt().coerceIn(0, source.width - side)
  val top = (source.height / 2f - offset.y / scale - side / 2f).toInt().coerceIn(0, source.height - side)
  val square = Bitmap.createBitmap(source, left, top, side, side)
  val out = Bitmap.createScaledBitmap(square, OUTPUT_PX, OUTPUT_PX, true)
  val bytes = ByteArrayOutputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it); it.toByteArray() }
  return bytes to out.asImageBitmap()
}

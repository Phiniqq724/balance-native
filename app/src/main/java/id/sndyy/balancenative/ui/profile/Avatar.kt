package id.sndyy.balancenative.ui.profile

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import id.sndyy.balancenative.ui.overview.RANK_1
import id.sndyy.balancenative.ui.overview.RANK_2
import id.sndyy.balancenative.ui.theme.Ember
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The avatar tile's corner: a third of its side, so 60dp reads like the 104dp preview. */
fun avatarShape(size: Dp) = RoundedCornerShape(size / 3)

/** Decoded photos, so the header and the sheet never decode the same file twice. */
private object Photos {
  private val cache = LruCache<String, ImageBitmap>(8)

  private fun key(file: File) = "${file.path}@${file.lastModified()}"

  fun peek(file: File): ImageBitmap? = cache.get(key(file))

  suspend fun load(file: File): ImageBitmap? = peek(file) ?: withContext(Dispatchers.IO) {
    BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { cache.put(key(file), it) }
  }
}

/**
 * The profile tile: the photo when there is one, else the Mosaic. [bitmap] shows a picture that isn't
 * saved yet (the sheet's preview).
 */
@Composable
fun Avatar(file: File?, seed: String, size: Dp, modifier: Modifier = Modifier, bitmap: ImageBitmap? = null) {
  val loaded by produceState(file?.let(Photos::peek), file) { value = file?.let { Photos.load(it) } }
  val image = bitmap ?: loaded
  Box(modifier.size(size).clip(avatarShape(size)).background(Ember.raised)) {
    if (image != null) {
      Image(image, contentDescription = "Profile photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    } else if (file == null) {
      Mosaic(seed, Modifier.fillMaxSize())
    }
  }
}

private val EMPTY = Color(0xFF332C27)

/**
 * A 5×5 field of cells, mirrored left to right, picked from [seed]: the same account always gets the
 * same pattern. Drawn in the grid's own inks, so a profile without a photo still looks like Balance.
 */
@Composable
fun Mosaic(seed: String, modifier: Modifier = Modifier) {
  val cells = remember(seed) { mosaicCells(seed) }
  Canvas(modifier) {
    val pad = size.minDimension * 0.1f
    val gap = size.minDimension * 0.035f
    val c = (size.minDimension - pad * 2 - gap * 4) / 5
    val radius = CornerRadius(c * 0.15f)
    for (i in 0 until 25) {
      val x = pad + (i % 5) * (c + gap)
      val y = pad + (i / 5) * (c + gap)
      val color = when (cells[i]) { 2 -> RANK_1; 1 -> RANK_2; else -> EMPTY }
      drawRoundRect(color, Offset(x, y), Size(c, c), radius)
    }
  }
}

/** 0 empty, 1 second ink, 2 first ink. Retries a few seeds so the field is never nearly blank. */
internal fun mosaicCells(seed: String): IntArray {
  var attempt = 0
  while (true) {
    val random = java.util.Random((seed.hashCode().toLong() shl 8) + attempt)
    val half = IntArray(15) { val v = random.nextInt(5); if (v < 2) 2 else if (v < 3) 1 else 0 }
    val cells = IntArray(25) { i -> val col = i % 5; half[(i / 5) * 3 + if (col > 2) 4 - col else col] }
    if (cells.count { it != 0 } >= 9 || attempt > 8) return cells
    attempt++
  }
}

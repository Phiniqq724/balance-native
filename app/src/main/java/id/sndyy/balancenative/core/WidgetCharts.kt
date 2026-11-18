package id.sndyy.balancenative.core

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Bitmaps for the DailySpend (4x2) and DayByDay (2x2) widgets. expo-widgets can't draw lines or
 * custom bars, so each chart is one PNG sized to its widget, like the spending grid. Props are
 * drawn by the Glance widgets at their exact size. Colors follow the Ember theme.
 */
internal object WidgetCharts {
  private const val TEXT = 0xFFF3EBE2.toInt()
  private const val MUTED = 0xFFAC9F92.toInt()
  private const val LINE = 0xFF3A322C.toInt()
  private const val ACCENT = 0xFFFF7444.toInt()

  private fun newCanvas(context: Context, widthDp: Float, heightDp: Float): Triple<Bitmap, Canvas, Float> {
    val density = context.resources.displayMetrics.density
    val bitmap = Bitmap.createBitmap(
      (widthDp * density).toInt().coerceAtLeast(1),
      (heightDp * density).toInt().coerceAtLeast(1),
      Bitmap.Config.ARGB_8888
    )
    return Triple(bitmap, Canvas(bitmap), density)
  }

  private fun font(context: Context): Typeface =
    runCatching { Typeface.createFromAsset(context.assets, "fonts/Satoshi-Medium.otf") }.getOrDefault(Typeface.DEFAULT)

  /** Same look as the Overview line: light line over a soft fill, two rules, accent dot on today. */
  /** The 30-day line, drawn at exactly [widthDp] x [heightDp]. */
  fun drawLine(context: Context, values: DoubleArray, days: Int, widthDp: Float, heightDp: Float): Bitmap {
    val (bitmap, canvas, density) = newCanvas(context, widthDp, heightDp)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val top = 2 * density
    val bottom = h - 2 * density
    val peak = (values.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
    val scale = niceCeil(peak)
    fun x(i: Int) = (i + 0.5f) * (w / days)
    fun y(v: Double) = bottom - (v / scale).toFloat() * (bottom - top)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = LINE
    paint.strokeWidth = density
    canvas.drawLine(0f, y(0.0), w, y(0.0), paint)
    canvas.drawLine(0f, y(scale), w, y(scale), paint)

    if (values.any { it > 0 }) {
      val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MUTED
        textSize = 10 * density
        typeface = font(context)
      }
      canvas.drawText(WidgetBridge.compact(scale.toLong()), 0f, y(scale) - 3 * density + label.textSize, label)
    }

    val points = values.mapIndexed { i, v -> x(i) to y(v) }
    if (points.size > 1) {
      val line = smoothPath(points)
      val area = Path(line).apply {
        lineTo(points.last().first, bottom)
        lineTo(points.first().first, bottom)
        close()
      }
      paint.style = Paint.Style.FILL
      paint.shader = LinearGradient(0f, top, 0f, bottom, (TEXT and 0x00FFFFFF) or 0x24000000, 0x00000000, Shader.TileMode.CLAMP)
      canvas.drawPath(area, paint)
      paint.shader = null
      paint.style = Paint.Style.STROKE
      paint.strokeWidth = 2 * density
      paint.strokeCap = Paint.Cap.ROUND
      paint.strokeJoin = Paint.Join.ROUND
      paint.color = TEXT
      canvas.drawPath(line, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = ACCENT
    canvas.drawCircle(points.last().first, points.last().second, 3.5f * density, paint)
    return bitmap
  }

  /** Seven bars, today's in accent and the rest muted, weekday initials underneath. */
  /** Seven day bars with initials, drawn at exactly [widthDp] x [heightDp]. */
  fun drawBars(context: Context, values: DoubleArray, initials: Array<String>, widthDp: Float, heightDp: Float): Bitmap {
    val (bitmap, canvas, density) = newCanvas(context, widthDp, heightDp)
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    val labelSize = 10 * density
    val base = h - labelSize - 6 * density
    val peak = (values.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
    val slot = w / values.size
    val barWidth = min(slot * 0.62f, 14 * density)
    val radius = min(barWidth / 2, 3 * density)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      textSize = labelSize
      typeface = font(context)
      textAlign = Paint.Align.CENTER
    }
    for (i in values.indices) {
      val cx = (i + 0.5f) * slot
      val isToday = i == values.lastIndex
      val v = values[i]
      paint.color = if (v <= 0) LINE else if (isToday) ACCENT else (MUTED and 0x00FFFFFF) or 0x73000000
      val barHeight = if (v <= 0) 2 * density else max((v / peak).toFloat() * (base - 2 * density), 2 * density)
      canvas.drawRoundRect(RectF(cx - barWidth / 2, base - barHeight, cx + barWidth / 2, base), radius, radius, paint)
      label.color = if (isToday) TEXT else MUTED
      canvas.drawText(initials[i], cx, h - 1 * density, label)
    }
    return bitmap
  }

  /** Rounds a chart maximum up to 1, 2 or 5 times a power of ten, like the Overview scale label. */
  private fun niceCeil(value: Double): Double {
    var magnitude = 1.0
    while (magnitude * 10 <= value) magnitude *= 10
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= value }
  }

  /** Monotone cubic curve (Fritsch-Carlson): never dips below zero between busy days. */
  private fun smoothPath(points: List<Pair<Float, Float>>): Path = Path().apply {
    moveTo(points[0].first, points[0].second)
    val n = points.size
    val slopes = FloatArray(n - 1) { i -> (points[i + 1].second - points[i].second) / (points[i + 1].first - points[i].first) }
    val tangents = FloatArray(n) { i ->
      when (i) {
        0 -> slopes[0]
        n - 1 -> slopes[n - 2]
        else -> if (slopes[i - 1] * slopes[i] <= 0f) 0f else (slopes[i - 1] + slopes[i]) / 2f
      }
    }
    for (i in 0 until n - 1) {
      if (slopes[i] == 0f) {
        tangents[i] = 0f
        tangents[i + 1] = 0f
        continue
      }
      val a = tangents[i] / slopes[i]
      val b = tangents[i + 1] / slopes[i]
      val hyp = a * a + b * b
      if (hyp > 9f) {
        val t = 3f / sqrt(hyp)
        tangents[i] = t * a * slopes[i]
        tangents[i + 1] = t * b * slopes[i]
      }
    }
    for (i in 0 until n - 1) {
      val (x0, y0) = points[i]
      val (x1, y1) = points[i + 1]
      val dx = (x1 - x0) / 3f
      cubicTo(x0 + dx, y0 + tangents[i] * dx, x1 - dx, y1 - tangents[i + 1] * dx, x1, y1)
    }
  }



}

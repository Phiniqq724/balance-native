package id.sndyy.balancenative.core

import android.content.Context
import id.sndyy.balancenative.widget.Widgets
import java.util.Locale

/** Widget refresh entry point for the core (ledger writes, midnight), plus number helpers the charts share. */
internal object WidgetBridge {
  /** Redraws every placed widget from the current ledger. Safe from any thread; returns at once. */
  fun refresh(context: Context) = Widgets.refresh(context)

  /** 1284500 -> "1.28M", 45000 -> "45K", 900 -> "900". */
  fun compact(value: Long): String {
    fun trim(n: Double): String {
      val s = String.format(Locale.US, if (n >= 100) "%.0f" else if (n >= 10) "%.1f" else "%.2f", n)
      return if ('.' in s) s.trimEnd('0').trimEnd('.') else s
    }
    return when {
      value >= 1_000_000_000 -> trim(value / 1_000_000_000.0) + "B"
      value >= 1_000_000 -> trim(value / 1_000_000.0) + "M"
      value >= 1_000 -> trim(value / 1_000.0) + "K"
      else -> value.toString()
    }
  }

  /** GitHub-style: 0 for no spend, then 1-4 by quartile of the days that had spending. */
  fun levelsFor(values: List<Long>): List<Int> {
    val nonZero = values.filter { it > 0 }.sorted()
    if (nonZero.isEmpty()) return values.map { 0 }
    fun quantile(q: Double) = nonZero[((nonZero.size - 1) * q).toInt()]
    val q1 = quantile(0.25)
    val q2 = quantile(0.5)
    val q3 = quantile(0.75)
    return values.map { v ->
      when {
        v <= 0 -> 0
        v <= q1 -> 1
        v <= q2 -> 2
        v <= q3 -> 3
        else -> 4
      }
    }
  }
}

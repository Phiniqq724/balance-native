package id.sndyy.balancenative.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import id.sndyy.balancenative.MainActivity
import id.sndyy.balancenative.core.BalanceEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object W {
  val bg = Color(0xFF1A1614)
  val raised = Color(0xFF2B2521)
  val text = Color(0xFFF3EBE2)
  val muted = Color(0xFFAC9F92)
  val accent = Color(0xFFFF7444)
  val income = Color(0xFF9BCB9F)
  const val PAD = 14
}

/** Every Balance widget. Redrawn on each ledger write and at midnight, with the app open or not. */
object Widgets {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val all: List<GlanceAppWidget> get() = listOf(SpendGridWidget(), MonthPulseWidget(), DailySpendWidget(), DayByDayWidget(), ToCheckWidget())

  fun refresh(context: Context) {
    val app = context.applicationContext
    // Running sessions pick changes up from the ledger stream; this starts the ones that aren't running.
    scope.launch { all.forEach { runCatching { it.updateAll(app) } } }
  }
}

/** The ledger as the widget sees it: read once on start, then again on every ledger write. */
@Composable
internal fun rememberSnapshot(initial: Snapshot): Snapshot {
  val context = LocalContext.current
  val snapshot by produceState(initial) {
    BalanceEvents.changes.collect { value = withContext(Dispatchers.IO) { Snapshot.load(context) } }
  }
  return snapshot
}

internal fun openApp(context: Context, link: String = "balancenative://"): Action =
  actionStartActivity(
    Intent(Intent.ACTION_VIEW, Uri.parse(link), context, MainActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
  )

/** Ember panel with the launcher's own corner radius (Android 12+), tap opens the app. */
@Composable
internal fun Panel(onClick: Action?, padding: Int = W.PAD, content: @Composable () -> Unit) {
  Box(
    GlanceModifier
      .fillMaxSize()
      .appWidgetBackground()
      .cornerRadius(android.R.dimen.system_app_widget_background_radius)
      .background(W.bg)
      .let { if (onClick != null) it.clickable(onClick) else it }
      .padding(padding.dp),
  ) { content() }
}

@Composable
internal fun T(text: String, size: TextUnit = 13.sp, color: Color = W.muted, bold: Boolean = false, medium: Boolean = false, maxLines: Int = 1, modifier: GlanceModifier = GlanceModifier) {
  Text(
    text,
    modifier,
    style = TextStyle(
      color = ColorProvider(color),
      fontSize = size,
      fontWeight = if (bold) FontWeight.Bold else if (medium) FontWeight.Medium else FontWeight.Normal,
    ),
    maxLines = maxLines,
  )
}

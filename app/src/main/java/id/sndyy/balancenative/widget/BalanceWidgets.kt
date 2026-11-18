package id.sndyy.balancenative.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionSendBroadcast
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.Button
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.layout.ContentScale
import androidx.glance.unit.ColorProvider
import id.sndyy.balancenative.core.ReviewActionReceiver
import id.sndyy.balancenative.core.ReviewNotifier
import id.sndyy.balancenative.core.WidgetBridge
import id.sndyy.balancenative.data.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

private suspend fun load(context: Context) = withContext(Dispatchers.IO) { Snapshot.load(context) }

@Composable
private fun Empty(text: String) = T(text, maxLines = 3)

// ---- Spending grid ---------------------------------------------------------------------------

/**
 * GitHub-style grid. Glance renders it once per exact size the launcher reports, so a resize shows
 * the right grid immediately: same comfortable cells, more weeks as it widens, bigger cells as it
 * grows taller. No broadcast hack, no stretched bitmap, and each placed copy sizes itself.
 */
class SpendGridWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val initial = load(context)
    provideContent {
      val s = rememberSnapshot(initial)
      val size = LocalSize.current
      val ctx = LocalContext.current
      Panel(openApp(ctx)) {
        if (!s.ready) return@Panel Empty("Open Balance to start.")
        val compact = size.width < 250.dp
        val gridW = size.width.value - W.PAD * 2
        val gridH = size.height.value - W.PAD * 2 - 28f
        val (bitmap, weeks) = remember(s, gridW, gridH) { WidgetDraw.grid(ctx, s, gridW, gridH) }
        val gridStart = s.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())
        val total = s.spentBetween(gridStart, s.today)
        val today = s.spent[s.today] ?: 0L
        Column(GlanceModifier.fillMaxSize()) {
          Row(verticalAlignment = Alignment.Bottom) {
            T(WidgetBridge.compact(if (compact) today else total), size = 15.sp, color = W.text, bold = true)
            T(if (compact) " today" else " in $weeks weeks  ·  ${WidgetBridge.compact(today)} today", size = 13.sp)
          }
          Spacer(GlanceModifier.height(8.dp))
          Image(ImageProvider(bitmap), "Daily spending, last $weeks weeks", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
      }
    }
  }
}

class SpendGridReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = SpendGridWidget() }

// ---- This week / month / year ----------------------------------------------------------------

internal val PERIOD_KEY = stringPreferencesKey("period")

/** Tap the period pill to cycle Week / Month / Year in place; tap anywhere else opens the app. */
class MonthPulseWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val initial = load(context)
    provideContent {
      val s = rememberSnapshot(initial)
      val period = currentState<Preferences>()[PERIOD_KEY] ?: "month"
      val ctx = LocalContext.current
      val tall = LocalSize.current.height >= 150.dp
      Panel(openApp(ctx)) {
        if (!s.ready) return@Panel Empty("Open Balance to start.")
        val (from, label) = when (period) {
          "week" -> s.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to "This week"
          "year" -> s.today.withDayOfYear(1) to s.today.year.toString()
          else -> s.today.withDayOfMonth(1) to Format.monthName(s.today)
        }
        Column(GlanceModifier.fillMaxSize()) {
          // The pill is its own tap target, so cycling never opens the app by accident.
          Box(
            GlanceModifier.cornerRadius(10.dp).background(W.raised).clickable(actionRunCallback<CyclePeriod>()).padding(horizontal = 10.dp, vertical = 4.dp),
          ) { T("$label  ↻", size = 12.sp, color = W.text, medium = true) }
          Spacer(GlanceModifier.defaultWeight())
          T(WidgetBridge.compact(s.spentBetween(from, s.today)), size = if (tall) 34.sp else 28.sp, color = W.text, bold = true)
          T("spent", size = 12.sp)
          Spacer(GlanceModifier.height(8.dp))
          T("+${WidgetBridge.compact(s.receivedBetween(from, s.today))} in", size = 13.sp, color = W.income, medium = true)
          T("${WidgetBridge.compact(s.spent[s.today] ?: 0L)} today", size = 13.sp)
        }
      }
    }
  }
}

class CyclePeriod : ActionCallback {
  override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
    updateAppWidgetState(context, glanceId) { prefs ->
      prefs[PERIOD_KEY] = when (prefs[PERIOD_KEY] ?: "month") { "week" -> "month"; "month" -> "year"; else -> "week" }
    }
    MonthPulseWidget().update(context, glanceId)
  }
}

class MonthPulseReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = MonthPulseWidget() }

// ---- Charts ------------------------------------------------------------------------------------

class DailySpendWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val initial = load(context)
    provideContent {
      val s = rememberSnapshot(initial)
      val size = LocalSize.current
      val ctx = LocalContext.current
      Panel(openApp(ctx), padding = 12) {
        if (!s.ready) return@Panel Empty("Open Balance to start.")
        val w = size.width.value - 24f
        val h = size.height.value - 24f - 24f
        val (bitmap, total) = remember(s, w, h) { WidgetDraw.daily(ctx, s, w, h) }
        Column(GlanceModifier.fillMaxSize()) {
          Row(GlanceModifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.Top) {
            T(WidgetBridge.compact(total), size = 15.sp, color = W.text, bold = true)
            T(" in 30 days", size = 13.sp)
          }
          Image(ImageProvider(bitmap), "Spending per day, last 30 days", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
      }
    }
  }
}

class DailySpendReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DailySpendWidget() }

class DayByDayWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val initial = load(context)
    provideContent {
      val s = rememberSnapshot(initial)
      val size = LocalSize.current
      val ctx = LocalContext.current
      Panel(openApp(ctx), padding = 12) {
        if (!s.ready) return@Panel Empty("Open Balance to start.")
        val w = size.width.value - 24f
        val h = size.height.value - 24f - 24f
        val (bitmap, total) = remember(s, w, h) { WidgetDraw.week(ctx, s, w, h) }
        Column(GlanceModifier.fillMaxSize()) {
          Row(GlanceModifier.fillMaxWidth().height(24.dp)) {
            T(WidgetBridge.compact(total), size = 15.sp, color = W.text, bold = true)
            T(" this week", size = 13.sp)
          }
          Image(ImageProvider(bitmap), "Spending, last 7 days", GlanceModifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
      }
    }
  }
}

class DayByDayReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = DayByDayWidget() }

// ---- To check -----------------------------------------------------------------------------------

/**
 * The newest notification waiting for an answer, answerable right here: Yes takes the AI's reading,
 * Not money drops it, Fix opens the app on it. Same receiver as the review notification buttons, so
 * the answer is recorded, taught to the model and the notification cleared exactly the same way.
 */
class ToCheckWidget : GlanceAppWidget() {
  override val sizeMode = SizeMode.Exact

  override suspend fun provideGlance(context: Context, id: GlanceId) {
    val initial = load(context)
    provideContent {
      val s = rememberSnapshot(initial)
      val ctx = LocalContext.current
      val roomy = LocalSize.current.height >= 180.dp
      Panel(openApp(ctx, "balancenative://activity?f=review")) {
        if (!s.ready) return@Panel Empty("Open Balance to start.")
        val item = s.toCheck.firstOrNull()
        Column(GlanceModifier.fillMaxSize()) {
          Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            T("To check", size = 13.sp, color = W.text, bold = true)
            Spacer(GlanceModifier.defaultWeight())
            if (s.toCheckCount > 0) {
              Box(GlanceModifier.cornerRadius(9.dp).background(W.accent).padding(horizontal = 7.dp, vertical = 1.dp)) {
                T(if (s.toCheckCount > 99) "99+" else "${s.toCheckCount}", size = 11.sp, color = W.bg, bold = true)
              }
            }
          }
          Spacer(GlanceModifier.height(8.dp))
          if (item == null) {
            Spacer(GlanceModifier.defaultWeight())
            T("All clear.", size = 20.sp, color = W.text, bold = true)
            T("Nothing waiting for an answer.", size = 12.sp, maxLines = 2)
            return@Column
          }
          T("${Format.dayLabel(Format.date(item.postedAt))}, ${Format.time(item.postedAt)}", size = 11.sp)
          T(item.body, size = 13.sp, color = W.text, maxLines = if (roomy) 4 else 2)
          Spacer(GlanceModifier.height(6.dp))
          when {
            item.state == "pending" -> T("Reading…", size = 13.sp)
            item.suggestion && item.kind != "ad_or_other" -> T(question(item), size = 13.sp, color = W.text, medium = true, maxLines = 2)
            else -> T("Couldn't read this one.", size = 13.sp)
          }
          Spacer(GlanceModifier.defaultWeight())
          if (item.state != "pending") {
            Row(GlanceModifier.fillMaxWidth()) {
              if (item.suggestion && item.kind != "ad_or_other") {
                Button("Yes", answer(ctx, ReviewNotifier.ACTION_YES, item.rawId), colors = ButtonDefaults.buttonColors(ColorProvider(W.text), ColorProvider(W.bg)))
                Spacer(GlanceModifier.width(6.dp))
              }
              Button("Not money", answer(ctx, ReviewNotifier.ACTION_NOT_MONEY, item.rawId), colors = ButtonDefaults.buttonColors(ColorProvider(W.raised), ColorProvider(W.text)))
              Spacer(GlanceModifier.width(6.dp))
              Button("Fix", openApp(ctx, "balancenative://activity?f=review&fix=${item.rawId}"), colors = ButtonDefaults.buttonColors(ColorProvider(W.raised), ColorProvider(W.text)))
            }
          }
        }
      }
    }
  }

  private fun question(i: ReviewNotifier.Item): String {
    val amount = Format.rupiah(i.amount ?: 0)
    return when (i.kind) {
      "expense" -> if (i.counterparty != null) "Spent $amount at ${i.counterparty}?" else "Spent $amount?"
      "income" -> if (i.counterparty != null) "Got $amount from ${i.counterparty}?" else "Got $amount?"
      "transfer" -> "Moved $amount between your accounts?"
      else -> "Not a transaction?"
    }
  }

  private fun answer(context: Context, action: String, rawId: Long) = actionSendBroadcast(
    Intent(context, ReviewActionReceiver::class.java).setAction(action).putExtra(ReviewNotifier.EXTRA_RAW_ID, rawId)
  )
}

class ToCheckReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ToCheckWidget() }

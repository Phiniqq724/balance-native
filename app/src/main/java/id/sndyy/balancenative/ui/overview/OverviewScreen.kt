package id.sndyy.balancenative.ui.overview

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.FlowChart
import id.sndyy.balancenative.core.FlowChartProps
import id.sndyy.balancenative.core.SyncWorker
import id.sndyy.balancenative.data.Analysis
import id.sndyy.balancenative.data.Analytics
import id.sndyy.balancenative.data.Flow
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.HeatCell
import id.sndyy.balancenative.data.Heatmap
import id.sndyy.balancenative.data.Period
import id.sndyy.balancenative.data.Share
import id.sndyy.balancenative.ui.kit.Failed
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Loaded
import id.sndyy.balancenative.ui.kit.Loading
import id.sndyy.balancenative.ui.kit.Money
import id.sndyy.balancenative.ui.kit.Rule
import id.sndyy.balancenative.ui.kit.SectionHead
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.UiEvents
import id.sndyy.balancenative.ui.kit.ledgerState
import id.sndyy.balancenative.ui.kit.shade
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val WEEKS = 20

data class OverviewData(val now: LocalDateTime, val flows: List<Flow>, val grid: List<List<HeatCell>>, val hasAccess: Boolean)

class OverviewViewModel(app: Application) : AndroidViewModel(app) {
  private val core = app.balance.core

  val state = viewModelScope.ledgerState {
    val now = LocalDateTime.now()
    val since = min(Analytics.start(now), Format.startOfDayMs(Heatmap.start(WEEKS, now.toLocalDate())))
    val flows = core.flows(since)
    OverviewData(now, flows, Heatmap.build(flows, WEEKS, now.toLocalDate()), core.hasNotificationAccess())
  }

  fun openAccess() = core.openNotificationAccessSettings()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(onCategory: (String) -> Unit, vm: OverviewViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  var period by rememberSaveable { mutableStateOf(Period.MONTH) }
  val context = androidx.compose.ui.platform.LocalContext.current
  val scope = rememberCoroutineScope()
  var refreshing by remember { mutableStateOf(false) }

  when (val s = state) {
    Loaded.Loading -> Loading()
    is Loaded.Error -> Failed("Couldn't read your ledger.") { UiEvents.retry() }
    is Loaded.Ready -> PullToRefreshBox(
      isRefreshing = refreshing,
      onRefresh = {
        // Pull to refresh = sync only (no re-read), like the Expo app.
        scope.launch {
          refreshing = true
          SyncWorker.request(context, delaySeconds = 0)
          UiEvents.retry()
          delay(700)
          refreshing = false
        }
      },
    ) {
      Overview(s.data, period, { period = it }, vm::openAccess, onCategory)
    }
  }
}

@Composable
private fun Overview(data: OverviewData, period: Period, onPeriod: (Period) -> Unit, onAllowAccess: () -> Unit, onCategory: (String) -> Unit) {
  val a = remember(data, period) { Analytics.analyze(data.flows, period, data.now) }
  val empty = data.flows.isEmpty()

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
    AnimatedVisibility(!data.hasAccess, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      AccessBanner(onAllowAccess)
    }

    PeriodTicker(period, onPeriod, rangeLabel(a))

    // The headline slides in from the side the new period sits on, so switching reads as moving along a ruler.
    AnimatedContent(
      targetState = period,
      transitionSpec = {
        val forward = targetState.ordinal > initialState.ordinal
        (slideInHorizontally(tween(260)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(260))) togetherWith
          (slideOutHorizontally(tween(200)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(160)))
      },
      label = "headline",
    ) { p ->
      val pa = if (p == period) a else Analytics.analyze(data.flows, p, data.now)
      Column(Modifier.padding(start = Space.page, end = Space.page, top = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label("Spent", size = 13.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
        Money(pa.spent)
        Pace(pa.paceDelta, p.compare)
      }
    }

    if (empty) {
      Label(
        "Nothing logged yet. Pay with Jago or myBCA and it shows up here.",
        Modifier.padding(start = Space.page, end = Space.page, top = 28.dp),
        size = 15.sp,
        tone = Tone.MUTED,
      )
    } else {
      FlowChart(
        FlowChartProps(
          mode = "line",
          current = a.line.current.map(Long::toDouble),
          previous = if (a.hasPrevious) a.line.previous.map(Long::toDouble) else emptyList(),
          ticks = a.line.ticks,
          readoutLabels = a.line.readoutLabels,
          readoutValues = a.line.readoutValues,
          defaultIndex = a.nowIndex,
          textColor = Ember.HEX_TEXT, mutedColor = Ember.HEX_MUTED, gridColor = Ember.HEX_LINE, accentColor = Ember.HEX_ACCENT,
        ),
        Modifier.fillMaxWidth().height(248.dp).padding(start = Space.page, end = Space.page, top = 24.dp),
      )
      Row(Modifier.padding(start = Space.page, end = Space.page, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendKey(period.current)
        if (a.hasPrevious) LegendKey(period.previous, dashed = true)
      }

      SpendGrid(data.grid)
      FlowSplit(a.spent, a.received)
      if (a.categories.isNotEmpty()) WhereItWent(a.categories, a.stats.payments, onCategory)
      if (a.sources.isNotEmpty()) WhereItCameFrom(a.sources)

      SectionHead(period.unit)
      FlowChart(
        FlowChartProps(
          mode = "bars",
          current = a.line.current.let { cur -> a.slots.indices.map { i -> cur.getOrNull(i)?.toDouble() ?: 0.0 } },
          income = a.incomeSlots.map(Long::toDouble),
          ticks = a.line.ticks,
          readoutLabels = a.slots.map { it.name },
          readoutValues = a.slots.indices.map { i ->
            val out = a.line.current.getOrNull(i) ?: 0L
            val inc = a.incomeSlots.getOrNull(i) ?: 0L
            listOfNotNull(out.takeIf { it > 0 }?.let { "−${Format.digits(it)}" }, inc.takeIf { it > 0 }?.let { "+${Format.digits(it)}" })
              .joinToString("  ").ifEmpty { "0" }
          },
          defaultIndex = a.nowIndex,
          textColor = Ember.HEX_TEXT, mutedColor = Ember.HEX_MUTED, gridColor = Ember.HEX_LINE, accentColor = Ember.HEX_ACCENT, incomeColor = Ember.HEX_INCOME,
        ),
        Modifier.fillMaxWidth().height(184.dp).padding(horizontal = Space.page),
      )
      Row(Modifier.padding(start = Space.page, end = Space.page, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendKey("Spent", tint = Ember.accent)
        LegendKey("Received", tint = Ember.income)
      }

      StatPlate(a)
    }
    Spacer(Modifier.height(Space.navClearance))
  }
}

private fun rangeLabel(a: Analysis): String {
  val first = Format.date(a.slots.first().start)
  val last = Format.date(a.slots.last().end - 1)
  if (a.slots.size == 12) return first.year.toString()
  fun s(d: LocalDate) = "${d.dayOfMonth} ${Format.shortMonth(d)}"
  return "${s(first)} – ${s(last)}"
}

/** Text tabs, no pills: the selected period is bright, and the underline glides to it. */
@Composable
private fun PeriodTicker(period: Period, onChange: (Period) -> Unit, range: String) {
  Row(Modifier.fillMaxWidth().padding(start = Space.page - 8.dp, end = Space.page, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
    Period.entries.forEach { p ->
      val on = p == period
      val bar by animateDpAsState(if (on) 16.dp else 0.dp, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "tick")
      Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable { onChange(p) }.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Label(p.label, size = 15.sp, weight = if (on) FontWeight.Bold else FontWeight.Medium, tone = if (on) Tone.TEXT else Tone.MUTED)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.size(bar, 2.dp).background(Ember.text))
      }
    }
    Spacer(Modifier.weight(1f))
    Label(range, size = 13.sp, tone = Tone.MUTED)
  }
}

@Composable
private fun Pace(delta: Double?, compare: String) {
  if (delta == null) {
    Label("Nothing from ${compare.replace(" so far", "")} to compare", size = 14.sp, tone = Tone.MUTED)
    return
  }
  val pct = (abs(delta) * 100).roundToInt()
  if (pct == 0) {
    Label("Level with $compare", size = 14.sp, tone = Tone.MUTED)
    return
  }
  androidx.compose.material3.Text(
    buildAnnotatedString {
      withStyle(SpanStyle(color = Ember.text, fontWeight = FontWeight.Bold)) { append("${if (delta > 0) "▲" else "▼"} $pct%") }
      append(" ${if (delta > 0) "over" else "under"} $compare")
    },
    color = Ember.muted,
    fontFamily = id.sndyy.balancenative.ui.theme.Satoshi,
    fontSize = 14.sp,
  )
}

@Composable
private fun LegendKey(label: String, dashed: Boolean = false, tint: Color = Ember.text) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    if (dashed) {
      Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { repeat(3) { Box(Modifier.size(3.dp, 2.dp).background(Ember.muted)) } }
    } else {
      Box(Modifier.size(11.dp, 2.dp).background(tint))
    }
    Label(label, size = 12.sp, tone = Tone.MUTED)
  }
}

/** Same grid as the home-screen widget, Monday on top. Cells bloom in column by column on first show. */
@Composable
private fun SpendGrid(columns: List<List<HeatCell>>) {
  SectionHead("Last $WEEKS weeks")
  var shown by remember { mutableStateOf(false) }
  LaunchedEffect(Unit) { shown = true }
  BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
    val gap = 3.dp
    val labelWidth = 18.dp
    val cell = (maxWidth - labelWidth - gap * (columns.size - 1)) / columns.size
    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
      Column(Modifier.width(labelWidth - gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        listOf("M", "", "W", "", "F", "", "S").forEach { d ->
          Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) { Label(d, size = 10.sp, weight = FontWeight.Medium, tone = Tone.MUTED) }
        }
      }
      columns.forEachIndexed { w, week ->
        val scale by animateFloatAsState(if (shown) 1f else 0.4f, tween(320, delayMillis = w * 18), label = "cell")
        val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(320, delayMillis = w * 18), label = "cellAlpha")
        Column(verticalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }) {
          week.forEach { day ->
            Box(Modifier.size(cell).clip(RoundedCornerShape(3.dp)).background(if (day.level < 0) Ember.bg else Ember.heat[day.level]))
          }
        }
      }
    }
  }
  Row(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Spacer(Modifier.weight(1f))
    Label("Less", size = 12.sp, tone = Tone.MUTED)
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { Ember.heat.forEach { Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(it)) } }
    Label("More", size = 12.sp, tone = Tone.MUTED)
  }
}

@Composable
private fun FlowSplit(spent: Long, received: Long) {
  val net = received - spent
  SectionHead("In and out")
  Row(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
    Figure("In", "+${Format.digits(received)}", Tone.INCOME)
    Figure("Out", "−${Format.digits(spent)}")
    Figure("Net", "${if (net >= 0) "+" else "−"}${Format.digits(net)}")
  }
  if (spent + received > 0) {
    // The split grows from the middle seam when it first appears.
    val grow by animateFloatAsState(1f, tween(500), label = "split")
    Canvas(Modifier.fillMaxWidth().height(6.dp).padding(horizontal = Space.page)) {
      val gapPx = 2.dp.toPx()
      val total = (spent + received).toFloat()
      val inW = (size.width - if (received > 0 && spent > 0) gapPx else 0f) * received / total * grow
      val outW = (size.width - if (received > 0 && spent > 0) gapPx else 0f) * spent / total * grow
      val r = CornerRadius(2.dp.toPx())
      if (received > 0) drawRoundRect(Ember.income, Offset.Zero, Size(inW, size.height), r)
      if (spent > 0) drawRoundRect(Ember.accent, Offset(size.width - outW, 0f), Size(outW, size.height), r)
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Figure(label: String, value: String, tone: Tone = Tone.TEXT) {
  Column(Modifier.weight(1f).padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Label(label, size = 12.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
    Label(value, size = 17.sp, weight = FontWeight.Bold, tone = tone, maxLines = 1)
  }
}

@Composable
private fun WhereItWent(categories: List<Share>, payments: Int, onCategory: (String) -> Unit) {
  val shown = categories.take(8)
  SectionHead("Where it went", if (payments == 1) "1 payment" else "$payments payments")
  Row(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, bottom = 8.dp).height(18.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    shown.forEachIndexed { i, c ->
      Box(Modifier.weight(maxOf(c.share, 0.005).toFloat()).height(10.dp).clip(RoundedCornerShape(2.dp)).background(shade(i, shown.size)))
    }
  }
  shown.forEachIndexed { i, c -> ShareRow(i + 1, shade(i, shown.size), c) { onCategory("cat:${c.id ?: "none"}") } }
}

@Composable
private fun WhereItCameFrom(sources: List<Share>) {
  SectionHead("Where it came from")
  sources.take(5).forEachIndexed { i, s -> ShareRow(i + 1, Ember.income, s, income = true) }
}

@Composable
private fun ShareRow(rank: Int, swatch: Color, share: Share, income: Boolean = false, onPress: (() -> Unit)? = null) {
  Row(
    Modifier.fillMaxWidth().then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier).padding(horizontal = Space.page, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Label(rank.toString().padStart(2, '0'), Modifier.width(18.dp), size = 12.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(swatch))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Label(share.name, weight = FontWeight.Medium, maxLines = 1)
      Label("${(share.share * 100).roundToInt()}%  ·  ${share.count}×", size = 12.sp, tone = Tone.MUTED)
    }
    Label("${if (income) "+" else ""}${Format.digits(share.amount)}", weight = FontWeight.Medium, tone = if (income) Tone.INCOME else Tone.TEXT)
  }
}

@Composable
private fun StatPlate(a: Analysis) {
  val s = a.stats
  SectionHead("Numbers")
  Column(Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
    Rule()
    Row(Modifier.fillMaxWidth()) {
      Stat("Per day", Format.rupiah(s.perDay.roundToInt().toLong()), first = true)
      Box(Modifier.size(1.dp, 88.dp).background(Ember.line))
      Stat("Biggest", s.biggest?.let { Format.rupiah(it.amount) } ?: "–", s.biggest?.title)
    }
    Rule()
    Row(Modifier.fillMaxWidth()) {
      Stat("Payments", s.payments.toString(), first = true)
      Box(Modifier.size(1.dp, 88.dp).background(Ember.line))
      Stat("Most often", s.regular?.name ?: "–", s.regular?.let { "${it.count} times" })
    }
    Rule()
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Stat(label: String, value: String, note: String? = null, first: Boolean = false) {
  Column(Modifier.weight(1f).height(88.dp).padding(start = if (first) 0.dp else 16.dp, top = 14.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    Label(label, size = 12.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
    Label(value, size = 17.sp, weight = FontWeight.Bold, maxLines = 1)
    if (note != null) Label(note, size = 12.sp, tone = Tone.MUTED, maxLines = 1)
  }
}

@Composable
private fun AccessBanner(onAllow: () -> Unit) {
  Column(Modifier.fillMaxWidth().background(Ember.raised).padding(horizontal = Space.page, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Label("Notification access is off", weight = FontWeight.Medium)
      Label("Balance needs it to log your payments.", size = 14.sp, tone = Tone.MUTED)
    }
    Button(onClick = onAllow, colors = ButtonDefaults.buttonColors(containerColor = Ember.text, contentColor = Ember.bg)) {
      Label("Allow access", weight = FontWeight.Medium, color = Ember.bg)
    }
  }
}

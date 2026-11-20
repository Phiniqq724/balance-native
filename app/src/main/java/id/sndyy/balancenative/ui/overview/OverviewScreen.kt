package id.sndyy.balancenative.ui.overview

import android.app.Application
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.FlowChart
import id.sndyy.balancenative.core.FlowChartProps
import id.sndyy.balancenative.data.Book
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.RangeStats
import id.sndyy.balancenative.data.Selection
import id.sndyy.balancenative.ui.kit.Failed
import id.sndyy.balancenative.ui.kit.Haptic
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Loaded
import id.sndyy.balancenative.ui.kit.Loading
import id.sndyy.balancenative.ui.kit.Money
import id.sndyy.balancenative.ui.kit.SectionHead
import id.sndyy.balancenative.ui.kit.SyncRefresh
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.UiEvents
import id.sndyy.balancenative.ui.kit.ledgerState
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class OverviewData(
  val book: Book,
  /** The details for what the map showed when this was read, so the screen opens complete. */
  val stats: RangeStats,
  val empty: Boolean,
  val hasAccess: Boolean,
  val toCheck: Int,
  /** What the AI read the waiting notifications as spending, when it could tell. */
  val toCheckAmount: Long,
)

class OverviewViewModel(app: Application) : AndroidViewModel(app) {
  private val core = app.balance.core

  /**
   * How many weeks the map's right edge sits behind this week. Fractional while a finger or a fling
   * moves it; it always comes to rest on a whole week. Lives with the ViewModel, so it survives
   * rotation and starts at "now" when the app restarts.
   */
  val pan = Animatable(0f)
  /** Where the map last came to rest: what the sections below describe. */
  var settled by mutableIntStateOf(0)
    private set
  var moving by mutableStateOf(false)
    private set
  /** A tapped week, shown instead of the whole view until it's cleared or the map moves. */
  var focus by mutableStateOf<LocalDate?>(null)
    private set

  val state = viewModelScope.ledgerState {
    val today = LocalDate.now()
    // Per-day totals for the whole history: what the map and every total read. Payments themselves
    // are only read for the range on screen (see stats), so the cost stays flat as the ledger grows.
    val days = core.dailyTotals()
    val book = withContext(Dispatchers.Default) { Book(Book.firstDay(today, days.firstOrNull()?.day), today, days) }
    val (toCheck, toCheckAmount) = runCatching { core.toCheckSummary() }.getOrDefault(0 to 0L)
    val initial = stats(book, focus?.let { book.week(it) } ?: inView(book, settled))
    withContext(Dispatchers.Default) {
      OverviewData(
        book = book,
        stats = initial,
        empty = days.isEmpty(),
        hasAccess = core.hasNotificationAccess(),
        toCheck = toCheck,
        toCheckAmount = toCheckAmount,
      )
    }
  }

  private val rest = spring<Float>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

  // These are suspend functions run from the screen's own coroutine scope: animations need the
  // composition's frame clock, which viewModelScope doesn't have (launching them there crashed).

  suspend fun drag(book: Book, weeks: Float) {
    focus = null
    moving = true
    pan.snapTo((pan.value + weeks).coerceIn(0f, mapMaxBack(book).toFloat()))
  }

  /** A fling carries on a little in its direction, then settles on the nearest whole week. */
  suspend fun release(book: Book, velocity: Float) {
    val target = (pan.value + velocity * 0.18f).roundToInt().coerceIn(0, mapMaxBack(book))
    try {
      pan.animateTo(target.toFloat(), rest, initialVelocity = velocity)
    } finally {
      settled = target
      moving = false
    }
  }

  suspend fun jump(book: Book, back: Float, done: Boolean) {
    focus = null
    moving = true
    if (!done) { pan.snapTo(back); return }
    val target = back.roundToInt().coerceIn(0, mapMaxBack(book))
    try {
      pan.animateTo(target.toFloat(), rest)
    } finally {
      settled = target
      moving = false
    }
  }

  /** Totals come from the book; categories, biggest and the rest from this range's payments alone. */
  suspend fun stats(book: Book, sel: Selection): RangeStats {
    val flows = core.flows(Format.startOfDayMs(sel.start), Format.startOfDayMs(sel.end.plusDays(1)))
    return withContext(Dispatchers.Default) { book.stats(sel, flows) }
  }

  fun tap(week: LocalDate) { focus = if (focus == week) null else week }

  fun clearFocus() { focus = null }

  fun openAccess() = core.openNotificationAccessSettings()
}

/** The days the map shows when its right edge is [back] weeks behind this week. */
private fun inView(book: Book, back: Int): Selection {
  val start = mapStart(book, back)
  return Selection(maxOf(book.first, start), minOf(book.today, start.plusWeeks(MAP_WEEKS.toLong()).minusDays(1)))
}

@Composable
fun OverviewScreen(onCategory: (String) -> Unit, vm: OverviewViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  when (val s = state) {
    Loaded.Loading -> Loading()
    is Loaded.Error -> Failed("Couldn't read your ledger.") { UiEvents.retry() }
    is Loaded.Ready -> SyncRefresh { Overview(s.data, vm, onCategory) }
  }
}

@Composable
private fun Overview(data: OverviewData, vm: OverviewViewModel, onCategory: (String) -> Unit) {
  val book = data.book
  val hostView = LocalView.current
  val scope = rememberCoroutineScope()
  // The sections below follow where the map rests (or the focused week); the total follows the map live.
  val sel = vm.focus?.let { book.week(it) } ?: inView(book, vm.settled)
  // The last details stay up while the next range's payments are read, so nothing blinks empty.
  var stats by remember(data) { mutableStateOf(data.stats) }
  LaunchedEffect(data, sel) { if (stats.selection != sel) stats = vm.stats(book, sel) }
  var cursor by remember { mutableStateOf<LocalDate?>(null) }
  var highlight by remember(sel) { mutableStateOf<String?>(null) }

  // A tick for every week the map crosses, a firmer one when a new month comes into view.
  LaunchedEffect(book) {
    snapshotFlow { vm.pan.value.roundToInt() }.distinctUntilChanged().drop(1).collect { b ->
      val start = mapStart(book, b)
      if (start.dayOfMonth <= 7) Haptic.month(hostView) else Haptic.week(hostView)
    }
  }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
    AnimatedVisibility(!data.hasAccess, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      AccessBanner(vm::openAccess)
    }

    MapHeader(data, book, vm, onCategory)

    // The ruler of every week is a scrollbar: it shows while the map moves and a moment after, floating
    // over the top of what's below, so it takes no room at rest. Grab it while it's up to jump far.
    val ruler by produceState(false) {
      snapshotFlow { vm.moving }.collectLatest { m -> if (m) value = true else { delay(1500); value = false } }
    }
    Box(Modifier.zIndex(1f)) {
      MapGrid(
        book = book,
        back = { vm.pan.value },
        focus = vm.focus,
        cursor = cursor,
        onDrag = { scope.launch { vm.drag(book, it) } },
        onRelease = { scope.launch { vm.release(book, it) } },
        onTap = { Haptic.key(hostView); vm.tap(it) },
        modifier = Modifier.padding(start = Space.page - MAP_PAD, end = Space.page - MAP_PAD, top = 12.dp),
      )
      androidx.compose.animation.AnimatedVisibility(
        ruler,
        Modifier.align(Alignment.BottomCenter).offset { IntOffset(0, RULER_HEIGHT.roundToPx()) },
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(260)),
      ) {
        MapRuler(
          book, { vm.pan.value },
          onJump = { b, done -> scope.launch { vm.jump(book, b, done) } },
          modifier = Modifier.background(Ember.bg).padding(start = Space.page, end = Space.page, top = 8.dp),
        )
      }
    }

    if (data.empty) {
      Label(
        "Nothing logged yet. Pay with Jago or myBCA and it lands here.",
        Modifier.padding(start = Space.page, end = Space.page, top = 24.dp),
        size = 15.sp,
        tone = Tone.MUTED,
      )
    } else {
      ChartSlot(book, stats) { i -> cursor = i?.let { stats.chartDays.getOrNull(it) }?.takeIf { sel.days <= 70 } }

      SectionHead("In and out")
      Column(Modifier.padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InOutRow(stats.received, stats.spent)
        val net = stats.received - stats.spent
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
          Label("In ${Format.digits(stats.received)}", size = 13.sp, tone = Tone.MUTED)
          Label("Out ${Format.digits(stats.spent)}", size = 13.sp, tone = Tone.MUTED)
          Spacer(Modifier.weight(1f))
          Label("Net ${if (net >= 0) "+" else "−"}Rp ${Format.digits(net)}", size = 14.sp, weight = FontWeight.Bold)
        }
      }

      if (stats.categories.isNotEmpty()) {
        SectionHead("Where it went", if (stats.payments == 1) "1 payment" else "${stats.payments} payments")
        Column(Modifier.padding(horizontal = Space.page)) {
          ShareField(stats.categories, highlight)
          Spacer(Modifier.height(8.dp))
          RankedRows(stats.categories, highlight, { highlight = it }) { onCategory("cat:${it.id ?: "none"}") }
        }
      }

      if (stats.sources.isNotEmpty()) {
        SectionHead("Where it came from", "+${Format.rupiah(stats.received)}")
        Column(Modifier.padding(horizontal = Space.page)) { SourceBars(stats.sources) }
      }

      NumberCells(stats)
    }
    Spacer(Modifier.height(Space.navClearance))
  }
}

/**
 * What's in view and what it cost. The only part of the screen that recomposes while the map moves:
 * it reads the live position, everything below reads where the map last came to rest.
 */
@Composable
private fun MapHeader(data: OverviewData, book: Book, vm: OverviewViewModel, onCategory: (String) -> Unit) {
  val hostView = LocalView.current
  val live = vm.focus?.let { book.week(it) } ?: inView(book, vm.pan.value.roundToInt())
  val liveSpent = book.spent(live.start, live.end)
  Column(Modifier.padding(start = Space.page, end = Space.page, top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      // No caption for the view itself: the map shows what's counted. A focused week says which one.
      AnimatedVisibility(vm.focus != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically) {
          Label("Week of ${live.start.dayOfMonth} ${Format.shortMonth(live.start)}${if (live.start.year != book.today.year) " ${live.start.year}" else ""}", Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium, tone = Tone.MUTED, maxLines = 1)
          Label("×", Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { Haptic.key(hostView); vm.clearFocus() }
            .semantics { contentDescription = "Back to the whole view" }.padding(start = 9.dp, top = 2.dp), size = 17.sp, tone = Tone.MUTED)
        }
      }
      Money(liveSpent, size = totalSize(liveSpent), animate = !vm.moving)
      Label(compare(book, live, vm.focus != null), size = 13.sp, tone = Tone.MUTED, maxLines = 1)
      Status(data) { onCategory("review") }
    }

}

/** Display size from the digit count, never measured, so it can't jitter mid-drag. */
private fun totalSize(value: Long) = when (abs(value).toString().length) {
  in 0..7 -> 52.sp
  8 -> 46.sp
  else -> 40.sp
}

/** Against the same number of days just before what's in view (prefix sums, so it's free mid-drag). */
private fun compare(book: Book, sel: Selection, week: Boolean): String {
  val n = sel.days.toLong()
  val before = sel.start.minusDays(n)
  if (before.isBefore(book.first) || book.firstData?.let { sel.start.minusDays(1).isBefore(it) } != false) return "Nothing earlier to compare"
  val base = book.spent(before, sel.start.minusDays(1))
  if (base <= 0) return "Nothing earlier to compare"
  val pct = ((book.spent(sel.start, sel.end) - base) * 100.0 / base).roundToInt()
  val label = if (week) "the week before" else "the weeks before"
  return if (pct == 0) "Level with $label" else "${abs(pct)}% ${if (pct < 0) "under" else "over"} $label"
}

@Composable
private fun Status(data: OverviewData, onOpen: () -> Unit) {
  val text = when {
    data.toCheck == 0 -> ""
    data.toCheckAmount > 0 -> "+${Format.rupiah(data.toCheckAmount)} to check"
    data.toCheck == 1 -> "1 to check"
    else -> "${data.toCheck} to check"
  }
  Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
    if (text.isNotEmpty()) Label(text, Modifier.clickable(onClick = onOpen), size = 13.sp, tone = Tone.MUTED)
  }
}

/** The chart, or for a single day that day's payments, in the same fixed height so nothing jumps. */
@Composable
private fun ChartSlot(book: Book, stats: RangeStats, onScrub: (Int?) -> Unit) {
  val height = 220.dp
  if (stats.selection.days == 1) {
    val flows = stats.outFlows
    Column(Modifier.fillMaxWidth().height(height).padding(start = Space.page, end = Space.page, top = 24.dp)) {
      if (flows.isEmpty()) Label("No spending this day.", size = 14.sp, tone = Tone.MUTED)
      flows.take(4).forEach {
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
          Label(it.title, Modifier.weight(1f), size = 14.sp, maxLines = 1)
          Label("−${Format.digits(it.amount)}", size = 14.sp, weight = FontWeight.Medium)
        }
      }
      if (flows.size > 4) Label("+${flows.size - 4} more", Modifier.padding(top = 8.dp), size = 12.sp, tone = Tone.MUTED)
    }
    return
  }
  val a = stats.chart
  FlowChart(
    FlowChartProps(
      mode = "line",
      current = a.current.map(Long::toDouble),
      previous = a.previous.map(Long::toDouble),
      ticks = a.ticks,
      readoutLabels = a.readoutLabels,
      readoutValues = a.readoutValues,
      defaultIndex = a.current.size - 1,
      textColor = Ember.HEX_TEXT, mutedColor = Ember.HEX_MUTED, gridColor = Ember.HEX_LINE, accentColor = Ember.HEX_ACCENT,
    ),
    Modifier.fillMaxWidth().height(height).padding(start = Space.page, end = Space.page, top = 20.dp),
    onScrub = onScrub,
  )
}

/**
 * Numbers as four large cells, two per row on the grid's pitch. Each carries one figure and a
 * 10-cell meter that says what the figure means, so the plate reads without dividers.
 */
@Composable
private fun NumberCells(s: RangeStats) {
  SectionHead("Numbers")
  val perDayMax = maxOf(s.perDay, s.basePerDay ?: 0.0)
  Column(Modifier.padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
      NumberCell(
        "Per day", Format.rupiah(s.perDay.roundToInt().toLong()),
        s.basePerDay?.let { "${Format.rupiah(it.roundToInt().toLong())} before" } ?: "Nothing earlier",
        if (perDayMax > 0) s.perDay / perDayMax else 0.0,
      )
      NumberCell(
        "Biggest", s.biggest?.let { Format.rupiah(it.amount) } ?: "–",
        s.biggest?.title ?: "No payments",
        if (s.spent > 0) (s.biggest?.amount ?: 0L).toDouble() / s.spent else 0.0,
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
      NumberCell(
        "Payments", s.payments.toString(),
        "on ${s.spendDays} of ${s.selection.days} days",
        s.spendDays.toDouble() / maxOf(1, s.selection.days),
      )
      NumberCell(
        "Most often", s.regular?.name ?: "–",
        s.regular?.let { "${it.count} of ${s.payments} payments" } ?: "Nothing repeats yet",
        if (s.payments > 0) (s.regular?.count ?: 0).toDouble() / s.payments else 0.0,
      )
    }
  }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.NumberCell(label: String, value: String, note: String, fraction: Double) {
  Column(
    Modifier
      .weight(1f)
      .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
      .background(Ember.surface)
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(3.dp),
  ) {
    Label(label, size = 12.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
    Label(value, size = 18.sp, weight = FontWeight.Bold, maxLines = 1)
    Label(note, size = 12.sp, tone = Tone.MUTED, maxLines = 1)
    Spacer(Modifier.height(10.dp))
    CellMeter(cellsFor(fraction, 10), 10, RANK_1)
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

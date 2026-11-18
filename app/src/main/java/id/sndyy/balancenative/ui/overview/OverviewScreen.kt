package id.sndyy.balancenative.ui.overview

import android.app.Application
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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
import id.sndyy.balancenative.data.AtlasView
import id.sndyy.balancenative.data.Book
import id.sndyy.balancenative.data.Direction
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.Preset
import id.sndyy.balancenative.data.RangeStats
import id.sndyy.balancenative.data.Selection
import id.sndyy.balancenative.data.Share
import id.sndyy.balancenative.data.Span
import id.sndyy.balancenative.data.monday
import id.sndyy.balancenative.data.viewFor
import id.sndyy.balancenative.ui.kit.CellButton
import id.sndyy.balancenative.ui.kit.Failed
import id.sndyy.balancenative.ui.kit.Haptic
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Loaded
import id.sndyy.balancenative.ui.kit.Loading
import id.sndyy.balancenative.ui.kit.Money
import id.sndyy.balancenative.ui.kit.Rule
import id.sndyy.balancenative.ui.kit.SectionHead
import id.sndyy.balancenative.ui.kit.SqueezeRow
import id.sndyy.balancenative.ui.kit.SyncRefresh
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.UiEvents
import id.sndyy.balancenative.ui.kit.ledgerState
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class OverviewData(
  val book: Book,
  val empty: Boolean,
  val hasAccess: Boolean,
  val toCheck: Int,
  /** What the AI read the waiting notifications as spending, when it could tell. */
  val toCheckAmount: Long,
)

class OverviewViewModel(app: Application) : AndroidViewModel(app) {
  private val core = app.balance.core
  private val prefs = app.getSharedPreferences("balance.ui", Context.MODE_PRIVATE)

  val state = viewModelScope.ledgerState {
    val today = LocalDate.now()
    val first = Book.firstDay(today)
    val flows = core.flows(Format.startOfDayMs(first))
    val waiting = runCatching { core.unparsed() }.getOrDefault(emptyList())
    withContext(Dispatchers.Default) {
      OverviewData(
        book = Book(first, today, flows),
        empty = flows.isEmpty(),
        hasAccess = core.hasNotificationAccess(),
        toCheck = waiting.size,
        toCheckAmount = waiting.sumOf { w -> w.review?.takeIf { it.direction == Direction.OUT }?.amount ?: 0L },
      )
    }
  }

  // Lives with the ViewModel: survives rotation, resets to This month when the app restarts.
  var selection by mutableStateOf<Selection?>(null)
    private set
  var view by mutableStateOf(AtlasView.WEEKS)
    private set
  /** Last column's Monday while drilled into 8 weeks. */
  var daysEnd by mutableStateOf<LocalDate?>(null)
    private set
  /** True while a finger is dragging a range: totals snap instead of counting up. */
  var dragging by mutableStateOf(false)
    private set
  var hint by mutableStateOf(false)
    private set

  fun current(book: Book): Selection = selection ?: book.preset(Preset.MONTH)

  private fun select(book: Book, sel: Selection) {
    selection = sel
    view = viewFor(sel, view)
    if (view == AtlasView.DAYS) {
      val end = daysEnd ?: monday(book.today)
      if (monday(sel.start).isBefore(end.minusWeeks(7)) || monday(sel.end).isAfter(end)) daysEnd = centred(book, sel)
    }
  }

  private fun centred(book: Book, sel: Selection): LocalDate {
    val mid = sel.start.plusDays(sel.days / 2L)
    return minOf(monday(book.today), monday(mid).plusWeeks(3))
  }

  fun lastWeek(book: Book): LocalDate {
    val now = monday(book.today)
    return when (view) {
      AtlasView.YEAR -> now
      AtlasView.DAYS -> daysEnd ?: now
      AtlasView.WEEKS -> {
        val start = monday(current(book).start)
        if (start.isBefore(now.minusWeeks(19))) start.plusWeeks(19) else now
      }
    }
  }

  fun tap(book: Book, d: LocalDate) {
    if (d.isAfter(book.today)) return
    dragging = false
    select(book, when (view) {
      AtlasView.WEEKS -> book.week(d)
      AtlasView.DAYS -> book.day(d)
      AtlasView.YEAR -> book.month(d)
    })
  }

  fun drag(book: Book, from: LocalDate, to: LocalDate, done: Boolean) {
    if (minOf(from, to).isAfter(book.today)) return
    dragging = !done
    select(book, if (view == AtlasView.YEAR) book.months(from, to) else book.range(from, to))
  }

  fun preset(book: Book, p: Preset) { dragging = false; select(book, book.preset(p)) }

  fun canStep(book: Book, dir: Int) = book.step(current(book), dir) != null

  fun step(book: Book, dir: Int): Boolean {
    val next = book.step(current(book), dir) ?: return false
    dragging = false
    select(book, next)
    return true
  }

  fun drill(book: Book) { daysEnd = centred(book, current(book)); view = AtlasView.DAYS }

  fun weeks() { view = AtlasView.WEEKS }

  fun reset() { selection = null; view = AtlasView.WEEKS; dragging = false }

  /** "Hold and drag to pick days" shows once, the first time a touch rests on the grid. */
  fun held() {
    if (prefs.getBoolean("atlas_hint", false)) return
    prefs.edit().putBoolean("atlas_hint", true).apply()
    hint = true
  }

  fun hintDone() { hint = false }

  fun openAccess() = core.openNotificationAccessSettings()
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
  val sel = vm.current(book)
  val stats = remember(book, sel) { book.stats(sel) }
  var cursor by remember { mutableStateOf<LocalDate?>(null) }
  var highlight by remember(sel) { mutableStateOf<String?>(null) }

  if (vm.hint) androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(2600); vm.hintDone() }

  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
    AnimatedVisibility(!data.hasAccess, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
      AccessBanner(vm::openAccess)
    }

    AtlasGrid(
      book = book,
      selection = sel,
      view = vm.view,
      lastWeek = vm.lastWeek(book),
      cursor = cursor,
      onTap = { vm.tap(book, it) },
      onDrag = { a, b, done -> vm.drag(book, a, b, done) },
      onHold = vm::held,
      modifier = Modifier.padding(start = Space.page, end = Space.page, top = 20.dp),
    )

    Caption(stats, vm, book)

    Presets(sel.preset, onPick = { vm.preset(book, it) })

    Column(Modifier.padding(start = Space.page, end = Space.page, top = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Label("Spent", size = 13.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
      Money(stats.spent, size = totalSize(stats.spent), animate = !vm.dragging)
      Status(data) { onCategory("review") }
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

/** Display size from the digit count, never measured, so it can't jitter mid-drag. */
private fun totalSize(value: Long) = when (abs(value).toString().length) {
  in 0..7 -> 52.sp
  8 -> 46.sp
  else -> 40.sp
}

/**
 * Two fixed lines under the grid: ‹ range › then the comparison with the drill and reset links.
 * A horizontal swipe on this band steps the period; the rest of the page still swipes tabs.
 */
@Composable
private fun Caption(stats: RangeStats, vm: OverviewViewModel, book: Book) {
  val view = LocalView.current
  val sel = stats.selection
  val threshold = with(LocalDensity.current) { 40.dp.toPx() }
  fun step(dir: Int) { if (vm.step(book, dir)) Haptic.confirm(view) }
  Column(
    Modifier
      .fillMaxWidth()
      .padding(start = Space.page - 8.dp, end = Space.page, top = 10.dp)
      .pointerInput(book) {
        var dx = 0f
        detectHorizontalDragGestures(
          onDragStart = { dx = 0f },
          onDragEnd = { if (abs(dx) > threshold) step(if (dx > 0) -1 else 1) },
        ) { change, amount -> change.consume(); dx += amount }
      },
  ) {
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
      Stepper("‹", "Previous period", vm.canStep(book, -1)) { step(-1) }
      AnimatedContent(
        targetState = "${if (sel.days == 1) "1 day" else "${sel.days} days"} · ${selectionLabel(sel)}",
        transitionSpec = { fadeIn(androidx.compose.animation.core.tween(120)) togetherWith fadeOut(androidx.compose.animation.core.tween(90)) },
        modifier = Modifier.weight(1f),
        label = "range",
      ) { Label(it, Modifier.fillMaxWidth(), size = 15.sp, weight = FontWeight.Bold, maxLines = 1) }
      Stepper("›", "Next period", vm.canStep(book, 1)) { step(1) }
    }
    Row(Modifier.fillMaxWidth().height(22.dp).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
      Label(if (vm.hint) "Hold and drag to pick days" else compare(stats), Modifier.weight(1f), size = 13.sp, tone = Tone.MUTED, maxLines = 1)
      val link = when {
        vm.view == AtlasView.DAYS -> "‹ Weeks"
        vm.view == AtlasView.WEEKS && sel.span == Span.WEEK && sel.preset == null -> "Days ›"
        else -> null
      }
      if (link != null) Label(link, Modifier.clickable { Haptic.key(view); if (vm.view == AtlasView.DAYS) vm.weeks() else vm.drill(book) }, size = 13.sp, weight = FontWeight.Bold)
      val isDefault = sel.preset == Preset.MONTH && vm.view == AtlasView.WEEKS
      if (!isDefault) Label("×", Modifier.width(20.dp).clickable { Haptic.key(view); vm.reset() }.semantics { contentDescription = "Back to this month" }, size = 16.sp, tone = Tone.MUTED)
    }
  }
}

private fun compare(stats: RangeStats): String {
  val pace = stats.pace ?: return "Nothing earlier to compare"
  val pct = (abs(pace) * 100).roundToInt()
  return if (pct == 0) "Level with ${stats.baseLabel}" else "$pct% ${if (pace < 0) "under" else "over"} ${stats.baseLabel}"
}

@Composable
private fun Stepper(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
  Box(
    Modifier.size(36.dp).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
    contentAlignment = Alignment.Center,
  ) { Label(glyph, size = 20.sp, color = if (enabled) Ember.muted else Ember.line) }
}

/** W M Q Y as one squeeze row of cell controls, cut to the grid's columns (5 each). */
@Composable
private fun Presets(active: Preset?, onPick: (Preset) -> Unit) {
  SqueezeRow(
    count = Preset.entries.size,
    modifier = Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 12.dp),
    chosen = active?.ordinal ?: -1,
  ) { i, source, weight ->
    val p = Preset.entries[i]
    CellButton(on = p == active, onClick = { onPick(p) }, modifier = weight, source = source) { fg ->
      Label(p.label, size = 13.sp, weight = FontWeight.Bold, color = fg, maxLines = 1)
    }
  }
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
    val flows = book.flowsOn(stats.selection.start).filter { it.direction == Direction.OUT }
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

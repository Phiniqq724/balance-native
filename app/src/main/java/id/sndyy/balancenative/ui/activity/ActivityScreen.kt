package id.sndyy.balancenative.ui.activity

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import id.sndyy.balancenative.balance
import id.sndyy.balancenative.core.SyncWorker
import id.sndyy.balancenative.data.ActivityQuery
import id.sndyy.balancenative.data.Category
import id.sndyy.balancenative.data.Direction
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.InstalledApp
import id.sndyy.balancenative.data.Transaction
import id.sndyy.balancenative.data.Unparsed
import id.sndyy.balancenative.ui.kit.Chip
import id.sndyy.balancenative.ui.kit.Failed
import id.sndyy.balancenative.ui.kit.Label
import id.sndyy.balancenative.ui.kit.Loaded
import id.sndyy.balancenative.ui.kit.Loading
import id.sndyy.balancenative.ui.kit.Rule
import id.sndyy.balancenative.ui.kit.Tone
import id.sndyy.balancenative.ui.kit.UiEvents
import id.sndyy.balancenative.ui.kit.ledgerState
import id.sndyy.balancenative.ui.theme.Ember
import id.sndyy.balancenative.ui.theme.Satoshi
import id.sndyy.balancenative.ui.theme.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val PAGE = 25

data class ActivityData(
  val page: id.sndyy.balancenative.data.ActivityPage?,
  val categories: List<Category>,
  val unparsed: List<Unparsed>,
  val today: LocalDate,
)

private data class Key(val filter: String, val q: String, val count: Int)

class ActivityViewModel(app: Application) : AndroidViewModel(app) {
  private val core = app.balance.core
  val filter = MutableStateFlow("all")
  val query = MutableStateFlow("")
  private val count = MutableStateFlow(PAGE)
  val apps = MutableStateFlow<Map<String, InstalledApp>>(emptyMap())

  // Search waits for a short pause in typing so each keystroke doesn't hit the ledger.
  @OptIn(kotlinx.coroutines.FlowPreview::class)
  private val q = query.map { it.trim().lowercase() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0 else 250 }

  val state = viewModelScope.ledgerState(combine(filter, q, count) { f, s, c -> Key(f, s, c) }) { k ->
    val reviewing = k.filter == "review"
    ActivityData(
      page = if (reviewing) null else core.activityPage(ActivityQuery(k.filter, k.q), k.count),
      categories = core.categories(),
      unparsed = core.unparsed(),
      today = LocalDate.now(),
    )
  }

  init {
    viewModelScope.launch { apps.value = core.installedApps().associateBy { it.packageName } }
    // Back to one page whenever the filter or search changes.
    viewModelScope.launch { combine(filter, q) { a, b -> a to b }.collect { count.value = PAGE } }
  }

  fun more() { count.value += PAGE }
  fun setFilter(f: String) { filter.value = f }
  fun label(source: String) = apps.value[source]?.label ?: core.sourceLabel(source)
  fun title(tx: Transaction) = core.title(tx)
}

private sealed interface Item {
  val key: String
  data class Day(override val key: String, val label: String, var spent: Long = 0, var received: Long = 0) : Item
  data class Tx(override val key: String, val tx: Transaction, val pair: Transaction?) : Item
  data class Note(override val key: String, val n: Unparsed) : Item
  data class More(override val key: String) : Item
}

private fun found(q: String, vararg fields: Any?): Boolean {
  if (q.isEmpty()) return true
  val number = q.replace(Regex("[.,]"), "")
  return fields.any { f ->
    when (f) {
      null -> false
      is Long -> number.isNotEmpty() && number.all(Char::isDigit) && f.toString().contains(number)
      else -> f.toString().lowercase().contains(q)
    }
  }
}

private fun buildItems(transactions: List<Transaction>, more: String?): List<Item> {
  val byId = transactions.associateBy { it.id }
  val items = mutableListOf<Item>()
  val days = HashMap<LocalDate, Item.Day>()
  for (tx in transactions) {
    val pair = if (tx.isTransfer && tx.pairId != null) byId[tx.pairId] else null
    // Both halves of an own transfer collapse into the outgoing one.
    if (pair != null && tx.direction == Direction.IN) continue
    val date = Format.date(tx.occurredAt)
    val day = days.getOrPut(date) { Item.Day("day$date", Format.dayLabel(date)).also { items += it } }
    if (!tx.isTransfer) {
      if (tx.direction == Direction.OUT) day.spent += tx.amount else day.received += tx.amount
    }
    items += Item.Tx("tx${tx.id}", tx, pair)
  }
  if (more != null && items.isNotEmpty()) items += Item.More(more)
  return items
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(landing: Pair<String?, Long?>?, onLandingUsed: () -> Unit, vm: ActivityViewModel = viewModel()) {
  val state by vm.state.collectAsState()
  val filter by vm.filter.collectAsState()
  val query by vm.query.collectAsState()
  val apps by vm.apps.collectAsState()
  var openId by remember { mutableStateOf<Long?>(null) }
  var fixId by remember { mutableStateOf<Long?>(null) }
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var refreshing by remember { mutableStateOf(false) }

  LaunchedEffect(landing) {
    landing ?: return@LaunchedEffect
    landing.first?.let(vm::setFilter)
    fixId = landing.second
    onLandingUsed()
  }

  val s = state
  if (s !is Loaded.Ready) {
    if (s is Loaded.Error) Failed("Couldn't load activity.") { UiEvents.retry() } else Loading()
    return
  }
  val data = s.data
  val reviewing = filter == "review"
  val q = query.trim().lowercase()
  val toCheck = data.unparsed.size

  // The first time there is something to check, ask once to be allowed to tell them about the next ones.
  val askAlerts = rememberAlertsAsker()
  LaunchedEffect(reviewing, toCheck > 0) { if (reviewing && toCheck > 0) askAlerts() }

  val notes = if (reviewing) data.unparsed.filter { found(q, it.title, it.body, vm.label(it.source)) }.sortedByDescending { it.id == fixId } else emptyList()
  val page = data.page
  val items = remember(page) { buildItems(page?.transactions.orEmpty(), if (page?.hasMore == true) "more${page.transactions.size}" else null) }
  val maxDay = (items.filterIsInstance<Item.Day>().maxOfOrNull { it.spent } ?: 0L).coerceAtLeast(1L)
  // In All, what still waits for an answer sits at the top, under Today.
  val pending = if (filter == "all") data.unparsed.filter { found(q, it.title, it.body, vm.label(it.source)) } else emptyList()
  val list: List<Item> = if (pending.isEmpty()) items else {
    val todayKey = "day${data.today}"
    val today = items.firstOrNull { it.key == todayKey } as? Item.Day ?: Item.Day(todayKey, "Today")
    listOf(today) + pending.map { Item.Note("note${it.id}", it) } + items.filter { it.key != todayKey }
  }
  val open = page?.transactions?.firstOrNull { it.id == openId }
  val shown = if (reviewing) notes.size else page?.total ?: 0

  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = {
      scope.launch {
        refreshing = true
        SyncWorker.request(context, delaySeconds = 0)
        delay(700)
        refreshing = false
      }
    },
    modifier = Modifier.fillMaxSize(),
  ) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
      Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
          Label("Activity", Modifier.weight(1f), size = 28.sp, weight = FontWeight.Bold)
          Label(
            if (reviewing) "$shown to check" else if (shown == 1) "1 entry" else "$shown entries",
            Modifier.padding(bottom = 6.dp), size = 13.sp, tone = Tone.MUTED,
          )
        }
        AnimatedVisibility(!reviewing) {
          Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Label("−${Format.digits(page?.spent ?: 0)}", size = 14.sp, weight = FontWeight.Medium, tone = Tone.ACCENT)
            Label("+${Format.digits(page?.received ?: 0)}", size = 14.sp, weight = FontWeight.Medium, tone = Tone.INCOME)
          }
        }
      }

      SearchField(query, { vm.query.value = it }, Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = 16.dp))
      FilterStrip(filter, data.categories, toCheck) { vm.setFilter(it); fixId = null }
      Rule()

      val listState = rememberLazyListState()
      when {
        reviewing && notes.isEmpty() -> Label(if (toCheck == 0) "Nothing to check." else "Nothing here.", Modifier.padding(start = Space.page, top = 24.dp), tone = Tone.MUTED)
        reviewing -> LazyColumn(
          Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = Space.page, end = Space.page, top = 16.dp, bottom = Space.navClearance),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          items(notes, key = { it.id }) { n ->
            UnparsedCard(n, apps[n.source], vm.label(n.source), fixing = n.id == fixId, Modifier.animateItem())
          }
        }
        list.isEmpty() -> Label(if (filter == "all" && q.isEmpty()) "No transactions yet." else "Nothing here.", Modifier.padding(start = Space.page, top = 24.dp), tone = Tone.MUTED)
        else -> LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = Space.navClearance)) {
          list.forEach { item ->
            when (item) {
              // Day headers stick while their day scrolls past, so you always know which day you're in.
              is Item.Day -> stickyHeader(key = item.key, contentType = "day") { DayHeader(item, maxDay) }
              is Item.Note -> item(key = item.key, contentType = "note") {
                UnparsedCard(item.n, apps[item.n.source], vm.label(item.n.source), fixing = false, Modifier.animateItem().padding(horizontal = Space.page, vertical = 6.dp))
              }
              is Item.Tx -> item(key = item.key, contentType = "tx") {
                TxRow(item.tx, item.pair, vm::label, vm::title, Modifier.animateItem()) { openId = item.tx.id }
              }
              is Item.More -> item(key = item.key, contentType = "more") {
                LaunchedEffect(item.key) { vm.more() }
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                  CircularProgressIndicator(Modifier.size(20.dp), color = Ember.muted, strokeWidth = 2.dp)
                }
              }
            }
          }
        }
      }
    }
  }

  if (open != null) TxSheet(open, data.categories, vm::label, vm::title, onClose = { openId = null })
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
  Row(
    modifier.clip(RoundedCornerShape(28.dp)).background(Ember.surface).padding(horizontal = 16.dp, vertical = 14.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(Icons.Rounded.Search, null, tint = Ember.muted, modifier = Modifier.size(20.dp))
    Box(Modifier.weight(1f)) {
      if (value.isEmpty()) Label("Search", tone = Tone.MUTED)
      BasicTextField(
        value, onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Satoshi, fontSize = 16.sp, color = Ember.text),
        cursorBrush = SolidColor(Ember.accent),
        modifier = Modifier.fillMaxWidth(),
      )
    }
    AnimatedVisibility(value.isNotEmpty()) {
      Icon(Icons.Rounded.Close, "Clear", tint = Ember.muted, modifier = Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { onChange("") })
    }
  }
}

@Composable
private fun FilterStrip(filter: String, categories: List<Category>, toCheck: Int, onPick: (String) -> Unit) {
  val options = buildList {
    add("all" to "All")
    // Only there while something waits, or while it's the open filter.
    if (toCheck > 0 || filter == "review") add("review" to if (toCheck > 0) "To check $toCheck" else "To check")
    add("out" to "Spent"); add("in" to "Received"); add("transfer" to "Transfers")
    categories.forEach { add("cat:${it.id}" to it.name) }
    add("cat:none" to "No category")
  }
  val state = rememberLazyListState()
  // Keep the open filter in view (Overview can land here on a category far down the strip).
  val index by remember(options, filter) { derivedStateOf { options.indexOfFirst { it.first == filter }.coerceAtLeast(0) } }
  LaunchedEffect(index) { state.animateScrollToItem(index) }
  LazyRow(
    state = state,
    contentPadding = PaddingValues(horizontal = Space.page, vertical = 14.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    items(options, key = { it.first }) { (value, label) -> Chip(label, value == filter, Modifier.animateItem()) { onPick(value) } }
  }
}

/** Day label and total, with a hairline bar showing how heavy the day was against the busiest one listed. */
@Composable
private fun DayHeader(day: Item.Day, max: Long) {
  val fraction by animateFloatAsState(if (day.spent > 0) maxOf(day.spent.toFloat() / max, 0.01f) else 0f, tween(500), label = "day")
  Column(Modifier.fillMaxWidth().background(Ember.bg).padding(start = Space.page, end = Space.page, top = 24.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Label(day.label, Modifier.weight(1f), size = 13.sp, weight = FontWeight.Bold)
      if (day.received > 0) Label("+${Format.digits(day.received)}", size = 13.sp, weight = FontWeight.Medium, tone = Tone.INCOME)
      if (day.spent > 0) Label("−${Format.digits(day.spent)}", size = 13.sp, weight = FontWeight.Medium, tone = Tone.ACCENT)
    }
    Box(Modifier.fillMaxWidth().height(2.dp).background(Ember.line)) {
      Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(Ember.accent))
    }
  }
}

/** One ledger line: time, who, how it was filed, amount. [pair] merges both halves of an own transfer. */
@Composable
private fun TxRow(tx: Transaction, pair: Transaction?, label: (String) -> String, title: (Transaction) -> String, modifier: Modifier, onPress: () -> Unit) {
  val name: String
  val meta: String
  var amount = Format.signed(tx.amount, tx.direction)
  var tone = if (tx.direction == Direction.IN) Tone.INCOME else Tone.ACCENT
  when {
    pair != null -> {
      name = "${label(tx.source)} → ${label(pair.source)}"
      meta = "Own transfer"
      amount = Format.digits(tx.amount)
      tone = Tone.MUTED
    }
    tx.isTransfer -> {
      name = title(tx)
      meta = "Transfer  ·  ${label(tx.source)}"
      tone = Tone.MUTED
    }
    else -> {
      name = title(tx)
      meta = "${tx.categoryName ?: if (tx.direction == Direction.IN) "Income" else "No category"}  ·  ${label(tx.source)}"
    }
  }
  Row(
    modifier.fillMaxWidth().clickable(onClick = onPress).padding(horizontal = Space.page, vertical = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Label(Format.time(tx.occurredAt), Modifier.width(38.dp).padding(top = 2.dp), size = 12.sp, weight = FontWeight.Medium, tone = Tone.MUTED)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Label(name, weight = FontWeight.Medium, maxLines = 1)
      Label(meta, size = 13.sp, tone = Tone.MUTED, maxLines = 1)
    }
    Label(amount, weight = FontWeight.Bold, tone = tone)
  }
}

@Composable
fun AppIcon(app: InstalledApp?, label: String, size: Int = 28) {
  val bitmap = remember(app?.packageName) { runCatching { app?.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull() }
  if (bitmap != null) {
    androidx.compose.foundation.Image(bitmap, null, Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)))
  } else {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(50)).background(Ember.raised), contentAlignment = Alignment.Center) {
      Label(label.take(1).uppercase(), size = 13.sp, weight = FontWeight.Bold, tone = Tone.MUTED)
    }
  }
}


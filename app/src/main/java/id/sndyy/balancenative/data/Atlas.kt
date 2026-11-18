package id.sndyy.balancenative.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

/** Calendar units: presets select one, and ‹ › step by one. A dragged range has none and steps by its length. */
enum class Span(val last: String) { WEEK("last week"), MONTH("last month"), QUARTER("last quarter"), YEAR("last year") }

enum class Preset(val label: String, val span: Span) { WEEK("Week", Span.WEEK), MONTH("Month", Span.MONTH), QUARTER("Quarter", Span.QUARTER), YEAR("Year", Span.YEAR) }

/** What the grid shows. The view follows the selection; nobody picks it directly. */
enum class AtlasView(val weeks: Int) { WEEKS(20), DAYS(8), YEAR(53) }

/** Any run of days, inclusive. [span] is set when it is one calendar unit, [preset] while it is the live preset. */
data class Selection(val start: LocalDate, val end: LocalDate, val span: Span? = null, val preset: Preset? = null) {
  val days: Int get() = ChronoUnit.DAYS.between(start, end).toInt() + 1
  operator fun contains(d: LocalDate) = !d.isBefore(start) && !d.isAfter(end)
}

data class RangeStats(
  val selection: Selection,
  val spent: Long,
  val received: Long,
  val payments: Int,
  val categories: List<Share>,
  val sources: List<Share>,
  val biggest: Flow?,
  val regular: Share?,
  /** Spending over the comparison range, or null with nothing earlier to compare. */
  val baseSpent: Long?,
  /** Finishes "8% under …". */
  val baseLabel: String,
  /** Length of the comparison range, for its per-day rate. */
  val baseDays: Int?,
  /** Days in the selection (up to today) with any spending. */
  val spendDays: Int,
  val chart: ChartSeries,
  /** Day or "Week of" start for each chart slot, so a scrub can point at the grid. */
  val chartDays: List<LocalDate>,
) {
  val perDay: Double get() = spent.toDouble() / max(1, selection.days)
  val basePerDay: Double? get() = baseSpent?.let { it.toDouble() / max(1, baseDays ?: 1) }
  val pace: Double? get() = baseSpent?.takeIf { it > 0 }?.let { (spent - it).toDouble() / it }
}

fun monday(d: LocalDate): LocalDate = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

private fun unitStart(d: LocalDate, span: Span): LocalDate = when (span) {
  Span.WEEK -> monday(d)
  Span.MONTH -> d.withDayOfMonth(1)
  Span.QUARTER -> LocalDate.of(d.year, (d.monthValue - 1) / 3 * 3 + 1, 1)
  Span.YEAR -> LocalDate.of(d.year, 1, 1)
}

private fun shift(d: LocalDate, span: Span, n: Long): LocalDate = when (span) {
  Span.WEEK -> d.plusWeeks(n)
  Span.MONTH -> d.plusMonths(n)
  Span.QUARTER -> d.plusMonths(3 * n)
  Span.YEAR -> d.plusYears(n)
}

private fun unitEnd(start: LocalDate, span: Span): LocalDate = shift(start, span, 1).minusDays(1)

/**
 * Every day from [first] to [today] with spend, income and its flows, plus prefix sums so any range
 * totals in O(1). Built once per ledger read off the main thread; selection changes only read it.
 */
class Book(val first: LocalDate, val today: LocalDate, flows: List<Flow>) {
  val size: Int = ChronoUnit.DAYS.between(first, today).toInt() + 1
  private val spentDay = LongArray(size)
  private val receivedDay = LongArray(size)
  private val byDay: Array<MutableList<Flow>> = Array(size) { mutableListOf() }
  private val spentSum = LongArray(size + 1)
  private val receivedSum = LongArray(size + 1)
  /** The first day anything was logged. Cells before it are drawn blank, not as "no spend". */
  val firstData: LocalDate?
  /** Upper bounds of heat levels 1-3 (quartiles of spending days in the last 20 weeks), shared by every view. */
  private val bounds: LongArray

  init {
    var earliest: LocalDate? = null
    for (f in flows) {
      val d = Format.date(f.at)
      val i = indexOf(d)
      if (i !in 0 until size) continue
      if (earliest == null || d.isBefore(earliest)) earliest = d
      byDay[i].add(f)
      if (f.direction == Direction.OUT) spentDay[i] += f.amount else receivedDay[i] += f.amount
    }
    firstData = earliest
    for (i in 0 until size) {
      spentSum[i + 1] = spentSum[i] + spentDay[i]
      receivedSum[i + 1] = receivedSum[i] + receivedDay[i]
    }
    // Same quartile rule as the widget (WidgetBridge.levelsFor), over the default 20-week window.
    val from = max(0, indexOf(monday(today).minusWeeks(19)))
    val spending = (from until size).map { spentDay[it] }.filter { it > 0 }.sorted()
    bounds = if (spending.isEmpty()) longArrayOf(0, 0, 0)
    else LongArray(3) { q -> spending[((spending.size - 1) * (q + 1) / 4.0).toInt()] }
  }

  fun indexOf(d: LocalDate): Int = ChronoUnit.DAYS.between(first, d).toInt()

  fun spentOn(d: LocalDate): Long = indexOf(d).let { if (it in 0 until size) spentDay[it] else 0 }

  fun flowsOn(d: LocalDate): List<Flow> = indexOf(d).let { if (it in 0 until size) byDay[it] else emptyList() }

  /** -2 before any data, -1 in the future, 0 no spend, 1-4 heat. */
  fun level(d: LocalDate): Int {
    if (d.isAfter(today)) return -1
    if (firstData == null || d.isBefore(firstData) || d.isBefore(first)) return -2
    val v = spentOn(d)
    return when {
      v <= 0 -> 0
      v <= bounds[0] -> 1
      v <= bounds[1] -> 2
      v <= bounds[2] -> 3
      else -> 4
    }
  }

  private fun clampRange(s: LocalDate, e: LocalDate): Pair<Int, Int> =
    max(0, indexOf(s)) to min(size - 1, indexOf(e))

  fun spent(s: LocalDate, e: LocalDate): Long = clampRange(s, e).let { (a, b) -> if (a > b) 0 else spentSum[b + 1] - spentSum[a] }

  fun received(s: LocalDate, e: LocalDate): Long = clampRange(s, e).let { (a, b) -> if (a > b) 0 else receivedSum[b + 1] - receivedSum[a] }

  // ---- selections ----

  fun preset(p: Preset): Selection = Selection(maxOf(first, unitStart(today, p.span)), today, p.span, p)

  fun week(d: LocalDate): Selection = monday(d).let { Selection(maxOf(first, it), minOf(today, it.plusDays(6)), Span.WEEK) }

  fun month(d: LocalDate): Selection = d.withDayOfMonth(1).let { Selection(maxOf(first, it), minOf(today, unitEnd(it, Span.MONTH)), Span.MONTH) }

  fun day(d: LocalDate): Selection = Selection(d, d)

  fun range(a: LocalDate, b: LocalDate): Selection = Selection(maxOf(first, minOf(a, b)), minOf(today, maxOf(a, b)))

  /** Whole months from [a]'s to [b]'s: how a drag reads on the year view. */
  fun months(a: LocalDate, b: LocalDate): Selection {
    val lo = minOf(a, b).withDayOfMonth(1)
    val hi = maxOf(a, b).let { unitEnd(it.withDayOfMonth(1), Span.MONTH) }
    return Selection(maxOf(first, lo), minOf(today, hi), if (lo.month == hi.month && lo.year == hi.year) Span.MONTH else null)
  }

  /** ‹ › : calendar units step by unit, dragged ranges by their own length. Null at the ends of the data. */
  fun step(sel: Selection, dir: Int): Selection? {
    val (s, e) = if (sel.span != null) {
      val s = shift(unitStart(sel.start, sel.span), sel.span, dir.toLong())
      s to unitEnd(s, sel.span)
    } else {
      val n = sel.days.toLong() * dir
      sel.start.plusDays(n) to sel.end.plusDays(n)
    }
    if (s.isAfter(today) || e.isBefore(first)) return null
    return Selection(maxOf(first, s), minOf(today, e), sel.span)
  }

  /** Presets and stepped units compare with the unit before, to the same point; dragged ranges with the same number of days just before. */
  private fun baseline(sel: Selection): Pair<Selection, String>? {
    val b = if (sel.span != null) {
      val s = shift(unitStart(sel.start, sel.span), sel.span, -1)
      Selection(s, minOf(s.plusDays(sel.days - 1L), unitEnd(s, sel.span))) to sel.span.last
    } else {
      val s = sel.start.minusDays(sel.days.toLong())
      Selection(s, sel.start.minusDays(1)) to if (sel.days == 1) "the day before" else "the days before"
    }
    return b.takeIf { !it.first.start.isBefore(first) && (firstData == null || !it.first.end.isBefore(firstData)) }
  }

  fun stats(sel: Selection): RangeStats {
    val (a, b) = clampRange(sel.start, sel.end)
    val out = ArrayList<Flow>()
    val income = ArrayList<Flow>()
    for (i in a..b) for (f in byDay[i]) if (f.direction == Direction.OUT) out.add(f) else income.add(f)
    val base = baseline(sel)
    val weekly = sel.days > 70
    val (cur, curDays) = series(sel.start, sel.end, weekly)
    val prev = base?.let { series(it.first.start, it.first.end, weekly).first } ?: emptyList()
    fun name(d: LocalDate) = if (weekly) "Week of ${d.dayOfMonth} ${Format.shortMonth(d)}" else Format.shortDate(d)
    val n = cur.size
    val ticks = List(n) { i -> if (i == 0 || i == n - 1 || (n > 6 && i == n / 2)) "${curDays[i].dayOfMonth} ${Format.shortMonth(curDays[i])}" else "" }
    return RangeStats(
      selection = sel,
      spent = spent(sel.start, sel.end),
      received = received(sel.start, sel.end),
      payments = out.size,
      categories = group(out) { Triple(it.categoryId ?: "none", it.categoryId, it.categoryName ?: "No category") },
      sources = group(income) { Triple(it.title.lowercase(), null, it.title) },
      biggest = out.maxByOrNull { it.amount },
      regular = group(out) { Triple(it.title.lowercase(), null, it.title) }.maxByOrNull { it.count }?.takeIf { it.count > 1 },
      baseSpent = base?.let { spent(it.first.start, it.first.end) },
      baseLabel = base?.second ?: "",
      baseDays = base?.first?.days,
      spendDays = (a..b).count { spentDay[it] > 0 },
      chart = ChartSeries(
        current = cur,
        previous = prev,
        ticks = ticks,
        readoutLabels = curDays.map(::name),
        readoutValues = cur.map(Format::rupiah),
      ),
      chartDays = curDays,
    )
  }

  private fun series(s: LocalDate, e: LocalDate, weekly: Boolean): Pair<List<Long>, List<LocalDate>> {
    val step = if (weekly) 7L else 1L
    val values = ArrayList<Long>()
    val days = ArrayList<LocalDate>()
    var d = s
    while (!d.isAfter(e)) {
      val end = minOf(e, d.plusDays(step - 1))
      values.add(spent(d, end))
      days.add(d)
      d = d.plusDays(step)
    }
    return values to days
  }

  private fun group(flows: List<Flow>, keyOf: (Flow) -> Triple<String, String?, String>): List<Share> {
    val map = LinkedHashMap<String, Share>()
    var total = 0L
    for (f in flows) {
      val (key, id, name) = keyOf(f)
      val e = map[key] ?: Share(key, id, name, 0, 0, 0.0)
      map[key] = e.copy(amount = e.amount + f.amount, count = e.count + 1)
      total += f.amount
    }
    return map.values.map { it.copy(share = if (total > 0) it.amount.toDouble() / total else 0.0) }.sortedByDescending { it.amount }
  }

  companion object {
    /** Far enough back for the year view (53 weeks) and for comparing this year with last. */
    fun firstDay(today: LocalDate): LocalDate = minOf(monday(today).minusWeeks(52), LocalDate.of(today.year - 1, 1, 1))
  }
}

/** Which view a selection belongs in, given the view it came from. */
fun viewFor(sel: Selection, current: AtlasView): AtlasView = when {
  sel.span == Span.YEAR || sel.days > AtlasView.WEEKS.weeks * 7 -> AtlasView.YEAR
  current == AtlasView.YEAR -> AtlasView.WEEKS
  current == AtlasView.DAYS && sel.days > AtlasView.DAYS.weeks * 7 -> AtlasView.WEEKS
  else -> current
}

package id.sndyy.balancenative.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** `current`/`previous` name the two lines; `compare` finishes "12% under …". Mirrors src/lib/analytics.ts. */
enum class Period(val label: String, val current: String, val previous: String, val compare: String, val unit: String) {
  WEEK("Week", "This week", "Last week", "last week so far", "Day by day"),
  MONTH("30D", "Last 30 days", "30 days before", "the 30 days before", "Day by day"),
  QUARTER("3M", "Last 13 weeks", "13 weeks before", "the 13 weeks before", "Week by week"),
  YEAR("Year", "This year", "Last year", "last year so far", "Month by month"),
}

data class Slot(val start: Long, val end: Long, val tick: String, val name: String)

data class Share(val key: String, val id: String?, val name: String, val amount: Long, val count: Int, val share: Double)

data class ChartSeries(
  val current: List<Long>,
  val previous: List<Long>,
  val ticks: List<String>,
  val readoutLabels: List<String>,
  val readoutValues: List<String>,
)

data class Stats(val perDay: Double, val biggest: Flow?, val payments: Int, val regular: Share?)

data class Analysis(
  val period: Period,
  val slots: List<Slot>,
  val nowIndex: Int,
  val spent: Long,
  val received: Long,
  /** Spent vs the same point last period, as a fraction (0.12 = 12% more). Null with nothing to compare. */
  val paceDelta: Double?,
  val hasPrevious: Boolean,
  val line: ChartSeries,
  val incomeSlots: List<Long>,
  val categories: List<Share>,
  val sources: List<Share>,
  val stats: Stats,
)

object Analytics {
  private const val DAY_MS = 86_400_000L

  private fun ms(date: LocalDate, zone: ZoneId) = date.atStartOfDay(zone).toInstant().toEpochMilli()

  private fun monday(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

  private fun label(d: LocalDate) = "${d.dayOfMonth} ${Format.shortMonth(d)}"

  /** The slots for the period containing [now], shifted back [back] periods. */
  fun slots(period: Period, now: LocalDateTime, back: Int, zone: ZoneId = ZoneId.systemDefault()): List<Slot> {
    val today = now.toLocalDate()
    return when (period) {
      Period.WEEK -> {
        val start = monday(today).minusWeeks(back.toLong())
        List(7) { i ->
          val day = start.plusDays(i.toLong())
          Slot(ms(day, zone), ms(day.plusDays(1), zone), "MTWTFSS"[i].toString(), Format.shortDate(day))
        }
      }
      Period.MONTH -> {
        // Rolling: the last slot is always today.
        val start = today.minusDays(29L + 30L * back)
        List(30) { i ->
          val day = start.plusDays(i.toLong())
          Slot(ms(day, zone), ms(day.plusDays(1), zone), if (i in setOf(0, 10, 20, 29)) label(day) else "", Format.shortDate(day))
        }
      }
      Period.QUARTER -> {
        val start = monday(today).minusWeeks(12L + 13L * back)
        List(13) { i ->
          val week = start.plusWeeks(i.toLong())
          Slot(ms(week, zone), ms(week.plusWeeks(1), zone), if (i % 4 == 0) label(week) else "", "Week of ${label(week)}")
        }
      }
      Period.YEAR -> {
        val year = today.year - back
        List(12) { m ->
          val first = LocalDate.of(year, m + 1, 1)
          Slot(ms(first, zone), ms(first.plusMonths(1), zone), Format.shortMonth(first).take(1), "${Format.monthName(first)} $year")
        }
      }
    }
  }

  /** Earliest moment any period view needs: the start of last year, or the previous 3M window if that's earlier. */
  fun start(now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Long {
    val lastYear = ms(LocalDate.of(now.year - 1, 1, 1), zone)
    return min(lastYear, slots(Period.QUARTER, now, 1, zone).first().start)
  }

  private fun bucket(flows: List<Flow>, slots: List<Slot>, direction: Direction): LongArray {
    val sums = LongArray(slots.size)
    var s = 0
    for (f in flows) {
      if (f.direction != direction) continue
      while (s < slots.size && f.at >= slots[s].end) s++
      if (s >= slots.size) break
      if (f.at >= slots[s].start) sums[s] += f.amount
    }
    return sums
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

  /** [all] must be ordered by time, so each bucket pass is a single sweep. */
  fun analyze(all: List<Flow>, period: Period, now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = ZoneId.systemDefault()): Analysis {
    val nowMs = now.atZone(zone).toInstant().toEpochMilli()
    val slots = slots(period, now, 0, zone)
    val prevSlots = slots(period, now, 1, zone)
    val nowIndex = max(0, slots.indexOfFirst { nowMs >= it.start && nowMs < it.end })
    val from = slots.first().start
    val to = slots.last().end

    val inPeriod = all.filter { it.at in from until to }
    val prevPeriod = all.filter { it.at >= prevSlots.first().start && it.at < prevSlots.last().end }
    val out = inPeriod.filter { it.direction == Direction.OUT }
    val income = inPeriod.filter { it.direction == Direction.IN }

    val spendSlots = bucket(inPeriod, slots, Direction.OUT)
    val incomeSlots = bucket(inPeriod, slots, Direction.IN)
    val prevSpend = bucket(prevPeriod, prevSlots, Direction.OUT)
    val prevCum = prevSpend.runningReduce { a, b -> a + b }
    val current = spendSlots.take(nowIndex + 1)
    val spent = current.sum()
    val received = income.sumOf { it.amount }
    // Same point in the last period. Longer months just repeat their final total.
    val prevAtNow = prevCum.getOrNull(min(nowIndex, prevCum.size - 1)) ?: 0L
    val previous = slots.indices.map { prevSpend.getOrElse(it) { 0L } }

    val elapsedDays = max(1.0, ceil((min(nowMs, to) - from).toDouble() / DAY_MS))
    val biggest = out.maxByOrNull { it.amount }
    val regular = group(out) { Triple(it.title.lowercase(), null, it.title) }.maxByOrNull { it.count }

    return Analysis(
      period = period,
      slots = slots,
      nowIndex = nowIndex,
      spent = spent,
      received = received,
      paceDelta = if (prevAtNow > 0) (spent - prevAtNow).toDouble() / prevAtNow else null,
      hasPrevious = prevPeriod.any { it.direction == Direction.OUT },
      line = ChartSeries(
        current = current,
        previous = previous,
        ticks = slots.map { it.tick },
        readoutLabels = slots.mapIndexed { i, s -> if (i <= nowIndex) s.name else prevSlots[min(i, prevSlots.size - 1)].name },
        readoutValues = slots.indices.map { i -> Format.rupiah(if (i <= nowIndex) current[i] else previous[i]) },
      ),
      incomeSlots = incomeSlots.toList(),
      categories = group(out) { Triple(it.categoryId ?: "none", it.categoryId, it.categoryName ?: "No category") },
      sources = group(income) { Triple(it.title.lowercase(), null, it.title) },
      stats = Stats(
        perDay = spent / elapsedDays,
        biggest = biggest,
        payments = out.size,
        regular = regular?.takeIf { it.count > 1 },
      ),
    )
  }
}

data class HeatCell(
  val date: LocalDate,
  val amount: Long,
  /** 0 = no spend, 1-4 = quartile of spending days, -1 = in the future. */
  val level: Int,
)

/** Monday-first week columns ending with the current week. Mirrors WidgetBridge.kt so the app and widget agree. */
object Heatmap {
  fun start(weeks: Int, today: LocalDate = LocalDate.now()): LocalDate =
    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks((weeks - 1).toLong())

  fun build(spend: List<Flow>, weeks: Int, today: LocalDate = LocalDate.now()): List<List<HeatCell>> {
    val totals = HashMap<LocalDate, Long>()
    for (s in spend) if (s.direction == Direction.OUT) totals.merge(Format.date(s.at), s.amount, Long::plus)

    val first = start(weeks, today)
    val days = List(weeks * 7) { i -> first.plusDays(i.toLong()) }
    val sorted = days.filter { !it.isAfter(today) }.mapNotNull { totals[it]?.takeIf { a -> a > 0 } }.sorted()
    fun q(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
    fun levelOf(amount: Long) = when {
      amount <= 0 || sorted.isEmpty() -> 0
      amount <= q(0.25) -> 1
      amount <= q(0.5) -> 2
      amount <= q(0.75) -> 3
      else -> 4
    }
    return days.chunked(7).map { week ->
      week.map { d ->
        val amount = totals[d] ?: 0
        HeatCell(d, amount, if (d.isAfter(today)) -1 else levelOf(amount))
      }
    }
  }
}

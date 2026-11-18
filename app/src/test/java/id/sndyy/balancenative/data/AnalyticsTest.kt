package id.sndyy.balancenative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class AnalyticsTest {
  private val zone = ZoneId.of("Asia/Jakarta")
  // A Wednesday.
  private val now = LocalDateTime.of(2026, 11, 18, 15, 0)

  private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
  private fun out(amount: Long, at: Long, title: String = "Kopi", cat: String? = "food") = Flow(Direction.OUT, amount, cat, cat, title, at)
  private fun inc(amount: Long, at: Long, title: String = "Salary") = Flow(Direction.IN, amount, null, null, title, at)

  @Test fun weekSlotsStartMonday() {
    val slots = Analytics.slots(Period.WEEK, now, 0, zone)
    assertEquals(7, slots.size)
    assertEquals(at(2026, 11, 16, 0), slots.first().start)
    assertEquals("M", slots.first().tick)
  }

  @Test fun monthIsRollingAndEndsToday() {
    val slots = Analytics.slots(Period.MONTH, now, 0, zone)
    assertEquals(30, slots.size)
    assertEquals(at(2026, 11, 19, 0), slots.last().end)
  }

  @Test fun weekTotalsAndPace() {
    val flows = listOf(
      out(10_000, at(2026, 11, 10)), // last Tuesday
      out(5_000, at(2026, 11, 13)), // last Friday, after "now" in last week's terms
      out(20_000, at(2026, 11, 16)),
      out(4_000, at(2026, 11, 18), title = "Warung"),
      inc(100_000, at(2026, 11, 17)),
    ).sortedBy { it.at }
    val a = Analytics.analyze(flows, Period.WEEK, now, zone)
    assertEquals(2, a.nowIndex)
    assertEquals(24_000L, a.spent)
    assertEquals(100_000L, a.received)
    // 24k vs 10k by the same weekday last week.
    assertEquals(1.4, a.paceDelta!!, 1e-9)
    assertEquals(listOf(20_000L, 0L, 4_000L), a.line.current)
    assertEquals(2, a.stats.payments)
    assertEquals(20_000L, a.stats.biggest!!.amount)
    assertNull("one payment per name is not 'most often'", a.stats.regular)
    assertEquals(1, a.categories.size)
    assertEquals(1.0, a.categories.first().share, 1e-9)
  }

  @Test fun heatLevelsAreQuartilesOfSpendingDays() {
    val today = LocalDate.of(2026, 11, 18)
    val flows = (1L..8L).map { i -> out(i * 1_000, at(2026, 11, (10 + i).toInt().coerceAtMost(18))) }
    val grid = Heatmap.build(flows, 2, today)
    assertEquals(2, grid.size)
    assertEquals(LocalDate.of(2026, 11, 9), grid[0][0].date)
    assertEquals(-1, grid[1][3].level) // Thursday, future
    assertEquals(0, grid[0][0].level)
  }

  @Test fun formats() {
    assertEquals("1.284.500", Format.digits(1_284_500))
    assertEquals("Rp 0", Format.rupiah(0))
    assertEquals("1,3jt", Format.compact(1_250_000))
    assertEquals("45rb", Format.compact(45_000))
  }
}

package id.sndyy.balancenative.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AtlasTest {
  private val today = LocalDate.of(2026, 11, 18) // a Wednesday

  private fun flow(d: LocalDate, amount: Long, dir: Direction = Direction.OUT, cat: String = "food") =
    Flow(dir, amount, cat, cat, "Shop", Format.startOfDayMs(d) + 3_600_000)

  private val flows = listOf(
    flow(LocalDate.of(2026, 10, 1), 100_000),
    flow(LocalDate.of(2026, 10, 2), 50_000),
    flow(LocalDate.of(2026, 11, 1), 3_500_000, cat = "bills"),
    flow(LocalDate.of(2026, 11, 2), 40_000),
    flow(LocalDate.of(2026, 11, 10), 2_000_000, Direction.IN),
  )

  // The book is built from per-day totals, as BalanceCore.dailyTotals sums them in SQL.
  private val days = flows.groupBy { Format.date(it.at) }.map { (d, fs) ->
    DayTotal(d, fs.filter { it.direction == Direction.OUT }.sumOf { it.amount }, fs.filter { it.direction == Direction.IN }.sumOf { it.amount })
  }.sortedBy { it.day }

  private val book = Book(Book.firstDay(today, days.first().day), today, days)

  private fun stats(sel: Selection) = book.stats(sel, flows)

  @Test fun monthPresetComparesWithLastMonthToTheSameDay() {
    val s = stats(book.preset(Preset.MONTH))
    assertEquals(LocalDate.of(2026, 11, 1), s.selection.start)
    assertEquals(3_540_000, s.spent)
    assertEquals(2_000_000, s.received)
    assertEquals(150_000L, s.baseSpent)
    assertEquals("last month", s.baseLabel)
  }

  @Test fun presetStepsByCalendarMonth() {
    val oct = book.step(book.preset(Preset.MONTH), -1)!!
    assertEquals(LocalDate.of(2026, 10, 1), oct.start)
    assertEquals(LocalDate.of(2026, 10, 31), oct.end)
    assertNull(book.step(book.preset(Preset.MONTH), 1))
  }

  @Test fun draggedRangeComparesWithTheDaysJustBefore() {
    val sel = book.range(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1))
    assertEquals(2, sel.days)
    val s = stats(sel)
    assertEquals(LocalDate.of(2026, 11, 1), sel.start)
    assertEquals(0L, s.baseSpent) // 30-31 Oct
    assertEquals(LocalDate.of(2026, 11, 3), book.step(sel, 1)!!.start)
  }

  @Test fun weekTapClampsToToday() {
    val w = book.week(today)
    assertEquals(LocalDate.of(2026, 11, 16), w.start)
    assertEquals(today, w.end)
  }

  @Test fun levelsMarkFutureAndBeforeData() {
    assertEquals(-1, book.level(today.plusDays(1)))
    assertEquals(-2, book.level(LocalDate.of(2026, 9, 1)))
    assertEquals(0, book.level(LocalDate.of(2026, 11, 3)))
  }

  @Test fun yearViewFollowsTheYearPreset() {
    assertEquals(AtlasView.YEAR, viewFor(book.preset(Preset.YEAR), AtlasView.WEEKS))
    assertEquals(AtlasView.WEEKS, viewFor(book.preset(Preset.MONTH), AtlasView.YEAR))
  }
}

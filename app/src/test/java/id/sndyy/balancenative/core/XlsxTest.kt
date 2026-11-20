package id.sndyy.balancenative.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for android.util.Xml, which is a stub on the plain JVM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class XlsxTest {
  private fun sheet(rows: String) =
    """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rows</sheetData></worksheet>""".toByteArray()

  private fun cell(ref: String, v: String = "1") = """<c r="$ref"><v>$v</v></c>"""

  @Test fun readsAnOrdinarySheet() {
    val rows = Xlsx.sheetRows(sheet("""<row r="1">${cell("A1", "5")}${cell("C1", "7")}</row><row r="3">${cell("B3", "2")}</row>"""), emptyList())
    assertEquals(listOf(5.0, null, 7.0), rows[0])
    assertEquals(emptyList<Any?>(), rows[1])
    assertEquals(listOf(null, 2.0), rows[2])
  }

  @Test fun rowPastExcelsLastIsRejected() {
    assertThrows(Xlsx.FormatException::class.java) { Xlsx.sheetRows(sheet("""<row r="999999999">${cell("A999999999")}</row>"""), emptyList()) }
  }

  @Test fun columnPastXfdIsRejectedWithoutOverflow() {
    assertThrows(Xlsx.FormatException::class.java) { Xlsx.sheetRows(sheet("""<row r="1">${cell("XFE1")}</row>"""), emptyList()) }
    assertThrows(Xlsx.FormatException::class.java) { Xlsx.sheetRows(sheet("""<row r="1">${cell("ZZZZZZZZZZ1")}</row>"""), emptyList()) }
  }

  @Test fun aFarRowWithOneCellIsCheap() {
    val rows = Xlsx.sheetRows(sheet("""<row r="1000000">${cell("A1000000")}</row>"""), emptyList())
    assertEquals(1_000_000, rows.size)
    assertEquals(listOf(1.0), rows.last())
  }

  @Test fun manyWideRowsHitTheCellBudget() {
    val wide = (1..300).joinToString("") { """<row r="$it">${cell("XFC$it")}</row>""" }
    assertThrows(Xlsx.FormatException::class.java) { Xlsx.sheetRows(sheet(wide), emptyList()) }
  }
}

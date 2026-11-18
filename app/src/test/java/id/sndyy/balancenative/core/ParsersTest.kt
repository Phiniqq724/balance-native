package id.sndyy.balancenative.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Real notifications from notif-samples.txt plus the wordings documented in parse_rules.json. */
class ParsersTest {
  private val rules = Parsers.compile(File("src/main/assets/parse_rules.json").readText())

  private val jago = "com.jago.digitalBanking"
  private val mybca = "com.bca.mybca.omni.android"
  private val bcaMobile = "com.bca"

  private fun tx(pkg: String, body: String): ParsedTransaction {
    val result = Parsers.parse(rules, pkg, null, body, generic = false)
    assertTrue("expected a transaction for: $body, got $result", result is ParseResult.Transaction)
    return (result as ParseResult.Transaction).tx
  }

  @Test fun jagoPaidSplitsMerchantType() {
    val t = tx(jago, "You have paid Rp 1.000 to FAHRELL S​. Z​. W​., Perleng. Need help? Contact Tanya Jago at 1500 746.")
    assertEquals(ParsedTransaction(Direction.OUT, 1000, "FAHRELL S. Z. W.", "Perleng"), t)
  }

  @Test fun jagoTransferOut() {
    val t = tx(jago, "You've transferred Rp10.000 to FAHRELL SANDY ZHARIIF WI. Need help? Contact Tanya Jago at 1500 746.")
    assertEquals(ParsedTransaction(Direction.OUT, 10_000, "FAHRELL SANDY ZHARIIF WI"), t)
  }

  @Test fun jagoReceived() {
    val t = tx(jago, "FAHRELL SANDY ZHARIIF WI has sent Rp10.000 to you. Need help? Contact Tanya Jago at 1500 746.")
    assertEquals(ParsedTransaction(Direction.IN, 10_000, "FAHRELL SANDY ZHARIIF WI"), t)
  }

  @Test fun jagoDottedMerchant() {
    assertEquals("DUNIA ELEKTRIK", tx(jago, "You've transferred Rp25.000 to .DUNIA ELEKTRIK..").counterparty)
  }

  @Test fun jagoPocketMovesAreNotMoney() {
    assertEquals(ParseResult.Ignored, Parsers.parse(rules, jago, null, "Rp50.000 has been moved from your Main Pocket to your Savings."))
    assertEquals(ParseResult.Ignored, Parsers.parse(rules, jago, null, "You've moved Rp50.000 out of your Savings."))
  }

  @Test fun jagoIndonesian() {
    assertEquals(ParsedTransaction(Direction.OUT, 50_000, "BUDI"), tx(jago, "Kamu telah melakukan transfer Rp50.000 ke BUDI."))
    assertEquals(ParsedTransaction(Direction.OUT, 20_000, "DNID BUDI"), tx(jago, "Kamu telah melakukan top up Rp20.000 ke DNID BUDI"))
    assertEquals(ParsedTransaction(Direction.OUT, 15_000, "KOPI", "Makan"), tx(jago, "Kamu telah membayar Rp15.000 ke KOPI, Makan"))
  }

  @Test fun myBcaEnglish() {
    assertEquals(ParsedTransaction(Direction.OUT, 1000, null, "Business Expense"), tx(mybca, "You spent IDR 1,000.00 at Business Expense."))
    assertEquals(
      ParsedTransaction(Direction.IN, 10_000, "****ELL **NDY **AR", "Account Transfer"),
      tx(mybca, "You received IDR 10,000.00 from ****ELL **NDY **AR at Account Transfer category."),
    )
  }

  @Test fun bcaMobileTitleInsideText() {
    assertEquals(ParsedTransaction(Direction.OUT, 75_000, null, "Makanan"), tx(bcaMobile, "Financial Diary: Pengeluaran sebesar Rp 75.000 di kategori Makanan"))
    assertEquals(ParsedTransaction(Direction.IN, 2_500, null, "Admin"), tx(bcaMobile, "Financial Diary: Pemasukan sebesar IDR 2,500.00 di kategori Admin"))
  }

  @Test fun rulesOnlyApplyToTheirApp() {
    assertEquals(ParseResult.Unparsed, Parsers.parse(rules, "com.other", null, "You have paid Rp 1.000 to X", generic = false))
  }

  @Test fun genericNeedsOneClearDirection() {
    val out = Parsers.parse(rules, "id.dana", null, "Pembayaran Rp 12.000 berhasil")
    assertEquals(ParseResult.Transaction(ParsedTransaction(Direction.OUT, 12_000)), out)
    assertEquals(ParseResult.Ignored, Parsers.parse(rules, "id.dana", null, "Dapatkan cashback Rp 5.000 untuk pembayaran"))
    assertEquals(ParseResult.Ignored, Parsers.parse(rules, "id.dana", null, "Your OTP is 123456"))
  }

  @Test fun amounts() {
    assertEquals(1000L, Parsers.amount("1.000"))
    assertEquals(1000L, Parsers.amount("1,000.00"))
    assertEquals(10_000L, Parsers.amount("10.000"))
    assertEquals(1_284_500L, Parsers.amount("1.284.500"))
    assertEquals(1_500L, Parsers.amount("1.500,00"))
    assertEquals(null, Parsers.amount(""))
  }

  @Test fun prefilter() {
    assertTrue(Prefilter.isCandidate("GoPay", "Pembayaran 25rb ke Warung berhasil"))
    assertEquals(false, Prefilter.isCandidate(null, "Kode verifikasi Rp 0: 123456 OTP"))
  }
}

package id.sndyy.balancenative.core

import android.content.ContentValues
import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.random.Random

/**
 * Dev and fast builds only: fills the ledger with made-up transactions to see how the app copes with a
 * big history. Seeded rows are stored like restored ones (a hidden empty notification) and tagged
 * through the notification title, so [clear] removes exactly them and nothing real.
 *
 * Local mode only. Sign-in refuses while any are left ([AccountManager.signInWithGoogle]), because
 * signing in merges the local ledger into the account and the sync would push them to Supabase.
 */
internal object DevSeed {
  const val MARK = "__balance_dummy__"

  private class Merchant(val name: String, val min: Long, val max: Long, val weight: Int)

  // Names hit the seed categories' keywords, so most rows get categorized the way real ones would.
  private val OUT = listOf(
    Merchant("GOFOOD Warung Bu Sri", 18_000, 85_000, 9),
    Merchant("Kopi Kenangan", 18_000, 45_000, 7),
    Merchant("Starbucks Senayan", 45_000, 120_000, 3),
    Merchant("Janji Jiwa", 20_000, 40_000, 4),
    Merchant("KFC Kemang", 35_000, 140_000, 3),
    Merchant("GOJEK GoRide", 10_000, 45_000, 8),
    Merchant("Grab Car", 25_000, 160_000, 4),
    Merchant("KRL Commuter Line", 3_000, 15_000, 5),
    Merchant("Pertamina SPBU", 100_000, 400_000, 2),
    Merchant("Indomaret", 12_000, 250_000, 8),
    Merchant("Alfamart", 10_000, 180_000, 6),
    Merchant("Superindo", 80_000, 650_000, 2),
    Merchant("Tokopedia", 45_000, 1_500_000, 3),
    Merchant("Shopee", 25_000, 900_000, 4),
    Merchant("Uniqlo", 150_000, 800_000, 1),
    Merchant("PLN Prabayar", 100_000, 500_000, 1),
    Merchant("Telkomsel", 50_000, 150_000, 1),
    Merchant("Biaya Admin", 2_500, 6_500, 3),
    Merchant("ANDI PRATAMA", 20_000, 1_000_000, 3),
    Merchant("SITI RAHAYU", 50_000, 750_000, 2),
    Merchant("Toko Sinar Jaya", 15_000, 300_000, 3),
  )
  private val SUBSCRIPTIONS = listOf("Netflix" to 186_000L, "Spotify" to 54_990L, "ChatGPT Plus" to 330_000L)
  private val IN = listOf(
    Merchant("BUDI SANTOSO", 50_000, 2_000_000, 3),
    Merchant("DEWI LESTARI", 25_000, 500_000, 2),
    Merchant("Refund Tokopedia", 30_000, 400_000, 1),
  )
  private val SOURCES = listOf("com.jago.digitalBanking" to 60, "com.bca" to 25, Ledger.MANUAL to 15)

  private class Row(val source: String, val direction: String, val amount: Long, val name: String, val at: Long)

  /** Adds [count] rows spread over the last [days] days. Returns how many were written. */
  fun seed(db: BalanceDb, count: Int, days: Int = 3 * 365): Int {
    val rows = generate(count, days)
    val sqlite = db.writableDatabase
    val rules = Ledger.loadRules(db)
    sqlite.beginTransaction()
    try {
      for (r in rows) {
        val rawId = LedgerMerge.insertRestoredRaw(sqlite, UUID.randomUUID().toString(), r.source, r.at) ?: continue
        sqlite.execSQL("UPDATE raw_notifications SET title = ? WHERE id = ?", arrayOf<Any>(MARK, rawId))
        sqlite.insertOrThrow("transactions", null, ContentValues().apply {
          put("raw_id", rawId)
          put("source", r.source)
          put("direction", r.direction)
          put("amount", r.amount)
          put("counterparty", r.name)
          put("category_id", Ledger.categorize(rules, r.name, null))
          put("is_transfer", 0)
          put("occurred_at", r.at)
        })
      }
      sqlite.setTransactionSuccessful()
    } finally {
      sqlite.endTransaction()
    }
    return rows.size
  }

  /** Removes every seeded row. Transactions go with their notification (ON DELETE CASCADE). */
  fun clear(db: BalanceDb): Int {
    val sqlite = db.writableDatabase
    sqlite.beginTransaction()
    try {
      val removed = sqlite.delete("raw_notifications", "title = ?", arrayOf(MARK))
      sqlite.execSQL("UPDATE transactions SET pair_id = NULL WHERE pair_id IS NOT NULL AND pair_id NOT IN (SELECT id FROM transactions)")
      sqlite.setTransactionSuccessful()
      return removed
    } finally {
      sqlite.endTransaction()
    }
  }

  fun count(db: BalanceDb): Int =
    db.readableDatabase.rawQuery("SELECT COUNT(*) FROM raw_notifications WHERE title = ?", arrayOf(MARK)).use {
      if (it.moveToFirst()) it.getInt(0) else 0
    }

  fun count(context: Context): Int = runCatching { count(BalanceDb.get(context)) }.getOrDefault(0)

  private fun generate(count: Int, days: Int): List<Row> {
    val rnd = Random(System.nanoTime())
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val now = System.currentTimeMillis()
    val rows = ArrayList<Row>(count)

    // Monthly fixtures first: a salary on the 25th and the subscriptions, so totals look like a real month.
    val months = (days / 30).coerceAtLeast(1)
    for (m in 0 until months) {
      if (rows.size >= count) break
      val month = today.minusMonths(m.toLong())
      val payday = month.withDayOfMonth(25).atTime(9, rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
      if (payday <= now) rows += Row(pick(SOURCES, rnd), "in", round(rnd.nextLong(8_000_000, 9_500_000)), "PT MAJU JAYA SEJAHTERA", payday)
      SUBSCRIPTIONS.forEachIndexed { i, (name, amount) ->
        val day = month.withDayOfMonth(3 + i * 5).atTime(rnd.nextInt(6, 23), rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
        if (day <= now && rows.size < count) rows += Row("com.jago.digitalBanking", "out", amount, name, day)
      }
    }

    while (rows.size < count) {
      val day = today.minusDays(rnd.nextLong(days.toLong()))
      // Busier around lunch and evening, like real spending.
      val hour = listOf(7, 8, 12, 12, 13, 15, 18, 19, 19, 20, 21, 22).random(rnd)
      val at = day.atTime(hour, rnd.nextInt(60), rnd.nextInt(60)).atZone(zone).toInstant().toEpochMilli()
      if (at > now) continue
      val incoming = rnd.nextInt(100) < 8
      val m = weighted(if (incoming) IN else OUT, rnd)
      rows += Row(pick(SOURCES, rnd), if (incoming) "in" else "out", round(rnd.nextLong(m.min, m.max + 1)), m.name, at)
    }
    return rows
  }

  // Bank amounts are mostly whole hundreds; small fees keep their odd values.
  private fun round(amount: Long): Long = if (amount < 10_000) amount else amount / 500 * 500

  private fun weighted(list: List<Merchant>, rnd: Random): Merchant {
    var n = rnd.nextInt(list.sumOf { it.weight })
    for (m in list) { n -= m.weight; if (n < 0) return m }
    return list.last()
  }

  private fun pick(list: List<Pair<String, Int>>, rnd: Random): String {
    var n = rnd.nextInt(list.sumOf { it.second })
    for ((v, w) in list) { n -= w; if (n < 0) return v }
    return list.last().first
  }
}

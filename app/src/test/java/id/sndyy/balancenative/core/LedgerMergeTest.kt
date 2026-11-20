package id.sndyy.balancenative.core

import android.content.ContentValues
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LedgerMergeTest {
  private val context: Context = RuntimeEnvironment.getApplication()

  private fun raw(db: BalanceDb, uid: String, body: String, at: Long, status: String): Long =
    db.writableDatabase.insertOrThrow("raw_notifications", null, ContentValues().apply {
      put("uid", uid); put("package", "com.jago.digitalBanking"); put("body", body); put("posted_at", at); put("status", status)
    })

  private fun tx(db: BalanceDb, rawId: Long, amount: Long, at: Long) {
    db.writableDatabase.insertOrThrow("transactions", null, ContentValues().apply {
      put("raw_id", rawId); put("source", "com.jago.digitalBanking"); put("direction", "out"); put("amount", amount); put("occurred_at", at)
    })
  }

  private fun count(db: BalanceDb, sql: String): Int = db.readableDatabase.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }

  private fun state(db: BalanceDb, uid: String): String? = db.readableDatabase.rawQuery(
    "SELECT a.state FROM ai_reviews a JOIN raw_notifications r ON r.id = a.raw_id WHERE r.uid = ?", arrayOf(uid)
  ).use { if (it.moveToFirst()) it.getString(0) else null }

  private fun source() {
    context.deleteDatabase("src.db")
    BalanceDb.withFile(context, "src.db") { db ->
      val confirmed = raw(db, "u-confirmed", "Rp 25.000 to Kopi", 1_000, "parsed")
      tx(db, confirmed, 25_000, 1_000)
      val rejected = raw(db, "u-rejected", "Promo Rp 10.000", 2_000, "ignored")
      val shared = raw(db, "u-shared", "Rp 5.000 sent", 3_000, "parsed")
      tx(db, shared, 5_000, 3_000)
      val sql = db.writableDatabase
      sql.execSQL("INSERT INTO ai_reviews (raw_id, state, kind, direction, amount) VALUES (?, 'confirmed', 'expense', 'out', 25000)", arrayOf<Any>(confirmed))
      sql.execSQL("INSERT INTO ai_reviews (raw_id, state, kind, direction, amount) VALUES (?, 'rejected', 'ad_or_other', 'out', 0)", arrayOf<Any>(rejected))
      sql.execSQL("INSERT INTO ai_reviews (raw_id, state, kind, direction, amount) VALUES (?, 'confirmed', 'expense', 'out', 5000)", arrayOf<Any>(shared))
      sql.execSQL("INSERT INTO ai_examples (text, kind, direction, amount, counterparty) VALUES ('Rp 25.000 to Kopi', 'expense', 'out', 25000, 'Kopi')")
      sql.execSQL("INSERT INTO ai_examples (text, kind, direction, amount, counterparty) VALUES ('Promo Rp 10.000', 'ad_or_other', 'out', 0, NULL)")
      // A restored row at the same app and millisecond as a different restored row in the target.
      val restored = LedgerMerge.insertRestoredRaw(sql, "u-restored-src", "com.jago.digitalBanking", 9_000)!!
      tx(db, restored, 7_000, 9_000)
    }
  }

  private fun merge(target: String) {
    BalanceDb.withFile(context, target) { LedgerMerge.mergeFile(it, context.getDatabasePath("src.db")) }
  }

  @Test fun mergeKeepsAnswersAndExamplesOnce() {
    source()
    context.deleteDatabase("dst.db")
    BalanceDb.withFile(context, "dst.db") { db ->
      // The target caught the shared notification too and still has it open.
      val shared = raw(db, "u-shared", "Rp 5.000 sent", 3_000, "unparsed")
      db.writableDatabase.execSQL("INSERT INTO ai_reviews (raw_id, state) VALUES (?, 'pending')", arrayOf<Any>(shared))
      LedgerMerge.insertRestoredRaw(db.writableDatabase, "u-restored-dst", "com.jago.digitalBanking", 9_000)
    }
    merge("dst.db")
    merge("dst.db")

    BalanceDb.withFile(context, "dst.db") { db ->
      assertEquals("confirmed", state(db, "u-confirmed"))
      assertEquals("rejected", state(db, "u-rejected"))
      assertEquals("confirmed", state(db, "u-shared")) // an answer wins over an open question
      assertEquals(2, count(db, "SELECT COUNT(*) FROM ai_examples"))
      assertEquals(1, count(db, "SELECT COUNT(*) FROM raw_notifications WHERE uid = 'u-restored-src'"))
      assertEquals(
        1,
        count(db, "SELECT COUNT(*) FROM transactions t JOIN raw_notifications r ON r.id = t.raw_id WHERE r.uid = 'u-restored-src' AND t.occurred_at = 9000"),
      )
    }
  }

  @Test fun reviewFailedMovesToFailedAfterThreeAttempts() {
    context.deleteDatabase("ai.db")
    BalanceDb.withFile(context, "ai.db") { db ->
      val id = raw(db, "u-1", "Rp 12.000 something", 1_000, "unparsed")
      db.writableDatabase.execSQL("INSERT INTO ai_reviews (raw_id, state) VALUES (?, 'pending')", arrayOf<Any>(id))
      repeat(2) { Ledger.reviewFailed(db, id) }
      assertEquals("pending", state(db, "u-1"))
      Ledger.reviewFailed(db, id)
      assertEquals("failed", state(db, "u-1"))
      assertEquals(0, count(db, "SELECT COUNT(*) FROM ai_reviews WHERE state = 'pending'"))
    }
  }
}

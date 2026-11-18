package id.sndyy.balancenative.core

import android.content.Context
import java.time.Instant
import java.time.OffsetDateTime
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two-way sync between this phone's ledger and the user's cloud copy. Port of src/lib/sync.ts.
 *
 * The ledger remembers what the cloud held for each row the last time they agreed (sync_base).
 * Against that, a row missing here was deleted here, and a cloud row this phone never saw is new
 * from another device. Raw notification text never leaves the phone.
 *
 * Changes from the Expo app: runs in WorkManager (see SyncWorker), so it also happens with the app
 * closed, and the cursor is compared as an [Instant] instead of as a string.
 */
internal object CloudSync {
  private const val CHUNK = 500
  private const val PAGE = 1000
  // Rows committed slightly out of order still get picked up; re-reading one is harmless.
  private const val CURSOR_OVERLAP_MS = 2 * 60 * 1000L

  private const val CATEGORY_COLUMNS = "id,name,keywords,position"
  private const val TRANSACTION_COLUMNS =
    "id,source,direction,amount,counterparty,bank_category,category_id,category_locked,is_transfer,transfer_locked,pair_id,occurred_at"

  data class Cat(val id: String, val name: String, val keywords: String, val position: Long)

  data class Tx(
    val id: String,
    val source: String,
    val direction: String,
    val amount: Long,
    val counterparty: String?,
    val bankCategory: String?,
    val categoryId: String?,
    val categoryLocked: Boolean,
    val isTransfer: Boolean,
    val transferLocked: Boolean,
    val pairId: String?,
    val occurredAt: Long,
  )

  class Remote<T>(val row: T, val updatedAt: String, val deleted: Boolean)

  // ---- Fingerprints -----------------------------------------------------------------------------

  private fun json(vararg values: Any?): String = JSONArray().apply { values.forEach { put(it ?: JSONObject.NULL) } }.toString()

  fun print(c: Cat): String = json(c.name, c.keywords, c.position)

  fun print(t: Tx): String = json(
    t.source, t.direction, t.amount, t.counterparty, t.bankCategory, t.categoryId,
    t.categoryLocked, t.isTransfer, t.transferLocked, t.pairId, t.occurredAt,
  )

  /** Same app, same moment, same amount: one transaction, even if two builds logged it under different ids. */
  fun moment(t: Tx) = "${t.source}|${t.occurredAt}|${t.direction}|${t.amount}"

  /** Server timestamps ("2026-11-18T04:12:52.719123+00:00") or ISO "Z" strings, as epoch ms. */
  fun instant(text: String): Instant = runCatching { OffsetDateTime.parse(text).toInstant() }.getOrElse { Instant.parse(text) }

  // ---- Decisions (pure) -------------------------------------------------------------------------

  /**
   * AGREE: both sides match. TAKE: the cloud's version replaces this phone's. DROP: deleted
   * elsewhere, delete here. FORGET: gone on both sides. KEEP: this phone's version wins; the push sends it.
   */
  enum class Verdict { AGREE, TAKE, DROP, FORGET, KEEP }

  fun decide(remote: String?, local: String?, base: String?): Verdict {
    if (remote == null) {
      if (local == null) return Verdict.FORGET
      return if (local == base) Verdict.DROP else Verdict.KEEP
    }
    if (local == remote) return Verdict.AGREE
    // Missing here but agreed before: deleted on this phone.
    if (local == null) return if (base == null) Verdict.TAKE else Verdict.KEEP
    // Changed only elsewhere, or never synced from here (the cloud is the shared copy).
    if (base == null || local == base) return Verdict.TAKE
    return Verdict.KEEP
  }

  class State(var reset: Boolean, var cursor: String?, val categories: MutableMap<String, String>, val transactions: MutableMap<String, String>)

  class Pulled(val changes: CloudChanges, val duplicates: Set<String>)

  /** Works out what to apply locally. Mutates [state]'s base maps and cursor to match. */
  fun planPull(
    state: State,
    remoteCategories: List<Remote<Cat>>,
    remoteTransactions: List<Remote<Tx>>,
    localCategories: List<Cat>,
    localTransactions: List<Tx>,
  ): Pulled {
    val categories = mutableListOf<CloudCategory>()
    val deletedCategories = mutableListOf<String>()
    val rekeys = mutableListOf<CloudRekey>()
    val transactions = mutableListOf<CloudTransaction>()
    val deletedTransactions = mutableListOf<String>()
    val base = mutableListOf<SyncBaseEntry>()
    val duplicates = mutableSetOf<String>()
    var cursor = state.cursor

    fun newest(at: String) {
      val current = cursor
      if (current == null || instant(at).isAfter(instant(current))) cursor = at
    }
    fun agreed(kind: String, id: String, print: String?) {
      base += SyncBaseEntry(kind, id, print)
      val map = if (kind == "category") state.categories else state.transactions
      if (print == null) map.remove(id) else map[id] = print
    }

    val localCategory = localCategories.associate { it.id to print(it) }
    for (r in remoteCategories) {
      newest(r.updatedAt)
      val c = r.row
      val p = if (r.deleted) null else print(c)
      when (val v = decide(p, localCategory[c.id], state.categories[c.id])) {
        Verdict.TAKE, Verdict.AGREE -> {
          if (v == Verdict.TAKE) categories += CloudCategory(c.id, c.name, c.keywords, c.position.toDouble())
          agreed("category", c.id, p)
        }
        Verdict.DROP, Verdict.FORGET -> {
          if (v == Verdict.DROP) deletedCategories += c.id
          agreed("category", c.id, null)
        }
        Verdict.KEEP -> Unit
      }
    }

    val local = localTransactions.associateBy { it.id }.toMutableMap()
    val byMoment = localTransactions.associate { moment(it) to it.id }.toMutableMap()
    // Lowest id first, so of two copies of one transaction the lower id is always the one kept.
    for (r in remoteTransactions.sortedBy { it.row.id }) {
      newest(r.updatedAt)
      val t = r.row
      val p = if (r.deleted) null else print(t)
      val mine = local[t.id]
      var verdict = decide(p, mine?.let(::print), state.transactions[t.id])

      // New to this phone, but maybe logged here under another id (two builds, before ids were shared).
      val twin = if (verdict == Verdict.TAKE && mine == null) byMoment[moment(t)] else null
      if (twin != null && twin != t.id) {
        if (twin < t.id) {
          duplicates += t.id
          continue
        }
        // The cloud's id is lower: this phone's copy takes it, and its old cloud row goes.
        val row = local.remove(twin)!!.copy(id = t.id)
        local[t.id] = row
        byMoment[moment(t)] = t.id
        rekeys += CloudRekey(twin, t.id)
        duplicates += twin
        verdict = if (print(row) == p) Verdict.AGREE else Verdict.TAKE
      }

      when (verdict) {
        Verdict.TAKE, Verdict.AGREE -> {
          if (verdict == Verdict.TAKE) {
            transactions += CloudTransaction(
              t.id, t.source, t.direction, t.amount.toDouble(), t.counterparty, t.bankCategory, t.categoryId,
              t.categoryLocked, t.isTransfer, t.transferLocked, t.pairId, t.occurredAt.toDouble(),
            )
            byMoment[moment(t)] = t.id
          }
          agreed("transaction", t.id, p)
        }
        Verdict.DROP, Verdict.FORGET -> {
          if (verdict == Verdict.DROP) deletedTransactions += t.id
          agreed("transaction", t.id, null)
        }
        Verdict.KEEP -> Unit
      }
    }

    val changes = CloudChanges(
      reset = state.reset,
      categories = categories,
      deletedCategories = deletedCategories,
      rekeys = rekeys,
      transactions = transactions,
      deletedTransactions = deletedTransactions,
      base = base,
      cursor = cursor,
    )
    state.reset = false
    state.cursor = cursor
    return Pulled(changes, duplicates)
  }

  // ---- Local side -------------------------------------------------------------------------------

  private fun localCategories(db: BalanceDb): List<Cat> =
    db.readableDatabase.rawQuery("SELECT id, name, keywords, position FROM categories", null).use { c ->
      buildList { while (c.moveToNext()) add(Cat(c.getString(0), c.getString(1), c.getString(2), c.getLong(3))) }
    }

  private fun localTransactions(db: BalanceDb): List<Tx> =
    db.readableDatabase.rawQuery(
      """
      SELECT r.uid, t.source, t.direction, t.amount, t.counterparty, t.bank_category, t.category_id,
             t.category_locked, t.is_transfer, t.transfer_locked, pr.uid AS pair_uid, t.occurred_at
      FROM transactions t
      JOIN raw_notifications r ON r.id = t.raw_id
      LEFT JOIN transactions p ON p.id = t.pair_id
      LEFT JOIN raw_notifications pr ON pr.id = p.raw_id
      WHERE r.uid IS NOT NULL
      """.trimIndent(),
      null,
    ).use { c ->
      fun s(i: Int) = if (c.isNull(i)) null else c.getString(i)
      buildList {
        while (c.moveToNext()) add(
          Tx(
            c.getString(0), c.getString(1), if (c.getString(2) == "in") "in" else "out", c.getLong(3), s(4), s(5), s(6),
            c.getLong(7) == 1L, c.getLong(8) == 1L, c.getLong(9) == 1L, s(10), c.getLong(11),
          )
        )
      }
    }

  /** What this ledger last agreed with the cloud on. Belongs to one user; anyone else starts over. */
  private fun loadState(db: BalanceDb, userId: String): State {
    val state = State(reset = true, cursor = null, categories = mutableMapOf(), transactions = mutableMapOf())
    if (db.getSetting(LedgerMerge.SYNC_USER) != userId) return state
    db.readableDatabase.rawQuery("SELECT kind, id, print FROM sync_base", null).use { c ->
      while (c.moveToNext()) (if (c.getString(0) == "category") state.categories else state.transactions)[c.getString(1)] = c.getString(2)
    }
    state.reset = false
    state.cursor = db.getSetting(LedgerMerge.SYNC_CURSOR)
    return state
  }

  // ---- Cloud side -------------------------------------------------------------------------------

  private fun since(cursor: String?): String? =
    cursor?.let { runCatching { instant(it).minusMillis(CURSOR_OVERLAP_MS).toString() }.getOrNull() }

  /** Rows changed after [since] (or all of them), deleted ones included. */
  private fun fetchChanged(context: Context, userId: String, table: String, columns: String, since: String?): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    var offset = 0
    while (true) {
      val filter = since?.let { "&updated_at=gt.${Cloud.enc(it)}" }.orEmpty()
      val page = Cloud.select(context, userId, "$table?select=$columns,updated_at,deleted_at$filter&order=updated_at,id&limit=$PAGE&offset=$offset")
      for (i in 0 until page.length()) out += page.getJSONObject(i)
      if (page.length() < PAGE) return out
      offset += PAGE
    }
  }

  private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

  private fun remoteCat(o: JSONObject) = Remote(
    Cat(o.getString("id"), o.getString("name"), o.optString("keywords"), o.optDouble("position", 0.0).toLong()),
    o.getString("updated_at"), !o.isNull("deleted_at"),
  )

  private fun remoteTx(o: JSONObject) = Remote(
    Tx(
      o.getString("id"), o.getString("source"), if (o.getString("direction") == "in") "in" else "out", o.getLong("amount"),
      o.str("counterparty"), o.str("bank_category"), o.str("category_id"),
      o.optBoolean("category_locked"), o.optBoolean("is_transfer"), o.optBoolean("transfer_locked"),
      o.str("pair_id"), instant(o.getString("occurred_at")).toEpochMilli(),
    ),
    o.getString("updated_at"), !o.isNull("deleted_at"),
  )

  private fun catJson(c: Cat, userId: String) = JSONObject()
    .put("user_id", userId).put("id", c.id).put("name", c.name).put("keywords", c.keywords).put("position", c.position)
    .put("deleted_at", JSONObject.NULL)

  private fun txJson(t: Tx, userId: String) = JSONObject()
    .put("user_id", userId).put("id", t.id).put("source", t.source).put("direction", t.direction).put("amount", t.amount)
    .put("counterparty", t.counterparty ?: JSONObject.NULL).put("bank_category", t.bankCategory ?: JSONObject.NULL)
    .put("category_id", t.categoryId ?: JSONObject.NULL).put("category_locked", t.categoryLocked)
    .put("is_transfer", t.isTransfer).put("transfer_locked", t.transferLocked).put("pair_id", t.pairId ?: JSONObject.NULL)
    .put("occurred_at", Instant.ofEpochMilli(t.occurredAt).toString()).put("deleted_at", JSONObject.NULL)

  // ---- Run --------------------------------------------------------------------------------------

  private fun record(context: Context, userId: String, changes: CloudChanges) {
    val applied = Ledger.write(context, notify = false) { db ->
      if (Account.userId(context) != userId) null else LedgerMerge.applyCloud(db, userId, changes)
    }
    if (applied == null) throw IllegalStateException("Account changed")
    if (applied) Ledger.onChanged(context)
  }

  private fun <T> push(
    context: Context,
    userId: String,
    table: String,
    kind: String,
    onConflict: String,
    rows: List<T>,
    id: (T) -> String,
    print: (T) -> String,
    toJson: (T) -> JSONObject,
    base: MutableMap<String, String>,
    alsoGone: Set<String> = emptySet(),
  ) {
    val changed = rows.filter { base[id(it)] != print(it) }
    for (batch in changed.chunked(CHUNK)) {
      Cloud.upsert(context, userId, table, onConflict, JSONArray().apply { batch.forEach { put(toJson(it)) } })
      record(context, userId, CloudChanges(base = batch.map { SyncBaseEntry(kind, id(it), print(it)) }))
      batch.forEach { base[id(it)] = print(it) }
    }
    val keep = rows.map(id).toSet()
    val gone = (base.keys + alsoGone).filter { it !in keep }
    for (batch in gone.chunked(CHUNK)) {
      // The server owns updated_at; deleted_at is ours to set.
      val ids = batch.joinToString(",") { "\"$it\"" }
      Cloud.update(context, userId, table, "id=in.${Cloud.enc("($ids)")}", JSONObject().put("deleted_at", Instant.now().toString()))
      record(context, userId, CloudChanges(base = batch.map { SyncBaseEntry(kind, it, null) }))
      batch.forEach { base.remove(it) }
    }
  }

  /** One full sync for [userId]. Throws on network or auth failure; nothing half-applied is lost (every step is re-runnable). */
  fun run(context: Context, userId: String) {
    val app = context.applicationContext
    val db = BalanceDb.get(app)
    if (Account.userId(app) != userId) throw IllegalStateException("Account changed")
    val state = loadState(db, userId)

    // Pull first: until this phone has seen the other devices' rows, it can't tell them from deletions.
    val from = since(state.cursor)
    val remoteCategories = fetchChanged(app, userId, "categories", CATEGORY_COLUMNS, from).map(::remoteCat)
    val remoteTransactions = fetchChanged(app, userId, "transactions", TRANSACTION_COLUMNS, from).map(::remoteTx)
    val pulled = planPull(state, remoteCategories, remoteTransactions, localCategories(db), localTransactions(db))
    record(app, userId, pulled.changes)

    // Categories first: transactions reference them.
    push(app, userId, "categories", "category", "user_id,id", localCategories(db), Cat::id, ::print, { catJson(it, userId) }, state.categories)
    push(app, userId, "transactions", "transaction", "id", localTransactions(db), Tx::id, ::print, { txJson(it, userId) }, state.transactions, pulled.duplicates)
  }
}

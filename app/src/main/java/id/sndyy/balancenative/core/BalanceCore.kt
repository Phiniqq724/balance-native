package id.sndyy.balancenative.core

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.Settings as AndroidSettings
import id.sndyy.balancenative.data.ActivityPage
import id.sndyy.balancenative.data.ActivityQuery
import id.sndyy.balancenative.data.AiKind
import id.sndyy.balancenative.data.Apps
import id.sndyy.balancenative.data.Category
import id.sndyy.balancenative.data.Direction
import id.sndyy.balancenative.data.Flow
import id.sndyy.balancenative.data.Format
import id.sndyy.balancenative.data.InstalledApp
import id.sndyy.balancenative.data.Review
import id.sndyy.balancenative.data.ReviewState
import id.sndyy.balancenative.data.Transaction
import id.sndyy.balancenative.data.Unparsed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Everything the UI does to the ledger. Replaces the Expo module bridge (BalanceCoreModule.kt) and
 * src/lib/ledger.ts. Reads run on IO; writes go through [Ledger.write]'s single writer thread so they
 * never race the notification listener.
 */
class BalanceCore(context: Context) {
  private val app = context.applicationContext
  private val db get() = BalanceDb.get(app)

  // ---- Notification access ----

  fun hasNotificationAccess(): Boolean = BalanceListenerService.hasAccess(app)

  fun ensureListenerBound() = BalanceListenerService.ensureBound(app)

  fun openNotificationAccessSettings() {
    val component = ComponentName(app, BalanceListenerService::class.java).flattenToString()
    val intent = Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS")
      .putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", component)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val fallback = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { app.startActivity(intent) }.onFailure { app.startActivity(fallback) }
  }

  fun openNotificationSettings() {
    app.startActivity(
      Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, app.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
  }

  // ---- Apps ----

  fun appLabel(pkg: String): String? = runCatching {
    val pm = app.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
  }.getOrNull()

  private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

  fun sourceLabel(pkg: String): String = when (pkg) {
    Apps.MANUAL -> Apps.MANUAL
    else -> Apps.MONEY_APPS[pkg] ?: labels.getOrPut(pkg) { appLabel(pkg) ?: pkg }
  }

  /** The name a row shows: who you paid, else what the bank called it. */
  fun title(tx: Transaction): String = tx.counterparty ?: tx.bankCategory ?: sourceLabel(tx.source)

  suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = app.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    pm.queryIntentActivities(launcher, 0)
      .map { it.activityInfo.applicationInfo }
      .distinctBy { it.packageName }
      .filter { it.packageName != app.packageName }
      .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString(), runCatching { pm.getApplicationIcon(it) }.getOrNull()) }
      .sortedBy { it.label.lowercase() }
  }

  // ---- Reads ----

  private suspend fun <T> read(sql: String, args: List<Any?> = emptyList(), row: (Cursor) -> T): List<T> = withContext(Dispatchers.IO) {
    db.readableDatabase.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c ->
      buildList { while (c.moveToNext()) add(row(c)) }
    }
  }

  private fun Cursor.str(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
  private fun Cursor.long(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
  private fun Cursor.double(name: String): Double? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getDouble(it) }

  private val txSelect = """
    SELECT t.id, t.source, t.direction, t.amount, t.counterparty, t.bank_category, t.category_id,
           c.name AS category_name, t.is_transfer, t.pair_id, t.occurred_at, r.body
    FROM transactions t
    JOIN raw_notifications r ON r.id = t.raw_id
    LEFT JOIN categories c ON c.id = t.category_id
  """.trimIndent()

  private fun Cursor.transaction() = Transaction(
    id = long("id")!!,
    source = str("source")!!,
    direction = Direction.of(str("direction")),
    amount = long("amount") ?: 0,
    counterparty = str("counterparty"),
    bankCategory = str("bank_category"),
    categoryId = str("category_id"),
    categoryName = str("category_name"),
    isTransfer = long("is_transfer") == 1L,
    pairId = long("pair_id"),
    occurredAt = long("occurred_at") ?: 0,
    rawBody = str("body").orEmpty(),
  )

  suspend fun transaction(id: Long): Transaction? = read("$txSelect WHERE t.id = ?", listOf(id)) { it.transaction() }.firstOrNull()

  private suspend fun activityWhere(query: ActivityQuery): Pair<String, List<Any?>> {
    val where = mutableListOf<String>()
    val args = mutableListOf<Any?>()
    val filter = query.filter
    when {
      filter == "transfer" -> where += "t.is_transfer = 1"
      filter != "all" -> {
        where += "t.is_transfer = 0"
        if (filter == "out" || filter == "in") {
          where += "t.direction = ?"; args += filter
        } else if (filter.startsWith("cat:")) {
          val id = filter.removePrefix("cat:")
          where += "t.direction = 'out'"
          if (id == "none") where += "t.category_id IS NULL" else { where += "t.category_id = ?"; args += id }
        }
      }
    }
    val q = query.q.trim().lowercase()
    if (q.isNotEmpty()) {
      val ors = mutableListOf("instr(lower(t.counterparty), ?) > 0", "instr(lower(c.name), ?) > 0", "instr(lower(t.bank_category), ?) > 0")
      repeat(3) { args += q }
      // "25000" and "25.000" both find Rp 25.000.
      val number = q.replace(Regex("[.,]"), "")
      if (number.isNotEmpty() && number.all(Char::isDigit)) {
        ors += "instr(CAST(t.amount AS TEXT), ?) > 0"; args += number
      }
      // The search also matches the app name ("jago", "gopay").
      val sources = read("SELECT DISTINCT source FROM transactions") { it.getString(0) }.filter { sourceLabel(it).lowercase().contains(q) }
      if (sources.isNotEmpty()) {
        ors += "t.source IN (${sources.joinToString(",") { "?" }})"; args.addAll(sources)
      }
      where += "(${ors.joinToString(" OR ")})"
    }
    return (if (where.isEmpty()) "" else " WHERE ${where.joinToString(" AND ")}") to args
  }

  /**
   * The newest [count] matching transactions, extended to the start of the last one's day so a day
   * is never split between pages, plus totals over everything matching.
   */
  suspend fun activityPage(query: ActivityQuery, count: Int): ActivityPage {
    val (sql, args) = activityWhere(query)
    val from = " FROM transactions t LEFT JOIN categories c ON c.id = t.category_id"
    val (total, spent, received) = read(
      """SELECT COUNT(*) AS n,
         COALESCE(SUM(CASE WHEN t.is_transfer = 0 AND t.direction = 'out' THEN t.amount END), 0) AS spent,
         COALESCE(SUM(CASE WHEN t.is_transfer = 0 AND t.direction = 'in' THEN t.amount END), 0) AS received$from$sql""",
      args,
    ) { Triple(it.getInt(0), it.getLong(1), it.getLong(2)) }.first()
    var cutoff = 0L
    if (total > count) {
      val edge = read("SELECT t.occurred_at$from$sql ORDER BY t.occurred_at DESC, t.id DESC LIMIT 1 OFFSET ?", args + (count - 1)) { it.getLong(0) }.first()
      cutoff = Format.startOfDayMs(Format.date(edge))
    }
    val rows = read(
      "$txSelect$sql${if (cutoff > 0) (if (sql.isEmpty()) " WHERE" else " AND") + " t.occurred_at >= ?" else ""} ORDER BY t.occurred_at DESC, t.id DESC",
      if (cutoff > 0) args + cutoff else args,
    ) { it.transaction() }
    return ActivityPage(rows, total, spent, received, hasMore = rows.size < total)
  }

  suspend fun flows(fromMs: Long): List<Flow> = read(
    """SELECT t.direction, t.amount, t.category_id, c.name AS category_name, t.counterparty, t.bank_category, t.source, t.occurred_at
       FROM transactions t LEFT JOIN categories c ON c.id = t.category_id
       WHERE t.is_transfer = 0 AND t.occurred_at >= ?
       ORDER BY t.occurred_at""",
    listOf(fromMs),
  ) {
    Flow(
      direction = Direction.of(it.str("direction")),
      amount = it.long("amount") ?: 0,
      categoryId = it.str("category_id"),
      categoryName = it.str("category_name"),
      title = it.str("counterparty") ?: it.str("bank_category") ?: sourceLabel(it.str("source")!!),
      at = it.long("occurred_at") ?: 0,
    )
  }

  suspend fun categories(): List<Category> =
    read("SELECT id, name, keywords FROM categories ORDER BY position") { Category(it.getString(0), it.getString(1), it.getString(2)) }

  suspend fun unparsed(): List<Unparsed> = read(
    """SELECT r.id, r.package, r.title, r.body, r.posted_at,
              a.state, a.kind, a.direction AS ai_direction, a.amount, a.counterparty, a.confidence
       FROM raw_notifications r LEFT JOIN ai_reviews a ON a.raw_id = r.id
       WHERE r.status = 'unparsed' ORDER BY r.posted_at DESC LIMIT 200""",
  ) {
    val state = when (it.str("state")) {
      "pending" -> ReviewState.PENDING
      "suggested" -> ReviewState.SUGGESTED
      "failed" -> ReviewState.FAILED
      else -> null
    }
    Unparsed(
      id = it.long("id")!!,
      source = it.str("package")!!,
      title = it.str("title"),
      body = it.str("body").orEmpty(),
      postedAt = it.long("posted_at") ?: 0,
      review = state?.let { s ->
        Review(
          state = s,
          kind = AiKind.of(it.str("kind")),
          direction = it.str("ai_direction")?.let(Direction::of),
          amount = it.long("amount"),
          counterparty = it.str("counterparty"),
          confidence = it.double("confidence"),
        )
      },
    )
  }

  suspend fun unparsedCount(): Int = read("SELECT COUNT(*) FROM raw_notifications WHERE status = 'unparsed'") { it.getInt(0) }.first()

  suspend fun setting(key: String): String? = withContext(Dispatchers.IO) { db.getSetting(key) }

  suspend fun watchedPackages(): List<String> = withContext(Dispatchers.IO) { Settings.splitList(db.getSetting(Settings.WATCHED_PACKAGES)) }

  /** Whose ledger is open: a user id, [Account.LOCAL], or null before anything is chosen. */
  fun activeUser(): String? = Account.userId(app)

  // ---- Writes ----

  private suspend fun <T> write(notify: Boolean = true, block: (BalanceDb) -> T): T =
    withContext(Dispatchers.IO) { Ledger.write(app, notify, block) }

  suspend fun setCategory(id: Long, categoryId: String?) = write { d ->
    d.writableDatabase.execSQL("UPDATE transactions SET category_id = ?, category_locked = 1 WHERE id = ?", arrayOf<Any?>(categoryId, id))
  }

  suspend fun setTransfer(id: Long, isTransfer: Boolean) = write { d ->
    d.writableDatabase.execSQL("UPDATE transactions SET is_transfer = ?, transfer_locked = 1 WHERE id = ?", arrayOf<Any>(if (isTransfer) 1 else 0, id))
  }

  suspend fun addTransaction(direction: Direction, amount: Long, counterparty: String?, categoryId: String?, occurredAt: Long): Long =
    write { d -> Ledger.addManual(d, direction.value, amount, counterparty, categoryId, occurredAt) }

  suspend fun deleteTransaction(id: Long) = write { d -> Ledger.deleteManual(d, id) }

  suspend fun saveCategory(id: String?, name: String, keywords: String): String = write { d ->
    val sqlite = d.writableDatabase
    val categoryId = id ?: UUID.randomUUID().toString()
    if (id == null) {
      sqlite.execSQL(
        "INSERT INTO categories (id, name, keywords, position) VALUES (?, ?, ?, (SELECT COALESCE(MAX(position), -1) + 1 FROM categories))",
        arrayOf(categoryId, name.trim(), keywords),
      )
    } else {
      sqlite.execSQL("UPDATE categories SET name = ?, keywords = ? WHERE id = ?", arrayOf(name.trim(), keywords, id))
    }
    Ledger.recategorize(d)
    categoryId
  }

  suspend fun deleteCategory(id: String) = write { d ->
    d.writableDatabase.execSQL("DELETE FROM categories WHERE id = ?", arrayOf(id))
    Ledger.recategorize(d)
  }

  suspend fun setSetting(key: String, value: String) = write { d ->
    d.setSetting(key, value)
    // Watched apps change what counts as a transaction.
    if (key == Settings.WATCHED_PACKAGES) Ledger.reprocessAll(d)
  }

  suspend fun setWatchedPackages(packages: List<String>) = setSetting(Settings.WATCHED_PACKAGES, packages.distinct().joinToString(","))

  /** Hands the bundled bank rules to the parser. Re-reads the ledger only when they changed. */
  suspend fun applyBundledRules() {
    val json = withContext(Dispatchers.IO) { app.assets.open("parse_rules.json").bufferedReader().use { it.readText() } }
    write(notify = false) { d -> Ledger.applyRules(d, json) }.also { changed -> if (changed) Ledger.onChanged(app) }
  }

  suspend fun setAiReview(on: Boolean) = write { d -> Ledger.setAiEnabled(d, on) }

  suspend fun aiReviewEnabled(): Boolean = withContext(Dispatchers.IO) { db.aiEnabled() }

  suspend fun runAiReview() = withContext(Dispatchers.IO) { AiReview.kick(app) }

  /** The user's own AI key. Stays on this phone; the UI only gets the provider and the last four characters. */
  suspend fun setAiKey(key: String): AiKeyInfo = withContext(Dispatchers.IO) {
    val info = AiKey.validateAndSave(app, key)
    AiReview.kick(app)
    AiKeyInfo(info.provider, info.last4, info.status)
  }

  fun aiKeyInfo(): AiKeyInfo? = AiKey.info(app)?.let { AiKeyInfo(it.provider, it.last4, it.status) }

  suspend fun clearAiKey() = withContext(Dispatchers.IO) {
    AiKey.clear(app)
    // Local mode has no other way to read, so AI review goes off with the key.
    if (Account.userId(app) == Account.LOCAL) Ledger.write(app) { d -> Ledger.setAiEnabled(d, false) }
  }

  suspend fun resolve(rawId: Long, kind: AiKind, direction: Direction, amount: Long, counterparty: String?) {
    require(amount >= 0) { "Negative amount" }
    write { d -> Ledger.resolve(d, rawId, kind.value, direction.value, amount, counterparty?.trim()?.takeIf { it.isNotEmpty() }) }
  }

  suspend fun setActiveUser(userId: String?) = withContext(Dispatchers.IO) { Ledger.switchUser(app, userId) }

  fun setCloudConfig(url: String, key: String) = Account.setCloudConfig(app, url, key)

  /** Applies one sync step. False if the ledger owner changed while the fetch ran. */
  suspend fun applyCloud(userId: String, changes: CloudChanges): Boolean = withContext(Dispatchers.IO) {
    // Sync bookkeeping alone doesn't wake screens, widgets or the next sync.
    val result = Ledger.write(app, notify = false) { d ->
      if (Account.userId(app) != userId) null else LedgerMerge.applyCloud(d, userId, changes)
    }
    if (result == true) Ledger.onChanged(app)
    result != null
  }

  suspend fun signInWithGoogle(activity: Activity, serverClientId: String): Map<String, String> = GoogleSignIn.signIn(activity, serverClientId)

  suspend fun forgetGoogleAccount() = GoogleSignIn.forget(app)

  // ---- Files ----

  suspend fun exportLedger(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
    val out = app.contentResolver.openOutputStream(uri, "wt") ?: throw LedgerFileException("Can't write file")
    val done = out.use { LedgerFile.export(app, db, emptyMap(), it) }
    done.transactions to done.categories
  }

  suspend fun previewImport(uri: Uri): Map<String, Any?> = withContext(Dispatchers.IO) {
    val d = db
    val parsed = LedgerFile.open(app, uri).use { LedgerFile.read(it, importLabels(d)) }
    LedgerFile.plan(d, parsed).summary()
  }

  suspend fun importLedger(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
    val parsed = LedgerFile.open(app, uri).use { LedgerFile.read(it, importLabels(db)) }
    val done = Ledger.write(app) { d -> LedgerFile.apply(d, parsed) }
    ImportResult(done.added, done.existing, done.newCategories)
  }

  private fun importLabels(d: BalanceDb): Map<String, String> = d.watchedPackages().associateWith { sourceLabel(it) }

  data class AiKeyInfo(val provider: String, val last4: String, val status: String?)
  data class ImportResult(val added: Int, val existing: Int, val newCategories: Int)

  companion object {
    /** Ledger writes from anywhere in the process. */
    val changes get() = BalanceEvents.changes
  }
}

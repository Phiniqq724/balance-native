package id.sndyy.balancenative.core

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs [CloudSync] for the signed-in user. Unlike the Expo app (sync only while open), this also
 * runs with the app closed: soon after any ledger change, and every few hours as a fallback.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
    val owner = Account.userId(applicationContext)
    if (owner == null || owner == Account.LOCAL) return@withContext Result.success()
    try {
      CloudSync.run(applicationContext, owner)
      prefs(applicationContext).edit().putLong(LAST_SYNCED, System.currentTimeMillis()).apply()
      Result.success()
    } catch (e: CloudException) {
      // Signed out elsewhere or revoked: retrying won't help until the user signs in again.
      if (e.code == 401 || e.code == 403) Result.failure() else Result.retry()
    } catch (e: IllegalStateException) {
      Result.success() // The account changed mid-run; the new owner gets its own sync.
    } catch (e: Exception) {
      if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
  }

  enum class Status { IDLE, SYNCING, SYNCED, FAILED }

  companion object {
    private const val NOW = "sync-now"
    private const val PERIODIC = "sync-periodic"
    private const val PREFS = "balance.sync"
    private const val LAST_SYNCED = "last_synced"
    private const val DEBOUNCE_S = 3L

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun lastSynced(context: Context): Long? = prefs(context).getLong(LAST_SYNCED, 0).takeIf { it > 0 }

    /**
     * Syncs soon. One run at a time with at most one queued behind it, so everything up to this
     * call is covered without piling up runs.
     */
    fun request(context: Context, delaySeconds: Long = DEBOUNCE_S) {
      val wm = WorkManager.getInstance(context)
      val pending = runCatching { wm.getWorkInfosForUniqueWork(NOW).get() }.getOrDefault(emptyList())
      if (pending.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return
      val work = OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(network)
        .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      wm.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    fun schedulePeriodic(context: Context) {
      val work = PeriodicWorkRequestBuilder<SyncWorker>(4, TimeUnit.HOURS).setConstraints(network).build()
      WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun cancel(context: Context) {
      WorkManager.getInstance(context).cancelUniqueWork(NOW)
      WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** For the Account row: what sync is doing right now. */
    fun status(context: Context): Flow<Status> {
      val wm = WorkManager.getInstance(context)
      return combine(wm.getWorkInfosForUniqueWorkFlow(NOW), wm.getWorkInfosForUniqueWorkFlow(PERIODIC)) { a, b -> a + b }.map { infos ->
        when {
          infos.any { it.state == WorkInfo.State.RUNNING } -> Status.SYNCING
          infos.any { it.state == WorkInfo.State.FAILED } && infos.none { it.state == WorkInfo.State.SUCCEEDED } -> Status.FAILED
          infos.any { it.state == WorkInfo.State.ENQUEUED && it.runAttemptCount > 0 } -> Status.FAILED
          lastSynced(context) != null -> Status.SYNCED
          else -> Status.IDLE
        }
      }
    }
  }
}

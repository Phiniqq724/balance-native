package id.sndyy.balancenative.core

import android.app.Activity
import android.content.Context
import id.sndyy.balancenative.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * CLOUD: signed in with Google, the ledger is backed up to Supabase.
 * LOCAL: no account, the ledger lives only on this phone.
 * NONE: nothing chosen yet (sign-in screen).
 *
 * The owner is stored natively ("balance.account" prefs), so the listener, widgets and workers follow
 * the same choice with the app closed. Port of src/lib/auth.tsx.
 */
sealed interface AccountState {
  data object Loading : AccountState
  data object None : AccountState
  data object Local : AccountState
  data class Signed(val user: CloudUser) : AccountState
}

class AccountManager(context: Context, private val core: BalanceCore) {
  private val app = context.applicationContext
  private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
  val state: StateFlow<AccountState> = _state

  // Sign-ins and mode switches can arrive back to back; apply them in order.
  private val lock = Mutex()

  val cloudConfigured: Boolean = BuildConfig.SUPABASE_URL.isNotEmpty() && BuildConfig.SUPABASE_KEY.isNotEmpty()

  init {
    if (cloudConfigured) Account.setCloudConfig(app, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY)
  }

  /** Reads who the open ledger belongs to. Call once on start. */
  suspend fun load() = lock.withLock {
    val owner = Account.userId(app)
    val user = withContext(Dispatchers.IO) { Cloud.user(app) }
    _state.value = when {
      owner == Account.LOCAL -> AccountState.Local
      owner != null && user?.id == owner -> AccountState.Signed(user).also { startCloud() }
      // The session is gone (signed out elsewhere or wiped): back to the sign-in screen, ledger kept on disk.
      owner != null -> { core.setActiveUser(null); AccountState.None }
      else -> AccountState.None
    }
  }

  private fun startCloud() {
    SyncWorker.request(app, delaySeconds = 0)
    SyncWorker.schedulePeriodic(app)
  }

  /**
   * Native "Sign in with Google" (Credential Manager) handed to Supabase as an ID token. From local
   * mode this merges the phone-only ledger into the account's. Returns an error message, or null when
   * signed in or cancelled.
   */
  suspend fun signInWithGoogle(activity: Activity): String? {
    if (!cloudConfigured || BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) return "Google sign-in isn’t set up."
    val credential = try {
      core.signInWithGoogle(activity, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    } catch (e: BalanceException) {
      return when (e.code) {
        "ERR_CANCELLED" -> null
        "ERR_NO_ACCOUNT" -> "Add a Google account to this phone first."
        else -> "Couldn’t reach Google."
      }
    }
    return lock.withLock {
      val user = try {
        withContext(Dispatchers.IO) { Cloud.signInWithIdToken(app, credential.getValue("idToken"), credential.getValue("nonce")) }
      } catch (e: CloudException) {
        return@withLock "Couldn’t sign in."
      } catch (e: Exception) {
        return@withLock "No connection."
      }
      try {
        core.setActiveUser(user.id)
      } catch (e: Exception) {
        return@withLock "Couldn’t open the ledger."
      }
      _state.value = AccountState.Signed(user)
      startCloud()
      null
    }
  }

  /** Use the app with no account. */
  suspend fun chooseLocal(): String? = lock.withLock {
    try {
      core.setActiveUser(Account.LOCAL)
      _state.value = AccountState.Local
      null
    } catch (e: Exception) {
      "Couldn’t open the ledger."
    }
  }

  /** One sync right now, outside WorkManager, for flows that must know it landed. */
  private suspend fun syncNow(userId: String): Boolean = withContext(Dispatchers.IO) {
    runCatching { CloudSync.run(app, userId) }.isSuccess
  }

  /**
   * Stops syncing and keeps everything on this phone. Nothing in the cloud is deleted. Syncs first so
   * rows from other devices come along; refuses rather than leave them behind.
   */
  suspend fun switchToLocal(): String? = lock.withLock {
    val signed = _state.value as? AccountState.Signed ?: return@withLock null
    if (!syncNow(signed.user.id)) return@withLock "Can’t reach the cloud."
    SyncWorker.cancel(app)
    withContext(Dispatchers.IO) { Cloud.signOut(app) }
    core.setActiveUser(Account.LOCAL)
    _state.value = AccountState.Local
    null
  }

  /** Back to the sign-in screen. The account's ledger stays on the phone for the next sign-in. */
  suspend fun signOut() = lock.withLock {
    (_state.value as? AccountState.Signed)?.let { syncNow(it.user.id) }
    SyncWorker.cancel(app)
    withContext(Dispatchers.IO) { Cloud.signOut(app) }
    core.forgetGoogleAccount()
    core.setActiveUser(null)
    _state.value = AccountState.None
  }

  /** Best effort: the user's answer teaches the cloud model. Redacted first; local mode has no account to teach. */
  suspend fun shareExample(text: String, kind: String, direction: String, amount: Long, counterparty: String?) {
    val signed = _state.value as? AccountState.Signed ?: return
    withContext(Dispatchers.IO) {
      runCatching {
        val row = JSONObject().put("text", Redact.text(text)).put("kind", kind).put("direction", direction).put("amount", amount)
          .put("counterparty", counterparty ?: JSONObject.NULL)
        val headers = CloudAuth.session(app, signed.user.id)?.let { CloudAuth.headers(app, it) } ?: return@runCatching
        CloudHttp.request("POST", "${Account.cloudUrl(app)}/rest/v1/ai_examples", headers + ("Prefer" to "return=minimal"), row.toString())
      }
    }
  }
}

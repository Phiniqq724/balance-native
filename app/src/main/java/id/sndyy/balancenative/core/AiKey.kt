package id.sndyy.balancenative.core

import android.content.Context
import java.io.IOException

internal class BadAiKey : BalanceException("ERR_BAD_KEY", "The key was rejected", null)
internal class AiKeyNoCredit : BalanceException("ERR_NO_CREDIT", "The key is out of credit", null)
internal class UnknownAiKey : BalanceException("ERR_UNKNOWN_KEY", "Not a Gemini, OpenAI or Claude key", null)
internal class AiKeyNetwork(cause: Throwable?) : BalanceException("ERR_NETWORK", "Couldn't reach the provider", cause)

/**
 * The user's own AI key. Per phone, not per ledger: it survives switching between local and Google
 * and goes only on Remove or uninstall. Encrypted with AES-GCM under a key that never leaves the
 * Android Keystore, in a SharedPreferences file of its own. It is never synced, exported, logged or
 * sent anywhere but the provider it belongs to, and the UI only ever sees [Info].
 */
internal object AiKey {
  private const val PREFS = "balance.ai_key"
  private const val BLOB = "blob"
  private const val PROVIDER = "provider"
  private const val LAST4 = "last4"
  private const val STATUS = "status"
  private val vault = Vault("balance_ai_key")
  // Rejected or out of credit: leave the queue alone this long before looking again.
  private const val BLOCK_SECONDS = 3600

  class Stored(val provider: AiProviders.Provider, val key: String)
  class Info(val provider: String, val last4: String, val status: String?)

  private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun isSet(context: Context): Boolean = prefs(context).contains(BLOB)

  /** Provider and last four characters, never the key. */
  fun info(context: Context): Info? {
    val p = prefs(context)
    if (!p.contains(BLOB)) return null
    val provider = p.getString(PROVIDER, null) ?: return null
    return Info(provider, p.getString(LAST4, "").orEmpty(), p.getString(STATUS, null))
  }

  /** For calls to the provider only. */
  fun get(context: Context): Stored? {
    val p = prefs(context)
    val blob = p.getString(BLOB, null) ?: return null
    val provider = AiProviders.Provider.from(p.getString(PROVIDER, null)) ?: return null
    // Null: the Keystore key is gone (restored backup, reset lock screen) and the ciphertext is useless.
    val key = vault.decrypt(blob) ?: run { clear(context); return null }
    return Stored(provider, key)
  }

  /** Checks the key with one tiny real call and stores it only if that works. */
  fun validateAndSave(context: Context, raw: String): Info {
    val key = raw.trim()
    val provider = AiProviders.Provider.detect(key) ?: throw UnknownAiKey()
    try {
      AiProviders.classify(provider, key, listOf(AiProviders.Item("1", "", "Rp 10.000 paid to Test")), emptyList())
    } catch (e: AiProviders.Failure) {
      when (e.reason) {
        AiProviders.Reason.BAD_KEY -> throw BadAiKey()
        AiProviders.Reason.NO_CREDIT -> throw AiKeyNoCredit()
        AiProviders.Reason.BUSY -> Unit // It got as far as being rate limited, so the key is real.
        AiProviders.Reason.MODEL, AiProviders.Reason.MODEL_GONE -> throw AiKeyNetwork(e)
      }
    } catch (e: IOException) {
      throw AiKeyNetwork(e)
    }
    prefs(context).edit()
      .putString(BLOB, vault.encrypt(key))
      .putString(PROVIDER, provider.id)
      .putString(LAST4, key.takeLast(4))
      .remove(STATUS)
      .commit()
    AiReview.block(context, 0)
    BalanceEvents.emitLedgerChanged()
    return Info(provider.id, key.takeLast(4), null)
  }

  fun clear(context: Context) {
    prefs(context).edit().clear().commit()
    vault.deleteKey()
    AiReview.block(context, 0)
    BalanceEvents.emitLedgerChanged()
  }

  /** Shown on the Profile row until a pass succeeds. Rejected, out of credit or a retired model also pause the queue. */
  fun setStatus(context: Context, reason: AiProviders.Reason?) {
    val text = when (reason) {
      AiProviders.Reason.BAD_KEY -> "Key rejected"
      AiProviders.Reason.NO_CREDIT -> "Out of credit"
      AiProviders.Reason.MODEL_GONE -> "Model unavailable, update the app"
      else -> null
    }
    val p = prefs(context)
    if (!p.contains(BLOB) || p.getString(STATUS, null) == text) return
    p.edit().apply { if (text == null) remove(STATUS) else putString(STATUS, text) }.commit()
    if (text != null) AiReview.block(context, BLOCK_SECONDS)
    BalanceEvents.emitLedgerChanged()
  }
}

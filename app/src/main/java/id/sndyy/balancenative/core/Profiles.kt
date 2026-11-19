package id.sndyy.balancenative.core

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the avatar shows when there's no photo of your own. */
enum class AvatarStyle { GOOGLE, MOSAIC }

/**
 * The profile as the UI draws it. [photo] is your own photo, [google] the account's Google photo; both
 * are files on this phone, so the header never waits on the network.
 */
data class ProfileView(
  val owner: String,
  val username: String?,
  val photo: File?,
  val google: File?,
  val style: AvatarStyle,
  /** Drives the Mosaic avatar: the user id, or a random id kept on this phone in local mode. */
  val seed: String,
) {
  /** The picture to show, or null for the Mosaic. */
  val shown: File? get() = photo ?: google?.takeIf { style == AvatarStyle.GOOGLE }
}

/** A change to the photo, applied on Save. */
sealed interface PhotoChange {
  /** A cropped JPEG from the picker. */
  class Upload(val jpeg: ByteArray) : PhotoChange
  /** Drop your own photo and fall back to [style]. */
  data class Use(val style: AvatarStyle) : PhotoChange
}

/**
 * Username and photo. Signed in, they live where the Expo app keeps them: the `profiles` table and
 * the private `avatars` bucket (`<user id>/<time>.jpg`, never reused), so both apps show the same
 * profile. Local mode keeps the same shape on this phone. The last known copy is cached in the
 * ledger's `profile` setting and the photos on disk, so the screen opens without a flash or offline.
 */
class ProfileStore(context: Context, private val core: BalanceCore) {
  private val app = context.applicationContext
  private val _state = MutableStateFlow<ProfileView?>(null)
  val state: StateFlow<ProfileView?> = _state
  private val lock = Mutex()

  companion object {
    val USERNAME = Regex("^[a-z0-9_.]{3,20}$")
    private const val CACHE_KEY = "profile"
    private const val SEED_KEY = "mosaic_seed"
    private const val BUCKET = "avatars"
  }

  private class Cached(val username: String?, val avatarPath: String?, val style: AvatarStyle, val googleUrl: String?) {
    fun json(): String = JSONObject()
      .put("username", username ?: JSONObject.NULL)
      .put("avatarPath", avatarPath ?: JSONObject.NULL)
      .put("style", style.name.lowercase())
      .put("googleUrl", googleUrl ?: JSONObject.NULL)
      .toString()

    fun copy(username: String? = this.username, avatarPath: String? = this.avatarPath, style: AvatarStyle = this.style, googleUrl: String? = this.googleUrl) =
      Cached(username, avatarPath, style, googleUrl)

    companion object {
      fun parse(text: String?): Cached {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        fun str(k: String) = o.optString(k).takeIf { o.has(k) && !o.isNull(k) && it.isNotEmpty() }
        return Cached(str("username"), str("avatarPath"), if (str("style") == "mosaic") AvatarStyle.MOSAIC else AvatarStyle.GOOGLE, str("googleUrl"))
      }
    }
  }

  private fun dir() = File(app.filesDir, "avatars").apply { mkdirs() }

  /** "<owner>/<time>.jpg" -> file in the avatars folder. */
  private fun fileFor(path: String) = File(dir(), path.replace('/', '-'))

  private fun googleFile(owner: String) = File(dir(), "google-$owner.jpg")

  private fun ownerOf(state: AccountState): String? = when (state) {
    is AccountState.Signed -> state.user.id
    AccountState.Local -> Account.LOCAL
    else -> null
  }

  private suspend fun cached() = Cached.parse(core.setting(CACHE_KEY))

  private suspend fun seed(owner: String): String {
    if (owner != Account.LOCAL) return owner
    core.setting(SEED_KEY)?.let { return it }
    return UUID.randomUUID().toString().also { core.setSetting(SEED_KEY, it) }
  }

  private suspend fun publish(owner: String, c: Cached) {
    val photo = c.avatarPath?.let(::fileFor)?.takeIf { it.exists() }
    val google = googleFile(owner).takeIf { owner != Account.LOCAL && c.googleUrl != null && it.exists() }
    _state.value = ProfileView(owner, c.username, photo, google, c.style, seed(owner))
  }

  private suspend fun commit(owner: String, c: Cached) {
    core.setSetting(CACHE_KEY, c.json())
    publish(owner, c)
  }

  /** Shows the cached profile at once, then refreshes it from the cloud when signed in. */
  suspend fun load(account: AccountState) = lock.withLock {
    val owner = ownerOf(account) ?: run { _state.value = null; return@withLock }
    var c = cached()
    if (_state.value?.owner != owner) publish(owner, c)
    val user = (account as? AccountState.Signed)?.user ?: return@withLock

    withContext(Dispatchers.IO) {
      // Google photo: asked for at a size that stays sharp in the big preview.
      val url = user.avatarUrl?.let { if ("googleusercontent" in it) it.replace(Regex("=s\\d+(-c)?$"), "=s512-c") else it }
      val gFile = googleFile(owner)
      if (url != null && (url != c.googleUrl || !gFile.exists())) {
        runCatching { gFile.writeBytes(CloudHttp.download(url)) }.onSuccess { c = c.copy(googleUrl = url) }
      } else if (url == null && c.googleUrl != null) {
        gFile.delete(); c = c.copy(googleUrl = null)
      }

      // Offline or failing: keep what's cached.
      val row = runCatching { Cloud.select(app, owner, "profiles?select=username,avatar_path&id=eq.$owner").optJSONObject(0) }
      if (row.isSuccess) {
        val o = row.getOrNull()
        val path = o?.optString("avatar_path")?.takeIf { !o.isNull("avatar_path") && it.isNotEmpty() }
        val username = o?.optString("username")?.takeIf { !o.isNull("username") && it.isNotEmpty() }
        if (path != null && !fileFor(path).exists()) {
          runCatching { Cloud.signedUrl(app, owner, BUCKET, path, 60)?.let { fileFor(path).writeBytes(CloudHttp.download(it)) } }
        }
        if (c.avatarPath != null && c.avatarPath != path) fileFor(c.avatarPath!!).delete()
        c = c.copy(username = username, avatarPath = path)
      }
    }
    commit(owner, c)
  }

  /**
   * Applies the edit sheet. Returns an error message, or null when everything saved. The photo goes
   * first, so a taken username doesn't throw away a new photo.
   */
  suspend fun save(username: String?, change: PhotoChange?): String? = lock.withLock {
    val view = _state.value ?: return@withLock "Couldn’t save."
    val owner = view.owner
    val local = owner == Account.LOCAL
    var c = cached()

    val name = username?.trim()?.lowercase()?.ifEmpty { null }
    if (name != null && name != c.username && !USERNAME.matches(name)) return@withLock "3–20 of a–z, 0–9, _ or ."

    when (change) {
      is PhotoChange.Upload -> {
        val path = "$owner/${System.currentTimeMillis()}.jpg"
        val error = withContext(Dispatchers.IO) {
          if (!local) {
            try {
              Cloud.upload(app, owner, BUCKET, path, change.jpeg, "image/jpeg", upsert = false)
            } catch (e: CloudException) {
              return@withContext "Couldn’t upload."
            } catch (e: Exception) {
              return@withContext "No connection."
            }
            try {
              Cloud.upsert(app, owner, "profiles", "id", JSONArray().put(JSONObject().put("id", owner).put("avatar_path", path)))
            } catch (e: Exception) {
              runCatching { Cloud.remove(app, owner, BUCKET, path) }
              return@withContext "Couldn’t save."
            }
          }
          fileFor(path).writeBytes(change.jpeg)
          null
        }
        if (error != null) return@withLock error
        c.avatarPath?.let { forget(owner, it) }
        c = c.copy(avatarPath = path)
        commit(owner, c)
      }
      is PhotoChange.Use -> {
        val previous = c.avatarPath
        if (previous != null) {
          if (!local) {
            val ok = withContext(Dispatchers.IO) {
              runCatching { Cloud.upsert(app, owner, "profiles", "id", JSONArray().put(JSONObject().put("id", owner).put("avatar_path", JSONObject.NULL))) }.isSuccess
            }
            if (!ok) return@withLock "Couldn’t remove the photo."
          }
          forget(owner, previous)
        }
        c = c.copy(avatarPath = null, style = change.style)
        commit(owner, c)
      }
      null -> Unit
    }

    if (name != c.username) {
      if (!local) {
        val error = withContext(Dispatchers.IO) {
          try {
            Cloud.upsert(app, owner, "profiles", "id", JSONArray().put(JSONObject().put("id", owner).put("username", name ?: JSONObject.NULL)))
            null
          } catch (e: CloudException) {
            if (e.code == 409) "Taken." else "Couldn’t save."
          } catch (e: Exception) {
            "No connection."
          }
        }
        if (error != null) return@withLock error
      }
      commit(owner, c.copy(username = name))
    }
    null
  }

  /** An old photo: gone from the bucket and the phone. Best effort; a leftover file is harmless. */
  private suspend fun forget(owner: String, path: String) = withContext(Dispatchers.IO) {
    if (owner != Account.LOCAL) runCatching { Cloud.remove(app, owner, BUCKET, path) }
    fileFor(path).delete()
  }
}

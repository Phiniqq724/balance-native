package id.sndyy.balancenative.core

import android.content.Context
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

class CloudException(val code: Int, message: String) : IOException(message)

data class CloudUser(val id: String, val email: String?, val name: String?, val avatarUrl: String?)

/**
 * Supabase over plain REST. The session lives in [Account] prefs in the same JSON shape supabase-js
 * writes, so [CloudAuth] (used by background workers) and the UI read one source of truth.
 */
internal object Cloud {
  private fun url(context: Context) = Account.cloudUrl(context) ?: throw IOException("No cloud config")
  private fun key(context: Context) = Account.cloudKey(context) ?: throw IOException("No cloud config")
  private fun storageKey(context: Context) = "sb-${URL(url(context)).host.substringBefore('.')}-auth-token"

  private fun stored(context: Context): JSONObject? =
    runCatching { Account.getAuthItem(context, storageKey(context))?.let(::JSONObject) }.getOrNull()

  fun user(context: Context): CloudUser? {
    if (Account.cloudUrl(context) == null) return null
    val u = stored(context)?.optJSONObject("user") ?: return null
    val meta = u.optJSONObject("user_metadata")
    return CloudUser(
      id = u.optString("id").takeIf { it.isNotEmpty() } ?: return null,
      email = u.optString("email").takeIf { it.isNotEmpty() },
      name = meta?.optString("full_name")?.takeIf { it.isNotEmpty() } ?: meta?.optString("name")?.takeIf { it.isNotEmpty() },
      avatarUrl = meta?.optString("avatar_url")?.takeIf { it.isNotEmpty() },
    )
  }

  /** Google ID token (with the raw nonce whose hash Google signed) -> Supabase session. */
  fun signInWithIdToken(context: Context, idToken: String, nonce: String): CloudUser {
    val body = JSONObject().put("provider", "google").put("id_token", idToken).put("nonce", nonce)
    val res = CloudHttp.post("${url(context)}/auth/v1/token?grant_type=id_token", mapOf("apikey" to key(context)), body.toString(), 30_000)
    if (res.code !in 200..299) throw CloudException(res.code, errorMessage(res.body) ?: "Sign-in failed")
    val session = JSONObject(res.body)
    if (!session.has("expires_at") && session.has("expires_in")) {
      session.put("expires_at", System.currentTimeMillis() / 1000 + session.getLong("expires_in"))
    }
    Account.setAuthItem(context, storageKey(context), session.toString())
    return user(context) ?: throw IOException("No user in session")
  }

  /** Local scope: other phones on this account stay signed in. The session is dropped even offline. */
  fun signOut(context: Context) {
    val owner = user(context)?.id
    runCatching {
      val session = owner?.let { CloudAuth.session(context, it) }
      if (session != null) CloudHttp.request("POST", "${url(context)}/auth/v1/logout?scope=local", CloudAuth.headers(context, session), "{}")
    }
    Account.removeAuthItem(context, storageKey(context))
  }

  private fun session(context: Context, userId: String): CloudAuth.Session =
    CloudAuth.session(context, userId) ?: throw CloudException(401, "Signed out")

  private fun check(res: HttpResult): HttpResult {
    if (res.code !in 200..299) throw CloudException(res.code, errorMessage(res.body) ?: "HTTP ${res.code}")
    return res
  }

  private fun errorMessage(body: String): String? = runCatching {
    JSONObject(body).let { it.optString("message").ifEmpty { it.optString("msg") }.ifEmpty { it.optString("error_description") } }
  }.getOrNull()?.takeIf { it.isNotEmpty() }

  fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

  /**
   * A value inside a PostgREST list or logic tree (`in.(…)`, `or=(…)`): double-quoted with backslash and
   * quote escaped, so commas, dots, colons and parentheses in it stay part of the value.
   */
  fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** GET /rest/v1/[path] (a table plus PostgREST query string) as a JSON array. */
  fun select(context: Context, userId: String, path: String): JSONArray {
    val res = CloudHttp.request("GET", "${url(context)}/rest/v1/$path", CloudAuth.headers(context, session(context, userId)))
    return JSONArray(check(res).body)
  }

  fun upsert(context: Context, userId: String, table: String, onConflict: String, rows: JSONArray) {
    val headers = CloudAuth.headers(context, session(context, userId)) + ("Prefer" to "resolution=merge-duplicates,return=minimal")
    check(CloudHttp.request("POST", "${url(context)}/rest/v1/$table?on_conflict=${enc(onConflict)}", headers, rows.toString()))
  }

  fun update(context: Context, userId: String, table: String, filter: String, patch: JSONObject) {
    val headers = CloudAuth.headers(context, session(context, userId)) + ("Prefer" to "return=minimal")
    check(CloudHttp.request("PATCH", "${url(context)}/rest/v1/$table?$filter", headers, patch.toString()))
  }

  fun rpc(context: Context, userId: String, fn: String, args: JSONObject = JSONObject()): String {
    val res = CloudHttp.request("POST", "${url(context)}/rest/v1/rpc/$fn", CloudAuth.headers(context, session(context, userId)), args.toString())
    return check(res).body
  }

  /** Uploads to Storage. [upsert] replaces what's there, which needs an update policy on the bucket. */
  fun upload(context: Context, userId: String, bucket: String, path: String, bytes: ByteArray, contentType: String, upsert: Boolean = true) {
    val headers = CloudAuth.headers(context, session(context, userId)) + ("x-upsert" to upsert.toString())
    check(CloudHttp.request("POST", "${url(context)}/storage/v1/object/$bucket/$path", headers, null, contentType = contentType, bytes = bytes))
  }

  /** Deletes one object from Storage. */
  fun remove(context: Context, userId: String, bucket: String, path: String) {
    check(CloudHttp.request("DELETE", "${url(context)}/storage/v1/object/$bucket/$path", CloudAuth.headers(context, session(context, userId))))
  }

  /** A short-lived URL for a private object. */
  fun signedUrl(context: Context, userId: String, bucket: String, path: String, seconds: Int = 3600): String? {
    val res = CloudHttp.request(
      "POST", "${url(context)}/storage/v1/object/sign/$bucket/$path",
      CloudAuth.headers(context, session(context, userId)), JSONObject().put("expiresIn", seconds).toString(),
    )
    if (res.code !in 200..299) return null
    val signed = JSONObject(res.body).optString("signedURL").ifEmpty { return null }
    return "${url(context)}/storage/v1$signed"
  }
}

package id.sndyy.balancenative.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM under an Android Keystore key named [alias] that never leaves the Keystore. The ciphertext is
 * the IV followed by the sealed bytes, as Base64. A lost key (reset lock screen, restored backup) makes
 * [decrypt] return null; the caller treats that as "nothing stored".
 */
internal class Vault(private val alias: String) {
  fun encrypt(plain: String): String {
    val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
    return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
  }

  fun decrypt(sealed: String): String? = try {
    val bytes = Base64.decode(sealed, Base64.NO_WRAP)
    val cipher = Cipher.getInstance(TRANSFORM).apply {
      init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
    }
    String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
  } catch (_: Exception) {
    null
  }

  fun deleteKey() {
    runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
  }

  private fun existingKey(): SecretKey? = KeyStore.getInstance(KEYSTORE).apply { load(null) }.getKey(alias, null) as? SecretKey

  private fun key(): SecretKey {
    existingKey()?.let { return it }
    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    )
    return generator.generateKey()
  }

  private companion object {
    const val KEYSTORE = "AndroidKeyStore"
    const val TRANSFORM = "AES/GCM/NoPadding"
    const val TAG_BITS = 128
    const val IV_BYTES = 12
  }
}

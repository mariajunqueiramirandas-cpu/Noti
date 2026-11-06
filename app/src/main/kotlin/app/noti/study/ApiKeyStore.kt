package app.noti.study

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The pasted Mistral API key, encrypted with an AES key that lives in the Android
 * Keystore (it never leaves the secure hardware), stored in its own preferences file
 * that is excluded from backups (see res/xml). Main thread.
 */
class ApiKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("study_keys", Context.MODE_PRIVATE)

    /** Whether a key is saved; Compose state. */
    var hasKey by mutableStateOf(prefs.contains(KEY))
        private set

    /** The last characters of the saved key, to show which one it is. */
    var hint by mutableStateOf(prefs.getString(HINT, "") ?: "")
        private set

    fun load(): String? = runCatching {
        val stored = prefs.getString(KEY, null) ?: return null
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes, 0, IV))
        String(cipher.doFinal(bytes, IV, bytes.size - IV), Charsets.UTF_8)
    }.getOrNull()

    /** Throws if the device can't keep it safe; never stores it in the clear. */
    fun save(key: String) {
        val clean = key.trim()
        require(clean.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val iv = cipher.iv
        check(iv.size == IV)
        val out = iv + cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val shown = "…" + clean.takeLast(4)
        prefs.edit().putString(KEY, Base64.encodeToString(out, Base64.NO_WRAP)).putString(HINT, shown).apply()
        hasKey = true
        hint = shown
    }

    fun clear() {
        prefs.edit().remove(KEY).remove(HINT).apply()
        hasKey = false
        hint = ""
    }

    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "noti-mistral-key"
        const val KEY = "mistral"
        const val HINT = "mistralHint"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}

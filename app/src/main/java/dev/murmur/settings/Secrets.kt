package dev.murmur.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys (ElevenLabs, Anthropic), encrypted with an Android keystore key (AES-GCM) and kept in the
 * "murmur" prefs. They never go into backups (see [Backup]). Needs Android 6.
 */
object Secrets {
    private const val PREFS = "murmur"
    private const val KEYSTORE_ALIAS = "murmur_elevenlabs" // first used for ElevenLabs; shared by every key
    val supported get() = android.os.Build.VERSION.SDK_INT >= 23

    const val SAVED_AT = "_saved_at"

    fun has(ctx: Context, name: String) = prefs(ctx).contains(name)

    /** When the key was saved, or null if there's none (keys saved before this was tracked report null too). */
    fun savedAt(ctx: Context, name: String): Long? = prefs(ctx).getLong(name + SAVED_AT, -1).takeIf { it > 0 && has(ctx, name) }

    fun put(ctx: Context, name: String, value: String?) {
        if (value.isNullOrBlank() || !supported) { prefs(ctx).edit().remove(name).remove(name + SAVED_AT).apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()) }
        val blob = cipher.iv + cipher.doFinal(value.trim().toByteArray())
        prefs(ctx).edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putLong(name + SAVED_AT, System.currentTimeMillis()).apply()
    }

    fun get(ctx: Context, name: String): String? = if (!supported) null else runCatching {
        val blob = Base64.decode(prefs(ctx).getString(name, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, blob, 0, 12))
        String(cipher.doFinal(blob, 12, blob.size - 12))
    }.getOrNull()

    @android.annotation.SuppressLint("NewApi") // only reached when [supported]
    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

package com.brainstormity.voicecontrol.voice.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.brainstormity.voicecontrol.core.VcLog
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the ChatGPT tokens in the app's private SharedPreferences (`vc_chatgpt_auth`), encrypted with an
 * AES-256-GCM key that lives in the Android Keystore (alias `vc_chatgpt_auth_key`, never exportable).
 *
 * Why not EncryptedSharedPreferences: androidx.security:security-crypto is deprecated, and this is a
 * single small blob, so plain Keystore + AES-GCM is ~40 lines and needs no dependency.
 */
internal object TokenStore {
    private const val TAG = "Auth"
    private const val PREFS = "vc_chatgpt_auth"
    private const val KEY_BLOB = "tokens_v1"
    private const val ALIAS = "vc_chatgpt_auth_key"

    data class Tokens(
        val idToken: String?,
        val accessToken: String,
        val refreshToken: String,
        val accountId: String,
        val lastRefreshMs: Long,
    )

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): Tokens? {
        val blob = prefs(ctx).getString(KEY_BLOB, null) ?: return null
        return try {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            val o = JSONObject(String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8))
            Tokens(
                idToken = o.optString("id_token").ifBlank { null },
                accessToken = o.getString("access_token"),
                refreshToken = o.getString("refresh_token"),
                accountId = o.getString("account_id"),
                lastRefreshMs = o.optLong("last_refresh_ms", 0L),
            )
        } catch (e: Exception) {
            VcLog.w(TAG, "stored ChatGPT tokens unreadable (${e.javaClass.simpleName}); clearing")
            prefs(ctx).edit().remove(KEY_BLOB).commit()
            null
        }
    }

    @Synchronized
    fun save(ctx: Context, t: Tokens) {
        val o = JSONObject()
            .put("id_token", t.idToken ?: "")
            .put("access_token", t.accessToken)
            .put("refresh_token", t.refreshToken)
            .put("account_id", t.accountId)
            .put("last_refresh_ms", t.lastRefreshMs)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(o.toString().toByteArray(Charsets.UTF_8))
        // commit(), not apply(): refresh tokens rotate, so the new one must be on disk before we use it.
        prefs(ctx).edit().putString(KEY_BLOB, Base64.encodeToString(iv + ct, Base64.NO_WRAP)).commit()
    }

    @Synchronized
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_BLOB).commit()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}

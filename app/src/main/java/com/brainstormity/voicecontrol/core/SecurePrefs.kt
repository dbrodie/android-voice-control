package com.brainstormity.voicecontrol.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small strings encrypted for SharedPreferences: AES-256-GCM with a key that lives in the Android Keystore
 * (never exportable). The same scheme as voice/auth/TokenStore, as a reusable helper.
 *
 * Blob format: Base64(NO_WRAP) of `iv (12 bytes) || ciphertext+tag`.
 */
object SecurePrefs {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12

    /** Encrypts [plain] with the Keystore key [alias] (created on first use). */
    @Synchronized
    fun encrypt(alias: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    /** Decrypts a blob from [encrypt]; null if it can't be read (corrupt, or the Keystore key is gone). */
    @Synchronized
    fun decrypt(alias: String, blob: String): String? = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, raw.copyOfRange(0, IV_BYTES)))
        String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    } catch (e: Exception) {
        VcLog.w("Settings", "encrypted value for $alias unreadable (${e.javaClass.simpleName})")
        null
    }

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }
}

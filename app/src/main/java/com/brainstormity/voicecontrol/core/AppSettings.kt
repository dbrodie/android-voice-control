package com.brainstormity.voicecontrol.core

import android.content.Context
import android.content.SharedPreferences
import com.brainstormity.voicecontrol.BuildConfig

/**
 * SharedPreferences-backed settings. A blank/unset stored value falls back to the BuildConfig default
 * (which comes from the project-root `.env` at build time; always "" for keys in release builds).
 *
 * The JEV API key is stored encrypted ([SecurePrefs], Keystore alias `vc_jev_key`) under `jev_api_key_enc`;
 * a plain `jev_api_key` value left by older versions is migrated on first read.
 */
object AppSettings {
    private const val PREFS = "vc_settings"
    private const val KEY_JEV_PLAIN = "jev_api_key"        // pre-T7: plain text, migrated + removed
    private const val KEY_JEV_ENC = "jev_api_key_enc"
    private const val KEY_JEV_CHECK = "jev_key_check"       // last verification result, human readable
    private const val KEY_JEV_CHECK_AT = "jev_key_check_at"
    private const val KEY_JEV_PROMPTED = "jev_key_prompted" // first-run routing to JevKeyActivity done
    private const val JEV_ALIAS = "vc_jev_key"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_MODEL = "realtime_model"
    private const val KEY_BACKEND = "voice_backend"
    private const val KEY_SYSTEM_ONE = "system_one_backend"

    /** Which GPT-Live transport to use. */
    const val BACKEND_AUTO = "auto"          // ChatGPT sign-in if signed in, else OpenAI API key if set
    const val BACKEND_CHATGPT = "chatgpt"    // unofficial Codex GPT-Live route (WebRTC), needs Sign in with ChatGPT
    const val BACKEND_OPENAI_KEY = "openai_key" // official GPT-Live API over WebSocket, needs an OpenAI API key

    /** Which System One backend decides each step (T15, agent/SystemOneBackends). */
    const val SYSTEM_ONE_AUTO = "auto"       // the on-device model when it is Ready, else JEV
    const val SYSTEM_ONE_JEV = "jev"         // TypeSafe JEV (cloud), needs a JEV API key
    const val SYSTEM_ONE_LAYA = "laya"       // on-device Laya model (T17)

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(ctx: Context, key: String, default: String): String =
        prefs(ctx).getString(key, null)?.takeIf { it.isNotBlank() } ?: default

    private fun set(ctx: Context, key: String, v: String) {
        prefs(ctx).edit().putString(key, v.trim()).apply()
    }

    /** The key the agent uses: the one the user entered, else the debug build's `.env` key, else "". */
    fun jevApiKey(ctx: Context): String = storedJevApiKey(ctx) ?: BuildConfig.JEV_KEY

    /** Stores the user's key encrypted (all whitespace removed); blank removes it. */
    fun setJevApiKey(ctx: Context, v: String) {
        val key = v.filterNot { it.isWhitespace() }
        synchronized(this) {
            val edit = prefs(ctx).edit().remove(KEY_JEV_PLAIN)
            if (key.isEmpty()) edit.remove(KEY_JEV_ENC).remove(KEY_JEV_CHECK).remove(KEY_JEV_CHECK_AT)
            else edit.putString(KEY_JEV_ENC, SecurePrefs.encrypt(JEV_ALIAS, key))
            edit.commit()
            jevCache = key.ifEmpty { null }
            jevLoaded = true
        }
    }

    /** The user-entered key only (null if none), decrypted. */
    fun storedJevApiKey(ctx: Context): String? {
        if (jevLoaded) return jevCache
        synchronized(this) {
            if (jevLoaded) return jevCache
            val p = prefs(ctx)
            val plain = p.getString(KEY_JEV_PLAIN, null)?.filterNot { it.isWhitespace() }
            jevCache = if (!plain.isNullOrEmpty()) {
                VcLog.i("Settings", "migrating the plain-text JEV key to encrypted storage")
                p.edit().putString(KEY_JEV_ENC, SecurePrefs.encrypt(JEV_ALIAS, plain)).remove(KEY_JEV_PLAIN).commit()
                plain
            } else {
                if (plain != null) p.edit().remove(KEY_JEV_PLAIN).commit()
                p.getString(KEY_JEV_ENC, null)?.let { SecurePrefs.decrypt(JEV_ALIAS, it) }?.ifBlank { null }
            }
            jevLoaded = true
            return jevCache
        }
    }

    @Volatile private var jevCache: String? = null
    @Volatile private var jevLoaded = false

    /** Last "Verify & save" result shown on the key screen, with when it happened (ms), or null. */
    fun jevKeyCheck(ctx: Context): Pair<String, Long>? {
        val text = prefs(ctx).getString(KEY_JEV_CHECK, null) ?: return null
        return text to prefs(ctx).getLong(KEY_JEV_CHECK_AT, 0L)
    }
    fun setJevKeyCheck(ctx: Context, text: String) {
        prefs(ctx).edit().putString(KEY_JEV_CHECK, text).putLong(KEY_JEV_CHECK_AT, System.currentTimeMillis()).apply()
    }

    /** First-run routing to the key screen happens once; after that the Setup row is the way in. */
    fun jevKeyPrompted(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_JEV_PROMPTED, false)
    fun setJevKeyPrompted(ctx: Context) { prefs(ctx).edit().putBoolean(KEY_JEV_PROMPTED, true).apply() }

    fun openAiApiKey(ctx: Context): String = get(ctx, KEY_OPENAI, BuildConfig.OPENAI_API_KEY)
    fun setOpenAiApiKey(ctx: Context, v: String) = set(ctx, KEY_OPENAI, v)

    fun realtimeModel(ctx: Context): String = get(ctx, KEY_MODEL, BuildConfig.OPENAI_REALTIME_MODEL)
    fun setRealtimeModel(ctx: Context, v: String) = set(ctx, KEY_MODEL, v)

    fun voiceBackend(ctx: Context): String = get(ctx, KEY_BACKEND, BACKEND_AUTO)
    fun setVoiceBackend(ctx: Context, v: String) = set(ctx, KEY_BACKEND, v)

    fun systemOneBackend(ctx: Context): String = get(ctx, KEY_SYSTEM_ONE, SYSTEM_ONE_AUTO)
    fun setSystemOneBackend(ctx: Context, v: String) = set(ctx, KEY_SYSTEM_ONE, v)
}

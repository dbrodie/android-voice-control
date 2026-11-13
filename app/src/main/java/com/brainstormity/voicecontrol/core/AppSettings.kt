package com.brainstormity.voicecontrol.core

import android.content.Context
import android.content.SharedPreferences
import com.brainstormity.voicecontrol.BuildConfig

/**
 * SharedPreferences-backed settings. A blank/unset stored value falls back to the BuildConfig default
 * (which comes from the project-root `.env` at build time).
 */
object AppSettings {
    private const val PREFS = "vc_settings"
    private const val KEY_JEV = "jev_api_key"
    private const val KEY_OPENAI = "openai_api_key"
    private const val KEY_MODEL = "realtime_model"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun get(ctx: Context, key: String, default: String): String =
        prefs(ctx).getString(key, null)?.takeIf { it.isNotBlank() } ?: default

    private fun set(ctx: Context, key: String, v: String) {
        prefs(ctx).edit().putString(key, v.trim()).apply()
    }

    fun jevApiKey(ctx: Context): String = get(ctx, KEY_JEV, BuildConfig.JEV_KEY)
    fun setJevApiKey(ctx: Context, v: String) = set(ctx, KEY_JEV, v)

    fun openAiApiKey(ctx: Context): String = get(ctx, KEY_OPENAI, BuildConfig.OPENAI_API_KEY)
    fun setOpenAiApiKey(ctx: Context, v: String) = set(ctx, KEY_OPENAI, v)

    fun realtimeModel(ctx: Context): String = get(ctx, KEY_MODEL, BuildConfig.OPENAI_REALTIME_MODEL)
    fun setRealtimeModel(ctx: Context, v: String) = set(ctx, KEY_MODEL, v)
}

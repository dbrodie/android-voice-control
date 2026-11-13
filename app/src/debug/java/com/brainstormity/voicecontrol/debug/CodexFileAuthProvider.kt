package com.brainstormity.voicecontrol.debug

import android.content.Context
import android.util.Base64
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.live.LiveAuthProvider
import com.brainstormity.voicecontrol.voice.live.LiveCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * DEBUG ONLY. Reads a Codex CLI sign-in (`~/.codex/auth.json`) pushed to the device:
 *   adb push ~/.codex/auth.json /data/local/tmp/codex-auth.json
 *   # if the app can't read /data/local/tmp (SELinux), copy it into the app's files dir:
 *   adb shell run-as com.brainstormity.voicecontrol cp /data/local/tmp/codex-auth.json files/codex-auth.json
 * Uses `tokens.access_token` and `tokens.account_id` (or the `https://api.openai.com/auth`.chatgpt_account_id
 * JWT claim). No refresh: T4a's ChatGptAuth owns that. Never logs token contents.
 */
class CodexFileAuthProvider(private val context: Context) : LiveAuthProvider {
    override suspend fun credentials(): LiveCredentials? = withContext(Dispatchers.IO) {
        val candidates = listOf(File(context.filesDir, "codex-auth.json"), File("/data/local/tmp/codex-auth.json"))
        for (file in candidates) {
            val text = try {
                if (file.canRead()) file.readText() else null
            } catch (e: Exception) {
                VcLog.w(TAG, "cannot read ${file.path}: ${e.javaClass.simpleName}")
                null
            } ?: continue
            val creds = parse(text)
            if (creds != null) {
                VcLog.i(TAG, "debug credentials loaded from ${file.path} (token ${creds.accessToken.length} chars)")
                return@withContext creds
            }
            VcLog.w(TAG, "${file.path} has no usable tokens")
        }
        VcLog.w(TAG, "no debug credentials: push ~/.codex/auth.json to /data/local/tmp/codex-auth.json")
        null
    }

    companion object {
        private const val TAG = "Debug"

        fun parse(json: String): LiveCredentials? {
            val root = try { JSONObject(json) } catch (_: Exception) { return null }
            val tokens = root.optJSONObject("tokens") ?: return null
            val access = tokens.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            val account = tokens.optString("account_id").takeIf { it.isNotBlank() }
                ?: accountFromJwt(access)
                ?: tokens.optString("id_token").takeIf { it.isNotBlank() }?.let(::accountFromJwt)
                ?: return null
            return LiveCredentials(access, account)
        }

        private fun accountFromJwt(jwt: String): String? = try {
            val payload = jwt.split('.').getOrNull(1) ?: return null
            val claims = JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)))
            claims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }
}

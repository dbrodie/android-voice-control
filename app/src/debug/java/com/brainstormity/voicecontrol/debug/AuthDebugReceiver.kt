package com.brainstormity.voicecontrol.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.auth.AuthState
import com.brainstormity.voicecontrol.voice.auth.ChatGptAuth
import com.brainstormity.voicecontrol.voice.auth.LoginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Debug-only adb hooks for voice/auth (log tag VC/AuthDebug; tokens are never logged):
 *   AUTH_IMPORT  --es path <file>   import a Codex ~/.codex/auth.json (relative paths are under the app's files/)
 *   AUTH_STATUS                     state, account id, plan, access-token expiry, last refresh
 *   AUTH_REFRESH                    force a refresh-token grant, log result + new expiry
 *   AUTH_SIGNOUT                    forget the tokens
 *   AUTH_LOGIN                      open LoginActivity and start the browser sign-in
 *   AUTH_PROBE                      POST a dummy offer to the GPT-Live call endpoint, log status + body excerpt
 *   AUTH_CALLBACK [--es code C] [--es state S] [--es error E] [--ez direct true]
 *                                   play OpenAI's redirect for the pending sign-in: opens
 *                                   http://127.0.0.1:<port>/auth/callback?code=C&state=<real state unless S> in the
 *                                   browser (or, with direct=true, sends it straight to the loopback server)
 * Actions are prefixed com.brainstormity.voicecontrol. Same manifest + runtime-registered pattern as DebugReceiver.
 */
class AuthDebugReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {

    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        val action = intent.action ?: return
        val ctx = context.applicationContext
        VcLog.i(TAG, "got $action (${if (runtimeRegistered) "runtime" else "manifest"})")
        val pending = goAsync()
        scope.launch {
            try {
                when (action) {
                    AUTH_IMPORT -> import(ctx, intent.getStringExtra("path"))
                    AUTH_STATUS -> status(ctx)
                    AUTH_REFRESH -> {
                        val r = ChatGptAuth.refreshNow(ctx)
                        VcLog.i(TAG, "AUTH_REFRESH ok=${r.ok} msg=${r.message} access expires ${r.expiresAtMs?.let(ChatGptAuth::fmt)}")
                        status(ctx)
                    }
                    AUTH_SIGNOUT -> { ChatGptAuth.signOut(ctx); status(ctx) }
                    AUTH_LOGIN -> LoginActivity.start(ctx, autoStart = true)
                    AUTH_PROBE -> probe(ctx)
                    AUTH_CALLBACK -> callback(ctx, intent)
                    else -> VcLog.w(TAG, "unknown action $action")
                }
            } catch (e: Exception) {
                VcLog.e(TAG, "$action failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun import(ctx: Context, path: String?) {
        if (path.isNullOrBlank()) { VcLog.w(TAG, "AUTH_IMPORT needs --es path <file>"); return }
        val file = if (path.startsWith("/")) File(path) else File(ctx.filesDir, path)
        val json = try {
            file.readText()
        } catch (e: Exception) {
            VcLog.e(
                TAG, "AUTH_IMPORT can't read $file (${e.javaClass.simpleName}: ${e.message}). Copy it into the " +
                    "app's private dir instead: adb exec-in run-as com.brainstormity.voicecontrol sh -c " +
                    "'cat > files/codex-auth.json' < ~/.codex/auth.json  then  --es path codex-auth.json"
            )
            return
        }
        val ok = ChatGptAuth.importCodexAuthJson(ctx, json)
        VcLog.i(TAG, "AUTH_IMPORT ${if (ok) "OK" else "FAILED"} from $file")
        // Don't leave a copy of the tokens lying around inside the app sandbox.
        if (file.canonicalPath.startsWith(ctx.filesDir.canonicalPath)) file.delete()
        status(ctx)
    }

    private fun callback(ctx: Context, intent: Intent) {
        val url = ChatGptAuth.debugCallbackUrl(
            code = intent.getStringExtra("code") ?: if (intent.hasExtra("error")) null else "debug-bogus-code",
            state = intent.getStringExtra("state"),
            error = intent.getStringExtra("error"),
        ) ?: run { VcLog.w(TAG, "AUTH_CALLBACK: no sign-in is waiting (${ChatGptAuth.login.value})"); return }
        val shown = url.replace(Regex("state=[^&]*"), "state=" + if (intent.hasExtra("state")) "<override>" else "<real>")
        if (intent.getBooleanExtra("direct", false)) {
            // Raw socket: OkHttp/HttpURLConnection refuse cleartext http:// under the default security config.
            val u = Uri.parse(url)
            java.net.Socket("127.0.0.1", u.port).use { s ->
                s.soTimeout = 90_000
                s.getOutputStream().write(
                    "GET ${u.encodedPath}?${u.encodedQuery} HTTP/1.1\r\nHost: 127.0.0.1:${u.port}\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                val status = s.getInputStream().bufferedReader().readLine()
                VcLog.i(TAG, "AUTH_CALLBACK direct $shown -> $status")
            }
        } else {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            VcLog.i(TAG, "AUTH_CALLBACK opened $shown in the browser")
        }
    }

    private fun status(ctx: Context) {
        val s = ChatGptAuth.state.value
        val info = ChatGptAuth.info(ctx)
        val stateText = when (s) {
            is AuthState.SignedIn -> "SignedIn"
            else -> s.toString()
        }
        if (info == null) {
            VcLog.i(TAG, "AUTH_STATUS state=$stateText signedIn=false login=${ChatGptAuth.login.value}")
        } else {
            VcLog.i(
                TAG, "AUTH_STATUS state=$stateText signedIn=true account_id=${info.accountId} plan=${info.plan} " +
                    "email=${info.email} hasIdToken=${info.hasIdToken} " +
                    "access_expires=${info.accessExpiresAtMs?.let(ChatGptAuth::fmt)} " +
                    "last_refresh=${ChatGptAuth.fmt(info.lastRefreshMs)}"
            )
        }
    }

    /** Minimal GPT-Live call-creation probe; same headers as upstream opencode-gpt-live live.ts. */
    private suspend fun probe(ctx: Context) {
        val creds = ChatGptAuth.credentials(ctx) ?: run { VcLog.w(TAG, "AUTH_PROBE: not signed in"); return }
        val body = JSONObject()
            .put("sdp", "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n")
            .put(
                "session", JSONObject()
                    .put("model", "gpt-live-1-codex")
                    .put("instructions", "test")
                    .put("audio", JSONObject().put("output", JSONObject().put("voice", "marin")))
                    .put("delegation", JSONObject().put("type", "client"))
            )
        val req = Request.Builder()
            .url("https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas")
            .header("Authorization", "Bearer ${creds.accessToken}")
            .header("ChatGPT-Account-ID", creds.accountId)
            .header("OpenAI-Alpha", "quicksilver=v2")
            .header("session-id", UUID.randomUUID().toString())
            .header("thread-id", UUID.randomUUID().toString())
            .header("x-session-id", UUID.randomUUID().toString())
            .header("originator", "opencode")
            .header("User-Agent", "opencode-gpt-live/0.1.0")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        client.newCall(req).execute().use { resp ->
            val text = (resp.body?.string() ?: "")
                .replace(creds.accessToken, "[redacted]").replace(creds.accountId, "[account]")
            val hdrs = listOf("content-type", "location", "openai-session-id", "cf-ray", "x-request-id")
                .mapNotNull { h -> resp.header(h)?.let { "$h=${it.take(80)}" } }
            VcLog.i(TAG, "AUTH_PROBE HTTP ${resp.code} ${resp.message} headers{${hdrs.joinToString()}}")
            VcLog.i(TAG, "AUTH_PROBE body[0..300]: ${text.take(300).replace("\n", "\\n").replace("\r", "\\r")}")
        }
    }

    companion object {
        private const val TAG = "AuthDebug"
        private const val P = "com.brainstormity.voicecontrol."
        const val AUTH_IMPORT = P + "AUTH_IMPORT"
        const val AUTH_STATUS = P + "AUTH_STATUS"
        const val AUTH_REFRESH = P + "AUTH_REFRESH"
        const val AUTH_SIGNOUT = P + "AUTH_SIGNOUT"
        const val AUTH_LOGIN = P + "AUTH_LOGIN"
        const val AUTH_PROBE = P + "AUTH_PROBE"
        const val AUTH_CALLBACK = P + "AUTH_CALLBACK"
        val ACTIONS = listOf(AUTH_IMPORT, AUTH_STATUS, AUTH_REFRESH, AUTH_SIGNOUT, AUTH_LOGIN, AUTH_PROBE, AUTH_CALLBACK)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Debug-only: registers the runtime AuthDebugReceiver at process start so implicit broadcasts arrive. */
class AuthDebugReceiverInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val filter = IntentFilter().apply { AuthDebugReceiver.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(ctx, AuthDebugReceiver(runtimeRegistered = true), filter, ContextCompat.RECEIVER_EXPORTED)
        VcLog.d("AuthDebug", "runtime AuthDebugReceiver registered")
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}

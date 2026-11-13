package com.brainstormity.voicecontrol.voice.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed class AuthState {
    object SignedOut : AuthState() { override fun toString() = "SignedOut" }
    object SigningIn : AuthState() { override fun toString() = "SigningIn" }
    data class SignedIn(val email: String?, val plan: String?, val accountId: String) : AuthState()
    data class Error(val message: String) : AuthState()
}

/** What a GPT-Live request needs: `Authorization: Bearer <accessToken>` and `ChatGPT-Account-ID: <accountId>`. */
data class Credentials(val accessToken: String, val accountId: String) {
    override fun toString() = "Credentials(accessToken=[redacted], accountId=$accountId)"
}

/**
 * "Sign in with ChatGPT" using the Codex CLI's OAuth client (see README.md in this package).
 *
 * Usage from the live transport:
 * ```
 * val creds = ChatGptAuth.credentials(ctx) ?: error("not signed in")   // refreshes if < 5 min left
 * headers: Authorization: Bearer ${creds.accessToken}, ChatGPT-Account-ID: ${creds.accountId}
 * ```
 * On HTTP 401 from the backend, call `credentials(ctx, forceRefresh = true)` once and retry.
 * Never log tokens.
 */
object ChatGptAuth {
    private const val TAG = "Auth"
    /** Codex: CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES = 5. */
    private const val REFRESH_WINDOW_MS = 5 * 60_000L
    private const val LOGIN_TIMEOUT_MS = 3 * 60_000L

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val lock = Any()
    @Volatile private var initialized = false
    private var loginJob: Job? = null
    private var loginServer: LoopbackServer? = null
    private var loginGen = 0

    /** Loads the stored sign-in and publishes it to [state]. Idempotent; every ctx-taking method calls it. */
    fun init(ctx: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true
            if (_state.value == AuthState.SignedOut) publishStored(ctx)
        }
    }

    fun isSignedIn(ctx: Context): Boolean {
        init(ctx)
        return TokenStore.load(ctx) != null
    }

    /**
     * Valid credentials, or null if not signed in / the sign-in is no longer usable.
     * Refreshes first when the access token expires within 5 minutes (or [forceRefresh]).
     * If a refresh fails but the current token hasn't expired yet, the current token is returned.
     */
    suspend fun credentials(ctx: Context, forceRefresh: Boolean = false): Credentials? = withContext(Dispatchers.IO) {
        init(ctx)
        val t = TokenStore.load(ctx) ?: return@withContext null
        if (!forceRefresh && !needsRefresh(t)) return@withContext Credentials(t.accessToken, t.accountId)
        refreshLocked(ctx, t, if (forceRefresh) "forced" else "proactive_expiry")
    }

    /** Result of [refreshNow] (debug / settings use). Contains no secrets. */
    data class RefreshResult(val ok: Boolean, val message: String, val expiresAtMs: Long?)

    /** Forces a refresh-token grant now. */
    suspend fun refreshNow(ctx: Context): RefreshResult = withContext(Dispatchers.IO) {
        init(ctx)
        val before = TokenStore.load(ctx) ?: return@withContext RefreshResult(false, "not signed in", null)
        try {
            val after = refreshOrThrow(ctx, before, "forced")
            RefreshResult(true, "refreshed", Jwt.expiresAtMs(after.accessToken))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RefreshResult(false, e.message ?: e.javaClass.simpleName, Jwt.expiresAtMs(before.accessToken))
        }
    }

    private fun needsRefresh(t: TokenStore.Tokens): Boolean {
        val exp = Jwt.expiresAtMs(t.accessToken) ?: return false // no exp claim: let the server decide (401)
        return exp - System.currentTimeMillis() <= REFRESH_WINDOW_MS
    }

    private suspend fun refreshLocked(ctx: Context, seen: TokenStore.Tokens, reason: String): Credentials? {
        return try {
            val t = refreshOrThrow(ctx, seen, reason)
            Credentials(t.accessToken, t.accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val current = TokenStore.load(ctx) ?: return null
            val exp = Jwt.expiresAtMs(current.accessToken)
            if (exp != null && exp > System.currentTimeMillis() + 30_000) {
                VcLog.w(TAG, "refresh failed (${e.message}); using the current token, still valid until ${fmt(exp)}")
                Credentials(current.accessToken, current.accountId)
            } else {
                null
            }
        }
    }

    /** Serialized: refresh tokens rotate, so two concurrent refreshes would burn each other's token. */
    private suspend fun refreshOrThrow(ctx: Context, seen: TokenStore.Tokens, reason: String): TokenStore.Tokens =
        refreshMutex.withLock {
            val t = TokenStore.load(ctx) ?: throw IllegalStateException("not signed in")
            // Someone else refreshed while we waited for the lock: use theirs (unless this one is forced and nobody did).
            if (t.accessToken != seen.accessToken && (reason == "forced" || !needsRefresh(t))) return@withLock t
            VcLog.i(TAG, "refreshing ChatGPT access token (reason=$reason)")
            val resp = try {
                CodexOAuth.refresh(t.refreshToken, reason)
            } catch (e: CodexOAuth.TokenEndpointException) {
                VcLog.e(TAG, "refresh failed: HTTP ${e.status} ${e.errorCode ?: ""} permanent=${e.isPermanent}")
                if (e.isPermanent) {
                    _state.value = AuthState.Error("ChatGPT sign-in expired or was revoked (${e.errorCode ?: e.status}). Sign in again.")
                }
                throw e
            }
            val access = resp.accessToken ?: t.accessToken
            val idToken = resp.idToken ?: t.idToken
            val updated = TokenStore.Tokens(
                idToken = idToken,
                accessToken = access,
                refreshToken = resp.refreshToken ?: t.refreshToken,
                accountId = Jwt.accountId(idToken) ?: Jwt.accountId(access) ?: t.accountId,
                lastRefreshMs = System.currentTimeMillis(),
            )
            TokenStore.save(ctx, updated)
            VcLog.i(
                TAG, "refresh ok: new access token expires ${Jwt.expiresAtMs(access)?.let(::fmt)}, " +
                    "refresh token rotated=${resp.refreshToken != null && resp.refreshToken != t.refreshToken}"
            )
            if (_state.value !is AuthState.SigningIn) _state.value = signedIn(updated)
            updated
        }

    /** Starts the browser sign-in: loopback server on 127.0.0.1:1455, authorize URL in the browser. */
    fun startLogin(ctx: Context) {
        init(ctx)
        val app = ctx.applicationContext
        synchronized(lock) {
            loginJob?.cancel()
            loginServer?.close()
            _state.value = AuthState.SigningIn
            val gen = ++loginGen
            // Started here (caller is normally a foreground activity) so the process isn't frozen while the
            // browser is in front; stopped when this login attempt ends.
            LoginKeepAliveService.start(app)
            loginJob = scope.launch { runLogin(app, gen) }
        }
    }

    /** Aborts a pending browser sign-in (no-op otherwise). */
    fun cancelLogin(ctx: Context) {
        synchronized(lock) {
            loginJob?.cancel()
            loginServer?.close()
        }
        if (_state.value == AuthState.SigningIn) publishStored(ctx)
    }

    fun signOut(ctx: Context) {
        synchronized(lock) {
            loginJob?.cancel()
            loginServer?.close()
        }
        TokenStore.clear(ctx)
        initialized = true
        _state.value = AuthState.SignedOut
        VcLog.i(TAG, "signed out")
    }

    /**
     * Imports a Codex CLI `~/.codex/auth.json`:
     * `{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"id_token","access_token","refresh_token","account_id"},"last_refresh":"..."}`.
     * `account_id` is taken from the id_token / access_token claims when missing. Returns false if unusable.
     */
    fun importCodexAuthJson(ctx: Context, json: String): Boolean {
        init(ctx)
        return try {
            val root = JSONObject(json)
            val tokens = root.optJSONObject("tokens") ?: run {
                VcLog.w(TAG, "import: no \"tokens\" object (API-key-only auth.json?)"); return false
            }
            fun s(k: String) = if (tokens.has(k) && !tokens.isNull(k)) tokens.optString(k).takeIf { it.isNotBlank() } else null
            // id_token is a raw JWT string in auth.json; tolerate an object with raw_jwt just in case.
            val idToken = s("id_token")?.takeIf { it.count { c -> c == '.' } == 2 }
                ?: tokens.optJSONObject("id_token")?.optString("raw_jwt")?.takeIf { it.isNotBlank() }
            val access = s("access_token") ?: run { VcLog.w(TAG, "import: no access_token"); return false }
            val refresh = s("refresh_token") ?: run { VcLog.w(TAG, "import: no refresh_token"); return false }
            val accountId = s("account_id") ?: Jwt.accountId(idToken) ?: Jwt.accountId(access) ?: run {
                VcLog.w(TAG, "import: no account id in file or token claims"); return false
            }
            val t = TokenStore.Tokens(idToken, access, refresh, accountId, System.currentTimeMillis())
            TokenStore.save(ctx, t)
            _state.value = signedIn(t)
            VcLog.i(TAG, "imported Codex auth.json: account=$accountId plan=${planOf(t)} access expires ${Jwt.expiresAtMs(access)?.let(::fmt)}")
            true
        } catch (e: Exception) {
            VcLog.w(TAG, "import failed: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            false
        }
    }

    /** Non-secret description of the stored sign-in, for UI and debug logs. Null if signed out. */
    data class Info(
        val email: String?, val plan: String?, val accountId: String,
        val accessExpiresAtMs: Long?, val lastRefreshMs: Long, val hasIdToken: Boolean,
    )

    fun info(ctx: Context): Info? {
        init(ctx)
        val t = TokenStore.load(ctx) ?: return null
        return Info(emailOf(t), planOf(t), t.accountId, Jwt.expiresAtMs(t.accessToken), t.lastRefreshMs, t.idToken != null)
    }

    // ---- internals ----

    private suspend fun runLogin(app: Context, gen: Int) {
        var server: LoopbackServer? = null
        try {
            server = try {
                LoopbackServer.bind()
            } catch (e: Exception) {
                fail("Can't start the sign-in listener: ${e.message}"); return
            }
            synchronized(lock) { loginServer = server }
            val pkce = CodexOAuth.generatePkce()
            val state = CodexOAuth.generateState()
            val redirectUri = CodexOAuth.redirectUri(server.port)
            val url = CodexOAuth.authorizeUrl(redirectUri, pkce, state)
            VcLog.i(TAG, "login: listening on 127.0.0.1:${server.port}, opening browser")
            try {
                withContext(Dispatchers.Main) {
                    app.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addCategory(Intent.CATEGORY_BROWSABLE)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            } catch (e: ActivityNotFoundException) {
                fail("No browser installed to sign in with."); return
            }

            when (val r = server.await(System.currentTimeMillis() + LOGIN_TIMEOUT_MS)) {
                LoopbackServer.Result.TimedOut -> fail("Sign-in timed out after 3 minutes.")
                LoopbackServer.Result.Cancelled -> {
                    VcLog.i(TAG, "login cancelled"); publishStored(app)
                }
                is LoopbackServer.Result.Callback -> handleCallback(app, r, state, redirectUri, pkce)
            }
        } catch (e: CancellationException) {
            VcLog.i(TAG, "login job cancelled")
            throw e
        } catch (e: Exception) {
            VcLog.e(TAG, "login failed", e)
            fail("Sign-in failed: ${e.message}")
        } finally {
            server?.close()
            synchronized(lock) {
                if (loginServer === server) loginServer = null
                if (gen == loginGen) LoginKeepAliveService.stop(app)
            }
        }
    }

    private fun handleCallback(
        app: Context, cb: LoopbackServer.Result.Callback, state: String, redirectUri: String, pkce: CodexOAuth.Pkce,
    ) {
        val p = cb.params
        VcLog.i(TAG, "login callback received (has_code=${!p["code"].isNullOrEmpty()} has_error=${p["error"] != null} state_ok=${p["state"] == state})")
        val problem = when {
            p["state"] != state -> "State mismatch, please try again."
            p["error"] != null -> "OpenAI returned ${p["error"]}${p["error_description"]?.let { ": $it" } ?: ""}"
            p["code"].isNullOrEmpty() -> "Missing authorization code."
            else -> null
        }
        if (problem != null) {
            cb.respond(400, page(app, false, problem)); fail(problem); bringToFront(app); return
        }
        val tokens = try {
            CodexOAuth.exchangeCode(p.getValue("code"), redirectUri, pkce)
        } catch (e: Exception) {
            val msg = "Token exchange failed: ${e.message}"
            cb.respond(400, page(app, false, msg)); fail(msg); bringToFront(app); return
        }
        val access = tokens.accessToken
        val refresh = tokens.refreshToken
        val accountId = Jwt.accountId(tokens.idToken) ?: Jwt.accountId(access)
        if (access == null || refresh == null || accountId == null) {
            val msg = "Sign-in response was missing tokens or the ChatGPT account id."
            cb.respond(400, page(app, false, msg)); fail(msg); bringToFront(app); return
        }
        val t = TokenStore.Tokens(tokens.idToken, access, refresh, accountId, System.currentTimeMillis())
        TokenStore.save(app, t)
        val signed = signedIn(t)
        _state.value = signed
        VcLog.i(TAG, "login ok: account=$accountId plan=${signed.plan} access expires ${Jwt.expiresAtMs(access)?.let(::fmt)}")
        cb.respond(200, page(app, true, signed.email?.let { "Signed in as $it." } ?: "Signed in."))
        bringToFront(app)
    }

    private fun fail(msg: String) {
        VcLog.w(TAG, "login: $msg")
        _state.value = AuthState.Error(msg)
    }

    private fun bringToFront(app: Context) {
        try {
            app.startActivity(
                Intent(app, LoginActivity::class.java)
                    .putExtra(LoginActivity.EXTRA_FROM_LOGIN, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        } catch (e: Exception) {
            VcLog.w(TAG, "couldn't bring the app to front: ${e.message}")
        }
    }

    private fun publishStored(ctx: Context) {
        val t = TokenStore.load(ctx)
        _state.value = if (t == null) AuthState.SignedOut else signedIn(t)
    }

    private fun emailOf(t: TokenStore.Tokens) = Jwt.email(t.idToken) ?: Jwt.email(t.accessToken)
    private fun planOf(t: TokenStore.Tokens) = Jwt.planType(t.idToken) ?: Jwt.planType(t.accessToken)
    private fun signedIn(t: TokenStore.Tokens) = AuthState.SignedIn(emailOf(t), planOf(t), t.accountId)

    internal fun fmt(ms: Long): String {
        val mins = (ms - System.currentTimeMillis()) / 60_000
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.US).format(java.util.Date(ms))
        return "$stamp (in ${mins / 60}h${mins % 60}m)"
    }

    private fun page(app: Context, ok: Boolean, detail: String): String {
        val esc = detail.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val title = if (ok) "You're signed in" else "Sign-in failed"
        val intentUrl = "intent://done#Intent;scheme=${LoginActivity.RETURN_SCHEME};package=${app.packageName};end"
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title>
<style>body{font-family:sans-serif;margin:48px 24px;text-align:center;color:#222}
a.b{display:inline-block;margin-top:24px;padding:14px 22px;border-radius:24px;background:#10a37f;color:#fff;text-decoration:none}</style>
</head><body><h2>$title</h2><p>$esc</p><p>${if (ok) "Return to Voice Control." else "Return to Voice Control and try again."}</p>
<a class="b" href="$intentUrl">Back to Voice Control</a></body></html>"""
    }
}

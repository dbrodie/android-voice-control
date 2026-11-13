package com.brainstormity.voicecontrol.voice.auth

import android.content.Context
import android.content.Intent
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
import java.io.IOException
import java.net.URLEncoder

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
 *
 * Two flows: [state] says whether a sign-in is stored; [login] is the progress of the current browser sign-in
 * attempt (LoginActivity renders it). The attempt lives here, not in the Activity, so it survives rotation and
 * the Activity being destroyed while the browser is in front.
 */
object ChatGptAuth {
    private const val TAG = "Auth"
    /** Codex: CHATGPT_ACCESS_TOKEN_REFRESH_WINDOW_MINUTES = 5. */
    private const val REFRESH_WINDOW_MS = 5 * 60_000L

    private val _state = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _login = MutableStateFlow<LoginState>(LoginState.Idle)
    /** Progress of the browser sign-in. Terminal states stay until [clearLoginResult] or the next attempt. */
    val login: StateFlow<LoginState> = _login.asStateFlow()

    /** Token endpoint client; replaceable for tests. */
    internal var oauth: CodexOAuth = CodexOAuth()

    /** Called after [signOut] (VoiceBackends resets its backend choice / stops a ChatGPT voice session). */
    @Volatile var onSignedOut: ((Context) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshMutex = Mutex()
    private val lock = Any()
    @Volatile private var initialized = false
    private var loginJob: Job? = null
    private var loginServer: LoopbackServer? = null
    private var loginGen = 0
    @Volatile private var pending: PendingLogin? = null

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
                oauth.refresh(t.refreshToken, reason)
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

    // ------------------------------------------------------------------------------------------ browser sign-in

    /**
     * Starts a browser sign-in: binds 127.0.0.1:1455 (or 1457), publishes [LoginState.Waiting] with the authorize
     * URL, and waits up to 5 minutes for the redirect. The caller (LoginActivity) opens the URL in a Custom Tab.
     * A second call while an attempt is in progress is ignored (double taps) and returns the running attempt's id.
     */
    fun startLogin(ctx: Context): Int {
        init(ctx)
        val app = ctx.applicationContext
        synchronized(lock) {
            val cur = _login.value
            if (cur.isInProgress && loginJob?.isActive == true) {
                VcLog.i(TAG, "login #$loginGen already in progress; ignoring a second start")
                return loginGen
            }
            return begin(app, null)
        }
    }

    /**
     * Restarts the listener for an attempt whose process died while the browser was in front (LoginActivity
     * keeps [PendingLogin] in its saved state). Same port, state and PKCE verifier, so a redirect from the
     * already-open login page still validates. Returns the attempt id, or 0 if an attempt is already running or
     * [p] has expired.
     */
    fun resumeLogin(ctx: Context, p: PendingLogin): Int {
        init(ctx)
        val app = ctx.applicationContext
        synchronized(lock) {
            if (_login.value.isInProgress && loginJob?.isActive == true) return 0
            if (p.deadlineMs <= System.currentTimeMillis()) return 0
            VcLog.i(TAG, "resuming a sign-in on port ${p.port} (the process was restarted)")
            return begin(app, p)
        }
    }

    /** The in-progress attempt's secrets, for LoginActivity.onSaveInstanceState. Null when not waiting. */
    fun pendingLogin(): PendingLogin? = pending?.takeIf { _login.value.isInProgress }

    /** Aborts a pending browser sign-in → [LoginState.Failed] with [LoginFailure.CANCELLED]. No-op otherwise. */
    fun cancelLogin(ctx: Context) {
        val app = ctx.applicationContext
        val gen = synchronized(lock) {
            if (!_login.value.isInProgress) return
            loginJob?.cancel()
            loginServer?.close()
            loginGen
        }
        VcLog.i(TAG, "login #$gen cancelled by the user")
        end(app, gen, LoginFlow.cancelledProblem())
    }

    /** Ends the current attempt with [problem] (e.g. no browser could be opened). */
    fun failLogin(ctx: Context, problem: LoginFlow.Problem) {
        val app = ctx.applicationContext
        val gen = synchronized(lock) {
            if (!_login.value.isInProgress) return
            loginJob?.cancel()
            loginServer?.close()
            loginGen
        }
        end(app, gen, problem)
    }

    /** Forgets a finished attempt's result (the screen showed it). */
    fun clearLoginResult() {
        synchronized(lock) { if (_login.value.isFinished) _login.value = LoginState.Idle }
    }

    fun signOut(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            loginJob?.cancel()
            loginServer?.close()
            pending = null
            loginGen++
            _login.value = LoginState.Idle
        }
        LoginKeepAliveService.stop(app)
        TokenStore.clear(app)
        initialized = true
        _state.value = AuthState.SignedOut
        VcLog.i(TAG, "signed out")
        try {
            onSignedOut?.invoke(app)
        } catch (e: Exception) {
            VcLog.w(TAG, "onSignedOut hook failed: ${e.message}")
        }
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

    /**
     * Debug builds: the callback URL the browser would be redirected to for the pending attempt, with the real
     * state unless [state] overrides it. Lets adb tests play OpenAI's redirect without knowing the state.
     */
    internal fun debugCallbackUrl(code: String?, state: String?, error: String?): String? {
        val p = pendingLogin() ?: return null
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val q = buildList {
            code?.let { add("code=${e(it)}") }
            error?.let { add("error=${e(it)}") }
            add("state=${e(state ?: p.state)}")
        }
        return p.redirectUri + "?" + q.joinToString("&")
    }

    // ---- internals ----

    /** Caller holds [lock]. */
    private fun begin(app: Context, resume: PendingLogin?): Int {
        loginJob?.cancel()
        loginServer?.close()
        pending = null
        val gen = ++loginGen
        _state.value = AuthState.SigningIn
        _login.value = LoginState.Starting(gen)
        // Started here (caller is a foreground activity) so the process isn't frozen while the browser is in
        // front; stopped when this attempt ends.
        LoginKeepAliveService.start(app)
        loginJob = scope.launch { runLogin(app, gen, resume) }
        return gen
    }

    private fun runLogin(app: Context, gen: Int, resume: PendingLogin?) {
        var server: LoopbackServer? = null
        try {
            server = try {
                if (resume != null) LoopbackServer.bindExact(resume.port) else LoopbackServer.bind()
            } catch (e: IOException) {
                VcLog.w(TAG, "login #$gen: can't bind the callback port: ${e.message}")
                end(app, gen, LoginFlow.portInUseProblem(resume?.port?.toString() ?: "${CodexOAuth.DEFAULT_PORT} and ${CodexOAuth.FALLBACK_PORT}"))
                return
            }
            val p = resume ?: run {
                val pkce = CodexOAuth.generatePkce()
                val st = CodexOAuth.generateState()
                PendingLogin(
                    verifier = pkce.verifier,
                    state = st,
                    port = server.port,
                    authorizeUrl = oauth.authorizeUrl(CodexOAuth.redirectUri(server.port), pkce, st),
                    deadlineMs = System.currentTimeMillis() + LoginFlow.TIMEOUT_MS,
                )
            }
            synchronized(lock) {
                if (gen != loginGen || !_login.value.isInProgress) return // superseded or cancelled while binding
                loginServer = server
                pending = p
                _login.value = LoginState.Waiting(gen, p.port, p.authorizeUrl, p.deadlineMs)
            }
            VcLog.i(TAG, "login #$gen: listening on 127.0.0.1:${p.port} (redirect_uri ${p.redirectUri}), waiting for the browser")

            when (val r = server.await(p.deadlineMs)) {
                LoopbackServer.Result.TimedOut -> end(app, gen, LoginFlow.timeoutProblem())
                LoopbackServer.Result.Cancelled -> end(app, gen, LoginFlow.cancelledProblem()) // no-op after cancelLogin
                is LoopbackServer.Result.Callback -> handleCallback(app, gen, r, p)
            }
        } catch (e: CancellationException) {
            VcLog.i(TAG, "login #$gen job cancelled")
            throw e
        } catch (e: Exception) {
            VcLog.e(TAG, "login #$gen failed", e)
            end(app, gen, LoginFlow.Problem(LoginFailure.INTERNAL, "Sign-in failed: ${e.message}"))
        } finally {
            server?.close()
            synchronized(lock) { if (loginServer === server) loginServer = null }
        }
    }

    private fun handleCallback(app: Context, gen: Int, cb: LoopbackServer.Result.Callback, p: PendingLogin) {
        val q = cb.params
        VcLog.i(
            TAG, "login #$gen: callback received (has_code=${!q["code"].isNullOrEmpty()} error=${q["error"]} " +
                "state_ok=${q["state"] == p.state})"
        )
        LoginFlow.checkCallback(q, p.state)?.let { problem ->
            cb.respond(400, page(app, false, problem.reason.title, problem.detail))
            end(app, gen, problem)
            bringToFront(app)
            return
        }
        synchronized(lock) { if (gen == loginGen && _login.value.isInProgress) _login.value = LoginState.Exchanging(gen) }

        val tokens = try {
            oauth.exchangeCode(q.getValue("code"), p.redirectUri, CodexOAuth.pkceFor(p.verifier))
        } catch (e: Exception) {
            VcLog.w(TAG, "login #$gen: token exchange failed: ${e.javaClass.simpleName}: ${e.message}")
            val problem = LoginFlow.exchangeProblem(e)
            cb.respond(400, page(app, false, problem.reason.title, problem.detail))
            end(app, gen, problem)
            bringToFront(app)
            return
        }
        val access = tokens.accessToken
        val refresh = tokens.refreshToken
        val accountId = Jwt.accountId(tokens.idToken) ?: Jwt.accountId(access)
        if (access == null || refresh == null || accountId == null) {
            VcLog.w(TAG, "login #$gen: token response incomplete ($tokens, accountId=${accountId != null})")
            val problem = LoginFlow.Problem(
                LoginFailure.MISSING_ACCOUNT,
                "OpenAI signed you in but didn't return a ChatGPT account for it. Make sure the account has a " +
                    "ChatGPT plan, or use an OpenAI API key.",
            )
            cb.respond(400, page(app, false, problem.reason.title, problem.detail))
            end(app, gen, problem)
            bringToFront(app)
            return
        }
        val t = TokenStore.Tokens(tokens.idToken, access, refresh, accountId, System.currentTimeMillis())
        val email = emailOf(t)
        val plan = planOf(t)
        if (!LoginFlow.planHasVoice(plan)) {
            VcLog.w(TAG, "login #$gen: plan '$plan' has no GPT-Live voice; not keeping the sign-in")
            val problem = LoginFlow.noVoiceProblem(email, plan)
            cb.respond(400, page(app, false, problem.reason.title, problem.detail))
            end(app, gen, problem)
            bringToFront(app)
            return
        }
        synchronized(lock) {
            if (gen != loginGen) { VcLog.w(TAG, "login #$gen superseded during the exchange; discarding tokens"); return }
            TokenStore.save(app, t)
            _state.value = signedIn(t)
        }
        VcLog.i(TAG, "login #$gen ok: account=$accountId plan=$plan access expires ${Jwt.expiresAtMs(access)?.let(::fmt)}")
        end(app, gen, null, LoginState.Succeeded(gen, email, plan))
        cb.respond(200, page(app, true, "You're signed in", email?.let { "Signed in to ChatGPT as $it." } ?: "Signed in to ChatGPT."))
        bringToFront(app)
    }

    /** Ends attempt [gen] (ignored if it was already ended or superseded). */
    private fun end(app: Context, gen: Int, problem: LoginFlow.Problem?, success: LoginState.Succeeded? = null) {
        synchronized(lock) {
            if (gen != loginGen || !_login.value.isInProgress) return
            pending = null
            _login.value = success ?: LoginState.Failed(gen, problem!!.reason, problem.detail)
            if (success == null) {
                VcLog.w(TAG, "login #$gen failed: ${problem!!.reason} (${problem.detail})")
                publishStored(app) // AuthState back from SigningIn; an existing sign-in is untouched
            }
        }
        LoginKeepAliveService.stop(app)
    }

    /**
     * Brings LoginActivity back over the browser. CLEAR_TOP finishes a Custom Tab that sits above it in our task;
     * SINGLE_TOP delivers onNewIntent instead of a second copy. Allowed from the background because the app is
     * exempt from background-activity-start limits while it has a Custom Tab in the foreground task, a visible
     * overlay, the SYSTEM_ALERT_WINDOW grant or a bound accessibility service. If Android still blocks it, the
     * page in the browser has a "Back to Voice Control" button (AuthReturnActivity).
     */
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

    private fun page(app: Context, ok: Boolean, title: String, detail: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val intentUrl = "intent://done#Intent;scheme=${AuthReturnActivity.SCHEME};package=${app.packageName};end"
        val accent = if (ok) "#1E9E5A" else "#D93025"
        val icon = if (ok) "&#10003;" else "!"
        val hint = if (ok) "Voice Control should already be back in front. If not, tap the button."
        else "Go back to Voice Control to try again."
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title>
<style>body{font-family:sans-serif;margin:56px 24px;text-align:center;color:#1B1E24;background:#F5F6F8}
.i{width:56px;height:56px;border-radius:28px;margin:0 auto 16px;background:$accent;color:#fff;font:bold 30px/56px sans-serif}
p{color:#5F6672;line-height:1.4}
a.b{display:inline-block;margin-top:20px;padding:14px 26px;border-radius:26px;background:#3F51F5;color:#fff;text-decoration:none;font-weight:bold}</style>
</head><body><div class="i">$icon</div><h2>${esc(title)}</h2><p>${esc(detail)}</p><p>${esc(hint)}</p>
<a class="b" href="$intentUrl">Back to Voice Control</a></body></html>"""
    }
}

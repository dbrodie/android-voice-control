package com.brainstormity.voicecontrol.voice.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.VoiceBackends
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Sign-in / onboarding / account screen. Start it with [LoginActivity.start].
 *
 * - Not signed in: explanation, "Sign in with ChatGPT" (browser sign-in in a Custom Tab) and
 *   "Use an OpenAI API key instead" (saves the key and sets the voice backend to openai_key).
 * - While the browser is open: "Waiting for you to finish signing in in the browser…" with Cancel and
 *   "Open browser again" and a 5-minute countdown.
 * - Afterwards: success (email + plan, Continue) or an error banner (cancelled, state mismatch, network,
 *   token exchange, port busy, timeout, plan without voice, …) with Try again.
 * - Signed in: account details (email, plan), Switch account, Sign out.
 *
 * The attempt itself lives in [ChatGptAuth] (process-wide), so rotation and the Activity being destroyed while
 * the Custom Tab is in front don't affect it. The PKCE verifier/state also go into the saved instance state so a
 * sign-in can resume on the same port if the whole process was killed meanwhile.
 */
class LoginActivity : AppCompatActivity() {

    private var startedScope: CoroutineScope? = null
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateCountdown()
            handler.postDelayed(this, 1000)
        }
    }

    /** Attempt id for which this screen already opened the browser (so rotation/resume doesn't reopen it). */
    private var launchedForId = 0
    private var keyPanelOpen = false
    private var keySaved = false
    private var onboarding = false

    private lateinit var errorBanner: View
    private lateinit var errorTitle: TextView
    private lateinit var errorDetail: TextView
    private lateinit var choosePanel: View
    private lateinit var signInButton: Button
    private lateinit var signInHint: TextView
    private lateinit var useKeyButton: Button
    private lateinit var keyPanel: View
    private lateinit var keyInput: EditText
    private lateinit var keyError: TextView
    private lateinit var notNowButton: Button
    private lateinit var waitingPanel: View
    private lateinit var waitingTitle: TextView
    private lateinit var waitingDetail: TextView
    private lateinit var waitingCountdown: TextView
    private lateinit var waitingActions: View
    private lateinit var openAgainButton: Button
    private lateinit var accountPanel: View
    private lateinit var accountTitle: TextView
    private lateinit var accountEmail: TextView
    private lateinit var accountPlan: TextView
    private lateinit var accountMeta: TextView
    private lateinit var continueButton: Button
    private lateinit var accountActions: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        bindViews()
        ChatGptAuth.init(this)
        onboarding = intent?.getStringExtra(EXTRA_MODE) == MODE_ONBOARDING

        if (savedInstanceState != null) {
            launchedForId = savedInstanceState.getInt(STATE_LAUNCHED_FOR)
            keyPanelOpen = savedInstanceState.getBoolean(STATE_KEY_PANEL)
            keySaved = savedInstanceState.getBoolean(STATE_KEY_SAVED)
            onboarding = savedInstanceState.getBoolean(STATE_ONBOARDING, onboarding)
            // The process was killed while the browser was in front: listen again on the same port with the same
            // state/verifier. The browser already shows the login page, so don't open another one.
            val p = savedInstanceState.pendingLogin()
            if (p != null && !ChatGptAuth.login.value.isInProgress) {
                val id = ChatGptAuth.resumeLogin(this, p)
                VcLog.i(TAG, "restored after process death: resumed sign-in=${id != 0}")
                if (id != 0) launchedForId = id
            }
        } else {
            // A stale result from an earlier visit; a result we were brought back for is kept.
            if (!isFromLogin(intent) && ChatGptAuth.login.value.isFinished) ChatGptAuth.clearLoginResult()
            if (intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) startSignIn()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ChatGptAuth.login.value.isInProgress) ChatGptAuth.cancelLogin(this@LoginActivity)
                leave()
            }
        })
    }

    private fun bindViews() {
        errorBanner = findViewById(R.id.errorBanner)
        errorTitle = findViewById(R.id.errorTitle)
        errorDetail = findViewById(R.id.errorDetail)
        choosePanel = findViewById(R.id.choosePanel)
        signInButton = findViewById(R.id.signInButton)
        signInHint = findViewById(R.id.signInHint)
        useKeyButton = findViewById(R.id.useKeyButton)
        keyPanel = findViewById(R.id.keyPanel)
        keyInput = findViewById(R.id.keyInput)
        keyError = findViewById(R.id.keyError)
        notNowButton = findViewById(R.id.notNowButton)
        waitingPanel = findViewById(R.id.waitingPanel)
        waitingTitle = findViewById(R.id.waitingTitle)
        waitingDetail = findViewById(R.id.waitingDetail)
        waitingCountdown = findViewById(R.id.waitingCountdown)
        waitingActions = findViewById(R.id.waitingActions)
        openAgainButton = findViewById(R.id.openAgainButton)
        accountPanel = findViewById(R.id.accountPanel)
        accountTitle = findViewById(R.id.accountTitle)
        accountEmail = findViewById(R.id.accountEmail)
        accountPlan = findViewById(R.id.accountPlan)
        accountMeta = findViewById(R.id.accountMeta)
        continueButton = findViewById(R.id.continueButton)
        accountActions = findViewById(R.id.accountActions)

        signInButton.setOnClickListener { startSignIn() }
        useKeyButton.setOnClickListener {
            keyPanelOpen = !keyPanelOpen
            render()
            if (keyPanelOpen) keyInput.requestFocus()
        }
        findViewById<Button>(R.id.saveKeyButton).setOnClickListener { saveKey() }
        keyInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { saveKey(); true } else false
        }
        findViewById<CheckBox>(R.id.showKeyCheck).setOnCheckedChangeListener { _, show ->
            val sel = keyInput.selectionEnd
            keyInput.inputType = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (sel >= 0) keyInput.setSelection(sel)
        }
        notNowButton.setOnClickListener { leave() }
        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            VcLog.i(TAG, "Cancel tapped")
            ChatGptAuth.cancelLogin(this)
        }
        openAgainButton.setOnClickListener {
            (ChatGptAuth.login.value as? LoginState.Waiting)?.let {
                VcLog.i(TAG, "Open browser again tapped")
                launchedForId = it.id
                openBrowser(it.authorizeUrl)
            }
        }
        continueButton.setOnClickListener { leave() }
        findViewById<Button>(R.id.switchAccountButton).setOnClickListener {
            VcLog.i(TAG, "Switch account tapped")
            startSignIn()
        }
        findViewById<Button>(R.id.signOutButton).setOnClickListener { confirmSignOut() }
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startedScope = scope
        scope.launch { ChatGptAuth.login.collect { render() } }
        scope.launch { ChatGptAuth.state.collect { render() } }
        handler.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        render()
        maybeOpenBrowser()
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        startedScope?.cancel()
        startedScope = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_MODE) == MODE_ONBOARDING) onboarding = true
        if (isFromLogin(intent)) VcLog.i(TAG, "back from the browser: ${ChatGptAuth.login.value}")
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) startSignIn()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_LAUNCHED_FOR, launchedForId)
        outState.putBoolean(STATE_KEY_PANEL, keyPanelOpen)
        outState.putBoolean(STATE_KEY_SAVED, keySaved)
        outState.putBoolean(STATE_ONBOARDING, onboarding) // getIntent() may since have been replaced (onNewIntent)
        ChatGptAuth.pendingLogin()?.let { p ->
            outState.putString(STATE_P_VERIFIER, p.verifier)
            outState.putString(STATE_P_STATE, p.state)
            outState.putInt(STATE_P_PORT, p.port)
            outState.putString(STATE_P_URL, p.authorizeUrl)
            outState.putLong(STATE_P_DEADLINE, p.deadlineMs)
        }
    }

    private fun Bundle.pendingLogin(): PendingLogin? {
        val verifier = getString(STATE_P_VERIFIER) ?: return null
        val state = getString(STATE_P_STATE) ?: return null
        val url = getString(STATE_P_URL) ?: return null
        return PendingLogin(verifier, state, getInt(STATE_P_PORT), url, getLong(STATE_P_DEADLINE))
    }

    // ------------------------------------------------------------------------------------------ actions

    private fun startSignIn() {
        keySaved = false
        signInButton.isEnabled = false // double taps: ChatGptAuth ignores a second start anyway
        val id = ChatGptAuth.startLogin(this)
        VcLog.i(TAG, "sign-in started (attempt #$id)")
        render()
    }

    private fun maybeOpenBrowser() {
        val s = ChatGptAuth.login.value as? LoginState.Waiting ?: return
        if (s.id == launchedForId || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        launchedForId = s.id
        openBrowser(s.authorizeUrl)
    }

    /**
     * Opens OpenAI's login page in a Custom Tab (launched from this Activity so it sits in our task, right above
     * this screen; ChatGptAuth's CLEAR_TOP relaunch then closes it), else in the default browser.
     */
    private fun openBrowser(url: String) {
        val uri = Uri.parse(url)
        val ctPackage = try { CustomTabsClient.getPackageName(this, null) } catch (_: Exception) { null }
        if (ctPackage != null) {
            try {
                val colors = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(getColor(R.color.vc_bg))
                    .build()
                val tab = CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colors)
                    .setShowTitle(true)
                    .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                    .setUrlBarHidingEnabled(false)
                    .build()
                tab.intent.setPackage(ctPackage)
                tab.launchUrl(this, uri)
                VcLog.i(TAG, "opened the sign-in page in a Custom Tab ($ctPackage)")
                return
            } catch (e: ActivityNotFoundException) {
                VcLog.w(TAG, "Custom Tab failed (${e.message}); falling back to a browser intent")
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
            VcLog.i(TAG, "opened the sign-in page with ACTION_VIEW (no Custom Tabs provider)")
        } catch (e: ActivityNotFoundException) {
            VcLog.w(TAG, "no browser to open the sign-in page")
            ChatGptAuth.failLogin(
                this,
                LoginFlow.Problem(
                    LoginFailure.NO_BROWSER,
                    "There's no web browser on this phone to show OpenAI's sign-in page. Install or enable one " +
                        "(for example Chrome), or use an OpenAI API key instead.",
                ),
            )
        }
    }

    private fun saveKey() {
        val key = keyInput.text.toString().trim()
        val problem = when {
            key.isEmpty() -> getString(R.string.login_key_empty)
            !key.startsWith("sk-") || key.length < 20 || key.any { it.isWhitespace() } -> getString(R.string.login_key_invalid)
            else -> null
        }
        keyError.text = problem ?: ""
        keyError.visibility = if (problem == null) View.GONE else View.VISIBLE
        if (problem != null) return
        AppSettings.setOpenAiApiKey(this, key)
        AppSettings.setVoiceBackend(this, AppSettings.BACKEND_OPENAI_KEY)
        VcLog.i(TAG, "OpenAI API key saved from the login screen; voice backend -> ${AppSettings.BACKEND_OPENAI_KEY}")
        keyInput.setText("")
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(keyInput.windowToken, 0)
        keySaved = true
        keyPanelOpen = false
        ChatGptAuth.clearLoginResult()
        render()
    }

    private fun confirmSignOut() {
        val who = ChatGptAuth.info(this)?.email ?: "this account"
        AlertDialog.Builder(this)
            .setTitle("Sign out of ChatGPT?")
            .setMessage(
                "Voice Control forgets the sign-in for $who on this phone. Voice keeps working only if you add an " +
                    "OpenAI API key or sign in again."
            )
            .setPositiveButton(R.string.login_sign_out) { _, _ ->
                VcLog.i(TAG, "Sign out confirmed")
                ChatGptAuth.signOut(this)
                Toast.makeText(this, R.string.login_signed_out_toast, Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun leave() {
        ChatGptAuth.clearLoginResult()
        finish()
    }

    // ------------------------------------------------------------------------------------------ rendering

    private fun render() {
        if (isFinishing || isDestroyed) return
        val s = ChatGptAuth.login.value
        val info = ChatGptAuth.info(this)
        errorBanner.visibility = View.GONE
        choosePanel.visibility = View.GONE
        waitingPanel.visibility = View.GONE
        accountPanel.visibility = View.GONE

        when (s) {
            is LoginState.Starting, is LoginState.Waiting -> {
                waitingPanel.visibility = View.VISIBLE
                waitingTitle.setText(if (s is LoginState.Starting) R.string.login_starting else R.string.login_waiting)
                waitingDetail.setText(R.string.login_waiting_detail)
                waitingActions.visibility = View.VISIBLE
                openAgainButton.isEnabled = s is LoginState.Waiting
                updateCountdown()
            }
            is LoginState.Exchanging -> {
                waitingPanel.visibility = View.VISIBLE
                waitingTitle.setText(R.string.login_exchanging)
                waitingDetail.setText(R.string.login_exchanging_detail)
                waitingCountdown.text = ""
                waitingActions.visibility = View.GONE
            }
            is LoginState.Succeeded -> showAccount(
                title = getString(R.string.login_success_title),
                email = s.email ?: info?.email,
                plan = s.plan ?: info?.plan,
                manage = false,
            )
            is LoginState.Failed -> {
                errorBanner.visibility = View.VISIBLE
                errorTitle.text = s.reason.title
                errorDetail.text = s.detail
                if (info != null) showAccount(getString(R.string.login_account_title), info.email, info.plan, manage = true)
                else showChoose(failed = true)
            }
            LoginState.Idle -> when {
                keySaved -> showKeySaved()
                info != null -> showAccount(getString(R.string.login_account_title), info.email, info.plan, manage = true)
                else -> showChoose(failed = false)
            }
        }
        maybeOpenBrowser()
    }

    private fun showChoose(failed: Boolean) {
        choosePanel.visibility = View.VISIBLE
        signInButton.isEnabled = true
        signInButton.setText(if (failed) R.string.login_try_again else R.string.login_sign_in)
        keyPanel.visibility = if (keyPanelOpen) View.VISIBLE else View.GONE
        useKeyButton.setText(if (keyPanelOpen) R.string.login_hide_key else R.string.login_use_key)
        notNowButton.visibility = if (onboarding) View.VISIBLE else View.GONE
    }

    private fun showAccount(title: String, email: String?, plan: String?, manage: Boolean) {
        accountPanel.visibility = View.VISIBLE
        accountTitle.text = title
        accountEmail.text = email ?: "ChatGPT account"
        accountPlan.text = LoginFlow.planDisplayName(plan) ?: "Plan unknown"
        accountPlan.visibility = View.VISIBLE
        accountMeta.text = "Voice in use now: ${VoiceBackends.describe(this)}"
        continueButton.setText(if (manage) R.string.login_done else R.string.login_continue)
        accountActions.visibility = if (manage) View.VISIBLE else View.GONE
    }

    private fun showKeySaved() {
        accountPanel.visibility = View.VISIBLE
        accountTitle.setText(R.string.login_key_saved_title)
        val key = AppSettings.openAiApiKey(this)
        accountEmail.text = if (key.length > 8) "sk-…${key.takeLast(4)}" else "sk-…"
        accountPlan.setText(R.string.login_key_saved_detail)
        accountMeta.text = "Voice in use now: ${VoiceBackends.describe(this)}"
        continueButton.setText(R.string.login_continue)
        accountActions.visibility = View.GONE
    }

    private fun updateCountdown() {
        val s = ChatGptAuth.login.value as? LoginState.Waiting ?: return
        val left = ((s.deadlineMs - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        waitingCountdown.text = "Port ${s.port} · times out in %d:%02d".format(left / 60, left % 60)
    }

    companion object {
        private const val TAG = "Auth"
        const val EXTRA_FROM_LOGIN = "com.brainstormity.voicecontrol.auth.FROM_LOGIN"
        /** Boolean extra: start the browser sign-in immediately (as if the button was tapped). */
        const val EXTRA_AUTO_START = "com.brainstormity.voicecontrol.auth.AUTO_START"
        /** String extra: [MODE_ONBOARDING] adds a "Not now" button (first launch, shown by MainActivity). */
        const val EXTRA_MODE = "com.brainstormity.voicecontrol.auth.MODE"
        const val MODE_ONBOARDING = "onboarding"

        private const val STATE_LAUNCHED_FOR = "launched_for"
        private const val STATE_KEY_PANEL = "key_panel"
        private const val STATE_KEY_SAVED = "key_saved"
        private const val STATE_ONBOARDING = "onboarding"
        private const val STATE_P_VERIFIER = "p_verifier"
        private const val STATE_P_STATE = "p_state"
        private const val STATE_P_PORT = "p_port"
        private const val STATE_P_URL = "p_url"
        private const val STATE_P_DEADLINE = "p_deadline"

        private fun isFromLogin(intent: Intent?) =
            intent?.getBooleanExtra(EXTRA_FROM_LOGIN, false) == true

        /**
         * Opens the screen: sign-in choices when signed out, account details (Switch account / Sign out) when
         * signed in. [autoStart] starts the browser sign-in right away; [onboarding] adds "Not now".
         */
        fun start(ctx: Context, autoStart: Boolean = false, onboarding: Boolean = false) {
            ctx.startActivity(Intent(ctx, LoginActivity::class.java).apply {
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (autoStart) putExtra(EXTRA_AUTO_START, true)
                if (onboarding) putExtra(EXTRA_MODE, MODE_ONBOARDING)
            })
        }
    }
}

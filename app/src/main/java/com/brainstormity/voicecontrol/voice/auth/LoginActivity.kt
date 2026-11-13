package com.brainstormity.voicecontrol.voice.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Sign in with ChatGPT" screen. Start it with [LoginActivity.start]. Shows the current state, the signed-in
 * email/plan, and Sign in / Cancel / Sign out buttons. The actual flow lives in [ChatGptAuth]; this activity
 * only observes it, so the login survives the activity being destroyed while the browser is in front.
 */
class LoginActivity : AppCompatActivity() {

    private val scope = MainScope()
    private var finishJob: Job? = null
    private lateinit var status: TextView
    private lateinit var account: TextView
    private lateinit var signIn: Button
    private lateinit var cancel: Button
    private lateinit var signOut: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge (targetSdk 36) puts the AppCompat action bar under the status bar; draw our own title.
        supportActionBar?.hide()
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(24), px(24), px(24))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(px(24) + bars.left, px(24) + bars.top, px(24) + bars.right, px(24) + bars.bottom)
            insets
        }
        root.addView(TextView(this).apply {
            text = "Sign in with ChatGPT"
            textSize = 24f
            setPadding(0, px(8), 0, px(16))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(TextView(this).apply {
            text = "GPT-Live voice uses your ChatGPT Plus/Pro subscription. Sign in with the same account you use " +
                "for ChatGPT; your browser opens OpenAI's login page and returns here when done."
            textSize = 15f
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        status = TextView(this).apply { textSize = 18f; setPadding(0, px(24), 0, px(8)) }
        root.addView(status, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        account = TextView(this).apply { textSize = 15f; setPadding(0, 0, 0, px(16)) }
        root.addView(account, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        signIn = Button(this).apply {
            text = "Sign in with ChatGPT"
            setOnClickListener { finishJob?.cancel(); ChatGptAuth.startLogin(this@LoginActivity) }
        }
        cancel = Button(this).apply {
            text = "Cancel sign-in"
            setOnClickListener { ChatGptAuth.cancelLogin(this@LoginActivity) }
        }
        signOut = Button(this).apply {
            text = "Sign out"
            setOnClickListener { ChatGptAuth.signOut(this@LoginActivity) }
        }
        listOf(signIn, cancel, signOut).forEach {
            root.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = px(8) })
        }
        setContentView(root)

        ChatGptAuth.init(this)
        scope.launch { ChatGptAuth.state.collect(::render) }
        handleIntent(intent)
        if (savedInstanceState == null && intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true) {
            ChatGptAuth.startLogin(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Back from a successful browser sign-in: show the result briefly, then return to whatever opened us. */
    private fun handleIntent(intent: Intent?) {
        val fromLogin = intent?.getBooleanExtra(EXTRA_FROM_LOGIN, false) == true || intent?.data?.scheme == RETURN_SCHEME
        if (fromLogin && ChatGptAuth.state.value is AuthState.SignedIn && !isTaskRoot) {
            finishJob?.cancel()
            finishJob = scope.launch { delay(1500); finish() }
        }
    }

    private fun render(s: AuthState) {
        status.text = when (s) {
            AuthState.SignedOut -> "Not signed in."
            AuthState.SigningIn -> "Waiting for the browser sign-in… (times out after 3 minutes)"
            is AuthState.SignedIn -> "Signed in to ChatGPT."
            is AuthState.Error -> "Error: ${s.message}"
        }
        val info = ChatGptAuth.info(this)
        account.text = if (info == null) "" else buildString {
            append("Account: ").append(info.email ?: "(email unknown)")
            info.plan?.let { append("\nPlan: ").append(it) }
            append("\nAccount id: ").append(info.accountId)
        }
        val signingIn = s == AuthState.SigningIn
        signIn.isEnabled = !signingIn
        signIn.text = if (info != null) "Sign in again" else "Sign in with ChatGPT"
        cancel.visibility = if (signingIn) TextView.VISIBLE else TextView.GONE
        signOut.visibility = if (info != null && !signingIn) TextView.VISIBLE else TextView.GONE
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_LOGIN = "com.brainstormity.voicecontrol.auth.FROM_LOGIN"
        /** Boolean extra: start the browser sign-in immediately (as if the button was tapped). */
        const val EXTRA_AUTO_START = "com.brainstormity.voicecontrol.auth.AUTO_START"
        /** Custom scheme used only by the "Back to Voice Control" link on the loopback success page. */
        const val RETURN_SCHEME = "com.brainstormity.voicecontrol.auth"

        /** Opens the sign-in screen; with [autoStart] the browser sign-in starts right away. */
        fun start(ctx: Context, autoStart: Boolean = false) {
            ctx.startActivity(Intent(ctx, LoginActivity::class.java).apply {
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (autoStart) putExtra(EXTRA_AUTO_START, true)
            })
        }
    }
}

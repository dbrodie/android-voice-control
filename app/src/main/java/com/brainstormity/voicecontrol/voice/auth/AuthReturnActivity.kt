package com.brainstormity.voicecontrol.voice.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.brainstormity.voicecontrol.core.VcLog

/**
 * Invisible trampoline for the "Back to Voice Control" button on the loopback result page
 * (`intent://done#Intent;scheme=com.brainstormity.voicecontrol.auth;package=…;end`).
 *
 * Chrome starts external intents with FLAG_ACTIVITY_NEW_TASK, which would stack a second LoginActivity on top
 * of the Custom Tab. Instead this activity re-launches LoginActivity with CLEAR_TOP | SINGLE_TOP, which finishes
 * the Custom Tab sitting above it in our task and resumes the existing screen (the AppAuth redirect-receiver
 * pattern). It ignores the intent data: the result comes from ChatGptAuth.login, never from the URL.
 */
class AuthReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VcLog.i("Auth", "returned from the browser via the result page link")
        startActivity(
            Intent(this, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_FROM_LOGIN, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    companion object {
        /** Custom scheme used only by the result page's "Back to Voice Control" link. */
        const val SCHEME = "com.brainstormity.voicecontrol.auth"
    }
}

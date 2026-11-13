package com.brainstormity.voicecontrol

import android.app.Application
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.VoiceBackends
import com.brainstormity.voicecontrol.voice.auth.ChatGptAuth

/**
 * Process-wide wiring, done once before any Activity/Service/Receiver runs:
 *  - TaskController gets the application Context (JEV key from AppSettings, run reports in filesDir/runs);
 *  - ChatGptAuth loads the stored sign-in so the UI and the voice backend see it immediately;
 *  - VoiceBackends installs the real GPT-Live auth provider + transport chooser. ContentProviders run before
 *    Application.onCreate, so this replaces the file-based provider the debug build's LiveDebugInstaller sets.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TaskController.init(this)
        try {
            ChatGptAuth.init(this)
        } catch (e: Exception) {   // a corrupt store is handled inside ChatGptAuth; never crash process start here
            VcLog.e(TAG, "ChatGptAuth.init failed", e)
        }
        VoiceBackends.install(this)
        VcLog.i(TAG, "App started (debug=${BuildConfig.DEBUG})")
    }

    companion object {
        private const val TAG = "App"
    }
}

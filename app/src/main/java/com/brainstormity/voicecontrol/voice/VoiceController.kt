package com.brainstormity.voicecontrol.voice

import android.content.Context
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.core.VoiceControllerApi
import com.brainstormity.voicecontrol.core.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** STUB (skeleton). The voice agent replaces this with the OpenAI Realtime client. */
object VoiceController : VoiceControllerApi {
    private const val TAG = "Voice"
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    override val isActive: Boolean
        get() = _state.value !is VoiceState.Idle && _state.value !is VoiceState.Error

    override fun start(context: Context) {
        VcLog.d(TAG, "start() [stub]")
        _state.value = VoiceState.Error("voice not implemented")
    }

    override fun stop() {
        VcLog.d(TAG, "stop() [stub]")
        _state.value = VoiceState.Idle
    }

    override fun sendText(text: String) {
        VcLog.d(TAG, "sendText(\"$text\") [stub]")
    }

    override fun tellUser(text: String) {
        VcLog.d(TAG, "tellUser(\"$text\") [stub]")
    }
}

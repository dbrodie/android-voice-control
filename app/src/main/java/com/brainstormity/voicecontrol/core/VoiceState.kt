package com.brainstormity.voicecontrol.core

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

sealed class VoiceState {
    object Idle : VoiceState()
    object Connecting : VoiceState()
    object Listening : VoiceState()      // session open, waiting for user speech
    data class UserSaid(val transcript: String) : VoiceState()
    data class AssistantSaying(val transcript: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/** voice/ implements this as a singleton `VoiceController`. */
interface VoiceControllerApi {
    val state: StateFlow<VoiceState>
    fun start(context: Context)   // open realtime session, start mic
    fun stop()                    // close session, stop mic
    fun sendText(text: String)    // debug: inject a user text turn instead of speech
    fun tellUser(text: String)    // make the assistant speak this (e.g. task result)
    val isActive: Boolean
}

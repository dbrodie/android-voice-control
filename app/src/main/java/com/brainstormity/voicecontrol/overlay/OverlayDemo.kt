package com.brainstormity.voicecontrol.overlay

import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VoiceState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Display-only overrides for testing the overlay without a real voice session / agent loop
 * (driven by the debug broadcast `OVERLAY_DEMO`). When a value is non-null the overlay shows it instead of
 * `VoiceController.state` / `TaskController.state`. Nothing else in the app reads these.
 */
object OverlayDemo {
    val voice = MutableStateFlow<VoiceState?>(null)
    val agent = MutableStateFlow<AgentState?>(null)

    /**
     * voice: `idle|connecting|listening|said:<text>|saying:<text>|error:<text>|off`
     * agent: `idle|running:<step>:<ACTION>:<detail>|finished:<status>:<message>|off`
     */
    fun apply(voiceSpec: String?, agentSpec: String?) {
        if (voiceSpec != null) voice.value = parseVoice(voiceSpec)
        if (agentSpec != null) agent.value = parseAgent(agentSpec)
    }

    private fun parseVoice(spec: String): VoiceState? {
        val kind = spec.substringBefore(':').trim().lowercase()
        val arg = spec.substringAfter(':', "")
        return when (kind) {
            "idle" -> VoiceState.Idle
            "connecting" -> VoiceState.Connecting
            "listening" -> VoiceState.Listening
            "said" -> VoiceState.UserSaid(arg)
            "saying" -> VoiceState.AssistantSaying(arg)
            "error" -> VoiceState.Error(arg.ifBlank { "demo error" })
            else -> null
        }
    }

    private fun parseAgent(spec: String): AgentState? {
        val parts = spec.split(':', limit = 4)
        return when (parts[0].trim().lowercase()) {
            "idle" -> AgentState.Idle
            "running" -> AgentState.Running(
                task = "demo task",
                step = parts.getOrNull(1)?.toIntOrNull() ?: 1,
                action = parts.getOrNull(2)?.ifBlank { null } ?: "TAP",
                detail = parts.getOrNull(3) ?: "",
                confidence = 0.9f,
            )
            "finished" -> {
                val rest = spec.split(':', limit = 3)
                AgentState.Finished(
                    task = "demo task",
                    status = rest.getOrNull(1)?.ifBlank { null } ?: "done",
                    message = rest.getOrNull(2) ?: "",
                    steps = 3, costUsd = 0.0,
                )
            }
            else -> null
        }
    }
}

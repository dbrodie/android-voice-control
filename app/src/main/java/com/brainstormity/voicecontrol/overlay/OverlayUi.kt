package com.brainstormity.voicecontrol.overlay

import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VoiceState

internal enum class Tone { NORMAL, ERROR, SUCCESS }

internal data class Pill(val text: String, val tone: Tone = Tone.NORMAL)

/** A short-lived message (task result, voice error, ...) shown in the pill until [until] (uptime ms). */
internal data class Transient(val pill: Pill, val createdAt: Long, val until: Long)

/** What the overlay should look like right now. */
internal sealed class OverlayUi {
    /** Normal mode: the floating mic bubble, optionally with a status pill beside it. */
    data class Bubble(
        val pill: Pill?,
        val sessionActive: Boolean,   // voice session open (Listening / UserSaid / AssistantSaying): pulsing ring
        val connecting: Boolean,
    ) : OverlayUi()

    /** A task is running: slim strip at the top of the screen + stop button; no bubble. */
    data class Strip(val text: String) : OverlayUi()
}

internal object OverlayPresenter {
    const val FINISHED_SHOW_MS = 6_000L
    const val ERROR_SHOW_MS = 8_000L

    fun present(voice: VoiceState, agent: AgentState, voiceChangedAt: Long, transient: Transient?): OverlayUi {
        if (agent is AgentState.Running) return OverlayUi.Strip(stripText(agent))

        val ongoing: Pill? = when (voice) {
            VoiceState.Connecting -> Pill("Connecting…")
            VoiceState.Listening -> Pill("Listening…")
            is VoiceState.UserSaid -> Pill(voice.transcript.trim().let { if (it.isEmpty()) "Listening…" else "“$it”" })
            is VoiceState.AssistantSaying -> Pill(voice.transcript.trim().ifEmpty { "…" })
            else -> null
        }
        // The newest of (transient message, ongoing voice state) wins.
        val pill = when {
            transient != null && (ongoing == null || transient.createdAt >= voiceChangedAt) -> transient.pill
            else -> ongoing
        }
        val active = voice is VoiceState.Listening || voice is VoiceState.UserSaid || voice is VoiceState.AssistantSaying
        return OverlayUi.Bubble(pill, sessionActive = active, connecting = voice is VoiceState.Connecting)
    }

    fun stripText(r: AgentState.Running): String = buildString {
        append("Voice Control · Step ").append(r.step).append(" · ").append(r.action)
        if (r.detail.isNotBlank()) append(' ').append(r.detail.trim())
    }

    fun finishedPill(f: AgentState.Finished): Pill {
        val (label, tone) = when (f.status) {
            "done" -> "Done" to Tone.SUCCESS
            "unsure" -> "Not sure" to Tone.NORMAL
            "stopped" -> "Stopped" to Tone.NORMAL
            "gave_up" -> "Gave up" to Tone.ERROR
            "error" -> "Error" to Tone.ERROR
            else -> f.status.replaceFirstChar { it.uppercase() } to Tone.NORMAL
        }
        val msg = f.message.trim()
        return Pill(if (msg.isEmpty()) label else "$label · $msg", tone)
    }
}

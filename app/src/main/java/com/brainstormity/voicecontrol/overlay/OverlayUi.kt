package com.brainstormity.voicecontrol.overlay

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VoiceState

/** Pill text colour. The voice tones match the bubble colour of the same state (see [BubbleLook]). */
internal enum class Tone(@ColorRes val textColor: Int) {
    NORMAL(R.color.vc_pill_text),
    ERROR(R.color.vc_pill_error),
    SUCCESS(R.color.vc_pill_success),
    CONNECTING(R.color.vc_pill_connecting),
    LISTENING(R.color.vc_pill_listening),
    SPEAKING(R.color.vc_pill_speaking),
}

/**
 * Single source of truth for how the bubble (and, while a task runs, the strip) looks, so the state reads at a
 * glance by colour: grey = idle, amber = connecting, green = listening / user talking, blue = GPT speaking,
 * red = error. [top]/[bottom] are the bubble's gradient, [ring] the pulsing ring, [strip] the task strip's
 * background and [accent] the strip's dot.
 */
internal enum class BubbleLook(
    @ColorRes val top: Int,
    @ColorRes val bottom: Int,
    @ColorRes val ring: Int,
    @ColorRes val strip: Int,
    @ColorRes val accent: Int,
    @DrawableRes val icon: Int,
    val pulsing: Boolean,   // pulsing ring: voice session open
    val fading: Boolean,    // breathing alpha: connecting
) {
    IDLE(
        R.color.vc_look_idle, R.color.vc_look_idle_dark, R.color.vc_look_idle_ring,
        R.color.vc_strip_bg, R.color.vc_look_idle, R.drawable.ic_mic_bubble, pulsing = false, fading = false,
    ),
    CONNECTING(
        R.color.vc_look_connecting, R.color.vc_look_connecting_dark, R.color.vc_look_connecting_ring,
        R.color.vc_strip_bg, R.color.vc_look_connecting, R.drawable.ic_mic_bubble, pulsing = false, fading = true,
    ),
    LISTENING(
        R.color.vc_look_listening, R.color.vc_look_listening_dark, R.color.vc_look_listening_ring,
        R.color.vc_strip_listening, R.color.vc_pill_listening, R.drawable.ic_mic_bubble, pulsing = true, fading = false,
    ),
    USER_SAID(
        R.color.vc_look_user_said, R.color.vc_look_user_said_dark, R.color.vc_look_listening_ring,
        R.color.vc_strip_listening, R.color.vc_pill_listening, R.drawable.ic_mic_bubble, pulsing = true, fading = false,
    ),
    ASSISTANT_SAYING(
        R.color.vc_look_speaking, R.color.vc_look_speaking_dark, R.color.vc_look_speaking_ring,
        R.color.vc_strip_speaking, R.color.vc_pill_speaking, R.drawable.ic_speaker_bubble, pulsing = true, fading = false,
    ),
    ERROR(
        R.color.vc_look_error, R.color.vc_look_error_dark, R.color.vc_look_error_ring,
        R.color.vc_strip_bg, R.color.vc_look_error, R.drawable.ic_mic_bubble, pulsing = false, fading = false,
    );

    /** Voice session open or opening (a tap stops it). */
    val active: Boolean get() = this != IDLE && this != ERROR
}

internal data class Pill(val text: String, val tone: Tone = Tone.NORMAL)

/** A short-lived message (task result, voice error, ...) shown in the pill until [until] (uptime ms). */
internal data class Transient(val pill: Pill, val createdAt: Long, val until: Long)

/** What the overlay should look like right now. */
internal sealed class OverlayUi {
    /** Normal mode: the floating mic bubble, optionally with a status pill beside it. */
    data class Bubble(val pill: Pill?, val look: BubbleLook) : OverlayUi()

    /** A task is running: slim strip at the top of the screen + stop button; no bubble. Tinted by [look]. */
    data class Strip(val text: String, val look: BubbleLook) : OverlayUi()
}

internal object OverlayPresenter {
    const val FINISHED_SHOW_MS = 6_000L
    const val ERROR_SHOW_MS = 8_000L

    fun present(voice: VoiceState, agent: AgentState, voiceChangedAt: Long, transient: Transient?): OverlayUi {
        if (agent is AgentState.Running) return OverlayUi.Strip(stripText(agent), stripLook(voice))

        val ongoing: Pill? = when (voice) {
            VoiceState.Connecting -> Pill("Connecting…", Tone.CONNECTING)
            VoiceState.Listening -> Pill("Listening…", Tone.LISTENING)
            is VoiceState.UserSaid ->
                Pill(voice.transcript.trim().let { if (it.isEmpty()) "Listening…" else "“$it”" }, Tone.LISTENING)
            is VoiceState.AssistantSaying -> Pill(voice.transcript.trim().ifEmpty { "…" }, Tone.SPEAKING)
            else -> null
        }
        // The newest of (transient message, ongoing voice state) wins.
        val pill = when {
            transient != null && (ongoing == null || transient.createdAt >= voiceChangedAt) -> transient.pill
            else -> ongoing
        }
        return OverlayUi.Bubble(pill, bubbleLook(voice, pill))
    }

    /** Red while an error pill is on screen; otherwise the colour of the voice state. */
    fun bubbleLook(voice: VoiceState, shownPill: Pill?): BubbleLook = when {
        shownPill?.tone == Tone.ERROR -> BubbleLook.ERROR
        voice is VoiceState.Connecting -> BubbleLook.CONNECTING
        voice is VoiceState.Listening -> BubbleLook.LISTENING
        voice is VoiceState.UserSaid -> BubbleLook.USER_SAID
        voice is VoiceState.AssistantSaying -> BubbleLook.ASSISTANT_SAYING
        else -> BubbleLook.IDLE   // Idle, or an old Error whose pill has expired
    }

    /** A running task always reads as "active": blue while GPT speaks, green otherwise. */
    fun stripLook(voice: VoiceState): BubbleLook =
        if (voice is VoiceState.AssistantSaying) BubbleLook.ASSISTANT_SAYING else BubbleLook.LISTENING

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

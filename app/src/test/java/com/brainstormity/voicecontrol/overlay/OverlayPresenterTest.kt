package com.brainstormity.voicecontrol.overlay

import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPresenterTest {
    private fun bubble(voice: VoiceState, agent: AgentState = AgentState.Idle, transient: Transient? = null,
                       voiceChangedAt: Long = 100) =
        OverlayPresenter.present(voice, agent, voiceChangedAt, transient) as OverlayUi.Bubble

    @Test fun voiceStatesMapToLooks() {
        assertEquals(BubbleLook.IDLE, bubble(VoiceState.Idle).look)
        assertEquals(BubbleLook.CONNECTING, bubble(VoiceState.Connecting).look)
        assertEquals(BubbleLook.LISTENING, bubble(VoiceState.Listening).look)
        assertEquals(BubbleLook.USER_SAID, bubble(VoiceState.UserSaid("hi")).look)
        assertEquals(BubbleLook.ASSISTANT_SAYING, bubble(VoiceState.AssistantSaying("ok")).look)
        // An old Error without its pill (expired / not replayed) is just idle.
        assertEquals(BubbleLook.IDLE, bubble(VoiceState.Error("x")).look)
    }

    @Test fun errorPillTurnsBubbleRed() {
        val err = Transient(Pill("Voice error · x", Tone.ERROR), createdAt = 200, until = 8200)
        val ui = bubble(VoiceState.Error("x"), transient = err)
        assertEquals(BubbleLook.ERROR, ui.look)
        assertEquals(Tone.ERROR, ui.pill?.tone)
        // A newer voice state hides the error pill, and with it the red.
        assertEquals(BubbleLook.LISTENING, bubble(VoiceState.Listening, transient = err, voiceChangedAt = 300).look)
    }

    @Test fun successPillKeepsVoiceColour() {
        val done = Transient(Pill("Done", Tone.SUCCESS), createdAt = 200, until = 6200)
        assertEquals(BubbleLook.IDLE, bubble(VoiceState.Idle, transient = done).look)
    }

    @Test fun pillTonesMatchVoiceState() {
        assertEquals(Tone.CONNECTING, bubble(VoiceState.Connecting).pill?.tone)
        assertEquals(Tone.LISTENING, bubble(VoiceState.Listening).pill?.tone)
        assertEquals(Tone.LISTENING, bubble(VoiceState.UserSaid("hi")).pill?.tone)
        assertEquals(Tone.SPEAKING, bubble(VoiceState.AssistantSaying("ok")).pill?.tone)
    }

    @Test fun stripIsTintedActive() {
        val run = AgentState.Running("t", 3, "TAP", "Wi-Fi toggle", 0.9f)
        val s1 = OverlayPresenter.present(VoiceState.Idle, run, 0, null) as OverlayUi.Strip
        assertEquals(BubbleLook.LISTENING, s1.look)
        val s2 = OverlayPresenter.present(VoiceState.AssistantSaying("ok"), run, 0, null) as OverlayUi.Strip
        assertEquals(BubbleLook.ASSISTANT_SAYING, s2.look)
    }

    @Test fun looksAreDistinct() {
        val main = listOf(BubbleLook.IDLE, BubbleLook.CONNECTING, BubbleLook.LISTENING,
            BubbleLook.ASSISTANT_SAYING, BubbleLook.ERROR)
        assertEquals(main.size, main.map { it.top }.toSet().size)
        assertNotEquals(BubbleLook.LISTENING.icon, BubbleLook.ASSISTANT_SAYING.icon)
        assertTrue(BubbleLook.LISTENING.pulsing && !BubbleLook.IDLE.pulsing && BubbleLook.CONNECTING.fading)
    }
}

package com.brainstormity.voicecontrol.voice

import android.content.Context
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.TaskControllerApi
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.core.VoiceControllerApi
import com.brainstormity.voicecontrol.core.VoiceState
import com.brainstormity.voicecontrol.voice.live.CodexLiveTransport
import com.brainstormity.voicecontrol.voice.live.LiveAuth
import com.brainstormity.voicecontrol.voice.live.LiveAuthProvider
import com.brainstormity.voicecontrol.voice.live.LiveChannel
import com.brainstormity.voicecontrol.voice.live.LiveEvent
import com.brainstormity.voicecontrol.voice.live.LiveException
import com.brainstormity.voicecontrol.voice.live.LivePrompt
import com.brainstormity.voicecontrol.voice.live.LiveSession
import com.brainstormity.voicecontrol.voice.live.LiveSessionConfig
import com.brainstormity.voicecontrol.voice.live.LiveTaskBridge
import com.brainstormity.voicecontrol.voice.live.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * The voice front-desk: a GPT-Live call (ChatGPT subscription route, [CodexLiveTransport]) whose client
 * delegations run on the phone-control agent via [LiveTaskBridge].
 *
 * State: Idle -> Connecting -> Listening <-> UserSaid / AssistantSaying; failures -> Error(human message).
 * All event handling runs on one background thread, so no locking is needed inside the handlers.
 */
object VoiceController : VoiceControllerApi {
    private const val TAG = "Voice"

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    override val isActive: Boolean get() = session != null

    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "vc-live") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Overridable for tests / the API-key transport (T4c). */
    @Volatile var sessionFactory: (Context, LiveAuthProvider) -> LiveSession = { ctx, auth ->
        CodexLiveTransport(ctx, auth, LiveSessionConfig(instructions = LivePrompt.INSTRUCTIONS))
    }
    @Volatile var tasks: TaskControllerApi = TaskController

    @Volatile private var session: LiveSession? = null
    private var bridge: LiveTaskBridge? = null
    private var connectJob: Job? = null
    private var stopping = false

    // Transcript accumulation (deltas -> running text per turn).
    private val buffers = mutableMapOf(Role.USER to StringBuilder(), Role.ASSISTANT to StringBuilder())
    private var lastRole: Role? = null
    private var lastUserText = ""
    private var sawFinals = false
    private val lastTurn = mutableMapOf<Role, String>()
    private var idleJob: Job? = null

    override fun start(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (session != null) {
                VcLog.d(TAG, "start(): already active")
                return@launch
            }
            val auth = LiveAuth.provider
            if (auth == null) {
                fail("Sign in to ChatGPT first (no sign-in available).")
                return@launch
            }
            VcLog.i(TAG, "start(): opening GPT-Live call")
            _state.value = VoiceState.Connecting
            stopping = false
            resetTranscripts()
            val s = sessionFactory(appContext, auth)
            session = s
            bridge = LiveTaskBridge(s, tasks, scope)
            s.onEvent = { event -> scope.launch { handle(s, event) } }
            connectJob = scope.launch {
                try {
                    s.connect()
                    if (session === s && _state.value == VoiceState.Connecting) _state.value = VoiceState.Listening
                    VcLog.i(TAG, "GPT-Live call up (call=${s.callId})")
                } catch (e: LiveException) {
                    if (session === s) fail(errorMessage(e), e)
                } catch (e: Exception) {
                    if (session === s) fail("Voice connection failed: ${e.message}", e)
                }
            }
        }
    }

    override fun stop() {
        scope.launch {
            val s = session ?: run {
                if (_state.value !is VoiceState.Error) _state.value = VoiceState.Idle
                return@launch
            }
            VcLog.i(TAG, "stop(): closing GPT-Live call")
            stopping = true
            connectJob?.cancel() // stop() during Connecting: abandon the handshake
            teardown()
            _state.value = VoiceState.Idle
            s.close()
        }
    }

    /**
     * Debug: inject a typed request. GPT-Live has no typed user turn for client delegation (the Live docs say typed
     * input goes straight to your backend), and tested on the Codex route: appending "The user typed: ..." as
     * context makes GPT *say* "I'll take care of that" but it never delegates context. So this simulates the
     * delegation: GPT gets a silent commentary note (so the conversation stays coherent), and the same handler
     * as `delegation.created` runs the task with no delegation id; the result comes back as session context.
     */
    override fun sendText(text: String) {
        scope.launch {
            val s = session
            val b = bridge
            if (s == null || b == null) {
                VcLog.w(TAG, "sendText(\"$text\") ignored: no active voice session")
                return@launch
            }
            VcLog.i(TAG, "sendText(\"$text\"): simulated delegation")
            onTranscript(Role.USER, text, final = true)
            s.append(
                "The user typed this request instead of saying it: \"$text\". It has already been handed to the phone; " +
                    "the result will follow.",
                LiveChannel.COMMENTARY,
            )
            b.onDelegation(null, text)
        }
    }

    /** Makes the assistant say [text] (paraphrased) now: session-level speakable context. */
    override fun tellUser(text: String) {
        scope.launch {
            val s = session ?: run { VcLog.w(TAG, "tellUser ignored: no active voice session"); return@launch }
            VcLog.i(TAG, "tellUser(\"$text\")")
            s.append(text, LiveChannel.SPEAKABLE)
        }
    }

    // ------------------------------------------------------------------------------------------------

    private fun handle(s: LiveSession, event: LiveEvent) {
        if (session !== s) return
        when (event) {
            is LiveEvent.Started -> {
                VcLog.i(TAG, "session.started")
                if (_state.value == VoiceState.Connecting) _state.value = VoiceState.Listening
            }
            is LiveEvent.Transcript -> onTranscript(event.role, event.text, event.final)
            is LiveEvent.Delegation -> {
                // Public-API delegations carry no text: fall back to what the user last said.
                val text = event.text.ifBlank { lastUserText }
                bridge?.onDelegation(event.id, text)
            }
            LiveEvent.SpeechCleared -> {
                VcLog.d(TAG, "assistant speech cleared (user interrupted)")
                buffers[Role.ASSISTANT]?.setLength(0)
                if (_state.value is VoiceState.AssistantSaying) _state.value = VoiceState.Listening
            }
            is LiveEvent.Error -> {
                VcLog.w(TAG, "GPT-Live error${if (event.fatal) " (fatal)" else ""}: ${event.message}")
                if (event.fatal) fail("Sign in again: ${event.message}")
            }
            is LiveEvent.Closed -> {
                VcLog.i(TAG, "call closed: ${event.reason}")
                val wasStopping = stopping
                teardown()
                if (!wasStopping && _state.value !is VoiceState.Error) {
                    val reason = event.reason ?: "closed"
                    _state.value = if (reason in NORMAL_CLOSE) VoiceState.Idle else VoiceState.Error("Voice call ended: $reason")
                }
            }
            is LiveEvent.Other -> {}
        }
    }

    /**
     * Deltas accumulate per role. The Codex route also sends `turn.done` (final, full text); when finals are seen,
     * they end turns. Without finals (public API), a change of speaker ends the previous speaker's turn.
     */
    private fun onTranscript(role: Role, text: String, final: Boolean) {
        val buf = buffers.getValue(role)
        if (final) {
            sawFinals = true
            buf.setLength(0)
            if (lastRole == role) lastRole = null
            val full = text.trim()
            if (full.isEmpty()) return
            // A late user `turn.done` must not replace what the assistant is currently saying.
            if (role == Role.ASSISTANT || _state.value !is VoiceState.AssistantSaying) show(role, full)
            endTurn(role, full)
            return
        }
        if (lastRole != null && lastRole != role && !sawFinals) {
            val other = buffers.getValue(lastRole!!)
            endTurn(lastRole!!, other.toString().trim())
            other.setLength(0)
        }
        lastRole = role
        buf.append(text)
        val current = buf.toString().trim()
        if (current.isNotEmpty()) show(role, current)
    }

    private fun show(role: Role, text: String) {
        idleJob?.cancel()
        _state.value = if (role == Role.USER) VoiceState.UserSaid(text) else VoiceState.AssistantSaying(text)
    }

    /** Logs a finished turn, remembers the user's words, and returns to Listening after the assistant spoke. */
    private fun endTurn(role: Role, text: String) {
        // A speaker change can end a turn just before its own `turn.done` repeats it.
        if (text.isEmpty() || lastTurn[role] == text) return
        lastTurn[role] = text
        if (role == Role.USER) {
            lastUserText = text
            VcLog.i(TAG, "UserSaid: \"$text\"")
        } else {
            VcLog.i(TAG, "AssistantSaying: \"$text\"")
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(2_500)
                if (_state.value is VoiceState.AssistantSaying) _state.value = VoiceState.Listening
            }
        }
    }

    private fun resetTranscripts() {
        buffers.values.forEach { it.setLength(0) }
        lastRole = null
        lastUserText = ""
        sawFinals = false
        lastTurn.clear()
    }

    private fun teardown() {
        connectJob = null
        bridge?.cancel()
        bridge = null
        session = null
        idleJob?.cancel()
    }

    private fun fail(message: String, t: Throwable? = null) {
        VcLog.e(TAG, "VoiceState.Error: $message", t)
        val s = session
        teardown()
        _state.value = VoiceState.Error(message)
        if (s != null) scope.launch { s.close() }
    }

    private fun errorMessage(e: LiveException): String = when (e.status) {
        401 -> "Sign in again (ChatGPT rejected the sign-in)."
        403 -> "Your ChatGPT plan has no voice, or the voice allowance is used up."
        429 -> "Voice limit reached. Try again later."
        else -> e.message ?: "Voice connection failed."
    }

    private val NORMAL_CLOSE = setOf("close_requested", "client_request", "remote_hangup")
}

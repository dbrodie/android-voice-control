package com.brainstormity.voicecontrol.voice

import android.content.Context
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.AppSettings
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
import com.brainstormity.voicecontrol.voice.live.OpenAiLiveWsTransport
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
 * The voice front-desk: a GPT-Live call whose client delegations run on the phone-control agent via
 * [LiveTaskBridge]. The transport (ChatGPT route [CodexLiveTransport] or API-key [OpenAiLiveWsTransport]) is
 * picked per call by [VoiceBackends.createSession].
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

    /** Builds the transport for one call; default = the backend chooser. Throws [LiveException] when unusable. */
    @Volatile var sessionFactory: (Context, LiveAuthProvider) -> LiveSession = { ctx, auth ->
        VoiceBackends.createSession(ctx, auth)
    }
    @Volatile var tasks: TaskControllerApi = TaskController

    @Volatile private var session: LiveSession? = null
    private var bridge: LiveTaskBridge? = null
    /** Writes the current call's record to files/voice/ (see VoiceSessionLog); null between calls. */
    private var recorder: VoiceSessionRecorder? = null
    private var connectJob: Job? = null
    private var stopping = false

    // Transcript accumulation (deltas -> running text per turn).
    private val buffers = mutableMapOf(Role.USER to StringBuilder(), Role.ASSISTANT to StringBuilder())
    private var lastRole: Role? = null
    private var lastUserText = ""
    // Public-API delegations carry no text: the request is rebuilt from the user's turns (see delegationText).
    private val requestTurns = mutableListOf<String>()
    private var assistantAsked = false
    private var consumedUserText = ""
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
            // No provider = not signed in; the API-key backend does not need one.
            val auth = LiveAuth.provider ?: LiveAuthProvider { null }
            val s = try {
                sessionFactory(appContext, auth)
            } catch (e: LiveException) {
                fail(e.message ?: VoiceBackends.NOT_CONFIGURED)
                return@launch
            }
            VcLog.i(TAG, "start(): opening GPT-Live call (${s.javaClass.simpleName})")
            _state.value = VoiceState.Connecting
            stopping = false
            resetTranscripts()
            session = s
            val rec = VoiceSessionRecorder(VoiceSessionLog.forFilesDir(appContext.filesDir), backendId(s), s.model)
            recorder = rec
            VcLog.i(TAG, "voice session record ${rec.id} (backend=${rec.backend}, model=${rec.model})")
            val b = LiveTaskBridge(s, tasks, scope)
            b.onDelegatedChanged = { rec.tasks(b.delegated) }
            bridge = b
            // The record sees every event of its call, also those after stop() (final usage, session.closed).
            s.onEvent = { event -> scope.launch { rec.onEvent(event); handle(s, event) } }
            scope.launch {
                // Keep the record current while the call is open, so a crash still leaves its data.
                while (!rec.isFinished) {
                    delay(RECORD_EVERY_MS)
                    rec.tick()
                }
            }
            connectJob = scope.launch {
                try {
                    s.connect()
                    rec.connected(s.callId)
                    if (session === s && _state.value == VoiceState.Connecting) _state.value = VoiceState.Listening
                    VcLog.i(TAG, "GPT-Live call up (call=${s.callId})")
                } catch (e: LiveException) {
                    if (session === s) fail(errorMessage(s, e), e)
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
            val rec = recorder
            teardown()
            _state.value = VoiceState.Idle
            s.close()
            // Normally session.closed has ended the record; this covers a call that never came up. Queued, so a
            // Closed event already on its way is recorded first (its reason wins).
            if (rec != null) scope.launch { rec.finish("close_requested") }
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
            // A typed turn is not a transport final: don't switch the public-API turn tracking to finals mode.
            val hadFinals = sawFinals
            onTranscript(Role.USER, text, final = true)
            sawFinals = hadFinals
            requestTurns.clear()
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
                // Public-API delegations carry no text: rebuild it from what the user said.
                val text = event.text.ifBlank { delegationText() }
                requestTurns.clear()
                bridge?.onDelegation(event.id, text)
            }
            LiveEvent.SpeechCleared -> {
                VcLog.d(TAG, "assistant speech cleared (user interrupted)")
                buffers[Role.ASSISTANT]?.setLength(0)
                if (_state.value is VoiceState.AssistantSaying) _state.value = VoiceState.Listening
            }
            is LiveEvent.Error -> {
                VcLog.w(TAG, "GPT-Live error${if (event.fatal) " (fatal)" else ""}: ${event.message}")
                if (event.fatal) fail(if (s is OpenAiLiveWsTransport) event.message else "Sign in again: ${event.message}")
            }
            is LiveEvent.Closed -> {
                VcLog.i(TAG, "call closed: ${event.reason}")
                val wasStopping = stopping
                val rec = recorder
                teardown()
                if (!wasStopping && _state.value !is VoiceState.Error) {
                    val reason = event.reason ?: "closed"
                    if (reason in NORMAL_CLOSE) {
                        _state.value = VoiceState.Idle
                    } else {
                        _state.value = VoiceState.Error("Voice call ended: $reason")
                        rec?.fail("Voice call ended: $reason")
                    }
                }
            }
            is LiveEvent.Usage -> {} // recorded by the call's VoiceSessionRecorder
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
        recorder?.turn(role)
        if (role == Role.USER) {
            lastUserText = text
            // Part of this turn may already have gone out with a delegation that arrived while the user spoke.
            val rest = if (consumedUserText.isNotEmpty() && text.startsWith(consumedUserText)) {
                text.removePrefix(consumedUserText).trim()
            } else text
            consumedUserText = ""
            if (rest.isNotEmpty()) addRequestTurn(rest)
            VcLog.i(TAG, "UserSaid: \"$text\"")
        } else {
            VcLog.i(TAG, "AssistantSaying: \"$text\"")
            assistantAsked = text.endsWith("?")
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(2_500)
                if (_state.value is VoiceState.AssistantSaying) _state.value = VoiceState.Listening
            }
        }
    }

    /** A user turn joins the pending request after a clarifying question, otherwise it starts a new request. */
    private fun addRequestTurn(text: String) {
        if (!assistantAsked) requestTurns.clear()
        assistantAsked = false
        requestTurns += text
        while (requestTurns.size > 3) requestTurns.removeAt(0)
    }

    /**
     * The request for a public-API delegation (`session.delegation.created` has no text): the user's words since the
     * last delegation. The delegation usually arrives while the user turn is still open (before GPT's spoken
     * acknowledgement ends it), so the in-progress transcript counts too; answers to a clarifying question
     * ("Send a message." / "Who to?" / "Anna, saying hi.") are joined with the request they complete.
     */
    private fun delegationText(): String {
        val current = if (lastRole == Role.USER) buffers.getValue(Role.USER).toString().trim() else ""
        if (current.isNotEmpty() && current != lastTurn[Role.USER]) {
            addRequestTurn(current)
            consumedUserText = current
        }
        val text = requestTurns.joinToString(" ") { if (it.last() in ".?!") it else "$it." }
        return text.ifBlank { lastUserText }
    }

    private fun resetTranscripts() {
        requestTurns.clear()
        assistantAsked = false
        consumedUserText = ""
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
        recorder = null
        idleJob?.cancel()
    }

    private fun fail(message: String, t: Throwable? = null) {
        VcLog.e(TAG, "VoiceState.Error: $message", t)
        recorder?.fail(message)
        val s = session
        teardown()
        _state.value = VoiceState.Error(message)
        if (s != null) scope.launch { s.close() }
    }

    private fun errorMessage(s: LiveSession, e: LiveException): String = if (s is OpenAiLiveWsTransport) {
        // Already readable and about the API key ("Invalid OpenAI API key (401). ..."), not the ChatGPT sign-in.
        e.message ?: "Voice connection failed."
    } else when (e.status) {
        401 -> "Sign in again (ChatGPT rejected the sign-in)."
        403 -> "Your ChatGPT plan has no voice, or the voice allowance is used up."
        429 -> "Voice limit reached. Try again later."
        else -> e.message ?: "Voice connection failed."
    }

    private const val RECORD_EVERY_MS = 3_000L

    /** AppSettings backend id of a transport, for the voice session record. */
    private fun backendId(s: LiveSession): String = when (s) {
        is CodexLiveTransport -> AppSettings.BACKEND_CHATGPT
        is OpenAiLiveWsTransport -> AppSettings.BACKEND_OPENAI_KEY
        else -> VoiceBackends.lastSessionBackend.ifEmpty { "other" }
    }

    private val NORMAL_CLOSE =setOf("close_requested", "client_request", "remote_hangup")
}

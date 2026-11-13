package com.brainstormity.voicecontrol.voice.live

import com.brainstormity.voicecontrol.agent.RunReport
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.TaskControllerApi
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Answers GPT-Live client delegations with the phone-control agent (the role bridge.ts plays for OpenCode):
 * - a delegation becomes `TaskController.runTask(text)`;
 * - while it runs, progress goes back on the silent "commentary" channel (at most every [progressEveryMs]);
 * - the final status + message goes back on the "speakable" channel with the delegation id so GPT says it;
 * - a second request while one runs gets a speakable "busy" answer; "Stop the current task" stops it.
 * [delegationId] null = a simulated delegation (debug `sendText`); answers then go to the session.
 *
 * Every delegation is also kept in [delegated] (for the voice session log, voice/VoiceSessionLog): the request,
 * what happened to it, and the id of the run it produced. The run id comes from [lastRunId] (RunReport's newest
 * run folder): a value that changed after `runTask` belongs to this delegation, since only one task runs at a time.
 */
class LiveTaskBridge(
    private val session: LiveSession,
    private val tasks: TaskControllerApi,
    private val scope: CoroutineScope,
    private val progressEveryMs: Long = 3_000,
    private val lastRunId: () -> String? = { RunReport.lastId },
) {
    private var watch: Job? = null
    @Volatile var delegations = 0
        private set

    /** One delegation. [outcome]: empty | stop | busy | running, then the run's final status (done, error, ...). */
    data class Delegated(val text: String, val runId: String?, val outcome: String)

    private val _delegated = mutableListOf<Delegated>()
    /** The delegations of this call so far, oldest first. */
    val delegated: List<Delegated> get() = synchronized(_delegated) { _delegated.toList() }
    /** Called (on the caller's thread) whenever [delegated] changes. */
    @Volatile var onDelegatedChanged: (() -> Unit)? = null

    private var watching = -1         // index in _delegated of the task this bridge started and is watching
    private var runIdBefore: String? = null

    private fun record(text: String, outcome: String): Int {
        val i = synchronized(_delegated) { _delegated += Delegated(text, null, outcome); _delegated.size - 1 }
        onDelegatedChanged?.invoke()
        return i
    }

    private fun update(i: Int, f: (Delegated) -> Delegated) {
        if (i < 0) return
        val changed = synchronized(_delegated) {
            if (i >= _delegated.size) return
            val old = _delegated[i]
            val new = f(old)
            _delegated[i] = new
            new != old
        }
        if (changed) onDelegatedChanged?.invoke()
    }

    /** Attaches the run the current task produced, once RunReport has created its folder. */
    private fun resolveRunId() {
        val i = watching
        if (i < 0) return
        val id = lastRunId()
        if (id != null && id != runIdBefore) update(i) { if (it.runId == null) it.copy(runId = id) else it }
    }

    fun onDelegation(delegationId: String?, rawText: String) {
        delegations++
        val task = rawText.trim()
        VcLog.i(TAG, "delegation ${delegationId ?: "(simulated)"}: \"$task\"")
        if (task.isEmpty()) {
            record(task, "empty")
            reply(delegationId, "The request was empty. Ask the user what they want done on the phone.", LiveChannel.SPEAKABLE)
            return
        }
        if (STOP.containsMatchIn(task)) {
            record(task, "stop")
            if (tasks.isRunning) {
                VcLog.i(TAG, "stop requested by voice")
                tasks.stop()
                reply(delegationId, "Stopping the current task.", LiveChannel.COMMENTARY)
            } else {
                reply(delegationId, "No phone task is running, so there is nothing to stop.", LiveChannel.SPEAKABLE)
            }
            return
        }
        if (tasks.isRunning) {
            record(task, "busy")
            val current =(tasks.state.value as? AgentState.Running)?.task ?: "an earlier task"
            reply(
                delegationId,
                "Busy: the phone is still working on \"${clip(current, 120)}\", so \"${clip(task, 120)}\" was not started. " +
                    "Tell the user to wait until it finishes, or to say stop.",
                LiveChannel.SPEAKABLE,
            )
            return
        }
        run(delegationId, task)
    }

    private fun run(delegationId: String?, task: String) {
        watch?.cancel()
        val before = tasks.state.value
        runIdBefore = lastRunId()
        watching = record(task, "running")
        VcLog.i(TAG, "TaskController.runTask(\"$task\")")
        tasks.runTask(task)
        resolveRunId()
        // A synchronous runTask that ends with a result equal to the previous one leaves the StateFlow unchanged.
        val now = tasks.state.value
        if (now === before && now is AgentState.Finished && now.task == task && !tasks.isRunning) {
            finished(delegationId, now)
            return
        }
        var lastProgress = 0L
        watch = scope.launch {
            tasks.state.collect { state ->
                if (state === before) return@collect
                when (state) {
                    is AgentState.Running -> if (state.task == task) {
                        resolveRunId()
                        val t = System.currentTimeMillis()
                        if (t - lastProgress >= progressEveryMs) {
                            lastProgress = t
                            reply(delegationId, progress(state), LiveChannel.COMMENTARY)
                        }
                    }
                    is AgentState.Finished -> if (state.task == task) {
                        finished(delegationId, state)
                        watch?.cancel()
                    }
                    AgentState.Idle -> {}
                }
            }
        }
    }

    private fun finished(delegationId: String?, state: AgentState.Finished) {
        VcLog.i(TAG, "task finished: status=${state.status} steps=${state.steps} message=\"${state.message}\"")
        resolveRunId()
        update(watching) { it.copy(outcome = state.status) }
        watching = -1
        reply(delegationId, outcome(state), LiveChannel.SPEAKABLE)
    }

    private fun reply(delegationId: String?, text: String, channel: LiveChannel) {
        session.append(text, channel, delegationId)
    }

    fun cancel() {
        resolveRunId()
        watch?.cancel()
        watch = null
    }

    companion object {
        private const val TAG = "Voice"
        /** Matches only the prompt's exact stop phrasing ("Stop the current task."), not e.g. "Stop the music". */
        private val STOP = Regex(
            "^\\s*(stop|cancel|abort)( the)?( current| running| phone)? (task|that|it)\\s*[.!]?\\s*$",
            RegexOption.IGNORE_CASE,
        )

        fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1) + "…"

        fun progress(s: AgentState.Running): String {
            val detail = s.detail.trim()
            return "Step ${s.step}: ${s.action.lowercase().replace('_', ' ')}" + if (detail.isNotEmpty()) " ${clip(detail, 120)}" else ""
        }

        fun outcome(s: AgentState.Finished): String {
            val label = clip(s.task, 120)
            val msg = clip(s.message.trim(), 300)
            return when (s.status) {
                "done" -> if (msg.isNotEmpty()) "Finished the task \"$label\". Outcome: $msg" else "Finished the task \"$label\"."
                "stopped" -> "Work on \"$label\" was stopped."
                "unsure" -> "Stopped the task \"$label\" because the next step was unclear" + if (msg.isNotEmpty()) ": $msg" else "."
                "gave_up" -> "Could not complete the task \"$label\" after ${s.steps} steps" + if (msg.isNotEmpty()) ": $msg" else "."
                else -> "The task \"$label\" failed: ${msg.ifEmpty { "unknown error" }}"
            }
        }
    }
}

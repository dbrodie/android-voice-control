package com.brainstormity.voicecontrol.voice.live

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
 */
class LiveTaskBridge(
    private val session: LiveSession,
    private val tasks: TaskControllerApi,
    private val scope: CoroutineScope,
    private val progressEveryMs: Long = 3_000,
) {
    private var watch: Job? = null
    @Volatile var delegations = 0
        private set

    fun onDelegation(delegationId: String?, rawText: String) {
        delegations++
        val task = rawText.trim()
        VcLog.i(TAG, "delegation ${delegationId ?: "(simulated)"}: \"$task\"")
        if (task.isEmpty()) {
            reply(delegationId, "The request was empty. Ask the user what they want done on the phone.", LiveChannel.SPEAKABLE)
            return
        }
        if (STOP.containsMatchIn(task)) {
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
            val current = (tasks.state.value as? AgentState.Running)?.task ?: "an earlier task"
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
        VcLog.i(TAG, "TaskController.runTask(\"$task\")")
        tasks.runTask(task)
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
        reply(delegationId, outcome(state), LiveChannel.SPEAKABLE)
    }

    private fun reply(delegationId: String?, text: String, channel: LiveChannel) {
        session.append(text, channel, delegationId)
    }

    fun cancel() {
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

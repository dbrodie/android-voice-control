package com.brainstormity.voicecontrol.debug

import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.TaskControllerApi
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * DEBUG ONLY: a scripted agent (`LIVE_START --ez fake_task true`) so the voice progress/result path can be
 * tested before the real agent loop is merged: 4 Running steps 2 s apart, then Finished(done).
 */
object FakeTaskController : TaskControllerApi {
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    override val state: StateFlow<AgentState> = _state.asStateFlow()
    override val isRunning: Boolean get() = _state.value is AgentState.Running
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val steps = listOf(
        "OPEN_APP" to "Settings",
        "TAP" to "on \"Network & internet\"",
        "TAP" to "on \"Internet\"",
        "TAP" to "the Wi-Fi switch",
    )

    override fun runTask(task: String) {
        if (isRunning) return
        VcLog.i("Debug", "FakeTaskController.runTask(\"$task\")")
        job = scope.launch {
            steps.forEachIndexed { i, (action, detail) ->
                _state.value = AgentState.Running(task, i + 1, action, detail, 0.9f)
                delay(2_000)
            }
            _state.value = AgentState.Finished(task, "done", "Wi-Fi is now on.", steps.size, 0.01)
        }
    }

    override fun stop() {
        val running = _state.value as? AgentState.Running ?: return
        job?.cancel()
        _state.value = AgentState.Finished(running.task, "stopped", "stopped by user", running.step, 0.0)
    }
}

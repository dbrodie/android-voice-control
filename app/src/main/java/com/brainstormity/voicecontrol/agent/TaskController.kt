package com.brainstormity.voicecontrol.agent

import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.TaskControllerApi
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** STUB (skeleton). The agent-loop agent replaces this with the real JEV loop. */
object TaskController : TaskControllerApi {
    private const val TAG = "Agent"
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    override val state: StateFlow<AgentState> = _state.asStateFlow()
    override val isRunning: Boolean get() = _state.value is AgentState.Running

    override fun runTask(task: String) {
        VcLog.d(TAG, "runTask(\"$task\") [stub]")
        _state.value = AgentState.Finished(task, "error", "agent not implemented", 0, 0.0)
    }

    override fun stop() {
        VcLog.d(TAG, "stop() [stub]")
    }
}

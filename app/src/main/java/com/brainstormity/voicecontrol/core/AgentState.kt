package com.brainstormity.voicecontrol.core

import kotlinx.coroutines.flow.StateFlow

sealed class AgentState {
    object Idle : AgentState()
    data class Running(
        val task: String, val step: Int, val action: String, val detail: String,
        val confidence: Float,
    ) : AgentState()
    data class Finished(
        val task: String, val status: String /* done|unsure|stopped|gave_up|error */,
        val message: String, val steps: Int, val costUsd: Double,
    ) : AgentState()
}

/** agent/ implements this as a singleton `TaskController`. */
interface TaskControllerApi {
    val state: StateFlow<AgentState>
    fun runTask(task: String)             // no-op if already running
    fun stop()
    val isRunning: Boolean
}

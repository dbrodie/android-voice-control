package com.brainstormity.voicecontrol.agent

import android.annotation.SuppressLint
import android.content.Context
import com.brainstormity.voicecontrol.BuildConfig
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.TaskControllerApi
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one JEV task at a time on a background coroutine (Dispatchers.IO).
 *
 * [state]: Idle -> Running(step 0, "START") -> Running(step n, ...) per step -> Finished(status, ...).
 * Finished.status is done | unsure | stopped | gave_up | error. [stop] is cooperative: it takes effect before
 * the next step, right after the JEV call in flight returns, or while waiting for the screen to settle.
 *
 * Context (for the JEV key in AppSettings and for run reports in filesDir/runs) comes from [init] if someone
 * called it, otherwise from the running Application.
 */
object TaskController : TaskControllerApi {
    private const val TAG = "Agent"
    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    override val state: StateFlow<AgentState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var job: Job? = null
    @SuppressLint("StaticFieldLeak") // application context only
    @Volatile private var appContext: Context? = null

    override val isRunning: Boolean get() = busy.get()

    /** Optional: give the controller a Context early (any; the application context is kept). */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    override fun runTask(task: String) {
        if (!busy.compareAndSet(false, true)) {
            VcLog.w(TAG, "runTask(\"$task\") ignored: a task is already running")
            return
        }
        stopRequested.set(false)
        // Always pass through Running first, so every runTask() is a visible state change (a StateFlow
        // doesn't re-emit an equal Finished value).
        _state.value = AgentState.Running(task, 0, "START", "", 0f)
        val ctx = context()
        if (jevKey(ctx).isBlank()) {
            // No key entered in the app and none built in (release builds never embed one).
            VcLog.w(TAG, "runTask(\"$task\"): no JEV API key")
            _state.value = AgentState.Finished(task, "error", JevClient.NO_KEY_MESSAGE, 0, 0.0)
            busy.set(false)
            return
        }
        val screen = Device.screen.value
        val hands = Device.hands.value
        if (screen == null || hands == null) {
            VcLog.w(TAG, "runTask(\"$task\"): accessibility service not connected")
            _state.value = AgentState.Finished(
                task, "error",
                "Voice Control can't see or touch the screen yet. Turn on its accessibility service: " +
                    "Settings → Accessibility → Voice Control.",
                0, 0.0,
            )
            busy.set(false)
            return
        }
        job = scope.launch {
            var finished: AgentState.Finished? = null
            try {
                val jev = JevClient({ jevKey(ctx) })
                val report = RunReport.create(ctx?.filesDir)
                val runner = TaskRunner(task, jev, screen, hands, report, { stopRequested.get() }) { running ->
                    _state.value = running
                }
                finished = runner.run()
            } catch (e: Throwable) {
                VcLog.e(TAG, "task crashed", e)
                finished = AgentState.Finished(task, if (stopRequested.get()) "stopped" else "error",
                    if (stopRequested.get()) "Stopped by you." else (e.message ?: e.javaClass.simpleName).take(500),
                    0, 0.0)
            } finally {
                val f = finished ?: AgentState.Finished(task, "stopped", "Stopped by you.", 0, 0.0)
                VcLog.i(TAG, "Finished: ${f.status} after ${f.steps} step(s), $${AgentRules.usd(f.costUsd)}: ${f.message}")
                if (f.message == JevClient.KEY_REJECTED_MESSAGE) {
                    ctx?.let { AppSettings.setJevKeyCheck(it, "Rejected by JEV during a task (HTTP 401/403)") }
                }
                job = null
                busy.set(false)
                _state.value = f
            }
        }
    }

    override fun stop() {
        if (!busy.get()) return
        VcLog.i(TAG, "stop requested")
        stopRequested.set(true)
    }

    private fun jevKey(ctx: Context?): String = ctx?.let { AppSettings.jevApiKey(it) } ?: BuildConfig.JEV_KEY

    private fun context(): Context? = appContext ?: try {
        // The app's own Application object; avoids requiring every caller (e.g. DebugReceiver) to call init().
        @SuppressLint("PrivateApi")
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context?
        app?.also { appContext = it }
    } catch (e: Exception) {
        VcLog.w(TAG, "no Context: run reports off, JEV key from BuildConfig (${e.message})")
        null
    }
}

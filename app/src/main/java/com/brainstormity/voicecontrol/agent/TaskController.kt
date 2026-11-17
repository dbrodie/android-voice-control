package com.brainstormity.voicecontrol.agent

import android.annotation.SuppressLint
import android.content.Context
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
 *
 * T15: the model is chosen per run by [SystemOneBackends.current] (the "Next-step model" setting). A backend
 * that isn't [Availability.Ready] ends the run at once with status "error" and its availability message (JEV
 * without a key: [JevClient.NO_KEY_MESSAGE], as before), which the overlay shows and GPT speaks.
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
        SystemOneBackends.init(context)
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
        // Which model decides the steps, and can it run? (JEV: no key entered in the app and none built in;
        // release builds never embed one.) Without a Context: JEV with the build's key, as before T15.
        val system = ctx?.let { SystemOneBackends.current(it) } ?: SystemOneBackends.jev()
        val notReady = if (ctx != null) {
            try {
                system.availability(ctx).takeIf { !it.isReady }?.message
            } catch (e: Exception) {
                "${system.displayName} can't run: ${e.message ?: e.javaClass.simpleName}"
            }
        } else system.preflight()
        if (notReady != null) {
            VcLog.w(TAG, "runTask(\"$task\"): ${system.id} not available: $notReady")
            _state.value = AgentState.Finished(task, "error", notReady, 0, 0.0)
            busy.set(false)
            return
        }
        VcLog.i(TAG, "runTask(\"$task\"): next-step model ${system.id}")
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
                val report = RunReport.create(ctx?.filesDir)
                val runner = TaskRunner(task, system, screen, hands, report, { stopRequested.get() }) { running ->
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

    private fun context(): Context? = appContext ?: try {
        // The app's own Application object; avoids requiring every caller (e.g. DebugReceiver) to call init().
        @SuppressLint("PrivateApi")
        val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context?
        app?.also { appContext = it; SystemOneBackends.init(it) }
    } catch (e: Exception) {
        VcLog.w(TAG, "no Context: run reports off, JEV key from BuildConfig (${e.message})")
        null
    }
}

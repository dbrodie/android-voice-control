package com.brainstormity.voicecontrol.overlay

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.core.VoiceState
import com.brainstormity.voicecontrol.ui.MainActivity
import com.brainstormity.voicecontrol.voice.VoiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the floating mic bubble + status pill (and the slim strip while a task runs).
 * Observes [VoiceController.state] and [TaskController.state]; tap on the bubble toggles the voice session.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var windows: OverlayWindows? = null

    private var voice: VoiceState = VoiceState.Idle
    private var agent: AgentState = AgentState.Idle
    private var voiceChangedAt = 0L
    private var transient: Transient? = null
    private val rerender = Runnable { render() }
    private val rebuild = Runnable { windows?.rebuild(); render() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VcLog.i(TAG, "onCreate")
        ensureChannel(this)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {   // e.g. a sticky restart that Android no longer allows to go foreground
            VcLog.e(TAG, "startForeground failed; stopping", e)
            stopSelf()
            return
        }
        _running.value = true
        if (!Settings.canDrawOverlays(this)) {
            VcLog.w(TAG, "no 'display over other apps' permission; stopping")
            stopSelf()
            return
        }
        windows = OverlayWindows(this, ::onBubbleTap, ::onStopTap)
        voice = effectiveVoice()
        agent = effectiveAgent()
        render()

        scope.launch {
            var wasDemo = false
            combine(VoiceController.state, OverlayDemo.voice) { real, demo -> (demo ?: real) to (demo != null) }
                .collectIndexed { i, (v, demo) ->
                    // Leaving demo mode re-exposes the (old) real state: show it, but not as news.
                    onVoice(v, initial = i == 0 || (wasDemo && !demo)); wasDemo = demo
                }
        }
        scope.launch {
            var wasDemo = false
            combine(TaskController.state, OverlayDemo.agent) { real, demo -> (demo ?: real) to (demo != null) }
                .collectIndexed { i, (a, demo) ->
                    onAgent(a, initial = i == 0 || (wasDemo && !demo)); wasDemo = demo
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VcLog.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALL -> {
                if (TaskController.isRunning) TaskController.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        VcLog.d(TAG, "onConfigurationChanged orientation=${newConfig.orientation}")
        // Give the window manager a moment to report the new metrics, then re-add everything.
        handler.removeCallbacks(rebuild)
        handler.postDelayed(rebuild, 150)
    }

    override fun onDestroy() {
        VcLog.i(TAG, "onDestroy")
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        windows?.destroy()
        windows = null
        // Without the overlay there is no way to see or end a voice session, so close it.
        if (VoiceController.isActive) VoiceController.stop()
        OverlayDemo.voice.value = null
        OverlayDemo.agent.value = null
        _running.value = false
        super.onDestroy()
    }

    // ---------------------------------------------------------------- state

    private fun effectiveVoice() = OverlayDemo.voice.value ?: VoiceController.state.value
    private fun effectiveAgent() = OverlayDemo.agent.value ?: TaskController.state.value

    private fun onVoice(v: VoiceState, initial: Boolean) {
        voice = v
        if (initial) {
            render(); return   // don't replay a stale error left over from before the overlay started
        }
        VcLog.d(TAG, "voice -> $v")
        voiceChangedAt = now()
        if (v is VoiceState.Error) showTransient(Pill(voiceErrorText(v), Tone.ERROR), OverlayPresenter.ERROR_SHOW_MS)
        else render()
    }

    private fun onAgent(a: AgentState, initial: Boolean) {
        agent = a
        if (initial) {
            render(); return   // a Finished from an earlier task is not news
        }
        VcLog.d(TAG, "agent -> $a")
        if (a is AgentState.Finished) showTransient(OverlayPresenter.finishedPill(a), OverlayPresenter.FINISHED_SHOW_MS)
        else render()
    }

    private fun showTransient(pill: Pill, durationMs: Long) {
        val t = now()
        transient = Transient(pill, createdAt = t, until = t + durationMs)
        render()
    }

    private fun render() {
        val w = windows ?: return
        handler.removeCallbacks(rerender)
        val t = now()
        val tr = transient?.takeIf { t < it.until }
        if (tr == null) transient = null
        w.render(OverlayPresenter.present(voice, agent, voiceChangedAt, tr))
        if (tr != null) handler.postAtTime(rerender, tr.until + 20)
    }

    private fun now() = SystemClock.uptimeMillis()

    private fun voiceErrorText(e: VoiceState.Error) = e.message.trim().let { if (it.isEmpty()) "Voice error" else "Voice error · $it" }

    // ---------------------------------------------------------------- taps

    private fun onBubbleTap() {
        if (OverlayDemo.voice.value != null) {   // demo mode: a tap just clears the fake state
            OverlayDemo.voice.value = null
            return
        }
        try {
            if (VoiceController.isActive) {
                VcLog.i(TAG, "bubble tap -> VoiceController.stop()")
                VoiceController.stop()
                return
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                VcLog.w(TAG, "bubble tap without RECORD_AUDIO; opening setup")
                showTransient(Pill(getString(R.string.overlay_need_mic), Tone.ERROR), OverlayPresenter.ERROR_SHOW_MS)
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }
            VcLog.i(TAG, "bubble tap -> VoiceController.start()")
            VoiceController.start(applicationContext)
            // StateFlow drops equal values, so a repeated identical Error wouldn't re-emit: show it explicitly.
            val s = VoiceController.state.value
            if (s is VoiceState.Error) {
                voice = s
                voiceChangedAt = now()
                showTransient(Pill(voiceErrorText(s), Tone.ERROR), OverlayPresenter.ERROR_SHOW_MS)
            }
        } catch (t: Throwable) {
            VcLog.e(TAG, "voice toggle failed", t)
            showTransient(Pill("Voice failed: ${t.message ?: t.javaClass.simpleName}", Tone.ERROR), OverlayPresenter.ERROR_SHOW_MS)
        }
    }

    private fun onStopTap() {
        VcLog.i(TAG, "stop tapped -> TaskController.stop()")
        val demo = OverlayDemo.agent.value
        if (demo is AgentState.Running) {
            OverlayDemo.agent.value = AgentState.Finished(demo.task, "stopped", "Stopped by you", demo.step, 0.0)
            return
        }
        try {
            TaskController.stop()
        } catch (t: Throwable) {
            VcLog.e(TAG, "TaskController.stop failed", t)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic_bubble)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.overlay_notification_stop), stop)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "Overlay"
        const val CHANNEL_ID = "overlay"
        const val NOTIFICATION_ID = 1
        /** Stops the overlay (and with it any voice session). */
        const val ACTION_STOP = "com.brainstormity.voicecontrol.overlay.STOP"
        /** Notification "Stop": also stops a running task. */
        const val ACTION_STOP_ALL = "com.brainstormity.voicecontrol.overlay.STOP_ALL"

        private val _running = MutableStateFlow(false)
        /** True while the service exists (the overlay is up or about to be). */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, ctx.getString(R.string.overlay_channel_name),
                        NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        /**
         * Starts the overlay. Returns false if Android refused: since Android 15 a *background* caller may not
         * start a foreground service even with SYSTEM_ALERT_WINDOW unless one of our overlay windows is
         * already visible, so call this from the foreground (MainActivity) or while the overlay is up.
         */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, OverlayService::class.java))
            true
        } catch (e: IllegalStateException) {   // ForegroundServiceStartNotAllowedException is a subclass
            VcLog.w(TAG, "can't start the overlay from the background: ${e.message}")
            false
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }
    }
}

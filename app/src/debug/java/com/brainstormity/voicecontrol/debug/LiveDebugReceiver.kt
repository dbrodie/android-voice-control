package com.brainstormity.voicecontrol.debug

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.net.Uri
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.VoiceController
import com.brainstormity.voicecontrol.voice.live.LiveAuth

/**
 * Debug-only adb hooks for the GPT-Live voice session:
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_START
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_STOP
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_TEXT --es text "'open settings and turn on wifi'"
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_SAY  --es text "'Hello, say the word banana'"
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_START --ez fake_mic true   (emulator without mic)
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_START --ez fake_task true  (scripted agent)
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_INJECT --es file /data/local/tmp/x.raw [--ei rate 16000]
 * (Multi-word extras need the nested quotes.) Same manifest + runtime registration scheme as DebugReceiver.
 */
class LiveDebugReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {
    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        val action = intent.action ?: return
        VcLog.i(TAG, "LiveDebugReceiver got $action")
        val app = context.applicationContext
        when (action) {
            LIVE_START -> {
                FakeMic.enabled = intent.getBooleanExtra("fake_mic", false)
                if (FakeMic.enabled) VcLog.i(TAG, "LIVE_START with fake mic (feed it with LIVE_INJECT)")
                val fakeTask = intent.getBooleanExtra("fake_task", false)
                VoiceController.tasks = if (fakeTask) FakeTaskController else TaskController
                if (fakeTask) VcLog.i(TAG, "LIVE_START with the scripted FakeTaskController")
                LiveDebugMicService.start(app)
            }
            LIVE_INJECT -> {
                val path = intent.getStringExtra("file") ?: return VcLog.w(TAG, "LIVE_INJECT without --es file <path>")
                val rate = intent.getIntExtra("rate", 16_000)
                val name = java.io.File(path).name
                val file = listOf(java.io.File(app.filesDir, name), java.io.File(path)).firstOrNull { it.canRead() }
                if (file == null) VcLog.w(TAG, "LIVE_INJECT: cannot read $path (try run-as cp into files/)")
                else FakeMic.inject(file, rate)
            }
            LIVE_STOP -> LiveDebugMicService.stop(app)
            LIVE_TEXT -> intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let(VoiceController::sendText)
                ?: VcLog.w(TAG, "LIVE_TEXT without --es text \"'...'\"")
            LIVE_SAY -> intent.getStringExtra("text")?.takeIf { it.isNotBlank() }?.let(VoiceController::tellUser)
                ?: VcLog.w(TAG, "LIVE_SAY without --es text \"'...'\"")
        }
    }

    companion object {
        private const val TAG = "Debug"
        private const val P = "com.brainstormity.voicecontrol."
        const val LIVE_START = P + "LIVE_START"
        const val LIVE_STOP = P + "LIVE_STOP"
        const val LIVE_TEXT = P + "LIVE_TEXT"
        const val LIVE_SAY = P + "LIVE_SAY"
        const val LIVE_INJECT = P + "LIVE_INJECT"
        val ACTIONS = listOf(LIVE_START, LIVE_STOP, LIVE_TEXT, LIVE_SAY, LIVE_INJECT)
    }
}

/**
 * Debug-only foreground service of type `microphone`, so mic capture works while the app is in the background
 * on Android 14+. In the product, OverlayService must carry the microphone FGS type instead (see report).
 */
class LiveDebugMicService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            VoiceController.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live voice (debug)", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("GPT-Live debug call")
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            VcLog.i(TAG, "debug mic FGS started (type=microphone)")
        } catch (e: Exception) {
            // E.g. started from the background without a while-in-use exemption: the call still works, mic may be silent.
            VcLog.w(TAG, "debug mic FGS could not go foreground: ${e.javaClass.simpleName}: ${e.message}")
        }
        VoiceController.start(applicationContext)
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "Debug"
        private const val CHANNEL = "live_debug"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "stop"

        fun start(ctx: Context) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                VcLog.w(TAG, "RECORD_AUDIO not granted: the call will connect but the mic is silent")
            }
            try {
                ctx.startForegroundService(Intent(ctx, LiveDebugMicService::class.java))
            } catch (e: Exception) {
                VcLog.w(TAG, "cannot start debug mic FGS (${e.javaClass.simpleName}); starting voice without it")
                VoiceController.start(ctx)
            }
        }

        fun stop(ctx: Context) {
            VoiceController.stop()
            try {
                ctx.startService(Intent(ctx, LiveDebugMicService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, LiveDebugMicService::class.java))
            }
        }
    }
}

/** Debug-only, created at process start: registers the runtime LiveDebugReceiver and the file-based auth. */
class LiveDebugInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val filter = IntentFilter().apply { LiveDebugReceiver.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(ctx, LiveDebugReceiver(runtimeRegistered = true), filter, ContextCompat.RECEIVER_EXPORTED)
        if (LiveAuth.provider == null) LiveAuth.provider = CodexFileAuthProvider(ctx)
        FakeMic.install()
        VcLog.d("Debug", "runtime LiveDebugReceiver registered; debug auth provider installed")
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}

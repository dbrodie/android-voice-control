package com.brainstormity.voicecontrol.voice.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.core.VcLog

/**
 * Keeps the process runnable while the browser is in front during sign-in. Without it, Android 14+'s cached-app
 * freezer suspends our process as soon as the browser takes the foreground (unless the accessibility/overlay
 * service happens to keep us alive), and the loopback server never answers the OAuth redirect.
 *
 * Type `specialUse` (permission already held for the overlay): the sign-in waits up to 5 minutes, longer than a
 * `shortService` may run (~3 min). It stops itself when the attempt ends, and after [MAX_RUNTIME_MS] at the latest.
 * The notification doubles as a way back: tapping it reopens the sign-in screen.
 */
class LoginKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable {
        VcLog.w(TAG, "keep-alive: max runtime reached; stopping")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ChatGPT sign-in", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Signing in to ChatGPT…")
            .setContentText("Finish signing in in the browser. Tap to go back to Voice Control.")
            .setContentIntent(open)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            VcLog.w(TAG, "keep-alive startForeground failed: ${e.message}")
            stopSelf()
        }
        handler.removeCallbacks(stopper)
        handler.postDelayed(stopper, MAX_RUNTIME_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopper)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Auth"
        private const val CHANNEL = "auth"
        private const val NOTIFICATION_ID = 1455
        /** Login timeout plus a little slack for the token exchange. */
        private const val MAX_RUNTIME_MS = LoginFlow.TIMEOUT_MS + 60_000L

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, LoginKeepAliveService::class.java))
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                VcLog.w(TAG, "keep-alive service not started (${e.javaClass.simpleName}); sign-in may stall if the app is frozen")
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LoginKeepAliveService::class.java)) }
        }
    }
}

package com.brainstormity.voicecontrol.voice.auth

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.core.VcLog

/**
 * Keeps the process runnable while the browser is in front during sign-in. Without it, Android 14+'s cached-app
 * freezer suspends our process as soon as Chrome takes the foreground (unless the accessibility/overlay service
 * happens to keep us alive), and the loopback server never answers the OAuth redirect.
 * Type `shortService` (API 34+): no extra permission, the system allows ~3 minutes, which matches the login timeout.
 */
class LoginKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ChatGPT sign-in", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Signing in to ChatGPT…")
            .setContentText("Finish the sign-in in your browser.")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            VcLog.w(TAG, "keep-alive startForeground failed: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        VcLog.w(TAG, "keep-alive shortService timed out")
        stopSelf()
    }

    companion object {
        private const val TAG = "Auth"
        private const val CHANNEL = "auth"
        private const val NOTIFICATION_ID = 1455

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

package com.brainstormity.voicecontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.overlay.OverlayService

/** STUB (skeleton). The overlay-ui agent turns this into the permission checklist + settings screen. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        VcLog.d(TAG, "MainActivity created")

        findViewById<Button>(R.id.startOverlayButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            VcLog.d(TAG, "Start overlay clicked")
            OverlayService.start(this)
        }
    }

    companion object {
        private const val TAG = "UI"
    }
}

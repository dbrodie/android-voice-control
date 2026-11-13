package com.brainstormity.voicecontrol.voice.live

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.brainstormity.voicecontrol.core.VcLog

/**
 * Puts the phone in "voice call" mode for the duration of a live session: MODE_IN_COMMUNICATION (enables the
 * platform AEC/NS path), transient audio focus, output to the loudspeaker. Restores everything on [release].
 */
class AudioRouting(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var focus: AudioFocusRequest? = null
    private var active = false

    fun acquire() {
        if (active) return
        active = true
        previousMode = am.mode
        @Suppress("DEPRECATION")
        previousSpeaker = am.isSpeakerphoneOn
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { am.requestAudioFocus(it) }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToSpeaker()
        VcLog.d(TAG, "audio routed: mode=IN_COMMUNICATION speaker=on")
    }

    private fun routeToSpeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            if (speaker != null && am.setCommunicationDevice(speaker)) return
        }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
    }

    fun release() {
        if (!active) return
        active = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = previousSpeaker
        am.mode = previousMode
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        VcLog.d(TAG, "audio routing restored")
    }

    companion object { private const val TAG = "Live" }
}

package com.brainstormity.voicecontrol.debug

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.live.RtcAudioPeer
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * DEBUG ONLY: lets an emulator "speak" into a live call without a working microphone.
 *
 * When enabled (`LIVE_START --ez fake_mic true`), the WebRTC capture thread's AudioRecord is swapped (via
 * reflection, at capture start) for [FileAudioRecord], which returns queued PCM (from `LIVE_INJECT`) or
 * silence, paced in real time. Test speech: `say -o x.aiff "..."; ffmpeg -i x.aiff -ar 16000 -ac 1 -f s16le x.raw`,
 * `adb push x.raw /data/local/tmp/` then
 * `adb shell am broadcast -a com.brainstormity.voicecontrol.LIVE_INJECT --es file /data/local/tmp/x.raw`.
 */
object FakeMic {
    private const val TAG = "Debug"
    @Volatile var enabled = false
    private val queue = ConcurrentLinkedQueue<ShortArray>()
    @Volatile private var micRate = 48_000

    fun install() {
        RtcAudioPeer.debugMicStartedHook = { adm -> if (enabled) swap(adm) }
    }

    @SuppressLint("MissingPermission")
    private fun swap(adm: JavaAudioDeviceModule) {
        try {
            val input = adm.audioInput
            val field = input.javaClass.getDeclaredField("audioRecord").apply { isAccessible = true }
            val original = field.get(input) as? AudioRecord ?: return
            micRate = original.sampleRate
            field.set(input, FileAudioRecord(micRate))
            try { original.stop(); original.release() } catch (_: Exception) {}
            VcLog.i(TAG, "FakeMic: capture source replaced (rate=$micRate)")
        } catch (e: Exception) {
            VcLog.w(TAG, "FakeMic: swap failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Queues raw s16le mono PCM at [rate] Hz, resampled to the capture rate. */
    fun inject(file: File, rate: Int): Boolean {
        val bytes = try { file.readBytes() } catch (e: Exception) {
            VcLog.w(TAG, "FakeMic: cannot read ${file.path}: ${e.javaClass.simpleName}")
            return false
        }
        val src = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)
        val target = micRate
        val n = (src.size.toLong() * target / rate).toInt()
        val out = ShortArray(n) { i ->
            val pos = i.toDouble() * rate / target
            val a = pos.toInt().coerceAtMost(src.size - 1)
            val b = (a + 1).coerceAtMost(src.size - 1)
            val f = pos - a
            (src[a] * (1 - f) + src[b] * f).toInt().toShort()
        }
        queue.add(out)
        VcLog.i(TAG, "FakeMic: queued ${"%.1f".format(n / target.toDouble())}s of audio from ${file.name}")
        return true
    }

    /** An AudioRecord whose reads come from [queue] (or silence) at real-time pace. Never started itself. */
    @SuppressLint("MissingPermission")
    private class FileAudioRecord(rate: Int) : AudioRecord(
        MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2,
    ) {
        private val bytesPerSecond = rate * 2L
        private var startNs = 0L
        private var delivered = 0L
        private var current: ShortArray? = null
        private var index = 0

        override fun read(audioBuffer: ByteBuffer, sizeInBytes: Int): Int {
            if (startNs == 0L) startNs = System.nanoTime()
            val samples = sizeInBytes / 2
            for (i in 0 until samples) {
                if (current == null || index >= current!!.size) { current = queue.poll(); index = 0 }
                val s = current?.let { it[index++] } ?: 0
                audioBuffer.put(i * 2, (s.toInt() and 0xff).toByte())
                audioBuffer.put(i * 2 + 1, ((s.toInt() shr 8) and 0xff).toByte())
            }
            delivered += sizeInBytes
            val dueNs = startNs + delivered * 1_000_000_000L / bytesPerSecond
            val waitMs = (dueNs - System.nanoTime()) / 1_000_000
            if (waitMs > 0) Thread.sleep(waitMs)
            return sizeInBytes
        }
    }
}

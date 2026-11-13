package com.brainstormity.voicecontrol.voice.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.brainstormity.voicecontrol.core.VcLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Raw PCM16 LE mono audio I/O for the WebSocket transport, at the session's wire rate.
 * The transport owns one instance per call; tests use a fake.
 */
interface PcmAudio {
    /** Starts capture; [onMic] gets wire-rate PCM chunks (even length) on a background thread. */
    fun start(onMic: (ByteArray) -> Unit)
    /** Queues assistant audio for playback, in order. */
    fun play(pcm: ByteArray)
    /** Drops all queued / buffered assistant audio (barge-in). */
    fun flushPlayback()
    /** Milliseconds of assistant audio queued and not yet handed to the speaker. */
    fun queuedMs(): Long
    /** false = send silence instead of the microphone (the stream stays continuous). */
    fun setMicEnabled(enabled: Boolean)
    fun release()
}

/**
 * [PcmAudio] on AudioRecord + AudioTrack:
 * - capture: `VOICE_COMMUNICATION` source (platform AEC/NS path), mono PCM16 at the wire rate; when that rate
 *   cannot be opened, 48 kHz then 16 kHz with linear resampling to the wire rate. Attaches
 *   AcousticEchoCanceler / NoiseSuppressor / AutomaticGainControl when the device has them. 40 ms chunks.
 * - playback: AudioTrack `USAGE_VOICE_COMMUNICATION` / `CONTENT_TYPE_SPEECH` in stream mode, fed from a queue by
 *   a writer thread so the WebSocket thread never blocks.
 * - routing: [AudioRouting] (MODE_IN_COMMUNICATION, transient focus, loudspeaker) for the call's lifetime.
 */
class AndroidPcmAudio(context: Context, private val wireRate: Int = LiveProtocol.PUBLIC_RATE) : PcmAudio {
    private val routing = AudioRouting(context.applicationContext)
    @Volatile private var running = false
    @Volatile private var micEnabled = true
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()
    private var micThread: Thread? = null
    private var speakerThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val queuedBytes = AtomicLong(0)
    private val flushGen = AtomicLong(0)

    @SuppressLint("MissingPermission")
    override fun start(onMic: (ByteArray) -> Unit) {
        if (running) return
        running = true
        routing.acquire()
        startSpeaker()
        val rec = openRecord() ?: run {
            VcLog.e(TAG, "mic: AudioRecord could not be opened at $wireRate/48000/16000 Hz (permission or device busy)")
            return
        }
        record = rec
        attachEffects(rec.audioSessionId)
        val captureRate = rec.sampleRate
        val resampler = if (captureRate == wireRate) null else LinearResampler(captureRate, wireRate)
        val frame = ShortArray(captureRate / 25) // 40 ms
        micThread = Thread({
            try {
                rec.startRecording()
                VcLog.i(TAG, "mic capture started: ${captureRate} Hz${resampler?.let { " -> $wireRate Hz (resampled)" } ?: ""}")
                while (running) {
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) {
                        if (n < 0) { VcLog.e(TAG, "mic read error $n"); break }
                        continue
                    }
                    var samples = if (n == frame.size) frame else frame.copyOf(n)
                    if (!micEnabled) samples = ShortArray(samples.size)
                    val out = resampler?.process(samples) ?: samples
                    if (out.isNotEmpty()) onMic(Pcm16.toBytes(out))
                }
            } catch (e: Exception) {
                VcLog.e(TAG, "mic thread failed: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { rec.stop() } catch (_: Exception) {}
            }
        }, "vc-live-mic").also { it.start() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): AudioRecord? {
        for (rate in linkedSetOf(wireRate, 48_000, 16_000)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, rate / 5 * 2)) // >= 200 ms
                    .build()
            } catch (e: Exception) {
                VcLog.w(TAG, "mic: AudioRecord at $rate Hz failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            } ?: continue
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec
            VcLog.w(TAG, "mic: AudioRecord at $rate Hz not initialized")
            rec.release()
        }
        return null
    }

    private fun attachEffects(sessionId: Int) {
        fun <T : android.media.audiofx.AudioEffect> add(name: String, available: Boolean, create: () -> T?) {
            if (!available) { VcLog.d(TAG, "mic: $name not available"); return }
            try {
                create()?.let { it.enabled = true; effects += it; VcLog.d(TAG, "mic: $name on") }
            } catch (e: Exception) {
                VcLog.w(TAG, "mic: $name failed: ${e.message}")
            }
        }
        add("AcousticEchoCanceler", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        add("NoiseSuppressor", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        add("AutomaticGainControl", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
    }

    private fun startSpeaker() {
        val min = AudioTrack.getMinBufferSize(wireRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(wireRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, wireRate / 10 * 2)) // ~100 ms: keeps barge-in flushes short
                .build()
        } catch (e: Exception) {
            VcLog.e(TAG, "speaker: AudioTrack failed: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        track = t
        t.play()
        speakerThread = Thread({
            while (running) {
                val chunk = try { playQueue.poll(200, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break } ?: continue
                queuedBytes.addAndGet(-chunk.size.toLong())
                val gen = flushGen.get()
                var off = 0
                // Write in small slices so a flush takes effect within ~20 ms.
                while (off < chunk.size && running && gen == flushGen.get()) {
                    val n = t.write(chunk, off, minOf(SLICE, chunk.size - off))
                    if (n < 0) { VcLog.e(TAG, "speaker write error $n"); break }
                    off += n
                }
            }
        }, "vc-live-speaker").also { it.start() }
        VcLog.d(TAG, "speaker started: $wireRate Hz, USAGE_VOICE_COMMUNICATION")
    }

    override fun play(pcm: ByteArray) {
        if (!running || pcm.isEmpty()) return
        queuedBytes.addAndGet(pcm.size.toLong())
        playQueue.offer(pcm)
    }

    override fun flushPlayback() {
        flushGen.incrementAndGet()
        val dropped = queuedBytes.getAndSet(0)
        playQueue.clear()
        track?.let {
            try { it.pause(); it.flush(); it.play() } catch (_: Exception) {}
        }
        VcLog.d(TAG, "speaker flushed (${dropped * 1000 / (wireRate * 2)} ms dropped)")
    }

    override fun queuedMs(): Long = queuedBytes.get().coerceAtLeast(0) * 1000 / (wireRate * 2)

    override fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
    }

    override fun release() {
        if (!running) return
        running = false
        micThread?.join(500)
        speakerThread?.interrupt()
        speakerThread?.join(500)
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        record?.release()
        record = null
        track?.let { try { it.pause(); it.flush(); it.release() } catch (_: Exception) {} }
        track = null
        playQueue.clear()
        routing.release()
        VcLog.d(TAG, "audio released")
    }

    companion object {
        private const val TAG = "Live"
        private const val SLICE = 960 // 20 ms at 24 kHz
    }
}

/** Little-endian PCM16 helpers. */
object Pcm16 {
    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val v = samples[i].toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    fun toShorts(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size / 2)
        for (i in out.indices) {
            out[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
        }
        return out
    }
}

/**
 * Streaming linear-interpolation resampler (mono PCM16). Good enough for speech going to a speech model;
 * when downsampling it averages the input span first as a crude low-pass.
 */
class LinearResampler(private val inRate: Int, private val outRate: Int) {
    private val step = inRate.toDouble() / outRate
    private var pos = 0.0          // position of the next output sample, relative to the current block
    private var prev: Short = 0    // last sample of the previous block (index -1)

    fun process(input: ShortArray): ShortArray {
        if (input.isEmpty()) return input
        val out = ArrayList<Short>((input.size / step).toInt() + 2)
        fun at(i: Int): Int = if (i < 0) prev.toInt() else input[i].toInt()
        while (pos <= input.size - 1) {
            val i = kotlin.math.floor(pos).toInt()
            val frac = pos - i
            val v = if (step > 1.0) {
                // Average over the span this output sample covers (box filter), then interpolate the edge.
                val start = maxOf(-1, (pos - step / 2).toInt())
                val end = minOf(input.size - 1, (pos + step / 2).toInt())
                var sum = 0L
                for (k in start..end) sum += at(k)
                (sum / (end - start + 1)).toInt()
            } else {
                val next = if (i + 1 < input.size) at(i + 1) else at(i) // i + 1 == size only when frac == 0
                (at(i) + (next - at(i)) * frac).toInt()
            }
            out += v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            pos += step
        }
        pos -= input.size
        prev = input.last()
        return out.toShortArray()
    }
}

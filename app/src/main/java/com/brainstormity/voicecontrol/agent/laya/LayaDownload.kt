package com.brainstormity.voicecontrol.agent.laya

import android.content.Context
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Downloads the missing model files ([LayaModelFiles.status]) from [AppSettings.layaModelBaseUrl] + file name.
 * Runs in a process-wide scope (keeps going when the settings screen closes; the accessibility service keeps the
 * process alive), one file at a time into `<name>.part`, resuming with `Range: bytes=<have>-` (a server that
 * ignores Range answers 200 and the file restarts), sha256-checked before the rename. [state] is observed by the
 * "Next-step model" card (progress bar + Cancel) and by [LayaModelFiles.availabilityFor].
 */
object LayaDownload {
    private const val TAG = "Laya"

    sealed class State {
        data class Running(val done: Long, val total: Long, val file: String) : State() {
            val percent: Int get() = if (total <= 0) 0 else (done * 100 / total).toInt()
        }
        data class Failed(val message: String) : State()
        object Done : State()
        object Cancelled : State()
    }

    @Volatile var state: State? = null
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(State?) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var job: Job? = null

    fun addListener(l: (State?) -> Unit) { listeners += l }
    fun removeListener(l: (State?) -> Unit) { listeners -= l }

    private fun set(s: State?) {
        state = s
        listeners.forEach { runCatching { it(s) } }
    }

    val running: Boolean get() = job?.isActive == true

    fun start(ctx: Context) {
        if (running) return
        val app = ctx.applicationContext
        job = scope.launch {
            try {
                run(app)
                set(State.Done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                set(State.Cancelled)
            } catch (e: Exception) {
                VcLog.e(TAG, "download failed: ${e.message}", e)
                set(State.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun run(ctx: Context) {
        val base = AppSettings.layaModelBaseUrl(ctx).let { if (it.endsWith("/")) it else "$it/" }
        val status = LayaModelFiles.status(ctx)
        val dir = LayaModelFiles.dir(ctx).apply { mkdirs() }
        val total = status.missingBytes
        var doneBefore = 0L
        VcLog.i(TAG, "download: ${status.missing.size} files, ${LayaModelFiles.mb(total)} from $base")
        for (spec in status.missing) {
            val part = File(dir, spec.name + ".part")
            var have = if (part.isFile) part.length() else 0L
            if (have > spec.bytes) { part.delete(); have = 0 }
            set(State.Running(doneBefore + have, total, spec.name))
            if (have < spec.bytes) {
                LayaModelFiles.http.newCall(LayaModelFiles.request(base + spec.name, have)).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for ${spec.name}")
                    val append = resp.code == 206
                    if (!append) have = 0
                    val body = resp.body ?: throw IOException("empty response for ${spec.name}")
                    LayaModelFiles.resumeInto(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            var lastReport = 0L
                            while (true) {
                                if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                have += n
                                if (have - lastReport > 1_000_000) {
                                    lastReport = have
                                    set(State.Running(doneBefore + have, total, spec.name))
                                }
                            }
                        }
                    }
                }
            }
            if (part.length() != spec.bytes) throw IOException("${spec.name}: got ${part.length()} of ${spec.bytes} bytes")
            val sha = LayaModelFiles.sha256(part)
            if (sha != spec.sha256) {
                part.delete()
                throw IOException("${spec.name} is corrupt (checksum mismatch); try again")
            }
            val dest = File(dir, spec.name)
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("could not move ${spec.name} into place")
            // Freshly verified: record it so the first engine load doesn't hash it again.
            File(dir, "verified.txt").appendText("\n${spec.name} ${dest.length()} ${dest.lastModified()}")
            doneBefore += spec.bytes
            set(State.Running(doneBefore, total, spec.name))
            VcLog.i(TAG, "download: ${spec.name} ok")
        }
        LayaEngine.releaseIfLoaded()
    }
}

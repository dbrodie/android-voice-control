package com.brainstormity.voicecontrol.agent.laya

import android.content.Context
import android.os.Build
import com.brainstormity.voicecontrol.agent.SystemOneException
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Runs the Laya-Android LiteRT graph on the phone (HOST_CONTRACT.md sections 4 and 6; `tools/laya/host.py` is
 * the reference). One engine per process ([get]), loaded lazily on first use (or by [warmup]); every native call
 * runs on one dedicated thread ("Laya-LiteRT"), so it is thread-safe and callers simply block (call it from a
 * background thread: TaskRunner's Dispatchers.IO).
 *
 * Load: sha256 check (once per file version, [LayaModelFiles.verify]), tokenizer, `rl_agent_config.json`
 * temperatures, memory-mapped fp16 token table, then `laya_android_multi_embeds_wfp16.tflite` on the first
 * [Backend] of the [AppSettings.layaAccelerator] order that compiles and binds:
 *  - [Backend.GPU]: LiteRT CompiledModel, GPU with explicit FP32 precision (what the community recommends for this
 *    architecture: no fp16-overflow rewrites in this export);
 *  - [Backend.CPU]: CompiledModel on the CPU (XNNPACK, up to 4 threads);
 *  - [Backend.CPU_REF]: the classic TFLite Interpreter with XNNPACK off (built-in kernels). Used instead of CPU
 *    where XNNPACK can't run: the Android emulator on Apple M4/M5 Macs advertises SME (`/proc/cpuinfo`), XNNPACK
 *    executes SME instructions and the process dies with SIGILL (LiteRT 2.1.6-2.3.2 all contain that path).
 * A GPU that produces non-finite logits is dropped for the CPU. A backend whose compile killed the process last
 * time (native crash, e.g. a GPU driver) is skipped: `compiling.txt` names the backend while it compiles.
 * Per window the multi-graph's signature `s<window>` is used; if it can't be bound, the single-window file
 * (`laya_android_s<window>_embeds_wfp16.tflite`, signature `serving_default`) if present, else the next larger
 * window (same ids, only more padding).
 *
 * Per question: ids padded with PAD to the window -> `inputs_embeds` = table rows (fp16 -> fp32; padding uses
 * the real row 0), `attention_mask` 1/0, `qtype_onehot` [1,0,0]; read `token_logits` at the markers, divide by
 * the temperature, softmax ([LayaDecoder]). The act head is not used. Logs under VC/Laya.
 */
class LayaEngine private constructor(private val app: Context) {

    /** Where the graph runs. */
    enum class Backend(val label: String) { GPU("GPU"), CPU("CPU"), CPU_REF("CPU, reference kernels") }

    /** What the last load did (for the settings line, run reports and logs). */
    data class LoadInfo(
        val backend: Backend, val threads: Int, val loadMs: Long, val verifyMs: Long, val tokenizerMs: Long,
        val compileMs: Long, val graph: String,
    ) {
        fun line(): String = "loaded on ${backend.label}${if (backend != Backend.GPU) ", $threads threads" else ""} in " +
            String.format(java.util.Locale.US, "%.1f s", loadMs / 1000.0)
    }

    /** One step's answers. [questionMs] per question (gather + graph), [prepMs] tokenize + build. */
    class Scored(
        val answers: LinkedHashMap<String, LayaDecoder.Answer>,
        val window: Int,
        val truncated: Boolean,
        val stateTokens: Int,
        val prepMs: Long,
        val questionMs: LinkedHashMap<String, Long>,
        val backend: String,
        /** Non-null when this call loaded the engine. */
        val loadMs: Long?,
        /** Real (unpadded) tokens over all questions. */
        val inputTokens: Long = 0,
    )

    /** A bound signature (or single-window graph) that runs one window. */
    private interface Runner {
        val window: Int
        fun run(embeds: FloatArray, attention: FloatArray, qtype: FloatArray): FloatArray
        fun close()
    }

    /** CompiledModel (GPU / CPU-XNNPACK): named buffers of one signature (never by index). */
    private class CompiledRunner(
        val model: CompiledModel, val signature: String, override val window: Int, val owned: Boolean,
    ) : Runner {
        private val inputs = LinkedHashMap<String, TensorBuffer>()
        private val outputs = LinkedHashMap<String, TensorBuffer>()

        init {
            try {
                for (n in INPUTS) inputs[n] = model.createInputBuffer(n, signature)
                for (n in OUTPUTS) outputs[n] = model.createOutputBuffer(n, signature)
            } catch (t: Throwable) {
                close(); throw t
            }
        }

        override fun run(embeds: FloatArray, attention: FloatArray, qtype: FloatArray): FloatArray {
            inputs.getValue("inputs_embeds").writeFloat(embeds)
            inputs.getValue("attention_mask").writeFloat(attention)
            inputs.getValue("qtype_onehot").writeFloat(qtype)
            model.run(inputs, outputs, signature)
            return outputs.getValue("token_logits").readFloat()
        }

        override fun close() {
            (inputs.values + outputs.values).forEach { runCatching { it.close() } }
            if (owned) runCatching { model.close() }
        }
    }

    /** Classic Interpreter, XNNPACK off ([Backend.CPU_REF]). Direct buffers, mapped by signature name. */
    private class InterpreterRunner(
        val interpreter: Interpreter, val signature: String, override val window: Int, val owned: Boolean,
    ) : Runner {
        private fun buf(floats: Int) = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
        private val emb = buf(window * WIDTH)
        private val att = buf(window)
        private val qt = buf(3)
        private val logits = buf(window)
        private val pooled = buf(WIDTH)

        override fun run(embeds: FloatArray, attention: FloatArray, qtype: FloatArray): FloatArray {
            emb.clear(); emb.asFloatBuffer().put(embeds)
            att.clear(); att.asFloatBuffer().put(attention)
            qt.clear(); qt.asFloatBuffer().put(qtype)
            logits.clear(); pooled.clear()
            interpreter.runSignature(mapOf("inputs_embeds" to emb, "attention_mask" to att, "qtype_onehot" to qt),
                mapOf("token_logits" to logits, "pooled_cls" to pooled), signature)
            logits.rewind()
            return FloatArray(window).also { logits.asFloatBuffer().get(it) }
        }

        override fun close() {
            if (owned) runCatching { interpreter.close() }
        }
    }

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "Laya-LiteRT").apply { isDaemon = true } }

    // Engine-thread state.
    private var env: Environment? = null
    private var backend = Backend.CPU
    private var compiled: CompiledModel? = null
    private var interpreter: Interpreter? = null
    /** CPU_REF on the single-window fp32 file: its only signature. */
    private var refSignature: String? = null
    private var threads = 1
    private val runners = HashMap<Int, Runner>()
    private val extraOwned = ArrayList<Runner>()
    private var tokenizer: LayaTokenizer? = null
    private var table: ShortBuffer? = null
    private var temperature = LayaDecoder.TEMPERATURE_CHOICE
    private var temperatureByOptions: Map<String, Double> = emptyMap()
    private val embeds = HashMap<Int, FloatArray>()

    @Volatile var loadInfo: LoadInfo? = null
        private set
    /** Last load failure (user-facing), null after a successful load. */
    @Volatile var loadError: String? = null
        private set
    /** A failed integrity check: availability reports it until the files change. */
    @Volatile var verifyError: String? = null
        private set
    @Volatile private var loading = false

    val isLoaded: Boolean get() = loadInfo != null
    val isLoading: Boolean get() = loading

    private fun <T> call(block: () -> T): T = try {
        executor.submit(Callable { block() }).get()
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    }

    /** Loads in the background if not loaded yet (cheap to call; errors are logged and kept in [loadError]). */
    fun warmup() {
        if (isLoaded || loading) return
        if (!LayaModelFiles.status(app).complete) return
        loading = true
        executor.execute { runCatching { ensureLoaded() } }
    }

    /** Blocking load; returns how it went. Throws [SystemOneException] with a user-facing message. */
    fun load(): LoadInfo = call { ensureLoaded() }

    /** Frees the graphs and the table (files are about to change / be deleted). Next use reloads. */
    fun release() = call {
        closeRunners()
        table = null
        tokenizer = null
        embeds.clear()
        loadInfo = null
        verifyError = null
        loadError = null
    }

    /** The tokenizer (loads the engine). */
    fun tokenizer(): LayaTokenizer = call { ensureLoaded(); tokenizer!! }

    /**
     * Encodes [questions] over [stateJson] (smallest window that holds every question untruncated, else
     * [maxWindow] with the state's tail cut) and answers them one after another on the same window.
     * Throws [LayaDoesNotFit] if a question's options don't fit [maxWindow] (the caller drops candidates).
     */
    fun score(stateJson: String, questions: LinkedHashMap<String, LayaQuestion>, maxWindow: Int = LayaSequence.MAX_WINDOW): Scored =
        call {
            val loadMs = if (loadInfo == null) ensureLoaded().loadMs else null
            val tok = tokenizer!!
            val t0 = System.nanoTime()
            val plan = LayaSequence.plan(tok::encode, stateJson, questions, minOf(maxWindow, backendMaxWindow()))
            val prepMs = (System.nanoTime() - t0) / 1_000_000
            if (!plan.fits) {
                throw LayaDoesNotFit(plan.questions.filter { !it.fits }
                    .joinToString { "${it.qid}: ${it.markersLost} options past $maxWindow tokens" })
            }
            val answers = LinkedHashMap<String, LayaDecoder.Answer>()
            val times = LinkedHashMap<String, Long>()
            var window = maxOf(plan.window, backendMinWindow())
            for (e in plan.questions) {
                val q = questions.getValue(e.qid)
                val s = System.nanoTime()
                val (raw, w) = runMarkers(e.ids, e.markers, maxOf(plan.window, backendMinWindow()))
                window = w
                times[e.qid] = (System.nanoTime() - s) / 1_000_000
                answers[e.qid] = LayaDecoder.decode(q.keys, raw, temperatureFor(q.size))
            }
            VcLog.i(TAG, "step: window $window${if (plan.truncated) " (state cut)" else ""}, state ${plan.stateTokens} tok, " +
                "prep $prepMs ms, " + times.entries.joinToString { "${it.key} ${it.value} ms" } + " on ${backend.label}")
            Scored(answers, window, plan.truncated, plan.stateTokens, prepMs, times, backend.label, loadMs,
                plan.questions.sumOf { it.ids.size.toLong() })
        }

    /** Raw marker logits for given ids at [window] (or a larger window if that one can't run). Parity tool. */
    fun runIds(ids: IntArray, markers: IntArray, window: Int): Pair<FloatArray, Int> = call {
        ensureLoaded()
        runMarkers(ids, markers, window)
    }

    fun backendLabel(): String = backend.label

    /** Largest window this engine runs (loads it). */
    fun maxWindow(): Int = call { ensureLoaded(); backendMaxWindow() }

    /**
     * The reference-kernel backend dequantizes every signature's fp16 weights into its own fp32 copy (~0.45 GB
     * each, and s256 is allocated when the Interpreter is created): cap it at 512 so at most two copies exist.
     */
    private fun backendMaxWindow(): Int = if (backend == Backend.CPU_REF) CPU_REF_WINDOW else LayaSequence.MAX_WINDOW

    /**
     * The reference kernels materialise fp32 weights for a signature the first time it runs (~0.45 GB each): on a
     * 2 GB emulator s256 + s512 got the app killed by lmkd. So CPU_REF runs every step on ONE signature (s512);
     * shorter sequences are just padded (identical ids, HOST_CONTRACT section 4).
     */
    fun backendMinWindow(): Int = if (backend == Backend.CPU_REF) CPU_REF_WINDOW else 0

    private fun temperatureFor(k: Int): Double = temperatureByOptions[LayaDecoder.bucket(k)] ?: temperature

    // ------------------------------------------------------------------ load (engine thread)

    private fun ensureLoaded(): LoadInfo {
        loadInfo?.let { return it }
        loading = true
        try {
            return doLoad().also { loadInfo = it; loadError = null }
        } catch (e: SystemOneException) {
            loadError = e.message
            throw e
        } catch (e: Throwable) {
            val msg = "The on-device model couldn't start: ${e.message ?: e.javaClass.simpleName}"
            VcLog.e(TAG, msg, e)
            loadError = msg
            throw SystemOneException(msg, e)
        } finally {
            loading = false
        }
    }

    private fun doLoad(): LoadInfo {
        val started = System.nanoTime()
        val dir = LayaModelFiles.dir(app)
        val specs = LayaModelFiles.requiredSpecs(LayaModelFiles.manifest(app))
        val status = LayaModelFiles.statusOf(dir, specs)
        if (!status.complete) {
            throw SystemOneException("The on-device model isn't downloaded yet (${LayaModelFiles.mb(status.missingBytes)} missing)")
        }
        val bad = LayaModelFiles.verify(dir, specs) { VcLog.i(TAG, it) }
        val verifyMs = (System.nanoTime() - started) / 1_000_000
        if (bad.isNotEmpty()) {
            verifyError = "The on-device model files are damaged (${bad.joinToString()}): delete and download them again"
            throw SystemOneException(verifyError!!)
        }
        val tStart = System.nanoTime()
        val tok = LayaTokenizer.load(File(dir, LayaModelFiles.TOKENIZER))
        val tokenizerMs = (System.nanoTime() - tStart) / 1_000_000
        readConfig(File(dir, LayaModelFiles.CONFIG))
        table = mapTable(File(dir, LayaModelFiles.TABLE), File(dir, LayaModelFiles.TABLE_META).takeIf { it.isFile })
        tokenizer = tok

        val cStart = System.nanoTime()
        val graphFile = File(dir, LayaModelFiles.GRAPH_MULTI)
        val crashMarker = File(dir, "compiling.txt")
        val crashedLast = if (crashMarker.isFile) crashMarker.readText().trim() else null
        if (crashedLast != null) VcLog.w(TAG, "the last load died while compiling on $crashedLast: skipping it")
        val order = backendOrder(AppSettings.layaAccelerator(app), xnnpackUnsafe()).filter { it.name != crashedLast }
        var lastError: Throwable? = null
        for (b in order) {
            try {
                crashMarker.writeText(b.name)
                open(graphFile, b)
                backend = b
                runner(LayaSequence.WINDOWS[0]) // bind now: a delegate that compiles but can't bind fails over here
                crashMarker.delete()
                lastError = null
                break
            } catch (t: Throwable) {
                crashMarker.delete()
                VcLog.w(TAG, "${b.label} unavailable for ${graphFile.name}: ${t.message}")
                closeRunners()
                lastError = t
            }
        }
        if (lastError != null || (compiled == null && interpreter == null)) {
            throw lastError ?: IllegalStateException("no backend could run the model (tried ${order.joinToString()})")
        }
        val compileMs = (System.nanoTime() - cStart) / 1_000_000
        val info = LoadInfo(backend, threads, (System.nanoTime() - started) / 1_000_000, verifyMs, tokenizerMs,
            compileMs, graphFile.name)
        VcLog.i(TAG, "engine ${info.line()}: verify ${info.verifyMs} ms, tokenizer ${info.tokenizerMs} ms, " +
            "compile+bind ${info.compileMs} ms, T=$temperature")
        return info
    }

    /** Opens [file] on [b] as the shared multi-signature model. */
    private fun open(file: File, b: Backend) {
        val t = System.nanoTime()
        threads = Runtime.getRuntime().availableProcessors().coerceIn(1, MAX_THREADS)
        when (b) {
            Backend.CPU_REF -> {
                // fp32-weight single-window graph if present (dev / emulator): its weights stay mmapped file pages
                // instead of a dequantized anonymous copy. Else the multi graph (one signature, CPU_REF_WINDOW).
                val fp32 = File(file.parentFile, LayaModelFiles.CPU_REF_GRAPH)
                refSignature = if (fp32.isFile) "serving_default" else null
                interpreter = interpreterFor(if (fp32.isFile) fp32 else file)
                if (fp32.isFile) VcLog.i(TAG, "reference kernels use ${fp32.name}")
            }
            else -> compiled = compile(file, b)
        }
        VcLog.i(TAG, "opened ${file.name} on ${b.label} in ${(System.nanoTime() - t) / 1_000_000} ms")
    }

    private fun compile(file: File, b: Backend): CompiledModel {
        val e = env ?: Environment.create().also { env = it }
        val options = if (b == Backend.GPU) {
            CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32)
            }
        } else {
            CompiledModel.Options(Accelerator.CPU).apply { cpuOptions = CompiledModel.CpuOptions(numThreads = threads) }
        }
        return CompiledModel.create(file.absolutePath, options, e)
    }

    private fun interpreterFor(file: File): Interpreter =
        Interpreter(file, Interpreter.Options().setNumThreads(threads).setUseXNNPACK(false))

    private fun readConfig(f: File) {
        @Suppress("UNCHECKED_CAST")
        val cfg = f.reader().use { LayaJsonReader(it).readValue() } as Map<String, Any?>
        val temps = (cfg["temperature"] as? List<*>)?.map { (it as? Number)?.toDouble() }
        temperature = LayaDecoder.clampTemperature(temps?.getOrNull(LayaSequence.QTYPE_CHOICE) ?: 1.0)
        temperatureByOptions = ((cfg["temperature_by_options"] as? Map<*, *>) ?: emptyMap<String, Any?>())
            .entries.filter { (it.key as String).startsWith("choice:") }
            .associate { it.key as String to LayaDecoder.clampTemperature((it.value as? Number)?.toDouble()) }
    }

    /**
     * Maps the [VOCAB] x [WIDTH] fp16 table. `token_embeddings.json` is optional (the published model release
     * doesn't carry it; the table's sha256 in the MANIFEST already pins its content): checked when present.
     */
    private fun mapTable(bin: File, meta: File?): ShortBuffer {
        if (meta != null) {
            @Suppress("UNCHECKED_CAST")
            val m = meta.reader().use { LayaJsonReader(it).readValue() } as Map<String, Any?>
            val shape = (m["shape"] as List<*>).map { (it as Number).toInt() }
            require(shape == listOf(VOCAB, WIDTH) && m["dtype"] == "float16" && m["byte_order"] == "little") {
                "unexpected token table $shape ${m["dtype"]}"
            }
        }
        val bytes = VOCAB.toLong() * WIDTH * 2
        require(bin.length() == bytes) { "token table is ${bin.length()} bytes, expected $bytes" }
        return RandomAccessFile(bin, "r").channel.use { ch ->
            ch.map(FileChannel.MapMode.READ_ONLY, 0, bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        }
    }

    // ------------------------------------------------------------------ run (engine thread)

    /** Binds (once) the runner for [window]; throws if neither the multi signature nor a single file works. */
    private fun runner(window: Int): Runner {
        runners[window]?.let { return it }
        val r = try {
            bindShared("s$window", window)
        } catch (t: Throwable) {
            val single = LayaModelFiles.SINGLE_GRAPHS[window]?.let { File(LayaModelFiles.dir(app), it) }
            if (single == null || !single.isFile) throw t
            VcLog.w(TAG, "signature s$window unusable (${t.message}); using ${single.name}")
            when (backend) {
                Backend.CPU_REF -> InterpreterRunner(interpreterFor(single), "serving_default", window, owned = true)
                else -> CompiledRunner(compile(single, backend), "serving_default", window, owned = true)
            }
        }
        runners[window] = r
        return r
    }

    private fun bindShared(sig: String, window: Int): Runner = when (backend) {
        Backend.CPU_REF -> InterpreterRunner(interpreter ?: error("not loaded"), refSignature ?: sig, window, owned = false)
        else -> CompiledRunner(compiled ?: error("not loaded"), sig, window, owned = false)
    }

    private fun closeRunners() {
        runners.values.forEach { it.close() }
        runners.clear()
        extraOwned.forEach { it.close() }
        extraOwned.clear()
        compiled?.let { runCatching { it.close() } }
        compiled = null
        interpreter?.let { runCatching { it.close() } }
        interpreter = null
    }

    /** Marker logits; falls back to larger windows if [window] can't be bound, and from GPU to CPU on bad output. */
    private fun runMarkers(ids: IntArray, markers: IntArray, window: Int): Pair<FloatArray, Int> {
        var lastError: Throwable? = null
        for (w in LayaSequence.WINDOWS.filter { it >= window && it >= ids.size && it <= backendMaxWindow() }) {
            val r = try { runner(w) } catch (t: Throwable) {
                VcLog.w(TAG, "window $w unavailable: ${t.message}"); lastError = t; continue
            }
            val logits = run(r, ids)
            val raw = FloatArray(markers.size) { logits[markers[it]] }
            if (raw.all { it.isFinite() }) return raw to w
            if (backend == Backend.GPU) {
                val next = if (xnnpackUnsafe()) Backend.CPU_REF else Backend.CPU
                VcLog.w(TAG, "GPU produced non-finite logits: switching to ${next.label}")
                closeRunners()
                open(File(LayaModelFiles.dir(app), LayaModelFiles.GRAPH_MULTI), next)
                backend = next
                loadInfo = loadInfo?.copy(backend = next, threads = threads)
                return runMarkers(ids, markers, window)
            }
            throw SystemOneException("The on-device model produced invalid numbers")
        }
        throw SystemOneException("The on-device model can't run a $window-token window: ${lastError?.message}", lastError)
    }

    private fun run(r: Runner, ids: IntArray): FloatArray {
        val n = r.window
        val emb = embeds.getOrPut(n) { FloatArray(n * WIDTH) }
        gather(ids, n, emb)
        val att = FloatArray(n) { if (it < ids.size) 1f else 0f }
        val qtype = FloatArray(3).also { it[LayaSequence.QTYPE_CHOICE] = 1f }
        val logits = r.run(emb, att, qtype)
        check(logits.size == n) { "token_logits has ${logits.size} values, expected $n" }
        return logits
    }

    /** `table[id]` fp16 -> fp32 for every position; positions past [ids] use PAD's real row (id 0). */
    private fun gather(ids: IntArray, window: Int, out: FloatArray) {
        val t = table!!.duplicate()
        val row = ShortArray(WIDTH)
        var o = 0
        for (p in 0 until window) {
            val id = if (p < ids.size) ids[p] else LayaTokenizer.PAD
            require(id in 0 until VOCAB) { "token id $id out of range" }
            t.position(id * WIDTH)
            t.get(row, 0, WIDTH)
            for (k in 0 until WIDTH) out[o++] = HALF[row[k].toInt() and 0xffff]
        }
    }

    companion object {
        private const val TAG = "Laya"
        const val VOCAB = 256000
        const val WIDTH = 768
        private const val MAX_THREADS = 4
        const val CPU_REF_WINDOW = 512
        private val INPUTS = listOf("inputs_embeds", "attention_mask", "qtype_onehot")
        private val OUTPUTS = listOf("token_logits", "pooled_cls")

        /** Backends to try, in order, for the accelerator setting (pure; unit-tested). */
        fun backendOrder(setting: String, xnnpackUnsafe: Boolean): List<Backend> {
            val cpu = if (xnnpackUnsafe) Backend.CPU_REF else Backend.CPU
            return when (setting) {
                AppSettings.LAYA_ACCEL_GPU -> listOf(Backend.GPU)
                AppSettings.LAYA_ACCEL_CPU -> listOf(cpu)
                AppSettings.LAYA_ACCEL_CPU_REF -> listOf(Backend.CPU_REF)
                else -> listOf(Backend.GPU, cpu)
            }
        }

        /**
         * True on an emulator whose virtual CPU advertises SME (Apple M4/M5 hosts): XNNPACK would execute SME
         * instructions there and die with SIGILL. Real phones that report SME support it.
         */
        fun xnnpackUnsafe(): Boolean {
            val emulator = Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.startsWith("sdk_gphone") ||
                Build.FINGERPRINT.contains("emulator") || Build.FINGERPRINT.startsWith("generic")
            if (!emulator) return false
            val features = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") } }
                .getOrNull() ?: return false
            return cpuFeaturesHaveSme(features)
        }

        fun cpuFeaturesHaveSme(featuresLine: String): Boolean =
            featuresLine.substringAfter(':').split(' ', '\t').any { it == "sme" || it == "sme2" || it.startsWith("sme2p") }

        /** Exact IEEE binary16 -> binary32 for all 65536 bit patterns. */
        val HALF: FloatArray by lazy { FloatArray(65536) { halfToFloat(it) } }

        fun halfToFloat(bits: Int): Float {
            val sign = (bits and 0x8000) shl 16
            val exp = (bits ushr 10) and 0x1f
            val frac = bits and 0x3ff
            val f = when (exp) {
                0 -> if (frac == 0) sign else {
                    var e = 113
                    var m = frac
                    while (m and 0x400 == 0) { m = m shl 1; e-- }
                    sign or (e shl 23) or ((m and 0x3ff) shl 13)
                }
                31 -> sign or 0x7f800000 or (frac shl 13)
                else -> sign or ((exp + 112) shl 23) or (frac shl 13)
            }
            return java.lang.Float.intBitsToFloat(f)
        }

        @Volatile private var instance: LayaEngine? = null

        fun get(ctx: Context): LayaEngine = instance ?: synchronized(this) {
            instance ?: LayaEngine(ctx.applicationContext).also { instance = it }
        }

        /** The engine if it was ever created (no side effects). */
        fun peek(): LayaEngine? = instance

        /** Before model files change: drop graphs/table so the next use reloads them. */
        fun releaseIfLoaded() {
            instance?.let { if (it.isLoaded || it.verifyError != null || it.loadError != null) it.release() }
        }
    }
}

/** The question's options don't fit the largest window: drop candidates and ask again. */
class LayaDoesNotFit(message: String) : SystemOneException("The screen has too many elements for the on-device model ($message)")

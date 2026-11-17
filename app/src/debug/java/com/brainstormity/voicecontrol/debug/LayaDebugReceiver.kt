package com.brainstormity.voicecontrol.debug

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat
import com.brainstormity.voicecontrol.agent.SystemOneBackends
import com.brainstormity.voicecontrol.agent.laya.LayaDecoder
import com.brainstormity.voicecontrol.agent.laya.LayaEngine
import com.brainstormity.voicecontrol.agent.laya.LayaModelFiles
import com.brainstormity.voicecontrol.agent.laya.LayaQuestion
import com.brainstormity.voicecontrol.agent.laya.LayaSequence
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Debug-only hooks for the on-device Laya model (agent/laya). Logs go to VC/Laya.
 *
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.IMPORT_LAYA [--es dir /sdcard/Android/data/com.brainstormity.voicecontrol/files/laya] [--ez delete true]
 *       copies the model files found in `dir` (default: the app-specific external dir `.../files/laya`, which
 *       `adb push` can write) into files/models/laya-android/; `delete` removes the copies in `dir` afterwards.
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LAYA_CONFIG [--es accelerator auto|gpu|cpu|cpu_ref][--ez allow_low_ram true] [--es base_url https://...]
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LAYA_STATUS
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LAYA_PARITY [--ei window 512] [--es rows a,b]
 *       runs the 24 T14 fixture rows (debug asset laya/parity.json) through the real tokenizer + builder + graph:
 *       checks the state JSON and token ids, then compares probabilities with laya's PyTorch reference and logs
 *       `PARITY rows=.. questions=.. ids_ok=.. argmax_ok=.. max_diff=..`. Without `window` each row runs at the
 *       adaptive window (smallest that fits); with it, at that window (rows that don't fit it are skipped).
 *   adb shell am broadcast -a com.brainstormity.voicecontrol.LAYA_RELEASE      (drop the loaded graphs)
 */
class LayaDebugReceiver(private val runtimeRegistered: Boolean) : BroadcastReceiver() {

    constructor() : this(false)

    override fun onReceive(context: Context, intent: Intent) {
        val explicit = intent.`package` != null || intent.component != null
        if (runtimeRegistered && explicit) return
        val action = intent.action ?: return
        val app = context.applicationContext
        // Not goAsync(): parity / import run for minutes, far past the broadcast timeout (the system would kill
        // the process for a background ANR). The work continues in this process-wide scope.
        scope.launch {
            try {
                when (action) {
                    IMPORT -> {
                        val dir = intent.getStringExtra("dir")?.let(::File) ?: File(app.getExternalFilesDir(null), "laya")
                        val t = System.nanoTime()
                        val names = LayaModelFiles.importDir(app, dir)
                        if (intent.getBooleanExtra("delete", false)) names.forEach { File(dir, it).delete() }
                        VcLog.i(TAG, "IMPORT_LAYA from $dir: ${names.ifEmpty { listOf("nothing") }} in " +
                            "${(System.nanoTime() - t) / 1_000_000} ms; status: ${SystemOneBackends.statusLine(app, SystemOneBackends.byId("laya")!!)}")
                    }
                    CONFIG -> {
                        intent.getStringExtra("accelerator")?.let { AppSettings.setLayaAccelerator(app, it) }
                        if (intent.hasExtra("allow_low_ram")) AppSettings.setLayaAllowLowRam(app, intent.getBooleanExtra("allow_low_ram", false))
                        intent.getStringExtra("base_url")?.let { AppSettings.setLayaModelBaseUrl(app, it) }
                        LayaEngine.releaseIfLoaded()
                        status(app)
                    }
                    STATUS -> status(app)
                    RELEASE -> { LayaEngine.releaseIfLoaded(); VcLog.i(TAG, "released") }
                    PARITY -> parity(app, intent.getIntExtra("window", 0),
                        intent.getStringExtra("rows")?.split(',')?.map { it.trim() }?.toSet())
                }
            } catch (e: Throwable) {
                VcLog.e(TAG, "$action failed: $e", e)
            }
        }
    }

    private fun status(app: Context) {
        val st = LayaModelFiles.status(app)
        VcLog.i(TAG, "LAYA_STATUS ${SystemOneBackends.statusLine(app, SystemOneBackends.byId("laya")!!)}; " +
            "files ${st.present.size}/${st.specs.size} (${LayaModelFiles.mb(st.totalBytes - st.missingBytes)}) in ${LayaModelFiles.dir(app)}; " +
            "accelerator=${AppSettings.layaAccelerator(app)} allow_low_ram=${AppSettings.layaAllowLowRam(app)} " +
            "loaded=${LayaEngine.peek()?.loadInfo?.line()}")
    }

    private fun parity(app: Context, forceWindow: Int, only: Set<String>?) {
        val engine = LayaEngine.get(app)
        val info = engine.load()
        val tok = engine.tokenizer()
        val root = JSONObject(app.assets.open("laya/parity.json").bufferedReader().use { it.readText() })
        val rows = root.getJSONArray("rows")
        var nRows = 0; var nQ = 0; var idsOk = 0; var argOk = 0; var jsonOk = 0
        var maxDiff = 0.0
        val perWindowMs = HashMap<Int, MutableList<Long>>()
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val name = r.getString("name")
            if (only != null && name !in only) continue
            val json = LayaSequence.stateJson(r.getString("goal"), strings(r.getJSONArray("history")), strings(r.getJSONArray("ui")))
            if (json == r.getString("state_json")) jsonOk++ else VcLog.w(TAG, "$name: state JSON differs")
            val qs = LinkedHashMap<String, LayaQuestion>()
            val qj = r.getJSONObject("questions")
            for (qid in qj.keys()) {
                val q = qj.getJSONObject(qid)
                val keys = strings(q.getJSONArray("keys"))
                val descs = q.getJSONArray("descs")
                qs[qid] = LayaQuestion(q.getString("instructions"),
                    keys.indices.associateTo(LinkedHashMap()) { keys[it] to (if (descs.isNull(it)) null else descs.getString(it)) })
            }
            val plan = LayaSequence.plan(tok::encode, json, qs, engine.maxWindow())
            val window = if (forceWindow > 0) forceWindow else plan.window
            val ref = r.getJSONObject("windows").optJSONObject(window.toString())
            if (ref == null || ref.has("error")) { VcLog.i(TAG, "$name: no reference at window $window, skipped"); continue }
            nRows++
            val stateIds = LayaSequence.encodeState(tok::encode, json)
            val refQs = ref.getJSONObject("questions")
            val diffs = ArrayList<String>()
            for ((qid, q) in qs) {
                val e = LayaSequence.build(tok::encode, stateIds, qid, q, window)
                val rq = refQs.getJSONObject(qid)
                val refIds = ints(rq.getJSONArray("ids"))
                nQ++
                if (e.ids.contentEquals(refIds) && e.markers.contentEquals(ints(rq.getJSONArray("markers")))) idsOk++
                else VcLog.w(TAG, "$name/$qid: ids differ at window $window")
                val t = System.nanoTime()
                val (raw, ran) = engine.runIds(e.ids, e.markers, window)
                perWindowMs.getOrPut(ran) { ArrayList() }.add((System.nanoTime() - t) / 1_000_000)
                val got = LayaDecoder.decode(q.keys, raw)
                val want = rq.getJSONArray("probabilities").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
                val d = got.probabilities.indices.maxOf { abs(got.probabilities[it] - want[it]) }
                maxDiff = maxOf(maxDiff, d)
                val wantBest = want.indices.maxByOrNull { want[it] }!!
                if (wantBest == got.best) argOk++
                diffs += "$qid ${q.keys[got.best]}${if (wantBest == got.best) "" else "!=" + q.keys[wantBest]} " +
                    String.format(java.util.Locale.US, "p=%.4f d=%.5f", got.answerConfidence, d)
            }
            VcLog.i(TAG, "parity $name @${window}: ${diffs.joinToString("; ")}")
        }
        val ms = perWindowMs.entries.sortedBy { it.key }.joinToString { (w, l) -> "s$w median ${l.sorted()[l.size / 2]} ms (n=${l.size})" }
        VcLog.i(TAG, "PARITY ${engine.backendLabel()} rows=$nRows questions=$nQ state_json_ok=$jsonOk ids_ok=$idsOk " +
            "argmax_ok=$argOk/$nQ max_diff=${String.format(java.util.Locale.US, "%.5f", maxDiff)} " +
            "load=${info.loadMs} ms (${info.line()}); per question: $ms")
    }

    private fun strings(a: JSONArray) = List(a.length()) { a.getString(it) }
    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }

    companion object {
        private const val TAG = "Laya"
        private const val P = "com.brainstormity.voicecontrol."
        const val IMPORT = P + "IMPORT_LAYA"
        const val CONFIG = P + "LAYA_CONFIG"
        const val STATUS = P + "LAYA_STATUS"
        const val PARITY = P + "LAYA_PARITY"
        const val RELEASE = P + "LAYA_RELEASE"
        val ACTIONS = listOf(IMPORT, CONFIG, STATUS, PARITY, RELEASE)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Debug-only: registers the runtime LayaDebugReceiver at process start (implicit broadcasts). */
class LayaDebugInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context?.applicationContext ?: return false
        val filter = IntentFilter().apply { LayaDebugReceiver.ACTIONS.forEach(::addAction) }
        ContextCompat.registerReceiver(ctx, LayaDebugReceiver(runtimeRegistered = true), filter, ContextCompat.RECEIVER_EXPORTED)
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}

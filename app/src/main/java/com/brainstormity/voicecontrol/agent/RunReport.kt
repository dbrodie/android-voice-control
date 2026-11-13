package com.brainstormity.voicecontrol.agent

import android.graphics.Bitmap
import com.brainstormity.voicecontrol.core.VcLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One run on disk, like upstream's runs/<id>/: `report.json` (rewritten after every step) plus one JPEG per
 * step with the target drawn on it. Lives in `<filesDir>/runs/<yyyyMMdd-HHmmss>/`; only the last [KEEP] runs
 * are kept. Inspect with:
 *   adb shell run-as com.brainstormity.voicecontrol ls files/runs
 *   adb shell run-as com.brainstormity.voicecontrol cat files/runs/<id>/report.json
 *   adb exec-out run-as com.brainstormity.voicecontrol cat files/runs/<id>/01-CLICK.jpg > 01.jpg
 * A null [dir] (no Context available) keeps everything in memory only.
 */
class RunReport(val dir: File?) {
    /** The report, in upstream's shape. Mutate it, then call [save]. */
    val data: MutableMap<String, Any?> = linkedMapOf()

    fun save() {
        val d = dir ?: return
        try {
            File(d, "report.json").writeText(Json.pretty(data))
        } catch (e: Exception) {
            VcLog.w(TAG, "couldn't write report.json: ${e.message}")
        }
    }

    fun saveImage(name: String, img: Bitmap) {
        val d = dir ?: return
        try {
            File(d, name).outputStream().use { img.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (e: Exception) {
            VcLog.w(TAG, "couldn't save $name: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "Agent"
        const val KEEP = 20

        /** A new run folder under [filesDir]/runs, pruning old runs. */
        fun create(filesDir: File?): RunReport {
            if (filesDir == null) return RunReport(null)
            return try {
                val runs = File(filesDir, "runs").apply { mkdirs() }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                var dir = File(runs, stamp)
                var i = 2
                while (dir.exists()) dir = File(runs, "$stamp-${i++}")
                dir.mkdirs()
                runs.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.dropLast(KEEP)?.forEach { it.deleteRecursively() }
                RunReport(dir)
            } catch (e: Exception) {
                VcLog.w(TAG, "couldn't create a run folder: ${e.message}")
                RunReport(null)
            }
        }
    }
}

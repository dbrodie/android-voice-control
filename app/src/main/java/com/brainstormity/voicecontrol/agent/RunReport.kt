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
 * are kept. Runs the user moved to the Trash (`runs/.trash/<id>/`, see ui/runs/RunStore) don't count towards
 * [KEEP] and are never pruned here; they go away only with "Delete forever". Inspect with:
 *   adb shell run-as com.brainstormity.voicecontrol ls files/runs
 *   adb shell run-as com.brainstormity.voicecontrol cat files/runs/<id>/report.json
 *   adb exec-out run-as com.brainstormity.voicecontrol cat files/runs/<id>/01-CLICK.jpg > 01.jpg
 * A null [dir] (no Context available) keeps everything in memory only.
 *
 * Besides upstream's fields, [save] adds `finished` (local ISO time like `started`, set once when the status
 * leaves "running"); `seconds` (the run's duration so far) is kept up to date by TaskRunner.
 */
class RunReport(val dir: File?) {
    /** The report, in upstream's shape. Mutate it, then call [save]. */
    val data: MutableMap<String, Any?> = linkedMapOf()

    fun save() {
        val d = dir ?: return
        val status = data["status"] as? String
        val ended = status != null && status != "running"
        if (ended && data["finished"] == null) data["finished"] = isoNow()
        try {
            // Write a temp file and rename it over the old one, so a reader (the Runs screen) never sees half a
            // report.
            val text = Json.pretty(data)
            val tmp = File(d, "report.json.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(d, "report.json"))) {
                File(d, "report.json").writeText(text)
                tmp.delete()
            }
        } catch (e: Exception) {
            VcLog.w(TAG, "couldn't write report.json: ${e.message}")
        }
        if (ended && activeDir == d) activeDir = null
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

        /**
         * Folder of the run being written right now; null when no run is in progress. Set by [create], cleared
         * by the [save] that records a final status. The Runs screen uses it to tell a live run from one that
         * was cut off (app killed) while its report still said "running".
         */
        @Volatile
        var activeDir: File? = null
            private set

        /**
         * Folder name (run id) of the most recently created run; unlike [activeDir] it isn't cleared when the run
         * ends. voice/live/LiveTaskBridge compares it before and after `runTask` to learn which run a voice
         * delegation produced.
         */
        @Volatile
        var lastId: String? = null
            private set

        /** "yyyy-MM-dd'T'HH:mm:ss" in local time: the format of `started` (Python's isoformat(timespec="seconds")). */
        fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())

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
                prune(runs)
                activeDir = dir
                lastId = dir.name
                RunReport(dir)
            } catch (e: Exception) {
                VcLog.w(TAG, "couldn't create a run folder: ${e.message}")
                RunReport(null)
            }
        }

        /**
         * Keep only the newest [keep] run folders in [runs]. Hidden folders aren't runs: `.trash` (trashed runs
         * are kept until the user deletes them forever) is never touched.
         */
        fun prune(runs: File, keep: Int = KEEP) {
            runs.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
                ?.sortedBy { it.name }?.dropLast(keep)?.forEach { it.deleteRecursively() }
        }
    }
}

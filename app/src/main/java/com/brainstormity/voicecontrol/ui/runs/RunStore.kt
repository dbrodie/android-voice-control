package com.brainstormity.voicecontrol.ui.runs

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The run reports TaskRunner writes (agent/RunReport): `<filesDir>/runs/<id>/report.json` + `NN-ACTION.jpg`.
 * A port of the data side of upstream's dashboard.py: list runs, read one report, move runs to the Trash
 * (`runs/.trash/<id>/`, like upstream's `runs/.trash`), restore them, delete them forever (only from the
 * Trash). Trashed runs don't count towards RunReport.KEEP.
 *
 * Plain JVM (java.io + org.json), so it's unit-tested on a temp dir. Parsing is tolerant: missing fields get
 * defaults, a report that can't be read (being written, or broken) is skipped in lists and null in [load].
 *
 * [activeId]: the folder name of the run being written right now (RunReport.activeDir), or null. It can't be
 * trashed, and a report that says "running" but isn't active was cut off: shown as "interrupted".
 */
class RunStore(val root: File, private val activeId: () -> String? = { null }) {
    val trashDir: File get() = File(root, TRASH)

    fun folder(trash: Boolean): File = if (trash) trashDir else root

    /** The run's folder, or null if [id] isn't a run id or doesn't exist there. */
    fun dir(id: String, trash: Boolean): File? =
        if (RUN_ID.matches(id)) File(folder(trash), id).takeIf { it.isDirectory } else null

    /** Runs in the list ([trash] = false) or the Trash, newest first. Unreadable reports are skipped. */
    fun list(trash: Boolean = false): List<Run> {
        val active = activeId()
        return (folder(trash).listFiles { f -> f.isDirectory && RUN_ID.matches(f.name) } ?: emptyArray())
            .sortedByDescending { it.name }
            .mapNotNull { d -> read(d, active.takeIf { !trash }) }
    }

    /** One run, or null if it doesn't exist or its report can't be read (yet). */
    fun load(id: String, trash: Boolean): Run? = dir(id, trash)?.let { read(it, activeId().takeIf { !trash }) }

    /** The raw report.json text (to notice changes cheaply), or null. */
    fun reportText(id: String, trash: Boolean): String? =
        dir(id, trash)?.let { File(it, "report.json") }?.takeIf { it.isFile }?.let {
            try { it.readText() } catch (_: Exception) { null }
        }

    fun count(trash: Boolean): Int =
        folder(trash).listFiles { f -> f.isDirectory && RUN_ID.matches(f.name) }?.size ?: 0

    /** Move runs to the Trash. The run in progress stays. Returns how many moved. */
    fun trash(ids: Collection<String>): Int = move(ids, root, trashDir, skip = activeId())

    /** Move runs from the Trash back to the list. Returns how many moved. */
    fun restore(ids: Collection<String>): Int = move(ids, trashDir, root, skip = null)

    /** Permanently delete runs. Only works on runs already in the Trash. Returns how many were deleted. */
    fun deleteForever(ids: Collection<String>): Int {
        var deleted = 0
        for (id in ids) {
            val d = dir(id, trash = true) ?: continue
            if (d.deleteRecursively()) deleted++
        }
        return deleted
    }

    private fun move(ids: Collection<String>, from: File, to: File, skip: String?): Int {
        to.mkdirs()
        var moved = 0
        for (id in ids) {
            if (!RUN_ID.matches(id) || id == skip) continue
            val src = File(from, id)
            if (!src.isDirectory) continue
            val dst = File(to, id)
            if (dst.exists()) dst.deleteRecursively()
            if (src.renameTo(dst)) moved++
        }
        return moved
    }

    private fun read(d: File, active: String?): Run? {
        val f = File(d, "report.json")
        if (!f.isFile) return null
        val text = try { f.readText() } catch (_: Exception) { return null }
        return parse(d.name, text, active == d.name)
    }

    companion object {
        const val TRASH = ".trash"
        /** RunReport's folder names: yyyyMMdd-HHmmss, plus -2, -3... when two runs start in the same second. */
        val RUN_ID = Regex("""^\d{8}-\d{6}(-\d+)?$""")
        /** JEV's price, for reports saved without `cost_usd` (as upstream's dashboard does). */
        const val PRICE_PER_INPUT_TOKEN = 0.042 / 1e6

        /**
         * Parses a report.json. [isActive]: this run is being written right now. Returns null only if [text]
         * isn't a JSON object at all; every field is optional.
         */
        fun parse(id: String, text: String, isActive: Boolean = false): Run? {
            val o = try { JSONObject(text) } catch (_: Exception) { return null }
            val steps = o.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parseStep) }
            } ?: emptyList()
            val input = o.optLong("input_tokens", steps.sumOf { it.inputTokens })
            val output = o.optLong("output_tokens", steps.sumOf { it.outputTokens })
            val status = o.str("status").ifEmpty { "running" }
            return Run(
                id = id,
                task = o.str("task"),
                status = status,
                displayStatus = if (status == "running" && !isActive) "interrupted" else status,
                isActive = isActive && status == "running",
                message = o.str("message"),
                warning = o.str("warning").ifBlank { null },
                started = o.str("started").ifEmpty { startedFromId(id) },
                finished = o.str("finished").ifEmpty { null },
                seconds = o.optDoubleOrNull("seconds"),
                device = o.str("device"),
                deviceName = o.str("device_name").ifEmpty { null },
                opened = o.str("opened").ifEmpty { null },
                canType = o.optJSONArray("can_type").strings(),
                websites = o.optJSONArray("websites").strings(),
                inputTokens = input,
                outputTokens = output,
                costUsd = o.optDoubleOrNull("cost_usd") ?: (input * PRICE_PER_INPUT_TOKEN),
                steps = steps,
            )
        }

        private fun parseStep(s: JSONObject): Step {
            val tokens = s.optJSONObject("tokens")
            val input = tokens?.optLong("input_tokens", 0) ?: 0
            val output = tokens?.optLong("output_tokens", 0) ?: 0
            val options = s.optJSONObject("options")?.let { opts ->
                opts.keys().asSequence().mapNotNull { k -> opts.optDoubleOrNull(k)?.let { k to it } }
                    .sortedByDescending { it.second }.toList()
            } ?: emptyList()
            val box = s.optJSONObject("target_box")?.let {
                TargetBox(it.optDouble("left", 0.0), it.optDouble("top", 0.0),
                    it.optDouble("right", 0.0), it.optDouble("bottom", 0.0))
            }
            val wait = s.optJSONObject("wait")
            val whyFull = s.str("why")
            val zoom = ZOOM_NOTE.find(whyFull)?.value
            return Step(
                n = s.optInt("n", 0),
                action = s.optString("action", "?"),
                detail = s.str("detail"),
                why = (if (zoom != null) whyFull.replace(zoom, "") else whyFull).trim().replace(Regex("\\s{2,}"), " "),
                zoomNote = zoom,
                confidence = s.optDoubleOrNull("confidence"),
                options = options,
                targetBox = box,
                targetConfidence = s.optDoubleOrNull("target_confidence"),
                image = s.str("image").ifEmpty { null },
                jevSeconds = s.optDoubleOrNull("jev_seconds"),
                inputTokens = input,
                outputTokens = output,
                costUsd = s.optDoubleOrNull("cost_usd") ?: (input * PRICE_PER_INPUT_TOKEN),
                result = s.str("result"),
                waited = wait?.optDoubleOrNull("waited"),
                waitTimedOut = wait?.optBoolean("timed_out", false) ?: false,
                lateBy = wait?.optDoubleOrNull("late_by"),
                reacted = wait?.let { if (it.has("reacted")) it.optBoolean("reacted") else null },
                seconds = s.optDoubleOrNull("seconds"),
            )
        }

        /** TaskRunner appends this to `why` when it zoomed in to find the tap point. */
        private val ZOOM_NOTE = Regex("""Zoomed in to find exactly where to tap \([^)]*\)\.?""")

        /** "20261113-105502" -> "2026-11-13T10:55:02" (for a report without `started`). */
        fun startedFromId(id: String): String {
            val m = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})""").find(id) ?: return ""
            val (y, mo, d, h, mi, s) = m.destructured
            return "$y-$mo-${d}T$h:$mi:$s"
        }

        fun totals(runs: List<Run>): Totals {
            val ended = runs.filter { it.displayStatus != "running" }
            return Totals(
                runs = runs.size,
                costUsd = runs.sumOf { it.costUsd },
                tokens = runs.sumOf { it.inputTokens + it.outputTokens },
                ended = ended.size,
                done = ended.count { it.status == "done" },
            )
        }

        /** The value as text; "" if missing or null (optString would turn JSON null into "null"). */
        private fun JSONObject.str(key: String): String = if (!has(key) || isNull(key)) "" else opt(key)?.toString() ?: ""

        private fun JSONObject.optDoubleOrNull(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val d = optDouble(key)
            return if (d.isNaN()) null else d
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { i -> opt(i)?.toString() }
    }
}

data class Run(
    val id: String,
    val task: String,
    /** As saved: running | done | unsure | stopped | gave_up | error (| dry_run | failed from upstream). */
    val status: String,
    /** [status], except "interrupted" for a report that still says running but whose run isn't in progress. */
    val displayStatus: String,
    /** This run is being written right now. */
    val isActive: Boolean,
    val message: String,
    val warning: String?,
    /** Local time "yyyy-MM-ddTHH:mm:ss". */
    val started: String,
    val finished: String?,
    /** Duration in seconds (so far, while running). */
    val seconds: Double?,
    val device: String,
    val deviceName: String?,
    val opened: String?,
    val canType: List<String>,
    val websites: List<String>,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    val steps: List<Step>,
) {
    val tokens: Long get() = inputTokens + outputTokens
}

data class Step(
    val n: Int,
    val action: String,
    val detail: String,
    /** What the action means, plus any note (CHAIN split), without [zoomNote]. */
    val why: String,
    /** "Zoomed in to find exactly where to tap (location confidence 40% → 85%)." when it zoomed in. */
    val zoomNote: String?,
    val confidence: Double?,
    /** Every action JEV considered, most likely first. */
    val options: List<Pair<String, Double>>,
    val targetBox: TargetBox?,
    val targetConfidence: Double?,
    val image: String?,
    val jevSeconds: Double?,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    /** "" while the step is still being carried out. */
    val result: String,
    val waited: Double?,
    val waitTimedOut: Boolean,
    val lateBy: Double?,
    val reacted: Boolean?,
    val seconds: Double?,
)

data class TargetBox(val left: Double, val top: Double, val right: Double, val bottom: Double)

data class Totals(val runs: Int, val costUsd: Double, val tokens: Long, val ended: Int, val done: Int) {
    /** Share of finished runs that ended "done"; null when none has finished. */
    val successRate: Double? get() = if (ended == 0) null else done.toDouble() / ended
}

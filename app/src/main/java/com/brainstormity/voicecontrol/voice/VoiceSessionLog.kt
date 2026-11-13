package com.brainstormity.voicecontrol.voice

import com.brainstormity.voicecontrol.voice.live.LiveEvent
import com.brainstormity.voicecontrol.voice.live.LiveTaskBridge
import com.brainstormity.voicecontrol.voice.live.LiveUsage
import com.brainstormity.voicecontrol.voice.live.Role
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GPT-Live voice pricing. The Runs & costs screen shows the API price for every session, including ChatGPT-plan
 * sessions (there it's the *equivalent* cost: the plan covers it).
 *
 * Source (checked 2026-11-13):
 * - https://developers.openai.com/api/docs/pricing (Audio, "GPT-Live": gpt-live-1, $0.05 price per minute;
 *   "voice sessions are billed per second, without rounding up to a whole minute. Backend model and tool usage is
 *   charged separately.")
 * - https://developers.openai.com/api/docs/models/gpt-live-1 ("Voice sessions cost $0.05 per minute, billed per
 *   second.")
 * - https://developers.openai.com/api/docs/guides/voice-latency-cost?api=live: what is billed is *session time*,
 *   not audio: "Active session time includes time when the user speaks, the assistant speaks, both are silent, or
 *   the backend is working."
 * Our backend is the phone agent (JEV), whose cost is in the run reports, so there's no OpenAI backend usage.
 */
object VoicePricing {
    /** USD per minute of GPT-Live session time (gpt-live-1), billed per second. */
    const val USD_PER_SESSION_MINUTE = 0.05

    /** Cost of [billedMs] of session time at [usdPerMinute]. */
    fun costUsd(billedMs: Long, usdPerMinute: Double): Double = billedMs / 60_000.0 * usdPerMinute

    /**
     * The session time to charge. A final server figure (`session.closed.usage`, sent by both routes on a clean
     * close) is used as is. Without one (the call died on our side, e.g. audio failure) the last snapshot can lag:
     * the ChatGPT route sends `session.usage.updated` only about every 15 s, so the larger of that snapshot and the
     * connected wall-clock time is used. No server figure and never connected = 0.
     */
    fun billedMs(serverMs: Long?, serverFinal: Boolean, connectedMs: Long?): Long = when {
        serverMs != null && serverFinal -> serverMs
        else -> maxOf(serverMs ?: 0L, connectedMs ?: 0L)
    }

    /** Where [billedMs] came from: "server" | "clock" | "none". */
    fun billedFrom(serverMs: Long?, serverFinal: Boolean, connectedMs: Long?): String = when {
        serverMs != null && (serverFinal || serverMs >= (connectedMs ?: 0L)) -> "server"
        connectedMs != null -> "clock"
        else -> "none"
    }
}

/** One delegated request in a voice session: what was asked, the run it produced (RunReport folder name), outcome. */
data class VoiceTask(val text: String, val runId: String?, val outcome: String)

/** A voice session record as read back from `files/voice/<id>.json`. See [VoiceSessionLog] for the format. */
data class VoiceSession(
    val id: String,
    /** Local time "yyyy-MM-ddTHH:mm:ss". */
    val started: String,
    val finished: String?,
    /** Wall-clock length of the session (so far, while it's open). */
    val seconds: Double,
    /** Time from "call up" to the end; null if the call never came up. */
    val connectedSeconds: Double?,
    /** chatgpt | openai_key (AppSettings backend ids). */
    val backend: String,
    val model: String?,
    val callId: String?,
    /** Last cumulative usage the server reported (ms of session time), null if it never reported any. */
    val audioDurationMs: Long?,
    /** Which field it came from: `audio_duration_ms` (ChatGPT route) or `seconds` (official API). */
    val usageSource: String?,
    /** [audioDurationMs] is the final figure from `session.closed` (official API), not a running snapshot. */
    val usageFinal: Boolean,
    val usageLimitStatus: String?,
    val usageLimitResetSeconds: Long?,
    val hasUsageLimit: Boolean,
    /** The price used for this record (kept in the record so old records stay right if the price changes). */
    val usdPerMinute: Double,
    val userTurns: Int,
    val assistantTurns: Int,
    val tasks: List<VoiceTask>,
    val endReason: String?,
    val error: String?,
    /** This session is being recorded right now. */
    val isActive: Boolean,
) {
    private val connectedMs: Long? get() = connectedSeconds?.let { Math.round(it * 1000) }
    /** Billed session time, see [VoicePricing.billedMs]. */
    val billedMs: Long get() = VoicePricing.billedMs(audioDurationMs, usageFinal, connectedMs)
    /** "server" (the server's figure) | "clock" (estimated from the connected time) | "none". */
    val billedFrom: String get() = VoicePricing.billedFrom(audioDurationMs, usageFinal, connectedMs)
    val billedFromServer: Boolean get() = billedFrom == "server"
    val costUsd: Double get() = VoicePricing.costUsd(billedMs, usdPerMinute)
    /** ChatGPT plan: [costUsd] is the equivalent at API pricing, included in the plan (not charged). */
    val isPlan: Boolean get() = backend == "chatgpt"
    /** active | error | ended | interrupted (the app died while it was open). */
    val status: String get() = when {
        error != null -> "error"
        finished == null -> if (isActive) "active" else "interrupted"
        else -> "ended"
    }
}

data class VoiceTotals(val sessions: Int, val billedMs: Long, val costUsd: Double) {
    val minutes: Double get() = billedMs / 60_000.0
}

/**
 * Voice sessions on disk: one JSON record per GPT-Live call in `<filesDir>/voice/<yyyyMMdd-HHmmss>.json` (same id
 * format as run folders; `-2`, `-3`... for two in the same second). Only the newest [KEEP] are kept. Written
 * atomically (temp file + rename) by [VoiceSessionRecorder], which rewrites the record every few seconds while the
 * call is open, so a crash still leaves the data up to then. Plain JVM (java.io + org.json): unit-tested.
 *
 * Record format (version 1):
 * ```
 * {"version":1, "id":"20261113-144533", "started":"2026-11-13T14:45:33", "finished":"2026-11-13T14:45:53"|null,
 *  "seconds":20.3, "connected_seconds":19.1|null, "backend":"chatgpt"|"openai_key", "model":"gpt-live-1-codex",
 *  "call_id":"rtc_…", "audio_duration_ms":13200|null, "usage_source":"audio_duration_ms"|"seconds"|null,
 *  "usage_final":false, "usage_updates":2, "usage_limit":{"status":null,"reset_seconds":null} (only if the server
 *  sent one), "billed_seconds":19.1, "billed_from":"server"|"clock"|"none", "rate_usd_per_minute":0.05,
 *  "cost_usd":0.015917, "cost_kind":"equivalent"|"billed", "user_turns":1, "assistant_turns":2,
 *  "tasks":[{"text":"Open Settings…","run_id":"20261113-144540"|null,"outcome":"done"}],
 *  "end_reason":"client_request"|null, "error":null|"Invalid OpenAI API key (401)…"}
 * ```
 * `audio_duration_ms` is the server's last cumulative usage (`usage.audio_duration_ms` on the ChatGPT route,
 * `usage.seconds` × 1000 on the official API); `outcome` is empty | stop | busy | running | the run's final status.
 * Readers recompute the cost with [VoicePricing.billedMs] (`audio_duration_ms`, `usage_final`,
 * `connected_seconds`) × `rate_usd_per_minute`; `billed_seconds`/`cost_usd` are there for people reading the file.
 */
class VoiceSessionLog(val root: File, private val activeId: () -> String? = { active }) {

    /** Sessions, newest first. Unreadable records are skipped. */
    fun list(): List<VoiceSession> {
        val a = activeId()
        return (root.listFiles { f -> f.isFile && f.name.endsWith(".json") && ID.matches(f.name.removeSuffix(".json")) }
            ?: emptyArray())
            .sortedByDescending { it.name.removeSuffix(".json") }
            .mapNotNull { f ->
                val id = f.name.removeSuffix(".json")
                val text = try { f.readText() } catch (_: Exception) { return@mapNotNull null }
                parse(id, text, id == a)
            }
    }

    fun load(id: String): VoiceSession? {
        if (!ID.matches(id)) return null
        val f = File(root, "$id.json").takeIf { it.isFile } ?: return null
        val text = try { f.readText() } catch (_: Exception) { return null }
        return parse(id, text, id == activeId())
    }

    /** A fresh id for a session starting at [atMs] (no file is created yet). */
    fun newId(atMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(atMs))
        var id = stamp
        var i = 2
        while (File(root, "$id.json").exists() || id in reserved) id = "$stamp-${i++}"
        reserved += id
        return id
    }

    private val reserved = mutableSetOf<String>()

    /** Atomically writes [record] as `<id>.json`. Returns false on an I/O error. */
    fun write(id: String, record: JSONObject): Boolean = try {
        root.mkdirs()
        val text = record.toString(1)
        val tmp = File(root, "$id.json.tmp")
        tmp.writeText(text)
        val dst = File(root, "$id.json")
        if (!tmp.renameTo(dst)) {
            dst.writeText(text)
            tmp.delete()
        }
        true
    } catch (_: Exception) {
        false
    }

    /** Keeps only the newest [keep] records (plain delete). */
    fun prune(keep: Int = KEEP) {
        root.listFiles { f -> f.isFile && f.name.endsWith(".json") && ID.matches(f.name.removeSuffix(".json")) }
            ?.sortedBy { it.name.removeSuffix(".json") }?.dropLast(keep)?.forEach { it.delete() }
    }

    companion object {
        const val KEEP = 100
        const val VERSION = 1
        /** Same id format as RunReport's run folders. */
        val ID = Regex("""^\d{8}-\d{6}(-\d+)?$""")

        /** Id of the session being recorded right now (set by [VoiceSessionRecorder]), else null. */
        @Volatile
        var active: String? = null
            internal set

        fun forFilesDir(filesDir: File): VoiceSessionLog = VoiceSessionLog(File(filesDir, "voice"))

        /** Parses a record; null only if [text] isn't a JSON object. */
        fun parse(id: String, text: String, isActive: Boolean = false): VoiceSession? {
            val o = try { JSONObject(text) } catch (_: Exception) { return null }
            val limit = o.optJSONObject("usage_limit")
            val tasks = o.optJSONArray("tasks")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { t -> VoiceTask(t.str("text"), t.str("run_id").ifEmpty { null }, t.str("outcome")) }
                }
            } ?: emptyList()
            return VoiceSession(
                id = id,
                started = o.str("started").ifEmpty { startedFromId(id) },
                finished = o.str("finished").ifEmpty { null },
                seconds = o.optDoubleOrNull("seconds") ?: 0.0,
                connectedSeconds = o.optDoubleOrNull("connected_seconds"),
                backend = o.str("backend"),
                model = o.str("model").ifEmpty { null },
                callId = o.str("call_id").ifEmpty { null },
                audioDurationMs = if (o.has("audio_duration_ms") && !o.isNull("audio_duration_ms")) o.optLong("audio_duration_ms") else null,
                usageSource = o.str("usage_source").ifEmpty { null },
                usageFinal = o.optBoolean("usage_final", false),
                usageLimitStatus = limit?.str("status")?.ifEmpty { null },
                usageLimitResetSeconds = limit?.let { if (it.has("reset_seconds") && !it.isNull("reset_seconds")) it.optLong("reset_seconds") else null },
                hasUsageLimit = limit != null,
                usdPerMinute = o.optDoubleOrNull("rate_usd_per_minute") ?: VoicePricing.USD_PER_SESSION_MINUTE,
                userTurns = o.optInt("user_turns", 0),
                assistantTurns = o.optInt("assistant_turns", 0),
                tasks = tasks,
                endReason = o.str("end_reason").ifEmpty { null },
                error = o.str("error").ifEmpty { null },
                isActive = isActive && o.str("finished").isEmpty(),
            )
        }

        fun totals(sessions: List<VoiceSession>): VoiceTotals = VoiceTotals(
            sessions = sessions.size,
            billedMs = sessions.sumOf { it.billedMs },
            costUsd = sessions.sumOf { it.costUsd },
        )

        /** The session (from [sessions]) whose delegation produced run [runId], if any. */
        fun sessionForRun(sessions: List<VoiceSession>, runId: String): VoiceSession? =
            sessions.firstOrNull { s -> s.tasks.any { it.runId == runId } }

        /** "20261113-144533" -> "2026-11-13T14:45:33". */
        fun startedFromId(id: String): String {
            val m = Regex("""^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})""").find(id) ?: return ""
            val (y, mo, d, h, mi, s) = m.destructured
            return "$y-$mo-${d}T$h:$mi:$s"
        }

        fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(ms))

        private fun JSONObject.str(key: String): String = if (!has(key) || isNull(key)) "" else opt(key)?.toString() ?: ""

        private fun JSONObject.optDoubleOrNull(key: String): Double? {
            if (!has(key) || isNull(key)) return null
            val d = optDouble(key)
            return if (d.isNaN()) null else d
        }
    }
}

/**
 * Records one voice session into a [VoiceSessionLog] (null log = memory only). VoiceController creates one per call
 * and feeds it every [LiveEvent], turn ends, the bridge's delegations, and failures; it calls [tick] every few
 * seconds while the call is open. All calls come from VoiceController's single event thread.
 */
class VoiceSessionRecorder(
    private val log: VoiceSessionLog?,
    val backend: String,
    val model: String?,
    private val now: () -> Long = System::currentTimeMillis,
    val usdPerMinute: Double = VoicePricing.USD_PER_SESSION_MINUTE,
) {
    private val startedAt = now()
    val id: String = log?.newId(startedAt) ?: VoiceSessionLog.iso(startedAt)
    private var callId: String? = null
    private var connectedAt: Long? = null
    private var finishedAt: Long? = null
    private var usageMs: Long? = null
    private var usageSource: String? = null
    private var usageFinal = false
    private var usageUpdates = 0
    private var hasLimit = false
    private var limitStatus: String? = null
    private var limitReset: Long? = null
    var userTurns = 0
        private set
    var assistantTurns = 0
        private set
    private var tasks: List<LiveTaskBridge.Delegated> = emptyList()
    private var endReason: String? = null
    private var error: String? = null

    val isFinished: Boolean get() = finishedAt != null

    init {
        log?.prune(VoiceSessionLog.KEEP - 1)
        VoiceSessionLog.active = id
        save()
    }

    fun onEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.Usage -> {
                usage(event.usage, event.final)
                save()
            }
            is LiveEvent.Closed -> {
                event.usage?.let { usage(it, final = true) }
                finish(event.reason ?: "closed")
            }
            else -> {}
        }
    }

    private fun usage(u: LiveUsage, final: Boolean) {
        usageUpdates++
        if (u.durationMs != null && (final || !usageFinal)) {
            usageMs = u.durationMs
            usageSource = u.source
            if (final) usageFinal = true
        }
        if (u.hasLimit) {
            hasLimit = true
            limitStatus = u.limitStatus
            limitReset = u.limitResetSeconds
        }
    }

    fun connected(callId: String?) {
        if (connectedAt == null) connectedAt = now()
        this.callId = callId ?: this.callId
        save()
    }

    fun turn(role: Role) {
        if (role == Role.USER) userTurns++ else assistantTurns++
        save()
    }

    fun tasks(list: List<LiveTaskBridge.Delegated>) {
        tasks = list
        save()
    }

    /** The call failed: keeps [message] as the error and closes the record (later usage still updates it). */
    fun fail(message: String) {
        if (error == null) error = message
        finish("error")
    }

    /** Ends the record once (the first reason wins); later calls only re-save. */
    fun finish(reason: String) {
        if (endReason == null) endReason = reason
        if (finishedAt == null) finishedAt = now()
        if (VoiceSessionLog.active == id) VoiceSessionLog.active = null
        save()
    }

    /** Periodic save while the call is open (keeps `seconds` current for a crash). */
    fun tick() {
        if (!isFinished) save()
    }

    fun toJson(): JSONObject {
        val end = finishedAt ?: now()
        val connectedMs = connectedAt?.let { (end - it).coerceAtLeast(0) }
        val connectedSeconds = connectedMs?.let { it / 1000.0 }
        val billedMs = VoicePricing.billedMs(usageMs, usageFinal, connectedMs)
        val o = JSONObject()
            .put("version", VoiceSessionLog.VERSION)
            .put("id", id)
            .put("started", VoiceSessionLog.iso(startedAt))
            .put("finished", finishedAt?.let(VoiceSessionLog::iso) ?: JSONObject.NULL)
            .put("seconds", (end - startedAt).coerceAtLeast(0) / 1000.0)
            .put("connected_seconds", connectedSeconds ?: JSONObject.NULL)
            .put("backend", backend)
            .put("model", model ?: JSONObject.NULL)
            .put("call_id", callId ?: JSONObject.NULL)
            .put("audio_duration_ms", usageMs ?: JSONObject.NULL)
            .put("usage_source", usageSource ?: JSONObject.NULL)
            .put("usage_final", usageFinal)
            .put("usage_updates", usageUpdates)
        if (hasLimit) {
            o.put("usage_limit", JSONObject()
                .put("status", limitStatus ?: JSONObject.NULL)
                .put("reset_seconds", limitReset ?: JSONObject.NULL))
        }
        val cost = VoicePricing.costUsd(billedMs, usdPerMinute)
        o.put("billed_seconds", billedMs / 1000.0)
            .put("billed_from", VoicePricing.billedFrom(usageMs, usageFinal, connectedMs))
            .put("rate_usd_per_minute", usdPerMinute)
            .put("cost_usd", Math.round(cost * 1e6) / 1e6)
            .put("cost_kind", if (backend == "chatgpt") "equivalent" else "billed")
            .put("user_turns", userTurns)
            .put("assistant_turns", assistantTurns)
            .put("tasks", JSONArray().apply {
                for (t in tasks) put(JSONObject()
                    .put("text", t.text)
                    .put("run_id", t.runId ?: JSONObject.NULL)
                    .put("outcome", t.outcome))
            })
            .put("end_reason", endReason ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
        return o
    }

    fun save() {
        log?.write(id, toJson())
    }
}

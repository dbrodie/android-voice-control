package com.brainstormity.voicecontrol.ui.runs

import com.brainstormity.voicecontrol.voice.VoiceSession
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Text for the Runs screens. Plain JVM so it's unit-tested. */
object RunFormat {
    /** Upstream's labels, except "Not sure" for unsure (what the overlay pill says) and "Error" for error. */
    fun label(status: String): String = when (status) {
        "running" -> "Running"
        "done" -> "Done"
        "unsure" -> "Not sure"
        "stopped" -> "Stopped"
        "gave_up" -> "Gave up"
        "error", "failed" -> "Error"
        "dry_run" -> "Dry run"
        "interrupted" -> "Interrupted"
        else -> status.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** Which colour family a status chip gets (upstream: running accent, done ok, failed bad, the rest warn). */
    enum class Tone { ACCENT, OK, BAD, WARN, MUTED }

    fun tone(status: String): Tone = when (status) {
        "running" -> Tone.ACCENT
        "done" -> Tone.OK
        "error", "failed" -> Tone.BAD
        "stopped", "gave_up", "unsure", "interrupted" -> Tone.WARN
        else -> Tone.MUTED
    }

    /** USD with 5 decimals, like the agent's log ("$0.00035"). */
    fun usd(x: Double): String = "$" + String.format(Locale.US, "%.5f", x)

    fun pct(x: Double?): String = if (x == null) "–" else String.format(Locale.US, "%.0f%%", x * 100)

    fun tokens(n: Long): String = String.format(Locale.US, "%,d", n)

    /** 19.9 -> "20s", 75 -> "1m 15s". */
    fun duration(seconds: Double?): String {
        if (seconds == null) return "–"
        val s = Math.round(seconds)
        return if (s < 60) "${s}s" else "${s / 60}m ${String.format(Locale.US, "%02d", s % 60)}s"
    }

    fun seconds(x: Double?): String = if (x == null) "–" else String.format(Locale.US, "%.1fs", x)

    private fun parseIso(iso: String): Date? = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { isLenient = false }.parse(iso)
    } catch (_: Exception) { null }

    /** "Today 10:55", "Yesterday 09:01", "13 Nov 10:55", or "13 Nov 2025 10:55" for another year. */
    fun whenShort(iso: String, now: Date = Date()): String {
        val d = parseIso(iso) ?: return iso.replace('T', ' ')
        val c = Calendar.getInstance().apply { time = d }
        val today = Calendar.getInstance().apply { time = now }
        val time = SimpleDateFormat("HH:mm", Locale.US).format(d)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun sameDay(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            sameDay(c, today) -> "Today $time"
            sameDay(c, yesterday) -> "Yesterday $time"
            c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> SimpleDateFormat("d MMM HH:mm", Locale.US).format(d)
            else -> SimpleDateFormat("d MMM yyyy HH:mm", Locale.US).format(d)
        }
    }

    /** "2026-11-13T10:55:02" -> "13 Nov 2026, 10:55:02". */
    fun whenLong(iso: String): String {
        val d = parseIso(iso) ?: return iso.replace('T', ' ')
        return SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.US).format(d)
    }

    /** One list row's metrics line: "2 steps · 20s · $0.00035 · 8,886 tokens". */
    fun rowMetrics(r: Run): String = listOf(
        "${r.steps.size} step${if (r.steps.size == 1) "" else "s"}",
        duration(r.seconds),
        usd(r.costUsd),
        "${tokens(r.tokens)} tokens",
    ).joinToString(" · ")

    /** The line under a step: what happened, timings. Mirrors upstream's step footer. */
    fun stepTiming(s: Step): String {
        val parts = mutableListOf<String>()
        s.jevSeconds?.let { parts += "JEV took ${seconds(it)}" }
        s.waited?.let {
            parts += "waited ${seconds(it)} for the screen" +
                (if (s.lateBy != null) " (it reacted ${seconds(s.lateBy)} late)" else "") +
                (if (s.waitTimedOut) " (gave up: something kept moving)" else "")
        }
        s.seconds?.let { parts += "step took ${seconds(it)}" }
        return parts.joinToString(" · ")
    }

    fun stepCost(s: Step): String =
        "${tokens(s.inputTokens)} in / ${tokens(s.outputTokens)} out tokens · ${usd(s.costUsd)}"

    // ------------------------------------------------------------------ voice (GPT-Live) sessions

    /** Voice money: 3 decimals ("$0.011"); "<$0.001" for a tiny non-zero amount. */
    fun usdVoice(x: Double): String =
        if (x > 0 && x < 0.0005) "<$0.001" else "$" + String.format(Locale.US, "%.3f", x)

    /** Billed voice time in minutes: "0.2 min", "12.5 min". */
    fun minutes(ms: Long): String = String.format(Locale.US, "%.1f", ms / 60_000.0)

    /** "ChatGPT plan · equiv. $0.011" or "OpenAI API · $0.011". */
    fun voiceCost(s: VoiceSession): String = when (s.backend) {
        "chatgpt" -> "ChatGPT plan · equiv. ${usdVoice(s.costUsd)}"
        "openai_key" -> "OpenAI API · ${usdVoice(s.costUsd)}"
        else -> "${s.backend.ifEmpty { "Voice" }} · ${usdVoice(s.costUsd)}"
    }

    fun voiceStatusLabel(status: String): String = when (status) {
        "active" -> "Live"
        "ended" -> "Ended"
        "error" -> "Error"
        "interrupted" -> "Interrupted"
        else -> label(status)
    }

    fun voiceTone(status: String): Tone = when (status) {
        "active" -> Tone.ACCENT
        "ended" -> Tone.MUTED
        "error" -> Tone.BAD
        else -> Tone.WARN
    }

    /** "20s · 0.2 min billed · 1 you / 2 GPT turns · 1 task". */
    fun voiceMetrics(s: VoiceSession): String = listOf(
        duration(s.seconds),
        "${minutes(s.billedMs)} min billed" + if (!s.billedFromServer && s.billedMs > 0) " (est.)" else "",
        "${s.userTurns} you / ${s.assistantTurns} GPT turn${if (s.assistantTurns == 1) "" else "s"}",
        "${s.tasks.size} task${if (s.tasks.size == 1) "" else "s"}",
    ).joinToString(" · ")

    /** What happened to a delegated request that didn't produce a run (or whose run is gone). */
    fun taskOutcome(outcome: String): String = when (outcome) {
        "busy" -> "not started: another task was running"
        "stop" -> "stop request"
        "empty" -> "empty request"
        "running" -> "still running when the call ended"
        else -> label(outcome)
    }

    /** Run detail header: "Triggered by voice session Today 14:45 · ChatGPT plan · equiv. $0.011". */
    fun triggeredBy(s: VoiceSession, now: Date = Date()): String =
        "Triggered by voice session ${whenShort(s.started, now)} · ${voiceCost(s)} for the whole call (not split per task) ›"
}

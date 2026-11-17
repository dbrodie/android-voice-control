package com.brainstormity.voicecontrol.ui.runs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.RunReport
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.voice.VoiceController
import com.brainstormity.voicecontrol.voice.VoicePricing
import com.brainstormity.voicecontrol.voice.VoiceSession
import com.brainstormity.voicecontrol.voice.VoiceSessionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * The native port of upstream's dashboard list (dashboard.html's sidebar): every run the agent made, newest
 * first, with status, when, steps, duration, cost and JEV tokens; totals on top; the task running right now
 * (from [TaskController.state]) as a live card with a Stop button; a Trash tab with select all / Restore /
 * Delete forever. Tap a run for its step-by-step report ([RunDetailActivity]).
 *
 * T12 adds GPT-Live voice costs (voice/VoiceSessionLog): a second row in the totals card (voice calls, billed
 * minutes, voice cost at API pricing, all-in = JEV + voice) and a Voice tab listing the sessions with the tasks
 * they delegated (tap one to open its run). ChatGPT-plan sessions show the *equivalent* API price.
 *
 * Refreshes on resume, when a task finishes, and every 1.5 s while a task or a voice call runs.
 */
class RunsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedScope: CoroutineScope? = null

    private lateinit var store: RunStore
    private lateinit var voiceLog: VoiceSessionLog
    private var inTrash = false
    private var inVoice = false
    private var runs: List<Run> = emptyList()
    private var sessions: List<VoiceSession> = emptyList()
    private val checked = linkedSetOf<String>()  // runs ticked in the Trash tab
    private var lastShown: String = ""
    /** Voice session to scroll to once (from a run's "Triggered by voice session" link). */
    private var focusSession: String? = null

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var note: TextView
    private lateinit var liveCard: View
    private lateinit var totalsCard: View
    private lateinit var totalsNote: TextView
    private lateinit var trashBar: View
    private lateinit var selectAll: CheckBox
    private lateinit var restore: Button
    private lateinit var purge: Button
    private lateinit var tabRuns: TextView
    private lateinit var tabVoice: TextView
    private lateinit var tabTrash: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runs)
        RunUi.edgeToEdge(this, findViewById(R.id.runsRoot))
        store = storeFor(this)
        voiceLog = VoiceSessionLog.forFilesDir(filesDir)
        val tab = savedInstanceState?.getString(KEY_TAB)
            ?: intent.getStringExtra(EXTRA_TAB)
            ?: if (intent.getBooleanExtra(EXTRA_TRASH, false)) TAB_TRASH else TAB_RUNS
        inTrash = tab == TAB_TRASH
        inVoice = tab == TAB_VOICE
        focusSession = if (savedInstanceState == null) intent.getStringExtra(EXTRA_SESSION) else null

        list = findViewById(R.id.runsList)
        empty = findViewById(R.id.emptyText)
        note = findViewById(R.id.listNote)
        liveCard = findViewById(R.id.liveCard)
        totalsCard = findViewById(R.id.totalsCard)
        totalsNote = findViewById(R.id.totalsNote)
        trashBar = findViewById(R.id.trashBar)
        selectAll = findViewById(R.id.selectAll)
        restore = findViewById(R.id.restoreButton)
        purge = findViewById(R.id.purgeButton)
        tabRuns = findViewById(R.id.tabRuns)
        tabVoice = findViewById(R.id.tabVoice)
        tabTrash = findViewById(R.id.tabTrash)

        findViewById<ImageButton>(R.id.runsBack).setOnClickListener { finish() }
        tabRuns.setOnClickListener { switchTab(TAB_RUNS) }
        tabVoice.setOnClickListener { switchTab(TAB_VOICE) }
        tabTrash.setOnClickListener { switchTab(TAB_TRASH) }
        findViewById<Button>(R.id.liveStop).setOnClickListener {
            VcLog.i(TAG, "Stop pressed on the Runs screen")
            TaskController.stop()
            it.isEnabled = false
        }
        liveCard.setOnClickListener {
            RunReport.activeDir?.name?.let { id -> RunDetailActivity.start(this, id, trash = false) }
        }
        selectAll.setOnClickListener {
            if (selectAll.isChecked) checked.addAll(runs.map { it.id }) else checked.clear()
            render()
        }
        restore.setOnClickListener { act { store.restore(checked.toList()) } }
        purge.setOnClickListener { confirmPurge() }
        stat(R.id.totalRuns, R.string.runs_stat_runs)
        stat(R.id.totalCost, R.string.runs_stat_cost)
        stat(R.id.totalTokens, R.string.runs_stat_tokens)
        stat(R.id.totalSuccess, R.string.runs_stat_success)
        stat(R.id.totalVoiceSessions, R.string.runs_stat_voice_sessions)
        stat(R.id.totalVoiceMinutes, R.string.runs_stat_voice_minutes)
        stat(R.id.totalVoiceCost, R.string.runs_stat_voice_cost)
        stat(R.id.totalAllIn, R.string.runs_stat_all_in)
        styleTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TAB, currentTab())
    }

    override fun onStart() {
        super.onStart()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startedScope = s
        s.launch {
            TaskController.state.collect { state ->
                showLive(state)
                if (state is AgentState.Finished) refresh()
            }
        }
        // While a task runs: keep its cost and the list fresh (the active report changes after every step).
        // While a voice call is open (or just ended): keep its minutes fresh.
        s.launch {
            var voiceWasActive = false
            while (isActive) {
                delay(1500)
                val voiceActive = VoiceController.isActive
                if (TaskController.isRunning) showLive(TaskController.state.value)
                if (TaskController.isRunning || voiceActive || voiceWasActive) refresh()
                voiceWasActive = voiceActive
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun currentTab(): String = when {
        inTrash -> TAB_TRASH
        inVoice -> TAB_VOICE
        else -> TAB_RUNS
    }

    private fun switchTab(tab: String) {
        if (tab == currentTab()) return
        inTrash = tab == TAB_TRASH
        inVoice = tab == TAB_VOICE
        checked.clear()
        lastShown = ""
        styleTabs()
        showLive(TaskController.state.value)
        refresh()
    }

    private fun styleTabs() {
        for ((tab, on) in listOf(tabRuns to (!inTrash && !inVoice), tabVoice to inVoice, tabTrash to inTrash)) {
            tab.setTextColor(getColor(if (on) R.color.vc_text else R.color.vc_text_secondary))
            tab.background = if (on) getDrawable(R.drawable.bg_tab_on) else null
        }
    }

    private fun stat(id: Int, label: Int) {
        findViewById<View>(id).findViewById<TextView>(R.id.statLabel).setText(label)
    }

    private fun setStat(id: Int, value: String) {
        findViewById<View>(id).findViewById<TextView>(R.id.statValue).text = value
    }

    /** Re-read the folders (off the main thread) and redraw if anything changed. */
    private fun refresh() {
        val tab = currentTab()
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // The totals need the run list (not the Trash) and the voice sessions on every tab but the Trash.
                Triple(store.list(tab == TAB_TRASH), store.count(trash = true), voiceLog.list())
            }
            if (tab != currentTab()) return@launch
            runs = loaded.first
            sessions = loaded.third
            checked.retainAll(runs.map { it.id }.toSet())
            val trashCount = loaded.second
            tabTrash.text = if (trashCount > 0) "${getString(R.string.runs_tab_trash)} ($trashCount)"
            else getString(R.string.runs_tab_trash)
            render()
        }
    }

    private fun render() {
        val shown = runs.filter { !it.isActive }  // the run in progress is the live card
        val signature = if (inVoice) {
            "voice#" + sessions.joinToString("|") { "${it.id}:${it.status}:${it.billedMs}:${it.tasks}" } +
                "#" + shown.joinToString("|") { "${it.id}:${it.displayStatus}" }
        } else {
            shown.joinToString("|") { "${it.id}:${it.displayStatus}:${it.steps.size}:${it.costUsd}" } +
                "#" + checked.joinToString(",") + "#" + inTrash
        }
        totalsCard.visibility = if (inTrash) View.GONE else View.VISIBLE
        trashBar.visibility = if (inTrash) View.VISIBLE else View.GONE
        if (!inTrash) renderTotals()
        when {
            inVoice -> note.text = "Newest first. The last ${VoiceSessionLog.KEEP} voice calls are kept. GPT-Live costs " +
                "\$${String.format(Locale.US, "%.2f", VoicePricing.USD_PER_SESSION_MINUTE)} per minute of call " +
                "time (OpenAI API price). On the ChatGPT plan that figure is the equivalent at API pricing, included in " +
                "your plan. Tap a task to open its run."
            inTrash -> {
                note.text = "Tick runs to restore them or delete them forever."
                selectAll.isChecked = shown.isNotEmpty() && checked.size == shown.size
                selectAll.isEnabled = shown.isNotEmpty()
                restore.isEnabled = checked.isNotEmpty()
                purge.isEnabled = checked.isNotEmpty()
                purge.alpha = if (purge.isEnabled) 1f else 0.4f  // its explicit red text doesn't grey out by itself
                purge.text = if (checked.isEmpty()) getString(R.string.runs_delete_forever)
                else "${getString(R.string.runs_delete_forever)} (${checked.size})"
            }
            else -> note.text = "The last ${RunReport.KEEP} runs are kept; older ones are deleted. Runs in the Trash " +
                "don't count and stay until you delete them forever."
        }
        val isEmpty = if (inVoice) sessions.isEmpty() else shown.isEmpty()
        empty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        empty.setText(when {
            inVoice -> R.string.runs_voice_empty
            inTrash -> R.string.runs_trash_empty
            else -> R.string.runs_empty
        })
        if (signature == lastShown) return  // nothing new: don't rebuild the rows
        lastShown = signature
        list.removeAllViews()
        if (inVoice) {
            val runIds = runs.map { it.id }.toSet()
            for (s in sessions) list.addView(voiceRow(s, runIds))
            scrollToFocus()
        } else {
            for (r in shown) list.addView(row(r))
        }
    }

    /** JEV row (unchanged), voice row, and the all-in total. */
    private fun renderTotals() {
        val t = RunStore.totals(runs)
        setStat(R.id.totalRuns, t.runs.toString())
        setStat(R.id.totalCost, RunFormat.usd(t.costUsd))
        setStat(R.id.totalTokens, RunFormat.tokens(t.tokens))
        setStat(R.id.totalSuccess, RunFormat.pct(t.successRate))
        val v = VoiceSessionLog.totals(sessions)
        setStat(R.id.totalVoiceSessions, v.sessions.toString())
        setStat(R.id.totalVoiceMinutes, RunFormat.minutes(v.billedMs))
        setStat(R.id.totalVoiceCost, RunFormat.usdVoice(v.costUsd))
        setStat(R.id.totalAllIn, RunFormat.usd(t.costUsd + v.costUsd))
        val plan = sessions.count { it.isPlan }
        findViewById<View>(R.id.totalVoiceCost).findViewById<TextView>(R.id.statLabel).setText(
            if (plan > 0 && plan == sessions.size) R.string.runs_stat_voice_equiv else R.string.runs_stat_voice_cost
        )
        totalsNote.text = buildString {
            append("Top: JEV, the phone agent (listed runs). Bottom: GPT voice at API pricing ")
            append("(\$${String.format(Locale.US, "%.2f", VoicePricing.USD_PER_SESSION_MINUTE)} per minute of call time). All-in = JEV + voice.")
            if (plan > 0) {
                append(" ")
                append(if (plan == sessions.size) "All" else "$plan of the ${sessions.size}")
                append(" voice calls used your ChatGPT plan: their cost is the equivalent at API pricing, included in your plan.")
            }
        }
    }

    private fun row(r: Run): View {
        val v = layoutInflater.inflate(R.layout.item_run_row, list, false)
        v.findViewById<TextView>(R.id.runTask).text = r.task.ifBlank { "(no task)" }
        RunUi.styleChip(v.findViewById(R.id.runChip), r.displayStatus)
        RunUi.styleChip(v.findViewById(R.id.runBackendChip), RunFormat.backendLabel(r), RunFormat.Tone.MUTED)
        v.findViewById<TextView>(R.id.runWhen).text = RunFormat.whenShort(r.started)
        v.findViewById<TextView>(R.id.runMetrics).text = RunFormat.rowMetrics(r)
        v.findViewById<TextView>(R.id.runWarning).apply {
            visibility = if (r.warning != null) View.VISIBLE else View.GONE
            text = r.warning?.let { "⚠ $it" }
        }
        val check = v.findViewById<CheckBox>(R.id.runCheck)
        val trashButton = v.findViewById<ImageButton>(R.id.runTrash)
        if (inTrash) {
            check.visibility = View.VISIBLE
            check.isChecked = r.id in checked
            check.setOnClickListener {
                if (check.isChecked) checked.add(r.id) else checked.remove(r.id)
                render()
            }
            trashButton.visibility = View.GONE
        } else {
            trashButton.setOnClickListener { act { store.trash(listOf(r.id)) } }
        }
        v.setOnClickListener { RunDetailActivity.start(this, r.id, inTrash) }
        return v
    }

    // ------------------------------------------------------------------ voice tab

    private fun voiceRow(s: VoiceSession, runIds: Set<String>): View {
        val v = layoutInflater.inflate(R.layout.item_voice_row, list, false)
        v.tag = s.id
        v.findViewById<TextView>(R.id.voiceWhen).text = RunFormat.whenShort(s.started)
        RunUi.styleChip(v.findViewById(R.id.voiceChip), RunFormat.voiceStatusLabel(s.status), RunFormat.voiceTone(s.status))
        v.findViewById<TextView>(R.id.voiceCost).text = RunFormat.voiceCost(s)
        v.findViewById<TextView>(R.id.voicePlanNote).visibility = if (s.isPlan) View.VISIBLE else View.GONE
        v.findViewById<TextView>(R.id.voiceMetrics).text = RunFormat.voiceMetrics(s)
        v.findViewById<TextView>(R.id.voiceError).apply {
            visibility = if (s.error != null) View.VISIBLE else View.GONE
            text = s.error?.let { "⚠ $it" }
        }
        val tasks = v.findViewById<LinearLayout>(R.id.voiceTasks)
        for (t in s.tasks) tasks.addView(taskLine(t.text, t.runId, t.outcome, runIds))
        return v
    }

    /** "› Open Settings and turn on Bluetooth" + the run's status; tappable when the run is still listed. */
    private fun taskLine(text: String, runId: String?, outcome: String, runIds: Set<String>): View {
        val run = runId?.let { id -> runs.firstOrNull { it.id == id } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            minimumHeight = RunUi.dp(this@RunsActivity, 40)
        }
        val label = TextView(this).apply {
            this.text = "› ${text.ifBlank { "(empty request)" }}"
            textSize = 14f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(getColor(if (run != null) R.color.vc_accent else R.color.vc_text))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)
        val chip = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = RunUi.dp(this@RunsActivity, 8) }
        }
        when {
            run != null -> RunUi.styleChip(chip, run.displayStatus)
            runId != null -> RunUi.styleChip(chip, "Run deleted", RunFormat.Tone.MUTED)
            else -> RunUi.styleChip(chip, RunFormat.taskOutcome(outcome).replaceFirstChar { it.uppercase() },
                if (outcome == "done") RunFormat.Tone.OK else RunFormat.Tone.MUTED)
        }
        row.addView(chip)
        if (run != null && runId in runIds) {
            row.isClickable = true
            row.isFocusable = true
            row.setBackgroundResource(android.R.drawable.list_selector_background)
            row.setOnClickListener { RunDetailActivity.start(this, run.id, trash = false) }
        }
        return row
    }

    /** Scroll the Voice tab to the session a run linked to, once, and flash it. */
    private fun scrollToFocus() {
        val id = focusSession ?: return
        val target = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it.tag == id } ?: return
        focusSession = null
        target.setBackgroundResource(R.drawable.bg_card_live)
        val scroll = findViewById<ScrollView>(R.id.runsScroll)
        scroll.post { scroll.smoothScrollTo(0, (target.top + list.top - RunUi.dp(this, 12)).coerceAtLeast(0)) }
    }

    private fun confirmPurge() {
        val n = checked.size
        if (n == 0) return
        AlertDialog.Builder(this)
            .setTitle("Delete $n run${if (n == 1) "" else "s"} forever?")
            .setMessage("The screenshots and reports can't be recovered.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.runs_delete_forever) { _, _ -> act { store.deleteForever(checked.toList()) } }
            .show()
    }

    /** Runs a file operation off the main thread, then clears the selection and refreshes. */
    private fun act(op: () -> Int) {
        scope.launch {
            val n = withContext(Dispatchers.IO) { op() }
            VcLog.i(TAG, "runs: $n changed (trash tab=$inTrash)")
            checked.clear()
            refresh()
        }
    }

    // ------------------------------------------------------------------ live card

    private fun showLive(state: AgentState) {
        val running = state as? AgentState.Running
        if (running == null || inTrash || inVoice) {
            liveCard.visibility = View.GONE
            findViewById<Button>(R.id.liveStop).isEnabled = true
            return
        }
        liveCard.visibility = View.VISIBLE
        RunUi.styleChip(findViewById(R.id.liveChip), "running")
        findViewById<TextView>(R.id.liveTask).text = running.task
        findViewById<TextView>(R.id.liveStep).text =
            if (running.step == 0) "Starting…" else "Step ${running.step}"
        findViewById<TextView>(R.id.liveAction).text = if (running.step == 0 && running.action == "START") ""
        else "${running.action} ${running.detail}".trim() +
            if (running.step > 0) "  (${RunFormat.pct(running.confidence.toDouble())} sure)" else ""
        val active = runs.firstOrNull { it.isActive }
        findViewById<TextView>(R.id.liveMetrics).text = active?.let {
            "${RunFormat.usd(it.costUsd)} so far · ${RunFormat.tokens(it.tokens)} tokens · ${RunFormat.duration(it.seconds)}"
        } ?: ""
    }

    companion object {
        private const val TAG = "Runs"
        private const val KEY_TAB = "tab"
        const val EXTRA_TRASH = "trash"
        /** Which tab to open: [TAB_RUNS], [TAB_VOICE] or [TAB_TRASH]. */
        const val EXTRA_TAB = "tab"
        /** With [TAB_VOICE]: the session id to scroll to. */
        const val EXTRA_SESSION = "session"
        const val TAB_RUNS = "runs"
        const val TAB_VOICE = "voice"
        const val TAB_TRASH = "trash"

        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, RunsActivity::class.java))

        /** Opens the Voice tab scrolled to session [id]. */
        fun startVoice(ctx: Context, id: String?) = ctx.startActivity(
            Intent(ctx, RunsActivity::class.java).putExtra(EXTRA_TAB, TAB_VOICE).putExtra(EXTRA_SESSION, id)
        )

        /** The app's run folder, with the run in progress (RunReport.activeDir) marked active. */
        fun storeFor(ctx: Context): RunStore {
            val root = File(ctx.filesDir, "runs")
            return RunStore(root) { RunReport.activeDir?.takeIf { it.parentFile == root }?.name }
        }
    }
}

package com.brainstormity.voicecontrol.ui.runs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.RunReport
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The native port of upstream's dashboard list (dashboard.html's sidebar): every run the agent made, newest
 * first, with status, when, steps, duration, cost and JEV tokens; totals on top; the task running right now
 * (from [TaskController.state]) as a live card with a Stop button; a Trash tab with select all / Restore /
 * Delete forever. Tap a run for its step-by-step report ([RunDetailActivity]).
 *
 * Refreshes on resume, when a task finishes, and every 1.5 s while one runs (like upstream's poll).
 */
class RunsActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startedScope: CoroutineScope? = null

    private lateinit var store: RunStore
    private var inTrash = false
    private var runs: List<Run> = emptyList()
    private val checked = linkedSetOf<String>()  // runs ticked in the Trash tab
    private var lastShown: String = ""

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var note: TextView
    private lateinit var liveCard: View
    private lateinit var totalsCard: View
    private lateinit var trashBar: View
    private lateinit var selectAll: CheckBox
    private lateinit var restore: Button
    private lateinit var purge: Button
    private lateinit var tabRuns: TextView
    private lateinit var tabTrash: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_runs)
        RunUi.edgeToEdge(this, findViewById(R.id.runsRoot))
        store = storeFor(this)
        inTrash = savedInstanceState?.getBoolean(KEY_TRASH) ?: intent.getBooleanExtra(EXTRA_TRASH, false)

        list = findViewById(R.id.runsList)
        empty = findViewById(R.id.emptyText)
        note = findViewById(R.id.listNote)
        liveCard = findViewById(R.id.liveCard)
        totalsCard = findViewById(R.id.totalsCard)
        trashBar = findViewById(R.id.trashBar)
        selectAll = findViewById(R.id.selectAll)
        restore = findViewById(R.id.restoreButton)
        purge = findViewById(R.id.purgeButton)
        tabRuns = findViewById(R.id.tabRuns)
        tabTrash = findViewById(R.id.tabTrash)

        findViewById<ImageButton>(R.id.runsBack).setOnClickListener { finish() }
        tabRuns.setOnClickListener { switchTab(false) }
        tabTrash.setOnClickListener { switchTab(true) }
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
        styleTabs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TRASH, inTrash)
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
        s.launch {
            while (isActive) {
                delay(1500)
                if (TaskController.isRunning) {
                    showLive(TaskController.state.value)
                    refresh()
                }
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

    private fun switchTab(trash: Boolean) {
        if (trash == inTrash) return
        inTrash = trash
        checked.clear()
        lastShown = ""
        styleTabs()
        refresh()
    }

    private fun styleTabs() {
        for ((tab, on) in listOf(tabRuns to !inTrash, tabTrash to inTrash)) {
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

    /** Re-read the folder (off the main thread) and redraw if anything changed. */
    private fun refresh() {
        val trash = inTrash
        scope.launch {
            val (loaded, trashCount) = withContext(Dispatchers.IO) { store.list(trash) to store.count(trash = true) }
            if (trash != inTrash) return@launch
            runs = loaded
            checked.retainAll(runs.map { it.id }.toSet())
            tabTrash.text = if (trashCount > 0) "${getString(R.string.runs_tab_trash)} ($trashCount)"
            else getString(R.string.runs_tab_trash)
            render()
        }
    }

    private fun render() {
        val shown = runs.filter { !it.isActive }  // the run in progress is the live card
        val signature = shown.joinToString("|") { "${it.id}:${it.displayStatus}:${it.steps.size}:${it.costUsd}" } +
            "#" + checked.joinToString(",") + "#" + inTrash
        totalsCard.visibility = if (inTrash) View.GONE else View.VISIBLE
        trashBar.visibility = if (inTrash) View.VISIBLE else View.GONE
        if (!inTrash) {
            val t = RunStore.totals(runs)
            setStat(R.id.totalRuns, t.runs.toString())
            setStat(R.id.totalCost, RunFormat.usd(t.costUsd))
            setStat(R.id.totalTokens, RunFormat.tokens(t.tokens))
            setStat(R.id.totalSuccess, RunFormat.pct(t.successRate))
            note.text = "The last ${RunReport.KEEP} runs are kept; older ones are deleted. Runs in the Trash " +
                "don't count and stay until you delete them forever."
        } else {
            note.text = "Tick runs to restore them or delete them forever."
            selectAll.isChecked = shown.isNotEmpty() && checked.size == shown.size
            selectAll.isEnabled = shown.isNotEmpty()
            restore.isEnabled = checked.isNotEmpty()
            purge.isEnabled = checked.isNotEmpty()
            purge.alpha = if (purge.isEnabled) 1f else 0.4f  // its explicit red text doesn't grey out by itself
            purge.text = if (checked.isEmpty()) getString(R.string.runs_delete_forever)
            else "${getString(R.string.runs_delete_forever)} (${checked.size})"
        }
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.setText(if (inTrash) R.string.runs_trash_empty else R.string.runs_empty)
        if (signature == lastShown) return  // nothing new: don't rebuild the rows
        lastShown = signature
        list.removeAllViews()
        for (r in shown) list.addView(row(r))
    }

    private fun row(r: Run): View {
        val v = layoutInflater.inflate(R.layout.item_run_row, list, false)
        v.findViewById<TextView>(R.id.runTask).text = r.task.ifBlank { "(no task)" }
        RunUi.styleChip(v.findViewById(R.id.runChip), r.displayStatus)
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
        if (running == null || inTrash) {
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
        private const val KEY_TRASH = "trash"
        const val EXTRA_TRASH = "trash"

        fun start(ctx: Context) = ctx.startActivity(Intent(ctx, RunsActivity::class.java))

        /** The app's run folder, with the run in progress (RunReport.activeDir) marked active. */
        fun storeFor(ctx: Context): RunStore {
            val root = File(ctx.filesDir, "runs")
            return RunStore(root) { RunReport.activeDir?.takeIf { it.parentFile == root }?.name }
        }
    }
}

package com.brainstormity.voicecontrol.ui.runs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.RunReport
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One run's step-by-step report: the native port of upstream's dashboard report pane. Header (task, status,
 * message, warning, when, totals), then one card per step: the annotated screenshot (tap = full screen),
 * "[n] ACTION detail", why / note, confidence bar + the options JEV weighed, target confidence and zoom note,
 * result, timings, tokens and cost. Follows a run in progress live (re-reads report.json every 1.5 s).
 * Actions: Share report (report.json via FileProvider), Move to trash; in the Trash: Restore, Delete forever.
 */
class RunDetailActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var poll: Job? = null

    private lateinit var store: RunStore
    private lateinit var id: String
    private var inTrash = false
    private var run: Run? = null
    private var lastText: String? = null
    /** The step cards on screen and the data each was drawn from (to redraw only what changed). */
    private val shownSteps = mutableListOf<Pair<Step, View>>()

    private lateinit var steps: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_run_detail)
        RunUi.edgeToEdge(this, findViewById(R.id.detailRoot))
        store = RunsActivity.storeFor(this)
        id = intent.getStringExtra(EXTRA_ID) ?: ""
        inTrash = intent.getBooleanExtra(EXTRA_TRASH, false)
        steps = findViewById(R.id.detailSteps)

        findViewById<ImageButton>(R.id.detailBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.detailShare).setOnClickListener { share() }
        findViewById<ImageButton>(R.id.detailTrash).setOnClickListener { onTrashPressed() }
    }

    override fun onStart() {
        super.onStart()
        poll = scope.launch {
            while (isActive) {
                reload()
                // Follow a run in progress like upstream (1.5 s); a finished run doesn't change.
                delay(if (run?.isActive == true) 1500 else 5000)
            }
        }
    }

    override fun onStop() {
        poll?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun reload() {
        val text = withContext(Dispatchers.IO) { store.reportText(id, inTrash) }
        if (text == null) {
            if (run == null) {
                findViewById<TextView>(R.id.detailEmpty).apply {
                    visibility = View.VISIBLE
                    this.text = "This run's report isn't there (it was deleted or moved)."
                }
            }
            return
        }
        // Parse the text just read. Even if it didn't change, the run may have stopped being active.
        val parsed = RunStore.parse(id, text, isActive = !inTrash && RunReport.activeDir?.name == id) ?: return
        if (text == lastText && parsed.isActive == run?.isActive) return
        lastText = text
        run = parsed
        render(parsed)
    }

    private fun render(r: Run) {
        RunUi.styleChip(findViewById(R.id.detailChip), r.displayStatus)
        findViewById<View>(R.id.detailInTrash).visibility = if (inTrash) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.detailTask).text = r.task
        findViewById<TextView>(R.id.detailMessage).apply {
            val msg = if (r.displayStatus == "interrupted")
                "The app stopped while this task was running, so the report ends here."
            else r.message
            visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
            text = msg
        }
        findViewById<TextView>(R.id.detailWarning).apply {
            visibility = if (r.warning != null) View.VISIBLE else View.GONE
            text = r.warning?.let { "⚠ $it" }
        }
        findViewById<TextView>(R.id.detailStats).text = stats(r)
        findViewById<ImageButton>(R.id.detailTrash).apply {
            isEnabled = !r.isActive
            alpha = if (r.isActive) 0.35f else 1f
            contentDescription = getString(if (inTrash) R.string.runs_delete_forever else R.string.runs_move_to_trash)
        }

        val empty = findViewById<TextView>(R.id.detailEmpty)
        empty.visibility = if (r.steps.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (r.isActive) "Working…" else "No steps."

        r.steps.forEachIndexed { i, s ->
            when {
                i < shownSteps.size && shownSteps[i].first == s -> Unit
                i < shownSteps.size -> {
                    val v = stepView(r, s)
                    steps.removeViewAt(i)
                    steps.addView(v, i)
                    shownSteps[i] = s to v
                }
                else -> {
                    val v = stepView(r, s)
                    steps.addView(v)
                    shownSteps += s to v
                }
            }
        }
        while (shownSteps.size > r.steps.size) {
            steps.removeViewAt(shownSteps.size - 1)
            shownSteps.removeAt(shownSteps.size - 1)
        }
    }

    private fun stats(r: Run): String = buildList {
        add("Started ${RunFormat.whenLong(r.started)}" +
            (r.finished?.let { " · finished ${RunFormat.whenLong(it).substringAfter(", ")}" } ?: ""))
        add("${r.steps.size} step${if (r.steps.size == 1) "" else "s"}" +
            (r.seconds?.let { " in ${RunFormat.duration(it)}" } ?: ""))
        add("${RunFormat.usd(r.costUsd)} · ${RunFormat.tokens(r.tokens)} JEV tokens " +
            "(${RunFormat.tokens(r.inputTokens)} in / ${RunFormat.tokens(r.outputTokens)} out; " +
            "JEV charges \$0.042 per million input tokens, output is free)")
        if (r.device == "android" || r.deviceName != null) add("Phone: ${r.deviceName ?: "Android phone"}")
        r.opened?.let { add("Opened $it first") }
        add("Quoted text: " + if (r.canType.isEmpty()) "nothing" else r.canType.joinToString(", ") { "“$it”" })
        add("Websites: " + if (r.websites.isEmpty()) "none" else r.websites.joinToString(", "))
    }.joinToString("\n")

    private fun stepView(r: Run, s: Step): View {
        val v = layoutInflater.inflate(R.layout.item_run_step, steps, false)
        val dir = store.dir(r.id, inTrash)
        val imageFile = if (dir != null && s.image != null) File(dir, s.image) else null
        val image = v.findViewById<ImageView>(R.id.stepImage)
        val caption = "Step ${s.n} · ${s.action} ${s.detail}".trim()
        if (imageFile != null && imageFile.isFile) {
            RunUi.loadInto(scope, image, imageFile, RunUi.dp(this, 112))
            image.setOnClickListener { RunUi.showFullScreen(this, scope, imageFile, caption) }
        } else {
            image.visibility = View.GONE
        }

        v.findViewById<TextView>(R.id.stepTitle).text = "[${s.n}] ${s.action}"
        v.findViewById<TextView>(R.id.stepDetail).apply {
            visibility = if (s.detail.isBlank()) View.GONE else View.VISIBLE
            text = s.detail
        }
        v.findViewById<TextView>(R.id.stepWhy).apply {
            visibility = if (s.why.isBlank()) View.GONE else View.VISIBLE
            text = s.why
        }
        v.findViewById<TextView>(R.id.stepConfidence).text = "How sure JEV was: ${RunFormat.pct(s.confidence)}"
        v.findViewById<ProgressBar>(R.id.stepConfidenceBar).progress = Math.round((s.confidence ?: 0.0) * 100).toInt()
        v.findViewById<TextView>(R.id.stepTarget).apply {
            visibility = if (s.targetBox != null) View.VISIBLE else View.GONE
            text = "Tap target confidence: ${RunFormat.pct(s.targetConfidence)} (red box on the screenshot)"
        }
        v.findViewById<TextView>(R.id.stepZoom).apply {
            visibility = if (s.zoomNote != null) View.VISIBLE else View.GONE
            text = s.zoomNote
        }

        val options = v.findViewById<LinearLayout>(R.id.stepOptions)
        for ((name, p) in s.options.take(4)) options.addView(optionRow(name, p, chosen = name == s.action))

        v.findViewById<TextView>(R.id.stepResult).apply {
            val result = s.result.ifBlank { if (r.isActive) "Deciding…" else "–" }
            text = result
            val bad = result.startsWith("Not executed") || result.startsWith("Failed")
            setTextColor(getColor(if (bad) R.color.vc_error else R.color.vc_text))
        }
        v.findViewById<TextView>(R.id.stepTiming).apply {
            val t = RunFormat.stepTiming(s)
            visibility = if (t.isBlank()) View.GONE else View.VISIBLE
            text = t
        }
        v.findViewById<TextView>(R.id.stepCost).text = RunFormat.stepCost(s)
        return v
    }

    private fun optionRow(name: String, p: Double, chosen: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(t: String, weight: Float) = TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(getColor(if (chosen) R.color.vc_text else R.color.vc_text_secondary))
            if (chosen) setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, weight)
        }
        row.addView(cell(name, 1f))
        row.addView(cell(RunFormat.pct(p), 0f))
        return row
    }

    // ------------------------------------------------------------------ actions

    private fun share() {
        val dir = store.dir(id, inTrash) ?: return
        val file = File(dir, "report.json")
        if (!file.isFile) return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.runs", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Voice Control run report: ${run?.task ?: id}")
                putExtra(Intent.EXTRA_TEXT, run?.let { "${it.task}\n${RunFormat.label(it.displayStatus)} · " +
                    RunFormat.rowMetrics(it) } ?: id)
                clipData = ClipData.newRawUri("report.json", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.run_share)))
        } catch (e: IllegalArgumentException) {
            VcLog.w(TAG, "share failed: ${e.message}")
            Toast.makeText(this, "Couldn't share the report", Toast.LENGTH_SHORT).show()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No app to share with", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onTrashPressed() {
        if (run?.isActive == true) return
        if (!inTrash) {
            scope.launch {
                val n = withContext(Dispatchers.IO) { store.trash(listOf(id)) }
                if (n > 0) {
                    Toast.makeText(this@RunDetailActivity, "Moved to trash", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            return
        }
        AlertDialog.Builder(this)
            .setTitle("This run is in the trash")
            .setMessage("Restore it, or delete it forever? The screenshots and report can't be recovered after " +
                "deleting.")
            .setNeutralButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.runs_restore) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { store.restore(listOf(id)) }
                    finish()
                }
            }
            .setPositiveButton(R.string.runs_delete_forever) { _, _ ->
                scope.launch {
                    withContext(Dispatchers.IO) { store.deleteForever(listOf(id)) }
                    finish()
                }
            }
            .show()
    }

    companion object {
        private const val TAG = "Runs"
        const val EXTRA_ID = "id"
        const val EXTRA_TRASH = "trash"

        fun start(ctx: Context, id: String, trash: Boolean) = ctx.startActivity(
            Intent(ctx, RunDetailActivity::class.java).putExtra(EXTRA_ID, id).putExtra(EXTRA_TRASH, trash)
        )
    }
}

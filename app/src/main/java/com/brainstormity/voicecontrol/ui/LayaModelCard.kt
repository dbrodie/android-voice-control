package com.brainstormity.voicecontrol.ui

import android.net.ConnectivityManager
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.Availability
import com.brainstormity.voicecontrol.agent.SystemOneBackends
import com.brainstormity.voicecontrol.agent.laya.LayaDownload
import com.brainstormity.voicecontrol.agent.laya.LayaModelFiles
import com.brainstormity.voicecontrol.core.VcLog
import java.util.concurrent.Executors

/**
 * The on-device model's files in the "Next-step model" card (T17): one status line, Download (progress bar,
 * becomes "Cancel download" while running; asks first on a metered network), Import… (ACTION_OPEN_DOCUMENT,
 * several files or a .zip) and Delete (asks first). Create it in onCreate (it registers an ActivityResult
 * launcher); [onChanged] lets the activity refresh its other status lines.
 */
class LayaModelCard(private val activity: AppCompatActivity, private val onChanged: () -> Unit) {
    private val status: TextView = activity.findViewById(R.id.layaFilesStatus)
    private val progress: ProgressBar = activity.findViewById(R.id.layaProgress)
    private val download: Button = activity.findViewById(R.id.layaDownload)
    private val import: Button = activity.findViewById(R.id.layaImport)
    private val delete: Button = activity.findViewById(R.id.layaDelete)
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var importing: String? = null
    @Volatile private var lastNote: String? = null

    private val picker = activity.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importUris(uris)
    }

    private val listener: (LayaDownload.State?) -> Unit = { activity.runOnUiThread { refresh(); onChanged() } }

    init {
        download.setOnClickListener { if (LayaDownload.running) LayaDownload.cancel() else confirmDownload() }
        import.setOnClickListener { picker.launch(arrayOf("*/*")) }
        delete.setOnClickListener { confirmDelete() }
    }

    fun onStart() { LayaDownload.addListener(listener); refresh() }
    fun onStop() { LayaDownload.removeListener(listener) }

    fun refresh() {
        val ctx = activity
        val st = LayaModelFiles.status(ctx)
        val dl = LayaDownload.state
        val unsupported = SystemOneBackends.byId(SystemOneBackends.LAYA)?.availability(ctx) as? Availability.Unsupported
        val running = dl as? LayaDownload.State.Running
        status.text = when {
            importing != null -> ctx.getString(R.string.laya_files_importing, importing)
            running != null -> ctx.getString(R.string.laya_files_downloading, running.file, running.percent, LayaModelFiles.mb(running.total))
            unsupported != null && !st.complete -> ctx.getString(R.string.laya_files_unsupported, unsupported.reason)
            st.complete -> ctx.getString(R.string.laya_files_ready, LayaModelFiles.mb(st.totalBytes))
            st.present.isEmpty() -> ctx.getString(R.string.laya_files_none, LayaModelFiles.mb(st.totalBytes))
            else -> ctx.getString(R.string.laya_files_partial, LayaModelFiles.mb(st.totalBytes - st.missingBytes), LayaModelFiles.mb(st.totalBytes))
        } + ((dl as? LayaDownload.State.Failed)?.let { "\n" + ctx.getString(R.string.laya_files_failed, it.message) } ?: "") +
            (lastNote?.let { "\n$it" } ?: "")
        progress.visibility = if (running != null) View.VISIBLE else View.GONE
        if (running != null) progress.progress = if (running.total > 0) (running.done * 1000 / running.total).toInt() else 0
        download.setText(if (running != null) R.string.laya_cancel else R.string.laya_download)
        download.isEnabled = running != null || (!st.complete && importing == null)
        import.isEnabled = running == null && importing == null
        delete.isEnabled = running == null && importing == null && (st.present.isNotEmpty() || LayaModelFiles.dir(ctx).exists())
    }

    private fun confirmDownload() {
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        val size = LayaModelFiles.mb(LayaModelFiles.status(activity).missingBytes)
        if (cm?.isActiveNetworkMetered == true) {
            AlertDialog.Builder(activity)
                .setTitle(R.string.laya_metered_title)
                .setMessage(activity.getString(R.string.laya_metered_message, size))
                .setPositiveButton(R.string.laya_metered_ok) { _, _ -> startDownload() }
                .setNegativeButton(R.string.laya_cancel_button, null)
                .show()
        } else startDownload()
    }

    private fun startDownload() {
        lastNote = null
        LayaDownload.start(activity)
        refresh()
    }

    private fun confirmDelete() {
        val st = LayaModelFiles.status(activity)
        AlertDialog.Builder(activity)
            .setTitle(R.string.laya_delete_title)
            .setMessage(activity.getString(R.string.laya_delete_message, LayaModelFiles.mb(st.totalBytes - st.missingBytes)))
            .setPositiveButton(R.string.laya_delete) { _, _ ->
                io.execute {
                    LayaModelFiles.delete(activity.applicationContext)
                    activity.runOnUiThread { lastNote = null; refresh(); onChanged() }
                }
            }
            .setNegativeButton(R.string.laya_cancel_button, null)
            .show()
    }

    private fun importUris(uris: List<Uri>) {
        importing = "${uris.size} file(s)"
        refresh()
        val app = activity.applicationContext
        io.execute {
            val note = try {
                val names = LayaModelFiles.importUris(app, uris)
                if (names.isEmpty()) activity.getString(R.string.laya_files_import_nothing)
                else activity.getString(R.string.laya_files_imported, names.joinToString())
            } catch (e: Exception) {
                VcLog.e("Laya", "import failed", e)
                "Import failed: ${e.message ?: e.javaClass.simpleName}"
            }
            importing = null
            activity.runOnUiThread { lastNote = note; refresh(); onChanged() }
        }
    }
}

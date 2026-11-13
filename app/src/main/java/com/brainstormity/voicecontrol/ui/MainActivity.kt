package com.brainstormity.voicecontrol.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.TaskController
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.Device
import com.brainstormity.voicecontrol.core.VcLog
import com.brainstormity.voicecontrol.core.VoiceState
import com.brainstormity.voicecontrol.overlay.OverlayService
import com.brainstormity.voicecontrol.voice.VoiceBackends
import com.brainstormity.voicecontrol.voice.VoiceController
import com.brainstormity.voicecontrol.voice.auth.AuthState
import com.brainstormity.voicecontrol.voice.auth.ChatGptAuth
import com.brainstormity.voicecontrol.voice.auth.LoginActivity
import com.brainstormity.voicecontrol.voice.auth.LoginFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Setup checklist + settings + a text box to run tasks without voice. Plain Views. */
class MainActivity : AppCompatActivity() {

    private class CheckRow(
        val optional: () -> Boolean,
        val isOk: () -> Boolean,
        val actionLabel: Int,
        val action: () -> Unit,
        val view: View,
        /** Detail text computed on refresh (null = keep the static one). */
        val detail: (() -> String?)? = null,
        /** Label for the button when ok; null = "Done" and disabled. */
        val okLabel: Int? = null,
    )

    private val rows = mutableListOf<CheckRow>()
    private var startedScope: CoroutineScope? = null

    private lateinit var overlayButton: Button
    private lateinit var overlayStatus: TextView
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var taskInput: EditText

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onPermissionResult(Manifest.permission.RECORD_AUDIO, granted)
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onPermissionResult(Manifest.permission.POST_NOTIFICATIONS, granted)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        VcLog.d(TAG, "MainActivity created")

        // targetSdk 36 => always edge-to-edge. Pad the scroll content by the bars (and the keyboard).
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        overlayButton = findViewById(R.id.overlayButton)
        overlayStatus = findViewById(R.id.overlayStatus)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        taskInput = findViewById(R.id.taskInput)

        buildChecklist()
        setupOverlayButton()
        setupBackend()
        setupSettings()
        setupTestBox()
        logText.text = logBuffer.joinToString("\n")
        maybeShowOnboarding(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        startedScope = scope
        scope.launch { TaskController.state.collect { log("agent  " + describe(it)) } }
        scope.launch { VoiceController.state.collect { log("voice  " + describe(it)) } }
        scope.launch { OverlayService.running.collect { updateOverlayButton(it) } }
        // Sign-in finishes in the browser / LoginActivity: refresh the ChatGPT row and the backend line live.
        scope.launch { ChatGptAuth.state.collect { refreshChecklist() } }
    }

    override fun onResume() {
        super.onResume()
        syncSettingsViews()
        refreshChecklist()
    }

    override fun onStop() {
        startedScope?.cancel()
        startedScope = null
        super.onStop()
    }

    // ---------------------------------------------------------------- checklist

    private fun buildChecklist() {
        val container = findViewById<LinearLayout>(R.id.checklist)
        fun add(
            title: Int, detail: Int, optional: Boolean, actionLabel: Int, isOk: () -> Boolean,
            dynamicDetail: (() -> String?)? = null, okLabel: Int? = null, isOptional: (() -> Boolean)? = null,
            action: () -> Unit,
        ) {
            val v = layoutInflater.inflate(R.layout.item_check_row, container, false)
            v.findViewById<TextView>(R.id.checkTitle).setText(title)
            v.findViewById<TextView>(R.id.checkDetail).setText(detail)
            container.addView(v)
            rows += CheckRow(isOptional ?: { optional }, isOk, actionLabel, action, v, dynamicDetail, okLabel)
        }
        add(R.string.check_overlay_title, R.string.check_overlay_detail, false, R.string.action_grant,
            { Settings.canDrawOverlays(this) }) { openOverlaySettings() }
        add(R.string.check_accessibility_title, R.string.check_accessibility_detail, false, R.string.action_open,
            ::isAccessibilityEnabled) {
            launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        add(R.string.check_mic_title, R.string.check_mic_detail, false, R.string.action_allow,
            { hasPermission(Manifest.permission.RECORD_AUDIO) }) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        // Voice needs either this sign-in or an OpenAI API key: the row is optional once a key is set.
        add(R.string.check_chatgpt_title, R.string.check_chatgpt_detail, false, R.string.action_sign_in,
            { ChatGptAuth.isSignedIn(this) },
            dynamicDetail = ::chatGptDetail,
            okLabel = R.string.action_manage,
            isOptional = { AppSettings.openAiApiKey(this).isNotBlank() },
        ) { LoginActivity.start(this) } // signed out: sign-in choices; signed in ("Manage"): account details
        add(
            R.string.check_notif_title,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.check_notif_detail else R.string.check_notif_detail_old,
            false, R.string.action_allow,
            {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            },
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // T7: JEV API key row (see the JEV block near the end); a second copy sits in Settings where the plain key field was.
        addJevKeyRow(container)
        addJevKeyRow(findViewById(R.id.jevKeySettingsSlot))
        add(R.string.check_battery_title, R.string.check_battery_detail, true, R.string.action_allow,
            { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (_: ActivityNotFoundException) {
                launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun refreshChecklist() {
        for (row in rows) {
            val ok = row.isOk()
            val optional = row.optional()
            val status = row.view.findViewById<TextView>(R.id.checkStatus)
            val button = row.view.findViewById<Button>(R.id.checkButton)
            status.text = if (ok) "✓" else if (optional) "–" else "!"
            status.backgroundTintList = ColorStateList.valueOf(
                getColor(if (ok) R.color.vc_ok else if (optional) R.color.vc_optional else R.color.vc_warn)
            )
            row.detail?.invoke()?.let { row.view.findViewById<TextView>(R.id.checkDetail).text = it }
            button.setText(if (ok) row.okLabel ?: R.string.action_done else row.actionLabel)
            button.isEnabled = !ok || row.okLabel != null
            button.setOnClickListener { row.action() }
        }
        refreshBackend()
    }

    private fun chatGptDetail(): String? = when (val s = ChatGptAuth.state.value) {
        is AuthState.SignedIn -> (s.email ?: "Signed in") + (LoginFlow.planDisplayName(s.plan)?.let { " · $it" } ?: "")
        AuthState.SigningIn -> "Signing in… finish in the browser."
        is AuthState.Error -> "Sign-in problem: ${s.message}"
        AuthState.SignedOut -> getString(R.string.check_chatgpt_detail)
    }

    /**
     * First launch (and every cold start while voice has neither a ChatGPT sign-in nor an API key): show the
     * sign-in screen on top. Once per process, so "Not now" / Back really leaves it.
     */
    private fun maybeShowOnboarding(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || onboardingShown) return
        onboardingShown = true
        if (VoiceBackends.needsSetup(this)) {
            VcLog.i(TAG, "voice not set up: showing the sign-in screen")
            LoginActivity.start(this, onboarding = true)
        }
    }

    /** LoginActivity may have changed the backend / API key: keep the radio buttons and key field in sync. */
    private fun syncSettingsViews() {
        val group = findViewById<RadioGroup>(R.id.backendGroup)
        val id = backendIds.entries.firstOrNull { it.value == AppSettings.voiceBackend(this) }?.key ?: R.id.backendAuto
        if (group.checkedRadioButtonId != id) group.check(id) // listener sees the same value: no write
        val openAi = findViewById<EditText>(R.id.openAiKeyInput)
        val key = AppSettings.openAiApiKey(this)
        if (!openAi.hasFocus() && openAi.text.toString() != key) openAi.setText(key)
    }

    // ---------------------------------------------------------------- voice backend

    private val backendIds by lazy {
        mapOf(
            R.id.backendAuto to AppSettings.BACKEND_AUTO,
            R.id.backendChatGpt to AppSettings.BACKEND_CHATGPT,
            R.id.backendOpenAiKey to AppSettings.BACKEND_OPENAI_KEY,
        )
    }

    private fun setupBackend() {
        val group = findViewById<RadioGroup>(R.id.backendGroup)
        val current = AppSettings.voiceBackend(this)
        group.check(backendIds.entries.firstOrNull { it.value == current }?.key ?: R.id.backendAuto)
        group.setOnCheckedChangeListener { _, id ->
            val v = backendIds[id] ?: return@setOnCheckedChangeListener
            if (v != AppSettings.voiceBackend(this)) {
                VcLog.d(TAG, "voice backend -> $v")
                AppSettings.setVoiceBackend(this, v)
            }
            refreshBackend()
        }
    }

    private fun refreshBackend() {
        val text = findViewById<TextView>(R.id.backendDescription)
        val desc = try { VoiceBackends.describe(this) } catch (e: Exception) { "Unknown (${e.message})" }
        text.text = "In use now: $desc"
        text.setTextColor(getColor(if (VoiceBackends.resolve(this).isEmpty()) R.color.vc_warn else R.color.vc_text_secondary))
    }

    private fun isAccessibilityEnabled(): Boolean {
        if (Device.isReady) return true
        val ours = ComponentName(packageName, ACCESSIBILITY_SERVICE_CLASS)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.split(':').any { ComponentName.unflattenFromString(it.trim()) == ours }
    }

    private fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionResult(permission: String, granted: Boolean) {
        VcLog.d(TAG, "permission $permission granted=$granted")
        if (!granted && !shouldShowRequestPermissionRationale(permission)) {
            // "Don't ask again" (or denied twice): the only way left is the app's settings page.
            Toast.makeText(this, "Enable it in the app's permission settings", Toast.LENGTH_LONG).show()
            launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        refreshChecklist()
    }

    private fun openOverlaySettings() {
        launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            VcLog.w(TAG, "no activity for ${intent.action}")
            Toast.makeText(this, "Can't open settings on this device", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------------------------------------------------------- overlay

    private fun setupOverlayButton() {
        overlayButton.setOnClickListener {
            if (OverlayService.running.value) {
                VcLog.d(TAG, "Stop overlay clicked")
                OverlayService.stop(this)
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_SHORT).show()
                openOverlaySettings()
            } else {
                VcLog.d(TAG, "Start overlay clicked")
                if (!OverlayService.start(this)) {
                    Toast.makeText(this, "Android refused to start the bubble; try again", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateOverlayButton(running: Boolean) {
        overlayButton.setText(if (running) R.string.stop_overlay else R.string.start_overlay)
        overlayStatus.text = if (running) "Bubble is running. You can close this screen." else "Bubble is off."
    }

    // ---------------------------------------------------------------- settings

    private fun setupSettings() {
        val header = findViewById<View>(R.id.settingsHeader)
        val body = findViewById<View>(R.id.settingsBody)
        val chevron = findViewById<TextView>(R.id.settingsChevron)
        header.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            chevron.text = if (show) "▾" else "▸"
        }

        val openAi = findViewById<EditText>(R.id.openAiKeyInput)
        val model = findViewById<EditText>(R.id.modelInput)
        // Prefill first, attach the save-watchers after, so prefilling doesn't write defaults into prefs.
        openAi.setText(AppSettings.openAiApiKey(this))
        model.setText(AppSettings.realtimeModel(this))
        openAi.addTextChangedListener(saver { AppSettings.setOpenAiApiKey(this, it); refreshChecklist() })
        model.addTextChangedListener(saver { AppSettings.setRealtimeModel(this, it) })

        findViewById<CheckBox>(R.id.showKeysCheck).setOnCheckedChangeListener { _, show ->
            val type = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            for (f in listOf(openAi)) {
                val sel = f.selectionEnd
                f.inputType = type
                if (sel >= 0) f.setSelection(sel)
            }
        }
    }

    private fun saver(save: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = save(s?.toString() ?: "")
    }

    // ---------------------------------------------------------------- test box

    private fun setupTestBox() {
        findViewById<Button>(R.id.runButton).setOnClickListener { runTypedTask() }
        taskInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { runTypedTask(); true } else false
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            log("ui     stop pressed")
            TaskController.stop()
        }
        findViewById<Button>(R.id.clearLogButton).setOnClickListener {
            logBuffer.clear()
            logText.text = ""
        }
    }

    private fun runTypedTask() {
        val task = taskInput.text.toString().trim()
        if (task.isEmpty()) {
            Toast.makeText(this, R.string.toast_enter_task, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(taskInput.windowToken, 0)
        log("ui     run \"$task\"")
        try {
            TaskController.runTask(task)
        } catch (t: Throwable) {
            VcLog.e(TAG, "runTask failed", t)
            log("ui     runTask threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun log(line: String) {
        val stamped = "${timeFormat.format(Date())}  $line"
        logBuffer.addLast(stamped)
        while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        logText.text = logBuffer.joinToString("\n")
        // scrollTo, not fullScroll: fullScroll would also scroll the outer page to the log.
        logScroll.post { logScroll.scrollTo(0, maxOf(0, logText.height - logScroll.height + logScroll.paddingTop + logScroll.paddingBottom)) }
    }

    private fun describe(s: AgentState): String = when (s) {
        AgentState.Idle -> "idle"
        is AgentState.Running ->
            "step ${s.step} · ${s.action} ${s.detail}".trimEnd() + " (conf %.2f)".format(Locale.US, s.confidence)
        is AgentState.Finished ->
            "finished [${s.status}] ${s.message} · ${s.steps} steps · $%.4f".format(Locale.US, s.costUsd)
    }

    private fun describe(s: VoiceState): String = when (s) {
        VoiceState.Idle -> "idle"
        VoiceState.Connecting -> "connecting"
        VoiceState.Listening -> "listening"
        is VoiceState.UserSaid -> "user said \"${s.transcript}\""
        is VoiceState.AssistantSaying -> "assistant: \"${s.transcript}\""
        is VoiceState.Error -> "ERROR ${s.message}"
    }

    // ---------------------------------------------------------------- JEV API key (T7)

    private fun addJevKeyRow(container: LinearLayout) {
        val v = layoutInflater.inflate(R.layout.item_check_row, container, false)
        v.findViewById<TextView>(R.id.checkTitle).setText(R.string.check_jev_title)
        v.findViewById<TextView>(R.id.checkDetail).setText(R.string.check_jev_detail)
        container.addView(v)
        rows += CheckRow(
            optional = { false }, isOk = { JevKeyActivity.isKeyOk(this) },
            actionLabel = R.string.action_add, action = { JevKeyActivity.start(this) }, view = v,
            detail = { JevKeyActivity.statusLine(this) }, okLabel = R.string.action_change,
        )
    }

    override fun onPostResume() {
        super.onPostResume()
        maybeAskForJevKey()
    }

    /** First run: once voice is set up (ChatGPT sign-in done or an OpenAI key set), open the key screen once. */
    private fun maybeAskForJevKey() {
        if (JevKeyActivity.hasKey(this) || AppSettings.jevKeyPrompted(this)) return
        if (!ChatGptAuth.isSignedIn(this) && AppSettings.openAiApiKey(this).isBlank()) return
        AppSettings.setJevKeyPrompted(this)
        VcLog.i(TAG, "first run: no JEV key yet, opening JevKeyActivity")
        JevKeyActivity.start(this)
    }

    companion object {
        private const val TAG = "UI"
        private const val ACCESSIBILITY_SERVICE_CLASS = "com.brainstormity.voicecontrol.control.PhoneAccessibilityService"
        private const val MAX_LOG_LINES = 300
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        /** Survives Activity recreation (rotation) for the life of the process. */
        private val logBuffer = ArrayDeque<String>()
        /** The automatic sign-in screen was already offered in this process. */
        private var onboardingShown = false
    }
}

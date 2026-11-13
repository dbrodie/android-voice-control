package com.brainstormity.voicecontrol.ui

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.brainstormity.voicecontrol.BuildConfig
import com.brainstormity.voicecontrol.R
import com.brainstormity.voicecontrol.agent.JevKeyProbe
import com.brainstormity.voicecontrol.core.AppSettings
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Lets the user enter their own JEV (TypeSafe System One) API key: explains what it is and where to get one,
 * verifies a pasted key with one tiny live request ([JevKeyProbe]) and stores it encrypted
 * ([AppSettings.setJevApiKey]). The full key is never shown again or logged, only [JevKeyProbe.mask].
 */
class JevKeyActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var field: EditText
    private lateinit var current: TextView
    private lateinit var lastCheck: TextView
    private lateinit var result: TextView
    private lateinit var verify: Button
    private lateinit var saveAnyway: Button
    private lateinit var remove: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_jev_key)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.jevRoot)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        field = findViewById(R.id.jevKeyField)
        current = findViewById(R.id.jevCurrent)
        lastCheck = findViewById(R.id.jevLastCheck)
        result = findViewById(R.id.jevResult)
        verify = findViewById(R.id.jevVerify)
        saveAnyway = findViewById(R.id.jevSaveAnyway)
        remove = findViewById(R.id.jevRemove)

        findViewById<Button>(R.id.jevOpenConsole).setOnClickListener { open(KEYS_URL) }
        findViewById<Button>(R.id.jevOpenSite).setOnClickListener { open(SITE_URL) }
        findViewById<Button>(R.id.jevPaste).setOnClickListener { paste() }
        findViewById<CheckBox>(R.id.jevShowKey).setOnCheckedChangeListener { _, show ->
            val sel = field.selectionEnd
            field.inputType = InputType.TYPE_CLASS_TEXT or
                if (show) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            if (sel >= 0) field.setSelection(minOf(sel, field.length()))
        }
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { verifyAndSave(); true } else false
        }
        verify.setOnClickListener { verifyAndSave() }
        saveAnyway.setOnClickListener { saveUnverified() }
        remove.setOnClickListener {
            AppSettings.setJevApiKey(this, "")
            VcLog.i(TAG, "JEV key removed")
            showResult("Key removed.", R.color.vc_text_secondary)
            refresh()
        }
        refresh()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refresh() {
        val stored = AppSettings.storedJevApiKey(this)
        current.text = when {
            stored != null -> "Saved: ${JevKeyProbe.mask(stored)}"
            BuildConfig.JEV_KEY.isNotBlank() -> "None saved. This debug build has a built-in key " +
                "(${JevKeyProbe.mask(BuildConfig.JEV_KEY)}) from .env."
            else -> "No key yet. Voice Control can't do anything on the phone until you add one."
        }
        current.setTextColor(getColor(if (stored == null && BuildConfig.JEV_KEY.isBlank()) R.color.vc_warn else R.color.vc_text))
        val check = AppSettings.jevKeyCheck(this)
        lastCheck.visibility = if (check != null && stored != null) View.VISIBLE else View.GONE
        if (check != null) lastCheck.text = "Last check: ${check.first} (${formatTime(check.second)})"
        remove.visibility = if (stored != null) View.VISIBLE else View.GONE
    }

    private fun enteredKey(): String = JevKeyProbe.normalize(field.text?.toString().orEmpty())

    private fun verifyAndSave() {
        val key = enteredKey()
        if (key.isEmpty()) {
            showResult("Paste your API key first.", R.color.vc_warn)
            return
        }
        hideKeyboard()
        verify.isEnabled = false
        saveAnyway.visibility = View.GONE
        showResult("Checking the key with TypeSafe…", R.color.vc_text_secondary)
        VcLog.i(TAG, "verifying JEV key ${JevKeyProbe.mask(key)} (${key.length} chars)")
        scope.launch {
            val r = withContext(Dispatchers.IO) { JevKeyProbe.probe(key, log = { VcLog.i(TAG, "probe: $it") }) }
            verify.isEnabled = true
            when (r) {
                is JevKeyProbe.Result.Valid -> {
                    AppSettings.setJevApiKey(this@JevKeyActivity, key)
                    AppSettings.setJevKeyCheck(this@JevKeyActivity, "valid (HTTP ${r.httpCode})")
                    field.setText("")
                    VcLog.i(TAG, "JEV key valid (HTTP ${r.httpCode}); saved")
                    showResult("✓ The key works (HTTP ${r.httpCode}) and is saved.", R.color.vc_ok)
                }
                is JevKeyProbe.Result.Invalid -> {
                    VcLog.w(TAG, "JEV key rejected (HTTP ${r.httpCode}); not saved")
                    showResult("✗ Invalid key: TypeSafe rejected it (HTTP ${r.httpCode}: ${r.detail.trimEnd('.')}). " +
                        "Check that you copied the whole key. Nothing was saved.", R.color.vc_error)
                }
                is JevKeyProbe.Result.Unexpected -> {
                    VcLog.w(TAG, "JEV key check inconclusive (HTTP ${r.httpCode})")
                    showResult("TypeSafe answered HTTP ${r.httpCode}: ${r.detail.trimEnd('.')}. Can't tell whether the key " +
                        "is right; try again later, or save it without verifying.", R.color.vc_warn)
                    saveAnyway.visibility = View.VISIBLE
                }
                is JevKeyProbe.Result.NetworkError -> {
                    showResult("Couldn't reach TypeSafe (${r.message}). Check the internet connection and try " +
                        "again, or save the key without verifying.", R.color.vc_warn)
                    saveAnyway.visibility = View.VISIBLE
                }
            }
            refresh()
        }
    }

    private fun saveUnverified() {
        val key = enteredKey()
        if (key.isEmpty()) return
        AppSettings.setJevApiKey(this, key)
        AppSettings.setJevKeyCheck(this, "not verified (saved without a check)")
        field.setText("")
        saveAnyway.visibility = View.GONE
        VcLog.i(TAG, "JEV key saved without verification")
        showResult("Saved without verifying.", R.color.vc_text_secondary)
        refresh()
    }

    private fun showResult(text: String, color: Int) {
        result.visibility = View.VISIBLE
        result.text = text
        result.setTextColor(getColor(color))
    }

    private fun paste() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (text.isBlank()) {
            Toast.makeText(this, "The clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        field.setText(JevKeyProbe.normalize(text))
        field.setSelection(field.length())
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No browser to open $url", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "JevKey"
        const val KEYS_URL = "https://console.typesafe.ai/keys"
        const val SITE_URL = "https://typesafe.ai"

        fun start(ctx: Context) {
            ctx.startActivity(Intent(ctx, JevKeyActivity::class.java))
        }

        /** True when the agent has a key (entered in the app, or built into a debug build). */
        fun hasKey(ctx: Context): Boolean = AppSettings.jevApiKey(ctx).isNotBlank()

        /** For the Setup row: a key is there and JEV didn't reject it since it was saved. */
        fun isKeyOk(ctx: Context): Boolean =
            hasKey(ctx) && AppSettings.jevKeyCheck(ctx)?.first?.startsWith(REJECTED_PREFIX) != true

        /** [AppSettings.setJevKeyCheck] text TaskController stores when JEV answers 401/403 mid-task. */
        const val REJECTED_PREFIX = "Rejected by JEV"

        /** One line for MainActivity's Setup row. */
        fun statusLine(ctx: Context): String {
            val stored = AppSettings.storedJevApiKey(ctx)
            return when {
                stored != null -> {
                    val check = AppSettings.jevKeyCheck(ctx)?.first
                    "Saved: ${JevKeyProbe.mask(stored)}" + (check?.let { " · $it" } ?: "")
                }
                BuildConfig.JEV_KEY.isNotBlank() -> ctx.getString(R.string.check_jev_builtin)
                else -> ctx.getString(R.string.check_jev_detail)
            }
        }

        private fun formatTime(ms: Long): String =
            if (ms <= 0) "unknown time" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
    }
}

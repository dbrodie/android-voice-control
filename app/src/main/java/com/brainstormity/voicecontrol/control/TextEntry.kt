package com.brainstormity.voicecontrol.control

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.inputmethod.EditorInfo
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.delay

/**
 * typeText / enter through the accessibility node tree (no keyboard needed, any unicode works).
 *
 * typeText: ACTION_SET_TEXT(old + new) on the focused editable node -> clipboard + ACTION_PASTE ->
 * (API 33+) commitText through the accessibility InputMethod.
 * enter: ACTION_IME_ENTER on the focused node -> (API 33+) InputMethod editor action / KEYCODE_ENTER ->
 * a node action labelled search/send/go/done.
 */
internal class TextEntry(private val service: AccessibilityService) {

    // ---------------------------------------------------------------- typeText

    suspend fun typeText(text: String) {
        if (text.isEmpty()) {
            VcLog.d(TAG, "typeText(\"\"): nothing to type")
            return
        }
        val node = awaitFocusedEditable()
        if (node == null) VcLog.w(TAG, "typeText: no focused editable node found")

        if (node != null && setTextAppend(node, text)) return
        // A field that has only just gained focus sometimes rejects SET_TEXT for a moment; retry once
        // before falling back to the clipboard (which overwrites the user's clipboard).
        var target = node
        if (node != null) {
            delay(300)
            target = focusedEditable() ?: node
            if (setTextAppend(target, text)) return
        }
        if (pasteInto(target ?: service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT), text)) return
        if (commitViaInputMethod(text)) return

        throw IllegalStateException(
            if (node == null) "typeText: no focused text field (tap the field first)"
            else "typeText: the focused field rejected SET_TEXT and PASTE"
        )
    }

    private fun currentText(node: AccessibilityNodeInfo): String {
        if (node.isShowingHintText) return ""
        val t = node.text?.toString() ?: return ""
        // Some (Compose/WebView) fields report the hint as text without setting isShowingHintText.
        val hint = node.hintText?.toString()
        if (hint != null && t == hint) return ""
        return t
    }

    private suspend fun setTextAppend(node: AccessibilityNodeInfo, text: String): Boolean {
        val supported = node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
        if (!supported) {
            VcLog.d(TAG, "typeText: focused node (${describe(node)}) has no ACTION_SET_TEXT")
            return false
        }
        val old = currentText(node)
        val wanted = old + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            VcLog.w(TAG, "typeText: ACTION_SET_TEXT returned false on ${describe(node)}")
            return false
        }
        // The app accepted the action. Web content (Chrome/WebView) applies it asynchronously and may read
        // back the old text for a while, so a readback mismatch is only logged: falling back to paste here
        // would type the text twice (seen on the emulator with Chrome).
        var now = old
        for (attempt in 0 until 4) {
            if (attempt > 0) delay(100)
            node.refresh()
            now = currentText(node)
            if (now != old) break
        }
        when {
            now == wanted -> VcLog.i(TAG, "typeText: SET_TEXT ok (${old.length}+${text.length} chars) on ${describe(node)}")
            now != old -> VcLog.i(TAG, "typeText: SET_TEXT ok but reads back differently (masked/filtered?): " +
                "${now.length} chars, wanted ${wanted.length} on ${describe(node)}")
            else -> VcLog.w(TAG, "typeText: SET_TEXT accepted but not visible yet (async field?) on ${describe(node)}")
        }
        // Keep the cursor at the end so a following typeText/enter behaves like a keyboard would.
        val len = if (now == old) wanted.length else now.length
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, len)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, len)
        })
        return true
    }

    private suspend fun pasteInto(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_PASTE }) {
            VcLog.d(TAG, "typeText: ${describe(node)} has no ACTION_PASTE")
            return false
        }
        val clipboard = service.getSystemService(ClipboardManager::class.java) ?: return false
        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("voice-control", text))
            delay(50) // let the clipboard change propagate before the target app reads it
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (ok) VcLog.i(TAG, "typeText: clipboard PASTE ok (${text.length} chars) into ${describe(node)}")
            else VcLog.w(TAG, "typeText: ACTION_PASTE returned false on ${describe(node)}")
            ok
        } catch (e: Exception) {
            VcLog.w(TAG, "typeText: clipboard paste failed", e)
            false
        }
    }

    private fun commitViaInputMethod(text: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val ic = service.inputMethod?.currentInputConnection
        if (ic == null) {
            VcLog.d(TAG, "typeText: no accessibility input connection (no field has IME focus)")
            return false
        }
        ic.commitText(text, 1, null)
        VcLog.i(TAG, "typeText: committed ${text.length} chars via accessibility InputMethod")
        return true
    }

    // ---------------------------------------------------------------- enter

    fun enter() {
        val node = focusedEditable() ?: service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node != null) {
            val imeEnter = AccessibilityAction.ACTION_IME_ENTER
            if (node.actionList.any { it.id == imeEnter.id }) {
                if (node.performAction(imeEnter.id)) {
                    VcLog.i(TAG, "enter: ACTION_IME_ENTER ok on ${describe(node)}")
                    return
                }
                VcLog.w(TAG, "enter: ACTION_IME_ENTER returned false on ${describe(node)}")
            } else {
                VcLog.d(TAG, "enter: ${describe(node)} does not offer ACTION_IME_ENTER")
            }
        } else {
            VcLog.d(TAG, "enter: no focused node")
        }

        if (enterViaInputMethod()) return

        if (node != null) {
            val labelled = node.actionList.firstOrNull { a ->
                val label = a.label?.toString() ?: return@firstOrNull false
                ENTER_LABEL.containsMatchIn(label)
            }
            if (labelled != null && node.performAction(labelled.id)) {
                VcLog.i(TAG, "enter: performed node action \"${labelled.label}\" on ${describe(node)}")
                return
            }
        }
        throw IllegalStateException("enter: no focused field accepted IME enter")
    }

    private fun enterViaInputMethod(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val im = service.inputMethod ?: return false
        val ic = im.currentInputConnection ?: return false
        val info = im.currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noEnterAction = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action)
            VcLog.i(TAG, "enter: InputMethod performEditorAction($action)")
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            VcLog.i(TAG, "enter: InputMethod KEYCODE_ENTER")
        }
        return true
    }

    // ---------------------------------------------------------------- focus lookup

    /**
     * A tap on a field often opens a new screen (e.g. a search activity) whose field gains focus a moment
     * later, so poll briefly instead of failing on the first miss.
     */
    private suspend fun awaitFocusedEditable(): AccessibilityNodeInfo? {
        val deadline = SystemClock.elapsedRealtime() + FOCUS_WAIT_MS
        while (true) {
            focusedEditable()?.let { return it }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            delay(250)
        }
    }

    /** The input-focused editable node, searching all interactive windows if needed. */
    fun focusedEditable(): AccessibilityNodeInfo? {
        val t0 = SystemClock.elapsedRealtime()
        val focus = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val t1 = SystemClock.elapsedRealtime()
        if (focus != null && focus.isEditable) {
            VcLog.d(TAG, "focusedEditable: findFocus -> ${describe(focus)} (${t1 - t0}ms)")
            return focus
        }
        VcLog.d(TAG, "focusedEditable: findFocus -> ${focus?.let { describe(it) + " (not editable)" }} (${t1 - t0}ms); scanning windows")
        val roots = try {
            service.windows.mapNotNull { it.root }
        } catch (e: Exception) {
            emptyList()
        }.ifEmpty { listOfNotNull(service.rootInActiveWindow) }
        for (root in roots) {
            findFocusedEditable(root, 0)?.let {
                VcLog.d(TAG, "focusedEditable: scan -> ${describe(it)} (${SystemClock.elapsedRealtime() - t0}ms)")
                return it
            }
        }
        VcLog.d(TAG, "focusedEditable: none in ${roots.size} windows (${SystemClock.elapsedRealtime() - t0}ms)")
        return null
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        if (depth > 60) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFocusedEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun describe(n: AccessibilityNodeInfo): String =
        "${n.className?.toString()?.substringAfterLast('.')}(${n.viewIdResourceName ?: n.packageName})"

    companion object {
        private const val TAG = "Hands"
        private const val FOCUS_WAIT_MS = 1500L
        private val ENTER_LABEL = Regex("""\b(search|send|go|done|submit|enter)\b""", RegexOption.IGNORE_CASE)
    }
}

package com.brainstormity.voicecontrol.control

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.brainstormity.voicecontrol.core.OverlayInfo
import com.brainstormity.voicecontrol.core.ScreenSize
import com.brainstormity.voicecontrol.core.UiNode
import com.brainstormity.voicecontrol.core.UiTree
import com.brainstormity.voicecontrol.core.UiWindow
import com.brainstormity.voicecontrol.core.VcLog

/**
 * Raw accessibility-tree snapshot for [com.brainstormity.voicecontrol.core.Screen.uiTree]: every window from
 * [AccessibilityService.getWindows] (needs flagRetrieveInteractiveWindows) and every node under each root,
 * pre-order with parent indices, nothing filtered except our own overlay.
 *
 * Budgets: at most [MAX_NODES] nodes, [MAX_DEPTH] levels and [BUDGET_MS] of wall time; when a budget runs out the
 * walk stops and what was collected so far is returned (logged as truncated). Every getChild/getRoot is a blocking
 * IPC into the owning app, so call this off the main thread. Never throws: returns null and logs under VC/Tree.
 *
 * Our overlay is masked: non-application windows whose root belongs to our package (the bubble / strip / stop
 * windows) and any window whose bounds equal one of [OverlayInfo.regions] are dropped with all their nodes, so the
 * tree matches the masked screenshot. Our own activities (type application) are kept.
 *
 * Chrome (and other Chromium WebViews) build the web-content subtree lazily, on the first request from an enabled
 * service; if a browser window's tree has a WebView with no children, the capture waits [LAZY_RETRY_MS] and
 * re-reads once.
 */
internal class UiTreeCapture(
    private val service: AccessibilityService,
    private val screenSize: () -> ScreenSize,
    /** Class name of the last activity-like TYPE_WINDOW_STATE_CHANGED event from this package, or null. */
    private val lastActivity: (String) -> String?,
) {

    /** Last capture's stats, for logs/debug hooks. */
    @Volatile var lastStats: String = ""
        private set

    fun capture(): UiTree? = try {
        val first = captureOnce()
        if (first != null && needsLazyRetry(first)) {
            VcLog.d(TAG, "web content not built yet (empty WebView in ${first.packageName}); re-reading in ${LAZY_RETRY_MS}ms")
            Thread.sleep(LAZY_RETRY_MS)
            captureOnce() ?: first
        } else first
    } catch (e: Throwable) {
        VcLog.e(TAG, "uiTree capture failed: $e", e)
        null
    }

    private fun captureOnce(): UiTree? {
        val t0 = SystemClock.elapsedRealtime()
        val deadline = t0 + BUDGET_MS
        val screen = screenSize()
        val ownPkg = service.packageName
        val overlayRects = OverlayInfo.regions.value.map { it.rect }
        val windowInfos = try {
            service.windows
        } catch (e: Exception) {
            VcLog.w(TAG, "getWindows failed: $e")
            emptyList()
        }
        val windows = ArrayList<UiWindow>(windowInfos.size)
        val nodes = ArrayList<UiNode>(512)
        var truncated: String? = null
        var droppedOverlay = 0
        var keyboard = false
        var activePkg: String? = null
        var firstAppPkg: String? = null
        val rect = Rect()

        for (w in windowInfos) {
            val type = typeName(w.type)
            w.getBoundsInScreen(rect)
            val wBounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom)
            val root: AccessibilityNodeInfo? = try { w.root } catch (e: Exception) { null }
            val rootPkg = root?.packageName?.toString()
            val isOurOverlay = (type != "application" && rootPkg == ownPkg) || overlayRects.any { it == rect }
            if (isOurOverlay) {
                droppedOverlay++
                root?.recycleCompat()
                continue
            }
            if (type == "input_method") keyboard = true
            windows += UiWindow(w.id, type, w.layer, w.isActive, w.isFocused, wBounds)
            if (type == "application" && rootPkg != null) {
                if (w.isActive && activePkg == null) activePkg = rootPkg
                if (firstAppPkg == null) firstAppPkg = rootPkg
            }
            if (root == null) continue
            if (truncated != null) { root.recycleCompat(); continue }
            truncated = walk(root, w.id, nodes, deadline)
        }
        // Old-API WindowInfos are pooled too.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            windowInfos.forEach { runCatching { it.recycle() } }
        }

        val pkg = activePkg ?: firstAppPkg ?: try {
            service.rootInActiveWindow?.let { r -> r.packageName?.toString().also { r.recycleCompat() } }
        } catch (e: Exception) { null } ?: ""
        val activity = if (pkg.isEmpty()) "" else lastActivity(pkg) ?: ""
        val ms = SystemClock.elapsedRealtime() - t0
        lastStats = "pkg=$pkg windows=${windows.size} nodes=${nodes.size} ms=$ms keyboard=$keyboard" +
            (if (droppedOverlay > 0) " overlayWindowsDropped=$droppedOverlay" else "") +
            (truncated?.let { " TRUNCATED($it)" } ?: "")
        VcLog.d(TAG, "uiTree: $lastStats")
        if (windows.isEmpty() && nodes.isEmpty()) return null
        return UiTree(
            packageName = pkg, activity = activity, screen = screen, keyboardShown = keyboard,
            takenAt = System.currentTimeMillis(), windows = windows, nodes = nodes,
        )
    }

    /** Pre-order walk of one window. Returns null when complete, else why it stopped. Recycles every node it got. */
    private fun walk(root: AccessibilityNodeInfo, windowId: Int, out: MutableList<UiNode>, deadline: Long): String? {
        // Stack entries: node, parent index, depth. Children pushed in reverse so they pop in order.
        val stack = ArrayDeque<Triple<AccessibilityNodeInfo, Int, Int>>()
        stack.addLast(Triple(root, -1, 0))
        val r = Rect()
        var stopped: String? = null
        while (stack.isNotEmpty()) {
            val (node, parent, depth) = stack.removeLast()
            if (stopped == null) {
                stopped = when {
                    out.size >= MAX_NODES -> "nodes>=$MAX_NODES"
                    SystemClock.elapsedRealtime() > deadline -> "time>${BUDGET_MS}ms"
                    else -> null
                }
            }
            if (stopped != null) { node.recycleCompat(); continue }
            try {
                val index = out.size
                node.getBoundsInScreen(r)
                out += UiNode(
                    id = index, parent = parent, window = windowId,
                    className = node.className?.toString() ?: "",
                    text = node.text?.toString() ?: "",
                    desc = node.contentDescription?.toString() ?: "",
                    hint = node.hintText?.toString() ?: "",
                    resId = node.viewIdResourceName ?: "",
                    bounds = intArrayOf(r.left, r.top, r.right, r.bottom),
                    clickable = node.isClickable, longClickable = node.isLongClickable,
                    editable = node.isEditable, scrollable = node.isScrollable,
                    checkable = node.isCheckable, checked = node.isChecked, selected = node.isSelected,
                    enabled = node.isEnabled, focused = node.isFocused, password = node.isPassword,
                    visible = node.isVisibleToUser,
                )
                if (depth < MAX_DEPTH) {
                    for (c in node.childCount - 1 downTo 0) {
                        val child = try { node.getChild(c) } catch (e: Exception) { null } ?: continue
                        stack.addLast(Triple(child, index, depth + 1))
                    }
                }
            } catch (e: Exception) {
                VcLog.w(TAG, "node read failed (window $windowId): $e") // stale node: skip its subtree
            } finally {
                node.recycleCompat()
            }
        }
        return stopped
    }

    /** A browser window whose WebView has no children yet (Chromium builds the web tree lazily). */
    private fun needsLazyRetry(tree: UiTree): Boolean {
        if (tree.packageName !in BROWSERS) return false
        val hasChild = BooleanArray(tree.nodes.size)
        tree.nodes.forEach { if (it.parent >= 0) hasChild[it.parent] = true }
        return tree.nodes.any { it.className == "android.webkit.WebView" && !hasChild[it.id] }
    }

    companion object {
        private const val TAG = "Tree"
        const val MAX_NODES = 3000
        const val MAX_DEPTH = 64
        const val BUDGET_MS = 300L
        const val LAZY_RETRY_MS = 300L
        private val BROWSERS = setOf("com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary")

        fun typeName(type: Int): String = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
            6 /* TYPE_MAGNIFICATION_OVERLAY, API 33 */ -> "magnification_overlay"
            else -> "unknown"
        }

        /** recycle() is required below API 33 (pooled objects) and a no-op from 33 on. */
        private fun AccessibilityNodeInfo.recycleCompat() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                runCatching { recycle() }
            }
        }
    }
}

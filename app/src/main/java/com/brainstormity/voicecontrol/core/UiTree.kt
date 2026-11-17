package com.brainstormity.voicecontrol.core

/**
 * A snapshot of the accessibility node tree of everything on screen, taken at the same moment as a screenshot.
 * Raw: every node the AccessibilityService can see, pre-order, with a parent index; nothing filtered. Backends
 * (e.g. the on-device Laya model) pick their own candidates from it; the Runs screen can show it.
 *
 * JSON form (shared with the Python reference tooling in tools/laya and the DUMP_TREE debug broadcast):
 * ```
 * {"package":"com.android.settings","activity":"com.android.settings.Settings","screen":{"w":1080,"h":2400},
 *  "keyboard_shown":false,"taken_at":1731800000000,
 *  "windows":[{"id":1,"type":"application","layer":0,"active":true,"focused":true,"bounds":[0,0,1080,2400]}],
 *  "nodes":[{"id":0,"parent":-1,"window":1,"class":"android.widget.FrameLayout","text":"","desc":"","hint":"",
 *            "res_id":"","bounds":[0,0,1080,2400],"clickable":false,"long_clickable":false,"editable":false,
 *            "scrollable":false,"checkable":false,"checked":false,"selected":false,"enabled":true,
 *            "focused":false,"password":false,"visible":true}]}
 * ```
 * Field names in JSON are exactly the snake_case names above; Kotlin uses camelCase. Bounds are screen pixels
 * [left, top, right, bottom]. `window` on a node is the id of the window it belongs to.
 */
data class UiTree(
    val packageName: String,
    val activity: String,
    val screen: ScreenSize,
    val keyboardShown: Boolean,
    val takenAt: Long,
    val windows: List<UiWindow>,
    val nodes: List<UiNode>,
)

/** One AccessibilityWindowInfo. [type] is one of application, input_method, system, accessibility_overlay, split_screen_divider, magnification_overlay, unknown. */
data class UiWindow(
    val id: Int,
    val type: String,
    val layer: Int,
    val active: Boolean,
    val focused: Boolean,
    val bounds: IntArray,
)

/** One AccessibilityNodeInfo. [parent] is the index of the parent node in [UiTree.nodes], -1 for a root. */
data class UiNode(
    val id: Int,
    val parent: Int,
    val window: Int,
    val className: String,
    val text: String,
    val desc: String,
    val hint: String,
    val resId: String,
    val bounds: IntArray,
    val clickable: Boolean,
    val longClickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val focused: Boolean,
    val password: Boolean,
    val visible: Boolean,
) {
    val left: Int get() = bounds[0]
    val top: Int get() = bounds[1]
    val right: Int get() = bounds[2]
    val bottom: Int get() = bounds[3]
}

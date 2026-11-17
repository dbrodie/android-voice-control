package com.brainstormity.voicecontrol.agent

import android.content.Context
import com.brainstormity.voicecontrol.core.UiTree
import java.io.IOException

/**
 * The "System One" seam (T15): the model that decides every step of a task. [TaskRunner] talks only to this
 * interface; [JevSystemOne] wraps the cloud JEV client, an on-device backend (Laya, T17) plugs in through
 * [SystemOneBackends.register].
 *
 * Contract for implementations:
 *  - [ask] and [refine] are blocking and are called on a background thread (Dispatchers.IO). They may throw a
 *    [SystemOneException] whose message is a user-facing sentence: the run ends with status "error" and that
 *    message (shown on the overlay, spoken by GPT).
 *  - Boxes are in 0..1 coordinates of the screenshot ([StepInput.screenshotW] x [StepInput.screenshotH]), which
 *    covers the whole screen, so they are also 0..1 of the real screen (UiTree bounds are real screen pixels:
 *    divide by [UiTree.screen]).
 *  - Choices use the same keys JEV uses: `action.choice` is a key of AgentRules.PHONE_ACTIONS (only the ones
 *    JevClient.offeredActions offers this step), `text` / `app` / `website` choices are the index ("0", "1", ...)
 *    into [StepInput.texts] / [StepInput.apps] / [StepInput.urls]. `probabilities` should cover every offered
 *    action (the Runs screen shows them and AgentRules.splitChain uses them).
 *  - Confidence gating is the runner's: low `action.confidence` stops the run as "unsure"; a target confidence
 *    below AgentRules.CLOSE_UP_BELOW triggers [refine] (tree backends: answer 1.0 for an element's bounds).
 */
interface SystemOne {
    /** Stable id, stored in settings and run reports: "jev", "laya". */
    val id: String
    /** Name used in user-facing sentences ("JEV was only 40% sure ...") and on the Runs screen chip. */
    val displayName: String
    /** USD per million input tokens; 0.0 for on-device (runs then cost $0). */
    val pricePerMillionInput: Double
    /**
     * True if [StepInput.uiTree] should be filled. The tree is captured in the same `grab()` as the screenshot
     * (every poll while waiting for the screen to settle), so pixel-only backends leave this false.
     */
    val wantsUiTree: Boolean get() = false

    /** Can it run right now? Cheap; called on the main thread by the settings screen and at every runTask. */
    fun availability(ctx: Context): Availability

    /**
     * A Context-free last check right before the loop starts (TaskRunner has no Context). Non-null = the run
     * ends with status "error" and this message before any step. JEV: the no-key message.
     */
    fun preflight(): String? = null

    /** One step: which action, where, which option. Blocking. */
    fun ask(input: StepInput): StepDecision

    /**
     * Zoomed-in second opinion on where to tap; only pixel backends implement it. [closeupJpeg] is the
     * [closeUp] crop of the screenshot; the returned box is relative to the close-up (TaskRunner maps it back).
     * Null = no refinement (the zoom step is skipped and the first answer stands).
     */
    fun refine(input: StepInput, closeUp: CloseUp, closeupJpeg: ByteArray): Refinement? = null
}

/** Everything a backend gets for one step. The screenshot and [uiTree] were taken at the same moment. */
class StepInput(
    val task: String,
    /** The screenshot (overlay painted out, shrunk to <= 1568 px), JPEG q85. */
    val screenshotJpeg: ByteArray,
    val screenshotW: Int,
    val screenshotH: Int,
    /** Null when the backend doesn't want it ([SystemOne.wantsUiTree]) or the service can't read the tree. */
    val uiTree: UiTree?,
    /** What was done so far ("CLICK at (540, 1200)", "... (nothing changed on screen)"), oldest first. */
    val history: List<String>,
    /** Text options from the task (TaskParser.textOptions): the only text the agent may type. */
    val texts: List<String>,
    val urls: List<String>,
    val apps: List<TaskParser.App>,
)

/**
 * A backend's answer for one step: JevResponse's fields plus where it came from. Answers that don't apply are
 * null. [target] may be null only for actions that don't touch the screen (not in AgentRules.CLICKS).
 */
data class StepDecision(
    val action: JevChoice?,
    val target: JevTarget?,
    val text: JevChoice?,
    val app: JevChoice?,
    val website: JevChoice?,
    val usage: JevUsage = JevUsage(),
    /** Time the backend spent answering (model / network), ms. */
    val latencyMs: Long = 0,
    /** [SystemOne.id] of the backend that answered. */
    val backend: String,
    /** Tree backends: [com.brainstormity.voicecontrol.core.UiNode.id] of the chosen element; null for JEV. */
    val targetNodeId: Int? = null,
    /** Free-form, JSON-serialisable details for the run report (`backend_extras`), e.g. candidate count. */
    val extras: Map<String, Any?> = emptyMap(),
)

/** [SystemOne.refine]'s answer: the box relative to the close-up, plus what it cost. */
data class Refinement(val target: JevTarget, val usage: JevUsage = JevUsage(), val latencyMs: Long = 0)

/** Whether a backend can run now; [message] is the one line the settings screen and a refused runTask show. */
sealed class Availability {
    abstract val message: String
    val isReady: Boolean get() = this is Ready

    data class Ready(override val message: String = "Ready") : Availability()
    /** Needs something from the user (a key, a sign-in). [message] says what. */
    data class NeedsSetup(override val message: String) : Availability()
    /** Model files must be downloaded first ([bytes] in total). */
    data class NeedsDownload(val bytes: Long, override val message: String = defaultDownloadMessage(bytes)) :
        Availability()
    /** Can't run on this phone / build. */
    data class Unsupported(val reason: String) : Availability() {
        override val message: String get() = reason
    }

    companion object {
        fun defaultDownloadMessage(bytes: Long): String =
            "Download the on-device model first (${Math.round(bytes / 1_000_000.0)} MB)"
    }
}

/**
 * A backend failure with a user-facing [message]. [httpCode] for cloud backends' HTTP errors (401/403 = key
 * rejected). [JevException] is a subclass.
 */
open class SystemOneException(message: String, cause: Throwable? = null, val httpCode: Int? = null) :
    IOException(message, cause)

/** A `choice` answer. [choice] is the chosen criteria key (an action name, or "0", "1", ... for options). */
data class JevChoice(val choice: String, val confidence: Double, val probabilities: Map<String, Double>)

/** A `box` answer: the box in 0..1 coordinates of the image, and how sure the backend is about the location. */
data class JevTarget(val box: Box, val confidence: Double)

data class JevUsage(val inputTokens: Long = 0, val outputTokens: Long = 0) {
    operator fun plus(o: JevUsage) = JevUsage(inputTokens + o.inputTokens, outputTokens + o.outputTokens)
    fun toMap(): Map<String, Long> = linkedMapOf("input_tokens" to inputTokens, "output_tokens" to outputTokens)
}

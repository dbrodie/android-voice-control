package com.brainstormity.voicecontrol.agent

import android.graphics.Bitmap
import android.os.Build
import com.brainstormity.voicecontrol.agent.AgentRules.CLICKS
import com.brainstormity.voicecontrol.agent.AgentRules.CLOSE_UP_BELOW
import com.brainstormity.voicecontrol.agent.AgentRules.LOADS_PAGE
import com.brainstormity.voicecontrol.agent.AgentRules.MAX_STEPS
import com.brainstormity.voicecontrol.agent.AgentRules.PHONE_ACTIONS
import com.brainstormity.voicecontrol.agent.AgentRules.costOf
import com.brainstormity.voicecontrol.agent.AgentRules.pct
import com.brainstormity.voicecontrol.agent.AgentRules.pyRepr
import com.brainstormity.voicecontrol.agent.AgentRules.usd
import com.brainstormity.voicecontrol.agent.ScreenDiff.Mask
import com.brainstormity.voicecontrol.agent.ScreenDiff.changedPixels
import com.brainstormity.voicecontrol.agent.ScreenDiff.fraction
import com.brainstormity.voicecontrol.core.AgentState
import com.brainstormity.voicecontrol.core.Hands
import com.brainstormity.voicecontrol.core.Screen
import com.brainstormity.voicecontrol.core.SwipeDirection
import com.brainstormity.voicecontrol.core.VcLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * The loop: a port of `run_task()` in jev_agent.py for the phone path (device="android").
 *
 *   screenshot -> ask JEV (action + target box + text choice) -> confidence gating / CHAIN split ->
 *   zoom-in refine when unsure where -> act via [Hands] -> wait for the screen to react and settle -> repeat
 *
 * Runs on a background thread (blocking JEV calls); [isStopped] is checked where upstream checks `stop`
 * (before each step and right before acting) and also while waiting for the screen to settle.
 */
class TaskRunner(
    private val task: String,
    private val jev: JevClient,
    private val screen: Screen,
    private val hands: Hands,
    private val report: RunReport,
    private val isStopped: () -> Boolean,
    private val onStep: (AgentState.Running) -> Unit = {},
) {
    /** A screenshot shrunk for JEV ([img], <= 1568 px) plus a tiny grayscale copy for change detection. */
    private class Shot(val img: Bitmap, val gray: ScreenDiff.Gray)

    private class Waited(val shot: Shot, val info: Map<String, Any?>, val selfMoving: Mask)

    private val r = report.data
    private val runStarted = now()

    private fun now(): Double = System.nanoTime() / 1e9
    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun log(msg: String) = msg.lines().forEach { VcLog.i(TAG, it) }

    private suspend fun grab(): Shot {
        val img = Images.shrink(screen.screenshot())
        return Shot(img, Images.grayThumb(img))
    }

    private fun steps(): MutableList<MutableMap<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        return r["steps"] as MutableList<MutableMap<String, Any?>>
    }

    private fun save(status: String? = null, message: String? = null): AgentState.Finished {
        r["seconds"] = round1(now() - runStarted)
        if (status != null) {
            r["status"] = status
            if (status != "running") {
                log("Cost: $${usd(r["cost_usd"] as Double)} (${r["input_tokens"]} input tokens), " +
                    "took ${Math.round(r["seconds"] as Double)}s")
            }
        }
        if (message != null) r["message"] = message
        report.save()
        return AgentState.Finished(task, r["status"] as String, r["message"] as String, steps().size,
            r["cost_usd"] as Double)
    }

    private fun addUsage(u: JevUsage): Double {
        r["input_tokens"] = (r["input_tokens"] as Long) + u.inputTokens
        r["output_tokens"] = (r["output_tokens"] as Long) + u.outputTokens
        val cost = costOf(u.inputTokens)
        r["cost_usd"] = (r["cost_usd"] as Double) + cost
        return cost
    }

    /**
     * wait_for_screen(): instead of a fixed pause,
     * 1. wait for the screen to react (up to 1.5 s), so we never act before a page responds;
     * 2. then wait until it holds still for [stillChecks] checks in a row (0.2 s apart).
     * Changes in [movingOnItsOwn] (a blinking cursor, an animated icon) are ignored throughout.
     */
    private suspend fun waitForScreen(
        before: Shot, maxSeconds: Double = 3.0, movingOnItsOwn: Mask? = null, stillChecks: Int = 1,
    ): Waited {
        val started = now()
        val deadline = started + maxSeconds
        val ignore = movingOnItsOwn?.let { ScreenDiff.grow(it) }
        var last = grab()
        while (fraction(changedPixels(before.gray, last.gray), ignore) <= ScreenDiff.REACTED &&
            now() < min(started + 1.5, deadline) && !isStopped()
        ) {
            delay(100)
            last = grab()
        }
        val reacted = fraction(changedPixels(before.gray, last.gray), ignore) > ScreenDiff.REACTED
        var still = 0
        var moving: Map<String, Any?>? = null
        var selfMoving = movingOnItsOwn ?: ScreenDiff.emptyMaskFor(last.gray)
        while (now() < deadline && !isStopped()) {
            delay(200)
            val current = grab()
            val mask = changedPixels(last.gray, current.gray)
            if (fraction(mask, ignore) <= ScreenDiff.STILL_ENOUGH) {
                selfMoving = ScreenDiff.lighter(selfMoving, mask)
                still += 1
                if (still >= stillChecks) {
                    return Waited(current, linkedMapOf("reacted" to reacted, "waited" to round1(now() - started)),
                        selfMoving)
                }
            } else {
                still = 0
                moving = linkedMapOf(
                    "share" to Math.round(fraction(mask, ignore) * 10000) / 10000.0,
                    "box" to ScreenDiff.bbox(mask),
                )
            }
            last = current
        }
        return Waited(last, linkedMapOf("reacted" to reacted, "waited" to round1(now() - started),
            "timed_out" to true, "still_moving" to moving), selfMoving)
    }

    private fun running(step: Int, action: String, detail: String, confidence: Double) =
        onStep(AgentState.Running(task, step, action, detail, confidence.toFloat()))

    /** Runs the task to the end and returns how it finished. Never throws (except on coroutine cancellation). */
    suspend fun run(): AgentState.Finished {
        val texts = TaskParser.textOptions(task)
        val urls = TaskParser.websites(task)
        val quoted = TaskParser.quotedTexts(task)
        r["task"] = task
        r["status"] = "running"
        r["message"] = ""
        r["started"] = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
        r["can_type"] = quoted
        r["websites"] = urls
        r["steps"] = mutableListOf<MutableMap<String, Any?>>()
        r["input_tokens"] = 0L
        r["output_tokens"] = 0L
        r["cost_usd"] = 0.0
        r["device"] = "android"
        save()
        log("Task: $task\nQuoted text: ${quoted.ifEmpty { "-" }}\nWebsites: ${urls.ifEmpty { "-" }}\n" +
            "Report: ${report.dir ?: "(not saved)"}/")
        try {
            if (!jev.hasKey()) throw JevException("No JEV API key: set JEV_KEY in .env or in the app's settings.")
            val size = screen.screenSize()  // taps use real screen pixels
            val screenW = size.width
            val screenH = size.height
            val apps = TaskParser.appsInTask(task, hands.installedApps())
            r["device_name"] = Build.MODEL ?: "Android"
            r["apps"] = apps.map { it.name }
            log("Phone: ${r["device_name"]} ${screenW}x$screenH  Apps: ${apps.map { it.name }.ifEmpty { "-" }}")
            val history = mutableListOf<String>()
            var nextScreen: Shot
            var selfMoving: Mask
            if (apps.isNotEmpty() || urls.isNotEmpty()) {
                // Open the app (or website) named in the task first, like the computer opens the website.
                val before = grab()
                delay(300)
                val movingOnItsOwn = changedPixels(before.gray, grab().gray)
                if (apps.isNotEmpty()) {
                    log("Opening the ${apps[0].name} app")
                    running(0, "OPEN_APP", apps[0].name, 1.0)
                    hands.openApp(apps[0].packageName)
                    history.add("OPEN_APP ${apps[0].name}")
                    r["opened"] = "the ${apps[0].name} app"
                } else {
                    log("Opening ${urls[0]}")
                    running(0, "OPEN_WEBSITE", urls[0], 1.0)
                    hands.openUrl(urls[0])
                    history.add("OPEN_WEBSITE ${urls[0]}")
                    r["opened"] = urls[0]
                }
                save()
                val w = waitForScreen(before, 8.0, movingOnItsOwn, stillChecks = 3)
                nextScreen = w.shot
                selfMoving = w.selfMoving
            } else {
                nextScreen = grab()
                delay(300)
                selfMoving = changedPixels(nextScreen.gray, grab().gray)
            }
            var unchanged = 0  // steps in a row where the screen didn't change
            var editing: Editing? = null  // the text field we last typed into
            var lastMiss: Miss? = null  // the last tap that changed nothing
            for (n in 1..MAX_STEPS) {
                if (isStopped()) return save("stopped", "Stopped by you.")
                val stepStarted = now()
                val shot = nextScreen  // taken when the last action's result settled
                val img = shot.img
                val imgBefore = shot
                val started = now()
                val response = jev.ask(task, Images.jpeg(img, 85), history, texts, urls, apps)
                val actionAnswer = response.action ?: throw JevException("JEV's answer has no action")
                var usage = response.usage
                var stepCost = addUsage(usage)
                val probabilities = actionAnswer.probabilities
                val split = AgentRules.splitChain(actionAnswer.choice, actionAnswer.confidence, probabilities)
                val action = split.action
                val confidence = split.confidence
                var note = split.note

                // Work out what exactly to do.
                val targetAnswer = response.target ?: throw JevException("JEV's answer has no target box")
                var box = targetAnswer.box
                var cx = box.cx
                var cy = box.cy
                var targetConfidence = targetAnswer.confidence
                // Unsure where, or the same spot just did nothing: zoom in and ask again where exactly.
                val keepsTyping = action == "TYPE" && editing?.box?.contains(cx, cy) == true
                val missedHere = lastMiss?.isNear(action, cx, cy) == true
                if (action in CLICKS && !keepsTyping && (targetConfidence < CLOSE_UP_BELOW || missedHere)) {
                    val c = CloseUp.around(box, img.width, img.height)
                    val close = jev.refine(task, Images.jpeg(Images.closeUp(img, c), 90), history)
                    val closeTarget = close.target ?: throw JevException("JEV's close-up answer has no target box")
                    usage += close.usage
                    stepCost += addUsage(close.usage)
                    if (closeTarget.confidence >= targetConfidence || missedHere) {
                        note = (if (note.isNotEmpty()) "$note " else "") +
                            "Zoomed in to find exactly where to tap " +
                            "(location confidence ${pct(targetConfidence)} → ${pct(closeTarget.confidence)})."
                        box = c.mapBack(closeTarget.box)
                        targetConfidence = closeTarget.confidence
                        cx = box.cx
                        cy = box.cy
                    }
                }
                val x = (cx * screenW).toInt()
                val y = (cy * screenH).toInt()
                var text = if (action == "TYPE" || action == "TYPE_ENTER") pick(texts, response.text, "text") else ""
                val url = if (response.website != null) pick(urls, response.website, "website") else urls.firstOrNull() ?: ""
                val app = if (response.app != null) pick(apps, response.app, "app") else apps.firstOrNull()
                val detail = when (action) {
                    "CLICK", "DOUBLE_CLICK", "LONG_PRESS" -> "at ($x, $y)"
                    "OPEN_APP" -> app?.name ?: ""
                    "TYPE" -> "${pyRepr(text)} at ($x, $y)"
                    "TYPE_ENTER" -> "${pyRepr(text)} + Enter at ($x, $y)"
                    "OPEN_WEBSITE" -> url
                    else -> ""
                }
                log("[$n] $action $detail  (confidence ${pct(confidence)}, $${usd(stepCost)})" +
                    if (note.isNotEmpty()) "\n    $note" else "")
                running(n, action, detail, confidence)

                // Save the screenshot with a red box around the target and a circle where we tap.
                val image = String.format(Locale.US, "%02d-%s.jpg", n, action)
                report.saveImage(image, if (action in CLICKS) Images.annotate(img, box, cx, cy) else img)

                val step: MutableMap<String, Any?> = linkedMapOf(
                    "n" to n, "action" to action, "detail" to detail,
                    "why" to (PHONE_ACTIONS[action] ?: "") + if (note.isNotEmpty()) " $note" else "",
                    "confidence" to confidence,
                    "options" to probabilities.entries.sortedByDescending { it.value }.associate { it.key to it.value },
                    "target_box" to if (action in CLICKS) box.toMap() else null,
                    "target_confidence" to targetConfidence,
                    "x" to x, "y" to y, "text" to text, "url" to if (action == "OPEN_WEBSITE") url else "",
                    "image" to image, "jev_seconds" to round1(now() - started),
                    "tokens" to usage.toMap(), "cost_usd" to stepCost, "result" to "",
                )
                steps().add(step)
                save()

                if (confidence < AgentRules.minConfidenceFor(action)) {
                    step["result"] = "Not executed: JEV wasn't sure enough."
                    log("JEV is only ${pct(confidence)} sure. Stopping.")
                    return save("unsure", "JEV was only ${pct(confidence)} sure about $action, so nothing was " +
                        "done. Try making the task more specific.")
                }
                if (action == "DONE") {
                    step["result"] = "Task finished."
                    log("Done.")
                    return save("done", "JEV says the task is finished.")
                }
                if (isStopped()) {
                    step["result"] = "Not executed."
                    return save("stopped", "Stopped by you.")
                }

                // Note what moves on its own (blinking cursor, animations) so we don't mistake it for a reaction.
                val movingOnItsOwn = ScreenDiff.lighter(selfMoving, changedPixels(imgBefore.gray, grab().gray))
                // Typing again into the box we just typed into: keep writing where we left off.
                val sameField = action == "TYPE" && editing?.box?.contains(cx, cy) == true

                // Do it.
                when (action) {
                    "CLICK" -> hands.tap(x, y)
                    "LONG_PRESS" -> hands.longPress(x, y)
                    "TYPE", "TYPE_ENTER" -> {
                        if (sameField) {
                            if (editing?.endsWithSpace != true) text = " $text"
                        } else {
                            hands.tap(x, y)  // focus the field; the keyboard slides up
                            // Tapping a search bar often opens a new screen: type only once it has settled,
                            // or the first letters go nowhere.
                            waitForScreen(imgBefore, 3.0, movingOnItsOwn, stillChecks = 2)
                        }
                        hands.typeText(text)
                        if (action == "TYPE_ENTER") hands.enter()
                    }
                    "ENTER" -> hands.enter()
                    "BACK" -> hands.back()
                    "HOME" -> hands.home()
                    "OPEN_APP" -> hands.openApp(app?.packageName ?: throw JevException("No app to open"))
                    "ALL_APPS" -> hands.allApps()
                    "OPEN_WEBSITE" -> hands.openUrl(url)
                    "SCROLL_DOWN" -> hands.swipe(SwipeDirection.UP)
                    "SCROLL_UP" -> hands.swipe(SwipeDirection.DOWN)
                    "SWIPE_LEFT" -> hands.swipe(SwipeDirection.LEFT)
                    "SWIPE_RIGHT" -> hands.swipe(SwipeDirection.RIGHT)
                    "WAIT" -> Unit
                    else -> VcLog.w(TAG, "unknown action $action: doing nothing")
                }

                // Wait only as long as the screen keeps changing, not a fixed time.
                val waited = if (action == "WAIT") {
                    delay(1000)
                    waitForScreen(imgBefore, 2.0, movingOnItsOwn)
                } else {
                    waitForScreen(
                        imgBefore, if (action == "OPEN_WEBSITE" || action == "OPEN_APP") 8.0 else 3.0,
                        movingOnItsOwn, stillChecks = if (action in LOADS_PAGE) 3 else 1,
                    )
                }
                nextScreen = waited.shot
                selfMoving = waited.selfMoving
                step["wait"] = waited.info
                // Tell JEV when an action did nothing (e.g. a tap that missed), so it tries something else.
                val changed = waited.info["reacted"] == true
                unchanged = if (changed) 0 else unchanged + 1
                step["seconds"] = round1(now() - stepStarted)
                step["result"] = if (changed) "Executed." else "Executed, but nothing changed on screen."
                lastMiss = if (changed || action !in CLICKS) null else Miss(action, cx, cy)
                save()
                history.add("$action $detail".trim() + if (changed) "" else " (nothing changed on screen)")
                if (action == "TYPE") {
                    editing = Editing(if (sameField) editing?.box else box, text.lastOrNull()?.isWhitespace() == true)
                } else if (action !in setOf("WAIT", "SCROLL_DOWN", "SCROLL_UP")) {
                    editing = null
                }
                if (unchanged >= 5) {
                    log("The screen hasn't changed for 5 steps. Stopping.")
                    return save("unsure", "The screen hasn't changed for 5 steps, so the agent stopped. " +
                        "Check the last screenshot: the page may not have loaded, or it needs you.")
                }
            }
            log("Stopped after $MAX_STEPS steps without finishing.")
            return save("gave_up", "Stopped after $MAX_STEPS steps without finishing.")
        } catch (e: CancellationException) {
            save("stopped", "Stopped by you.")
            throw e
        } catch (e: Exception) {
            VcLog.e(TAG, "Failed: ${e.message}", e)
            return save("error", (e.message ?: e.javaClass.simpleName).take(500))
        }
    }

    /** The text field we last typed into. */
    private data class Editing(val box: Box?, val endsWithSpace: Boolean)

    private fun <T> pick(options: List<T>, answer: JevChoice?, what: String): T {
        val i = answer?.choice?.toIntOrNull()
        return options.getOrNull(i ?: -1)
            ?: throw JevException("JEV picked $what option ${answer?.choice} of ${options.size}")
    }

    companion object {
        private const val TAG = "Agent"
    }
}

package com.brainstormity.voicecontrol.agent.laya

import android.content.Context
import com.brainstormity.voicecontrol.agent.AgentRules
import com.brainstormity.voicecontrol.agent.Availability
import com.brainstormity.voicecontrol.agent.Box
import com.brainstormity.voicecontrol.agent.JevChoice
import com.brainstormity.voicecontrol.agent.JevClient
import com.brainstormity.voicecontrol.agent.JevTarget
import com.brainstormity.voicecontrol.agent.JevUsage
import com.brainstormity.voicecontrol.agent.StepDecision
import com.brainstormity.voicecontrol.agent.StepInput
import com.brainstormity.voicecontrol.agent.SystemOne
import com.brainstormity.voicecontrol.agent.SystemOneBackends
import com.brainstormity.voicecontrol.agent.SystemOneException
import com.brainstormity.voicecontrol.core.UiTree
import com.brainstormity.voicecontrol.core.VcLog

/** Runs Laya questions (the engine in the app, a fake in tests). */
fun interface LayaScorer {
    fun score(stateJson: String, questions: LinkedHashMap<String, LayaQuestion>): LayaEngine.Scored
}

/**
 * The on-device Laya-Android model as a [SystemOne] ("On-device (Laya)" in the Next-step model card).
 *
 * Per step ([ask]):
 *  1. candidates = [LayaCandidates.build] of the step's accessibility tree (Byun11's rules, byte-exact). No tree
 *     at all (the service can't read this screen, e.g. a secure window) is an error with [NO_TREE]: Laya's state
 *     IS the tree, and without it we can't even offer BACK/HOME sensibly (decided T17). A tree with zero
 *     candidates (a game, a canvas) is asked anyway: only actions that need no element can win.
 *  2. state = `{goal: task, history: last 3 in Laya's format (LayaHistory), ui: candidate lines}`, questions =
 *     `operation` (12 trained ops) + `target` (candidates); nothing else (T14: extra criteria hurt).
 *  3. If a question doesn't fit the largest window, candidates are dropped from the end (bottom of the screen;
 *     ids are positions, nothing renumbered) and it's asked again.
 *  4. mapping ([LayaPolicy]): ops -> our actions (scroll directions verified), INPUT_TEXT -> TYPE / TYPE_ENTER
 *     (search field heuristic), probabilities summed over the offered actions; target = chosen candidate's bounds
 *     / screen size with target confidence 1.0 (a tree element is exact: no zoom-in); `text` from the task
 *     ([LayaPolicy.chooseText]) or, if that can't decide, Laya's untrained `text` question; `app` / `website` = 0.
 * Gating stays TaskRunner's: action confidence = Laya's mass on the chosen action (max(p) is the calibrated number).
 */
class LayaSystemOne(
    private val context: Context?,
    private val scorer: LayaScorer = LayaScorer { s, q -> LayaEngine.get(context!!).score(s, q) },
) : SystemOne {
    override val id: String = SystemOneBackends.LAYA
    override val displayName: String = "Laya"
    override val pricePerMillionInput: Double = 0.0
    override val wantsUiTree: Boolean = true

    private val history = LayaHistory()

    override fun availability(ctx: Context): Availability {
        val engine = LayaEngine.peek()
        val base = LayaModelFiles.availabilityFor(LayaModelFiles.device(ctx), LayaModelFiles.status(ctx),
            LayaDownload.state, engine?.verifyError, engineLine(engine))
        val err = engine?.loadError
        // A load that failed for another reason than the files (no backend could compile): not usable here, so
        // `auto` falls back to JEV. Cleared by Delete / Import / a new download (the engine is released).
        if (base.isReady && err != null && engine.verifyError == null) return Availability.Unsupported(err)
        return base
    }

    private fun engineLine(engine: LayaEngine?): String? = when {
        engine == null -> null
        engine.isLoaded -> engine.loadInfo?.line()
        engine.isLoading -> "model ready, loading it now"
        else -> null
    }

    /** Loads the model in the background if it can run (called when voice starts). */
    fun warmup() {
        val ctx = context ?: return
        if (availability(ctx).isReady) LayaEngine.get(ctx).warmup()
    }

    override fun ask(input: StepInput): StepDecision {
        val started = System.nanoTime()
        val tree = input.uiTree ?: throw SystemOneException(NO_TREE)
        var cands = LayaCandidates.build(tree)
        val total = cands.size
        val hist = LayaCandidates.historyLines(history.convert(input.task, input.history))
        var scored: LayaEngine.Scored
        while (true) {
            try {
                scored = scorer.score(LayaSequence.stateJson(input.task, hist, LayaCandidates.uiLines(cands)), questions(cands))
                break
            } catch (e: LayaDoesNotFit) {
                if (cands.isEmpty()) throw e
                val keep = cands.size - maxOf(1, cands.size / 8)
                VcLog.w(TAG, "${cands.size} candidates don't fit (${e.message}); keeping the first $keep")
                cands = cands.take(keep)
            }
        }
        val op = scored.answers.getValue(OPERATION)
        val tgt = scored.answers[TARGET]
        val chosen = tgt?.let { cands[it.best] }
        val offered = JevClient.offeredActions(input.texts, input.urls, input.apps).keys
        val typeInto = LayaPolicy.typeTarget(tree, cands, chosen)
        val inputAs = LayaPolicy.inputTextAction(typeInto?.let { LayaPolicy.fieldInfo(tree, it) }, input.task)
        val probs = LayaPolicy.actionProbabilities(op.probabilityMap(), offered, inputAs, cands.isNotEmpty())
        var (action, confidence) = LayaPolicy.best(probs) ?: ("WAIT" to 0.0)
        // A switch named in the task that Laya pointed at: its on/off state decides between DONE and CLICK.
        val override = LayaPolicy.toggleOverride(input.task, action, probs, chosen, tgt?.answerConfidence ?: 0.0)
        if (override != null) {
            VcLog.i(TAG, "override: $action -> ${override.action}: ${override.note}")
            action = override.action
            confidence = override.confidence
        }
        val typing = action == "TYPE" || action == "TYPE_ENTER"
        val targetCand = if (typing) typeInto else (override?.target ?: chosen)

        // Text: from the task when it decides, else (only when typing now) Laya's text question.
        var textSource = ""
        var textAnswer: JevChoice? = null
        var textMs: Long? = null
        if (input.texts.isNotEmpty()) {
            val pick = LayaPolicy.chooseText(input.task, input.texts)
            textAnswer = when {
                pick != null -> { textSource = if (input.texts.size == 1) "only" else "task"; certain(pick.toString()) }
                typing -> {
                    textSource = "laya"
                    val tq = linkedMapOf(TEXT to LayaPolicy.textQuestion(input.texts))
                    val s = System.nanoTime()
                    val a = scorer.score(LayaSequence.stateJson(input.task, hist, LayaCandidates.uiLines(cands)), tq)
                        .answers.getValue(TEXT)
                    textMs = (System.nanoTime() - s) / 1_000_000
                    JevChoice(a.choice, a.answerConfidence, a.probabilityMap())
                }
                else -> { textSource = "default"; certain("0") }
            }
        }
        history.remember(input.history.size, cands, targetCand)

        val w = tree.screen.width.toDouble().coerceAtLeast(1.0)
        val h = tree.screen.height.toDouble().coerceAtLeast(1.0)
        val target = targetCand?.let { c ->
            JevTarget(Box(c.bounds[0] / w, c.bounds[1] / h, c.bounds[2] / w, c.bounds[3] / h), 1.0)
        }
        if (target == null && action in AgentRules.CLICKS) throw SystemOneException("Laya chose $action but found no element to tap")
        val latency = (System.nanoTime() - started) / 1_000_000
        val extras = linkedMapOf<String, Any?>(
            "candidates" to total,
            "candidates_dropped" to (total - cands.size),
            "laya_op" to op.choice,
            "laya_op_p" to round4(op.answerConfidence),
            "target_id" to tgt?.best,
            "target_p" to tgt?.let { round4(it.answerConfidence) },
            "target_label" to targetCand?.line,
            "window" to scored.window,
            "state_truncated" to scored.truncated,
            "question_ms" to LinkedHashMap(scored.questionMs).also { m -> textMs?.let { m[TEXT] = it } },
            "prep_ms" to scored.prepMs,
            "engine" to scored.backend,
            "load_ms" to scored.loadMs,
            "history" to hist,
        )
        if (typing) extras["input_text_as"] = inputAs
        if (override != null) { extras["override"] = override.note; extras["override_from"] = op.choice }
        if (textSource.isNotEmpty()) extras["text_source"] = textSource
        VcLog.i(TAG, "ask: $action ${AgentRules.pct(confidence)} (laya ${op.choice} ${AgentRules.pct(op.answerConfidence)})" +
            (targetCand?.let { " -> ${it.line} (${tgt?.let { t -> AgentRules.pct(t.answerConfidence) }})" } ?: "") +
            ", $total candidates, window ${scored.window}, ${latency} ms")
        return StepDecision(
            action = JevChoice(action, confidence, probs),
            target = target,
            text = textAnswer,
            app = if (input.apps.isNotEmpty()) certain("0") else null,
            website = if (input.urls.isNotEmpty()) certain("0") else null,
            usage = JevUsage(scored.inputTokens, 0),
            latencyMs = latency,
            backend = id,
            targetNodeId = targetCand?.let { tree.nodes.getOrNull(it.nodeIndex)?.id },
            extras = extras,
        )
    }

    private fun questions(cands: List<Candidate>): LinkedHashMap<String, LayaQuestion> {
        val q = linkedMapOf(OPERATION to LayaPolicy.operationQuestion())
        if (cands.isNotEmpty()) q[TARGET] = LayaPolicy.targetQuestion(cands)
        return q
    }

    private fun certain(key: String) = JevChoice(key, 1.0, mapOf(key to 1.0))
    private fun round4(x: Double) = Math.round(x * 10000) / 10000.0

    companion object {
        private const val TAG = "Laya"
        const val OPERATION = "operation"
        const val TARGET = "target"
        const val TEXT = "text"
        const val NO_TREE = "The on-device model needs the app's accessibility view of the screen, which this screen doesn't provide"
    }
}

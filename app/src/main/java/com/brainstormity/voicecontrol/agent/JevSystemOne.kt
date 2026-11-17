package com.brainstormity.voicecontrol.agent

import android.content.Context
import com.brainstormity.voicecontrol.core.AppSettings

/**
 * JEV (TypeSafe System One, cloud) as a [SystemOne]: a thin adapter over [JevClient], which is unchanged, so the
 * request bodies stay byte-identical to upstream (JevClientTest's golden tests).
 *
 * [client] is built per call from [keyProvider] like TaskController did before T15 (the key may change between
 * runs in JevKeyActivity).
 */
class JevSystemOne(
    private val keyProvider: () -> String,
    private val clientFactory: (() -> String) -> JevClient = { JevClient(it) },
) : SystemOne {
    override val id: String = SystemOneBackends.JEV
    override val displayName: String = "JEV"
    override val pricePerMillionInput: Double = AgentRules.PRICE_PER_MILLION_INPUT_TOKENS

    private val client: JevClient by lazy { clientFactory(keyProvider) }

    fun hasKey(): Boolean = client.hasKey()

    override fun availability(ctx: Context): Availability = availabilityFor(AppSettings.jevApiKey(ctx),
        AppSettings.storedJevApiKey(ctx) != null, AppSettings.jevKeyCheck(ctx)?.first)

    override fun preflight(): String? = if (client.hasKey()) null else JevClient.NO_KEY_MESSAGE

    override fun ask(input: StepInput): StepDecision {
        val started = System.nanoTime()
        val r = client.ask(input.task, input.screenshotJpeg, input.history, input.texts, input.urls, input.apps)
        return fromResponse(r, (System.nanoTime() - started) / 1_000_000)
    }

    /** JEV's zoom-in: never null; a close-up answer without a box is an error, exactly as before T15. */
    override fun refine(input: StepInput, closeUp: CloseUp, closeupJpeg: ByteArray): Refinement {
        val started = System.nanoTime()
        val close = client.refine(input.task, closeupJpeg, input.history)
        val target = close.target ?: throw JevException("JEV's close-up answer has no target box")
        return Refinement(target, close.usage, (System.nanoTime() - started) / 1_000_000)
    }

    companion object {
        fun fromResponse(r: JevResponse, latencyMs: Long): StepDecision = StepDecision(
            action = r.action, target = r.target, text = r.text, app = r.app, website = r.website,
            usage = r.usage, latencyMs = latencyMs, backend = SystemOneBackends.JEV,
        )

        /**
         * Pure: [key] the key the agent would use, [userKey] whether the user entered one (vs. the debug build's
         * key), [check] the last verification text (AppSettings.jevKeyCheck).
         */
        fun availabilityFor(key: String, userKey: Boolean, check: String?): Availability = when {
            key.isBlank() -> Availability.NeedsSetup(JevClient.NO_KEY_MESSAGE)
            !userKey -> Availability.Ready("key built into this debug build")
            check == null -> Availability.Ready("key saved")
            check.startsWith("valid") -> Availability.Ready("key verified")
            // Still runs (as before T15); the run will end with KEY_REJECTED_MESSAGE if JEV refuses it again.
            check.startsWith("Rejected") -> Availability.Ready("key ${check.replaceFirstChar { it.lowercase() }}")
            else -> Availability.Ready("key $check")
        }
    }
}

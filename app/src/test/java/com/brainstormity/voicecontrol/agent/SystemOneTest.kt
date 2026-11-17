package com.brainstormity.voicecontrol.agent

import android.content.Context
import com.brainstormity.voicecontrol.core.AppSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** T15: the System One seam — backend resolution, the JEV adapter, report fields, the runner's pure rules. */
class SystemOneTest {
    private lateinit var server: MockWebServer
    private val img = "SCREENSHOT".toByteArray()

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() {
        server.shutdown()
        SystemOneBackends.register(PlaceholderLayaSystemOne)  // undo any fake registered by a test
    }

    private fun jev(key: String = "test-key") =
        JevSystemOne({ key }) { JevClient(it, server.url("/v1/systemone").toString()) }

    private fun input(task: String = "Open Settings") =
        StepInput(task, img, 100, 200, null, listOf("CLICK at (1, 2)"), emptyList(), emptyList(), emptyList())

    private val answer = """
        {"answers": {
           "action": {"choice": "CLICK", "confidence": 0.9, "probabilities": {"CLICK": 0.9, "BACK": 0.1}},
           "target": {"box": {"left": 0.1, "top": 0.2, "right": 0.3, "bottom": 0.4}, "confidence": 0.55}},
         "usage": {"input_tokens": 2345, "output_tokens": 12}}
    """.trimIndent()

    /** A fake on-device backend: tree-style answers, no refine. */
    private class FakeLaya(private val ready: Boolean) : SystemOne {
        override val id = SystemOneBackends.LAYA
        override val displayName = "Laya"
        override val pricePerMillionInput = 0.0
        override val wantsUiTree = true
        override fun availability(ctx: Context) =
            if (ready) Availability.Ready() else Availability.NeedsDownload(700_000_000)
        override fun ask(input: StepInput) = StepDecision(
            action = JevChoice("CLICK", 0.8, mapOf("CLICK" to 0.8, "BACK" to 0.2)),
            target = JevTarget(Box(0.0, 0.0, 0.5, 0.1), 1.0), text = null, app = null, website = null,
            latencyMs = 140, backend = id, targetNodeId = 12, extras = mapOf("candidates" to 34),
        )
    }

    // ------------------------------------------------------------------ resolution

    private val both = setOf(SystemOneBackends.JEV, SystemOneBackends.LAYA)

    @Test fun autoPicksLayaOnlyWhenReady() {
        assertEquals("laya", SystemOneBackends.resolveId(AppSettings.SYSTEM_ONE_AUTO, both) { true })
        assertEquals("jev", SystemOneBackends.resolveId(AppSettings.SYSTEM_ONE_AUTO, both) { false })
        assertEquals("jev", SystemOneBackends.resolveId(AppSettings.SYSTEM_ONE_AUTO, setOf("jev")) { true })
    }

    @Test fun explicitChoiceWinsEvenWhenNotReady() {
        assertEquals("laya", SystemOneBackends.resolveId(AppSettings.SYSTEM_ONE_LAYA, both) { false })
        assertEquals("jev", SystemOneBackends.resolveId(AppSettings.SYSTEM_ONE_JEV, both) { true })
    }

    @Test fun unknownSettingBehavesLikeAuto() {
        assertEquals("jev", SystemOneBackends.resolveId("gemma", both) { false })
        assertEquals("laya", SystemOneBackends.resolveId("gemma", both) { it == "laya" })
    }

    @Test fun registryHasJevAndPlaceholderAndRegisterReplaces() {
        assertEquals(listOf("jev", "laya"), SystemOneBackends.all().map { it.id })
        assertSame(PlaceholderLayaSystemOne, SystemOneBackends.byId("laya"))
        val fake = FakeLaya(ready = true)
        SystemOneBackends.register(fake)
        assertSame(fake, SystemOneBackends.byId("laya"))
        assertEquals(listOf("jev", "laya"), SystemOneBackends.all().map { it.id })
        assertTrue(SystemOneBackends.jev() is JevSystemOne)
    }

    @Test fun placeholderLayaIsNeverUsable() {
        assertEquals("On-device model not installed in this build", PlaceholderLayaSystemOne.preflight())
        assertEquals(0.0, PlaceholderLayaSystemOne.pricePerMillionInput, 0.0)
        try {
            PlaceholderLayaSystemOne.ask(input())
            fail("expected SystemOneException")
        } catch (e: SystemOneException) {
            assertEquals(PlaceholderLayaSystemOne.MESSAGE, e.message)
        }
    }

    @Test fun availabilityMessagesAndReadiness() {
        assertTrue(Availability.Ready().isReady)
        assertFalse(Availability.NeedsSetup("x").isReady)
        assertEquals("Download the on-device model first (700 MB)", Availability.NeedsDownload(700_000_000).message)
        assertEquals("nope", Availability.Unsupported("nope").message)
    }

    // ------------------------------------------------------------------ JEV adapter

    @Test fun jevAvailabilityTable() {
        assertEquals(Availability.NeedsSetup(JevClient.NO_KEY_MESSAGE), JevSystemOne.availabilityFor("", false, null))
        assertEquals("key built into this debug build", JevSystemOne.availabilityFor("k", false, null).message)
        assertEquals("key verified", JevSystemOne.availabilityFor("k", true, "valid (HTTP 200)").message)
        assertEquals("key saved", JevSystemOne.availabilityFor("k", true, null).message)
        assertTrue(JevSystemOne.availabilityFor("k", true, "Rejected by JEV during a task (HTTP 401/403)").isReady)
    }

    @Test fun jevIdentityAndPrice() {
        val j = jev()
        assertEquals("jev", j.id)
        assertEquals("JEV", j.displayName)
        assertEquals(AgentRules.PRICE_PER_MILLION_INPUT_TOKENS, j.pricePerMillionInput, 0.0)
        assertFalse(j.wantsUiTree)
        assertNull(j.preflight())
        assertEquals(JevClient.NO_KEY_MESSAGE, jev(key = " ").preflight())
    }

    @Test fun jevAskSendsTheSameBodyAsJevClientAndMapsTheAnswer() {
        server.enqueue(MockResponse().setBody(answer))
        val d = jev().ask(input())
        val sent = server.takeRequest().body.readUtf8()
        val expected = Json.write(JevClient.askBody("Open Settings", img, listOf("CLICK at (1, 2)"),
            emptyList(), emptyList(), emptyList()))
        assertEquals(expected, sent)
        assertEquals("CLICK", d.action!!.choice)
        assertEquals(0.55, d.target!!.confidence, 0.0)
        assertEquals(2345L, d.usage.inputTokens)
        assertEquals("jev", d.backend)
        assertNull(d.targetNodeId)
        assertTrue(d.extras.isEmpty())
        assertTrue(d.latencyMs >= 0)
    }

    @Test fun jevRefineReturnsCloseUpBoxAndUsage() {
        server.enqueue(MockResponse().setBody(answer))
        val c = CloseUp.around(Box(0.1, 0.2, 0.3, 0.4), 100, 200)
        val r = jev().refine(input(), c, "CLOSEUP".toByteArray())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("closeup", body.getJSONObject("state").getString("screen"))
        assertEquals(0.1, r.target.box.left, 0.0)
        assertEquals(2345L, r.usage.inputTokens)
    }

    @Test fun jevRefineWithoutBoxFailsLikeBefore() {
        server.enqueue(MockResponse().setBody("""{"answers": {}, "usage": {}}"""))
        try {
            jev().refine(input(), CloseUp.around(Box(0.1, 0.2, 0.3, 0.4), 100, 200), img)
            fail("expected JevException")
        } catch (e: JevException) {
            assertEquals("JEV's close-up answer has no target box", e.message)
        }
    }

    @Test fun jevRejectedKeyIsASystemOneExceptionWithHttpCode() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        try {
            jev().ask(input())
            fail("expected SystemOneException")
        } catch (e: SystemOneException) {
            assertTrue(e is JevException)
            assertEquals(401, e.httpCode)
            assertEquals(JevClient.KEY_REJECTED_MESSAGE, e.message)
        }
    }

    // ------------------------------------------------------------------ runner rules + report fields

    @Test fun fakeBackendDefaultsHaveNoRefineAndNoPreflight() {
        val fake = FakeLaya(ready = true)
        assertNull(fake.refine(input(), CloseUp.around(Box(0.1, 0.1, 0.2, 0.2), 100, 200), img))
        assertNull(fake.preflight())
        assertTrue(fake.wantsUiTree)
    }

    @Test fun zoomRuleIsUpstreams() {
        assertTrue(TaskRunner.shouldZoom("CLICK", false, 0.5, false))
        assertFalse(TaskRunner.shouldZoom("CLICK", false, 0.6, false))
        assertTrue(TaskRunner.shouldZoom("TYPE", false, 0.9, true))
        assertFalse(TaskRunner.shouldZoom("TYPE", true, 0.1, true))
        assertFalse(TaskRunner.shouldZoom("BACK", false, 0.1, true))
    }

    @Test fun finishedMessagesForJevAreUnchanged() {
        assertEquals("JEV was only 40% sure about CLICK, so nothing was done. Try making the task more specific.",
            TaskRunner.unsureMessage("JEV", 0.4, "CLICK"))
        assertEquals("JEV says the task is finished.", TaskRunner.doneMessage("JEV"))
        assertEquals("Laya says the task is finished.", TaskRunner.doneMessage("Laya"))
        assertEquals("Laya was split between TYPE / TYPE_ENTER, so only the shared first part (TYPE) is done.",
            AgentRules.splitChain("TYPE_ENTER", 0.4, mapOf("TYPE" to 0.3, "TYPE_ENTER" to 0.4), "Laya").note)
    }

    @Test fun stepReportFieldsArePresentAndSerialise() {
        val jevFields = TaskRunner.backendFields(JevSystemOne.fromResponse(JevClient.parse(answer), 412), 812)
        assertEquals(listOf("latency_ms", "backend", "backend_latency_ms", "target_node_id", "backend_extras"),
            jevFields.keys.toList())
        assertEquals(812L, jevFields["latency_ms"])
        assertEquals("jev", jevFields["backend"])
        assertNull(jevFields["target_node_id"])
        val json = JSONObject(Json.write(jevFields))
        assertTrue(json.isNull("target_node_id"))
        assertEquals(0, json.getJSONObject("backend_extras").length())

        val laya = JSONObject(Json.write(TaskRunner.backendFields(FakeLaya(true).ask(input()), 150)))
        assertEquals("laya", laya.getString("backend"))
        assertEquals(12, laya.getInt("target_node_id"))
        assertEquals(140, laya.getInt("backend_latency_ms"))
        assertEquals(34, laya.getJSONObject("backend_extras").getInt("candidates"))
    }
}

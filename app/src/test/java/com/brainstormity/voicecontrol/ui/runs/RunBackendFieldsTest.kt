package com.brainstormity.voicecontrol.ui.runs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T15 report fields (backend, latency_ms, target_node_id, backend_extras) on the Runs screens. */
class RunBackendFieldsTest {
    private fun resource(name: String): String =
        javaClass.classLoader!!.getResource("runs/$name")!!.readText()

    @Test fun preT15ReportsAreJev() {
        val r = RunStore.parse("20261113-105502", resource("report_done.json"))!!
        assertNull(r.backend)
        assertEquals("JEV", RunFormat.backendLabel(r))
        assertFalse(RunFormat.isLocal(r))
        assertNull(r.steps[0].latencyMs)
        assertNull(RunFormat.stepBackend(r.steps[0]))
        // Unchanged footer for old JEV steps.
        assertTrue(RunFormat.stepTiming(r.steps[0], RunFormat.backendLabel(r)).startsWith("JEV took 0.8s"))
    }

    private val layaReport = """
        {"task": "Open Settings", "status": "done", "message": "Laya says the task is finished.",
         "started": "2026-11-17T10:00:00", "backend": "laya", "backend_name": "Laya", "cost_usd": 0.0,
         "input_tokens": 0, "output_tokens": 0,
         "steps": [{"n": 1, "action": "CLICK", "jev_seconds": 0.2, "latency_ms": 143, "backend": "laya",
                    "backend_latency_ms": 120, "target_node_id": 12, "backend_extras": {"candidates": 34},
                    "tokens": {"input_tokens": 0, "output_tokens": 0}, "cost_usd": 0.0, "result": "Executed."}]}
    """.trimIndent()

    @Test fun layaReportShowsBackendLatencyAndNoCost() {
        val r = RunStore.parse("20261117-100000", layaReport)!!
        assertEquals("laya", r.backend)
        assertEquals("Laya", RunFormat.backendLabel(r))
        assertTrue(RunFormat.isLocal(r))
        assertEquals(0.0, r.costUsd, 0.0)
        val s = r.steps[0]
        assertEquals(143L, s.latencyMs)
        assertEquals(12, s.targetNodeId)
        assertEquals(mapOf("candidates" to "34"), s.backendExtras)
        assertEquals("Laya took 143 ms", RunFormat.stepTiming(s, "Laya"))
        assertEquals("Chose element #12 · candidates: 34", RunFormat.stepBackend(s))
    }

    @Test fun jevReportWithT15FieldsKeepsSecondsFooter() {
        val r = RunStore.parse("20261117-100001", layaReport.replace("\"laya\"", "\"jev\"")
            .replace("\"Laya\"", "\"JEV\"").replace("\"target_node_id\": 12", "\"target_node_id\": null")
            .replace("{\"candidates\": 34}", "{}"))!!
        assertEquals("JEV", RunFormat.backendLabel(r))
        assertEquals("JEV took 0.2s", RunFormat.stepTiming(r.steps[0], "JEV"))
        assertNull(r.steps[0].targetNodeId)
        assertNull(RunFormat.stepBackend(r.steps[0]))
    }
}

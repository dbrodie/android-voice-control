package com.brainstormity.voicecontrol.agent.laya

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import java.io.File

/**
 * T14 golden data for the Kotlin host: `tools/laya/fixtures/<row>.json` (committed) and the checkpoint tokenizer
 * `tools/laya/out/tokenizer.json` (gitignored, 34 MB; set LAYA_OUT to another `out/` directory). Tests that need
 * the tokenizer are skipped (JUnit assumption) when it isn't there, e.g. in CI.
 */
object LayaFixtures {
    /** Gradle runs unit tests with the module directory (app/) as working directory. */
    private val root: File = listOf(File(".."), File(".")).first { File(it, "tools/laya/fixtures").isDirectory }

    val fixtureDir = File(root, "tools/laya/fixtures")

    private val outDir: File = System.getenv("LAYA_OUT")?.let(::File) ?: File(root, "tools/laya/out")

    val tokenizerFile = File(outDir, "tokenizer.json")

    val rowNames: List<String> by lazy {
        JSONObject(File(fixtureDir, "INDEX.json").readText()).getJSONArray("rows").let { a -> List(a.length()) { a.getString(it) } }
    }

    fun row(name: String): JSONObject = JSONObject(File(fixtureDir, "$name.json").readText(Charsets.UTF_8))

    val rows: List<JSONObject> by lazy { rowNames.map(::row) }

    private val tokenizerOrNull: LayaTokenizer? by lazy { if (tokenizerFile.isFile) LayaTokenizer.load(tokenizerFile) else null }

    /** The tokenizer, or skip the calling test. */
    fun tokenizer(): LayaTokenizer {
        Assume.assumeTrue("no ${tokenizerFile.path} (gitignored model file): skipped", tokenizerFile.isFile)
        return tokenizerOrNull!!
    }

    fun strings(a: JSONArray): List<String> = List(a.length()) { a.getString(it) }
    fun ints(a: JSONArray): IntArray = IntArray(a.length()) { a.getInt(it) }

    /** A fixture question -> LayaQuestion (criteria order kept: org.json's JSONObject is unordered, so the keys come from the ids). */
    fun questions(row: JSONObject): LinkedHashMap<String, LayaQuestion> {
        val raw = File(fixtureDir, row.getString("name") + ".json").readText(Charsets.UTF_8)
        // org.json loses key order; read the questions with our ordered reader instead.
        @Suppress("UNCHECKED_CAST")
        val ordered = LayaJsonReader(raw.reader()).readValue() as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val qs = ordered["questions"] as Map<String, Map<String, Any?>>
        val out = LinkedHashMap<String, LayaQuestion>()
        for ((qid, q) in qs) {
            @Suppress("UNCHECKED_CAST")
            val crit = q["criteria"] as Map<String, Any?>
            out[qid] = LayaQuestion(q["instructions"] as String, crit.mapValuesTo(LinkedHashMap()) { it.value as String? })
        }
        return out
    }

    fun state(row: JSONObject): Triple<String, List<String>, List<String>> {
        val s = row.getJSONObject("state")
        return Triple(s.getString("goal"), strings(s.getJSONArray("history")), strings(s.getJSONArray("ui")))
    }
}

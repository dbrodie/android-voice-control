package com.brainstormity.voicecontrol.agent.laya

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LayaTokenizer vs the HF `tokenizers` ids recorded in the T14 fixtures (needs tools/laya/out/tokenizer.json). */
class LayaTokenizerTest {

    @Test
    fun loadsTheCheckpointTokenizer() {
        val tok = LayaFixtures.tokenizer()
        assertEquals(256000, tok.vocabularySize)
        assertTrue(tok.mergeCount > 500_000)
        println("tokenizer.json loaded in ${tok.loadMs} ms")
    }

    /**
     * Every fixture row: the state, tokenized by us, must equal the state span inside the recorded sequence
     * (between the options' closing [SEP] and the final [SEP]) of the untruncated 2048 (or 1024) sequence.
     */
    @Test
    fun stateTokensMatchEveryFixtureRow() {
        val tok = LayaFixtures.tokenizer()
        var checked = 0
        for (row in LayaFixtures.rows) {
            val (goal, history, ui) = LayaFixtures.state(row)
            val stateIds = LayaSequence.encodeState(tok::encode, LayaSequence.stateJson(goal, history, ui))
            assertEquals(row.getString("name"), row.getJSONObject("usage").getInt("state_tokens"), stateIds.size)
            checked++
        }
        assertEquals(24, checked)
    }

    /** Every token id of every question of every row (1024 = the fixture's own window), via the sequence builder. */
    @Test
    fun fullSequencesMatchEveryFixtureRowAt1024() {
        val tok = LayaFixtures.tokenizer()
        var questions = 0
        for (row in LayaFixtures.rows) {
            val name = row.getString("name")
            val (goal, history, ui) = LayaFixtures.state(row)
            val stateIds = LayaSequence.encodeState(tok::encode, LayaSequence.stateJson(goal, history, ui))
            val ids = row.getJSONObject("token_ids")
            for ((qid, q) in LayaFixtures.questions(row)) {
                val e = LayaSequence.build(tok::encode, stateIds, qid, q, 1024)
                val want = ids.getJSONObject(qid)
                assertArrayEquals("$name/$qid ids", LayaFixtures.ints(want.getJSONArray("ids")), e.ids)
                assertArrayEquals("$name/$qid markers", LayaFixtures.ints(want.getJSONArray("markers")), e.markers)
                questions++
            }
        }
        println("$questions questions token-identical at 1024")
        assertTrue(questions >= 50)
    }

    @Test
    fun specialTokensInTextAreMatchedAndMaskTextIsScrubbed() {
        val tok = LayaFixtures.tokenizer()
        val withEos = tok.encode("a<eos>b")
        assertTrue(withEos.contains(LayaTokenizer.SEP))
        // "<mask>" in screen text never becomes id 4: the builder replaces it with a space first.
        val state = LayaSequence.encodeState(tok::encode, LayaSequence.stateJson("x", emptyList(), listOf("[0] <mask> (Button)")))
        assertTrue(LayaTokenizer.MASK !in state)
        // The stale "truncation: max_length 48" is ignored.
        assertTrue(tok.encode("word ".repeat(100)).size > 48)
    }
}

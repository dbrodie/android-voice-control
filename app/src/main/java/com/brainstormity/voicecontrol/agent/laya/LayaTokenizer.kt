package com.brainstormity.voicecontrol.agent.laya

import java.io.File
import java.io.InputStream
import java.util.PriorityQueue

/**
 * The Laya-Android (mmBERT-base) tokenizer, read from the checkpoint's HF `tokenizer.json`
 * (`tools/laya/out/tokenizer.json`; HOST_CONTRACT.md section 2). No Android APIs, so JVM tests run it against the
 * T14 fixtures' token ids.
 *
 * Pipeline (exactly what HF `tokenizers` does for this file with `add_special_tokens=False`):
 *  1. added tokens (249: `<pad>`=0, `<eos>`=1, `<bos>`=2, `<unk>`=3, `<mask>`=4 with lstrip, ...) are matched
 *     leftmost-longest in the RAW text, before normalization (`split_special_tokens=False`);
 *  2. each remaining segment: normalizer `Replace(" " -> "▁")`, then pre-tokenizer
 *     `Metaspace(replacement "▁", prepend_scheme "always", split true)`: prepend "▁" unless it already starts with
 *     one, split before every "▁";
 *  3. BPE per piece: lowest merge rank first (ties: leftmost), unknown scalars fall back to `<0xXX>` byte tokens.
 * Not applied, on purpose: the post-processor (no [CLS]/[SEP] added here) and the file's stale
 * `"truncation": {"max_length": 48}` (laya never truncates instructions or state; honouring it cuts every fragment
 * to 48 tokens). The algorithm follows the Kotlin port in litert-community/Laya-Multilingual-LiteRT
 * (`android/.../LayaTokenizer.kt`, Apache-2.0); loading is ours (streaming, compact merge table).
 */
class LayaTokenizer private constructor(
    private val vocabulary: HashMap<String, Int>,
    private val merges: LongLongMap,
    private val addedRoot: TrieNode,
    private val byteIds: IntArray,
    /** Parse + index time, ms. */
    val loadMs: Long,
) {
    val vocabularySize: Int get() = vocabulary.size
    val mergeCount: Int get() = merges.size

    private class AddedToken(val content: String, val id: Int, val lstrip: Boolean, val rstrip: Boolean)

    internal class TrieNode {
        val children = HashMap<Char, TrieNode>(4)
        var token: Any? = null // AddedToken
    }

    private class Cand(val rank: Int, val left: Int, val right: Int, val leftId: Int, val rightId: Int, val id: Int)

    /** Token ids of [text], no special tokens added, no truncation. */
    fun encode(text: String): IntArray {
        val out = IntArrayList(text.length / 3 + 4)
        var unprocessed = 0
        var position = 0
        while (position < text.length) {
            var node = addedRoot
            var end = position
            var matched: AddedToken? = null
            var matchedEnd = position
            while (end < text.length) {
                node = node.children[text[end]] ?: break
                end++
                (node.token as AddedToken?)?.let { matched = it; matchedEnd = end }
            }
            val token = matched
            if (token == null) { position++; continue }
            var begin = position
            if (token.lstrip) {
                while (begin > unprocessed && isRustWhitespace(text.codePointBefore(begin))) {
                    begin -= Character.charCount(text.codePointBefore(begin))
                }
            }
            encodeOrdinary(text.substring(unprocessed, begin), out)
            out.add(token.id)
            if (token.rstrip) {
                while (matchedEnd < text.length && isRustWhitespace(text.codePointAt(matchedEnd))) {
                    matchedEnd += Character.charCount(text.codePointAt(matchedEnd))
                }
            }
            position = matchedEnd
            unprocessed = matchedEnd
        }
        encodeOrdinary(text.substring(unprocessed), out)
        return out.toArray()
    }

    private fun encodeOrdinary(text: String, out: IntArrayList) {
        if (text.isEmpty()) return
        var normalized = text.replace(' ', META)
        if (normalized[0] != META) normalized = META + normalized
        var begin = 0
        for (i in 1 until normalized.length) {
            if (normalized[i] == META) {
                bpe(normalized.substring(begin, i), out)
                begin = i
            }
        }
        bpe(normalized.substring(begin), out)
    }

    private fun bpe(piece: String, out: IntArrayList) {
        // Whole piece in the vocabulary: BPE would merge back to it only if every intermediate merge exists, so
        // do not shortcut; run the merges (HF does the same, modulo its cache).
        val symbols = IntArrayList(piece.length + 2)
        var offset = 0
        while (offset < piece.length) {
            val cp = piece.codePointAt(offset)
            val ch = String(Character.toChars(cp))
            val id = vocabulary[ch]
            if (id != null) symbols.add(id) else {
                for (b in ch.toByteArray(Charsets.UTF_8)) {
                    val fb = byteIds[b.toInt() and 255]
                    check(fb >= 0) { "Missing byte fallback for U+${cp.toString(16)}" }
                    symbols.add(fb)
                }
            }
            offset += Character.charCount(cp)
        }
        if (symbols.size < 2) { out.addAll(symbols); return }
        val ids = symbols.toArray()
        val n = ids.size
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it + 1 < n) it + 1 else -1 }
        val alive = BooleanArray(n) { true }
        val queue = PriorityQueue<Cand>(n, compareBy<Cand> { it.rank }.thenBy { it.left })
        fun offer(left: Int) {
            if (left < 0 || !alive[left]) return
            val right = next[left]
            if (right < 0) return
            val m = merges.get(key(ids[left], ids[right]))
            if (m == LongLongMap.MISSING) return
            queue.add(Cand((m ushr 32).toInt(), left, right, ids[left], ids[right], m.toInt()))
        }
        for (i in 0 until n - 1) offer(i)
        while (queue.isNotEmpty()) {
            val c = queue.remove()
            val l = c.left
            val r = c.right
            if (!alive[l] || !alive[r] || next[l] != r || ids[l] != c.leftId || ids[r] != c.rightId) continue
            ids[l] = c.id
            alive[r] = false
            next[l] = next[r]
            if (next[r] >= 0) prev[next[r]] = l
            offer(prev[l])
            offer(l)
        }
        for (i in 0 until n) if (alive[i]) out.add(ids[i])
    }

    companion object {
        const val META = '▁'
        const val PAD = 0
        const val SEP = 1   // <eos>
        const val CLS = 2   // <bos>
        const val UNK = 3
        const val MASK = 4

        fun load(file: File): LayaTokenizer = file.inputStream().buffered(1 shl 16).use { load(it) }

        /** Reads tokenizer.json. Validates the parts of the pipeline this port implements (fails loudly otherwise). */
        fun load(input: InputStream): LayaTokenizer {
            val started = System.nanoTime()
            val r = LayaJsonReader(input.reader(Charsets.UTF_8))
            var vocabulary: HashMap<String, Int>? = null
            var merges: LongLongMap? = null
            val root = TrieNode()
            var sawModel = false
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "added_tokens" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            @Suppress("UNCHECKED_CAST")
                            val e = r.readValue() as Map<String, Any?>
                            require(e["normalized"] == false && e["single_word"] == false) { "unsupported added token $e" }
                            val t = AddedToken(e["content"] as String, (e["id"] as Number).toInt(),
                                e["lstrip"] == true, e["rstrip"] == true)
                            var node = root
                            for (ch in t.content) node = node.children.getOrPut(ch) { TrieNode() }
                            node.token = t
                        }
                        r.endArray()
                    }
                    "normalizer" -> {
                        @Suppress("UNCHECKED_CAST")
                        val n = r.readValue() as Map<String, Any?>
                        require(n["type"] == "Replace" && n["content"] == META.toString() &&
                            (n["pattern"] as Map<*, *>)["String"] == " ") { "unsupported normalizer $n" }
                    }
                    "pre_tokenizer" -> {
                        @Suppress("UNCHECKED_CAST")
                        val p = r.readValue() as Map<String, Any?>
                        require(p["type"] == "Metaspace" && p["replacement"] == META.toString() &&
                            p["prepend_scheme"] == "always" && p["split"] == true) { "unsupported pre_tokenizer $p" }
                    }
                    "model" -> {
                        sawModel = true
                        r.beginObject()
                        while (r.hasNext()) {
                            when (val k = r.nextName()) {
                                "type" -> require(r.nextString() == "BPE") { "not a BPE model" }
                                "byte_fallback" -> require(r.nextBoolean()) { "byte_fallback must be true" }
                                "ignore_merges" -> require(!r.nextBoolean()) { "ignore_merges must be false" }
                                "dropout", "continuing_subword_prefix", "end_of_word_suffix" ->
                                    require(r.readValue() == null) { "unsupported BPE option $k" }
                                "vocab" -> {
                                    val v = HashMap<String, Int>(1 shl 19)
                                    r.beginObject()
                                    while (r.hasNext()) { val tok = r.nextName(); v[tok] = r.nextInt() }
                                    r.endObject()
                                    vocabulary = v
                                }
                                "merges" -> {
                                    val v = requireNotNull(vocabulary) { "merges before vocab in tokenizer.json" }
                                    val m = LongLongMap(1 shl 20)
                                    var rank = 0
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        val left: String
                                        val right: String
                                        if (r.peek() == '[') {
                                            r.beginArray(); r.hasNext(); left = r.nextString(); r.hasNext()
                                            right = r.nextString(); r.endArray()
                                        } else {
                                            val s = r.nextString()
                                            val sp = s.indexOf(' ')
                                            left = s.substring(0, sp); right = s.substring(sp + 1)
                                        }
                                        val l = v.getValue(left)
                                        val rr = v.getValue(right)
                                        val merged = v.getValue(left + right)
                                        val k2 = key(l, rr)
                                        // First (lowest-rank) occurrence wins, as in HF.
                                        if (m.get(k2) == LongLongMap.MISSING) m.put(k2, (rank.toLong() shl 32) or (merged.toLong() and 0xffffffffL))
                                        rank++
                                    }
                                    r.endArray()
                                    merges = m
                                }
                                else -> r.skipValue() // unk_token, fuse_unk
                            }
                        }
                        r.endObject()
                    }
                    // "truncation" (stale max_length 48), "padding", "post_processor", "decoder", "version": ignored.
                    else -> r.skipValue()
                }
            }
            r.endObject()
            require(sawModel) { "tokenizer.json has no model" }
            val vocab = requireNotNull(vocabulary) { "tokenizer.json has no vocab" }
            require(vocab["<pad>"] == PAD && vocab["<eos>"] == SEP && vocab["<bos>"] == CLS &&
                vocab["<unk>"] == UNK && vocab["<mask>"] == MASK) { "unexpected special token ids" }
            val byteIds = IntArray(256) { vocab["<0x" + it.toString(16).uppercase().padStart(2, '0') + ">"] ?: -1 }
            return LayaTokenizer(vocab, requireNotNull(merges) { "tokenizer.json has no merges" }, root, byteIds,
                (System.nanoTime() - started) / 1_000_000)
        }

        private fun key(left: Int, right: Int): Long = (left.toLong() shl 32) or (right.toLong() and 0xffffffffL)

        /** Rust `char::is_whitespace` (Unicode White_Space), used by HF for added-token lstrip/rstrip. */
        private fun isRustWhitespace(cp: Int): Boolean =
            cp in 0x09..0x0d || cp == 0x20 || cp == 0x85 || cp == 0xa0 || cp == 0x1680 || cp in 0x2000..0x200a ||
                cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f || cp == 0x3000
    }
}

/** Growable int list without boxing. */
internal class IntArrayList(capacity: Int = 16) {
    private var a = IntArray(maxOf(4, capacity))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    fun addAll(o: IntArrayList) { for (i in 0 until o.size) add(o.a[i]) }
    operator fun get(i: Int): Int = a[i]
    fun toArray(): IntArray = a.copyOf(size)
}

/** Open-addressing Long -> Long map (linear probing; keys never equal [EMPTY]). For the 580k BPE merges. */
internal class LongLongMap(initialCapacity: Int) {
    private var keys = LongArray(Integer.highestOneBit(maxOf(16, initialCapacity) * 2 - 1)).also { it.fill(EMPTY) }
    private var values = LongArray(keys.size)
    var size = 0
        private set

    fun get(k: Long): Long {
        val mask = keys.size - 1
        var i = mix(k) and mask
        while (true) {
            val kk = keys[i]
            if (kk == EMPTY) return MISSING
            if (kk == k) return values[i]
            i = (i + 1) and mask
        }
    }

    fun put(k: Long, v: Long) {
        if ((size + 1) * 10 > keys.size * 7) grow()
        val mask = keys.size - 1
        var i = mix(k) and mask
        while (true) {
            val kk = keys[i]
            if (kk == EMPTY) { keys[i] = k; values[i] = v; size++; return }
            if (kk == k) { values[i] = v; return }
            i = (i + 1) and mask
        }
    }

    private fun grow() {
        val ok = keys
        val ov = values
        keys = LongArray(ok.size * 2).also { it.fill(EMPTY) }
        values = LongArray(keys.size)
        size = 0
        for (i in ok.indices) if (ok[i] != EMPTY) put(ok[i], ov[i])
    }

    private fun mix(k: Long): Int {
        var h = k * -0x61c8864680b583ebL
        h = h xor (h ushr 29)
        return h.toInt()
    }

    companion object {
        const val EMPTY = Long.MIN_VALUE
        const val MISSING = Long.MIN_VALUE
    }
}

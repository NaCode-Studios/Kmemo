package dev.kmemo.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** One corpus scored by GPTCache, as `tools/gptcache-comparison/compare.py` recorded it. */
data class GptCacheResult(
    val corpus: String,
    val corpusSha256: String,
    val precision: Double,
    val recall: Double,
    val f1: Double,
    val falseHitRate: Double,
)

/**
 * GPTCache's numbers, measured out of band and read back here.
 *
 * **Why it is a file and not a test.** GPTCache is a Python package that downloads an ONNX model on
 * first use; CI is a JVM build. Running it in CI would mean a Python toolchain, a model download and a
 * pinned dependency set that has already broken once — for a number that changes only when the corpus
 * does. So the harness is committed and runnable, its output is committed, and the *coupling* between
 * them is what CI enforces.
 *
 * **The coupling is a digest.** Each row carries the SHA-256 of the corpus it was measured against.
 * [verifyAgainstCorpora] recomputes it. A corpus that grew without the harness being re-run fails the
 * build, rather than leaving a stale comparison in the README with a fresh corpus underneath it, which
 * is the only way a committed measurement can quietly become a lie.
 *
 * **The digest is over the pairs, not over the file.** It was the file bytes until `2.3.0`, and it
 * fired the day a provenance field was added to the corpus documents: nothing about the labels had
 * moved, and the remedy on offer was a model download to record that a prose field had changed. What
 * the coupling is about is the data, so the digest is over the data: each pair's two prompts and its
 * label, in file order, separated by a byte that cannot occur in a prompt.
 */
object GptCacheComparison {

    /** A byte that cannot occur in a prompt, so no pair can be confused with another. */
    private const val SEPARATOR: Char = '\u0000'

    val results: List<GptCacheResult> by lazy { load() }

    val measuredOn: String by lazy { field("measuredOn") }

    val evaluator: String by lazy { field("evaluator") }

    val decisionRule: String by lazy { field("decisionRule") }

    fun forCorpus(name: String): GptCacheResult =
        results.firstOrNull { it.corpus == name } ?: error("no GPTCache result for $name")

    /** The pairs this measurement describes must still be the pairs on disk. */
    fun verifyAgainstCorpora(corpora: List<Corpus>): List<String> = corpora.mapNotNull { corpus ->
        val recorded = forCorpus(corpus.name).corpusSha256
        val actual = digestOf(corpus)
        if (recorded == actual) {
            null
        } else {
            "the ${corpus.name} pairs have changed since GPTCache was last run against them " +
                "(recorded $recorded, now $actual). Re-run tools/gptcache-comparison/compare.py."
        }
    }

    /**
     * The canonical digest `compare.py` computes: each pair's two prompts and its label, in file
     * order, joined by a NUL that cannot occur in a prompt.
     */
    fun digestOf(corpus: Corpus): String {
        val body = corpus.pairs.joinToString("\n") {
            "${it.a}$SEPARATOR${it.b}$SEPARATOR${it.shouldMatch}"
        }
        return MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val root by lazy {
        val json = GptCacheComparison::class.java.getResourceAsStream("/gptcache-comparison.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("/gptcache-comparison.json is missing from the test classpath")
        Json.parseToJsonElement(json).jsonObject
    }

    private fun field(name: String): String = root.getValue(name).jsonPrimitive.content

    private fun load(): List<GptCacheResult> = root.getValue("corpora").jsonArray.map { element ->
        val fields = element.jsonObject
        GptCacheResult(
            corpus = fields.getValue("corpus").jsonPrimitive.content,
            corpusSha256 = fields.getValue("corpusSha256").jsonPrimitive.content,
            precision = fields.getValue("precision").jsonPrimitive.content.toDouble(),
            recall = fields.getValue("recall").jsonPrimitive.content.toDouble(),
            f1 = fields.getValue("f1").jsonPrimitive.content.toDouble(),
            falseHitRate = fields.getValue("falseHitRate").jsonPrimitive.content.toDouble(),
        )
    }
}

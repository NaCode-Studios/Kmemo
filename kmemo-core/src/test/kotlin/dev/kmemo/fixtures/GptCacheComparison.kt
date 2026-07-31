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
 * **The coupling is a digest.** Each row carries the SHA-256 of the corpus file it was measured
 * against. [verifyAgainstCorpora] recomputes it. A corpus that grew without the harness being re-run
 * fails the build, rather than leaving a stale comparison in the README with a fresh corpus underneath
 * it — which is the only way a committed measurement can quietly become a lie.
 */
object GptCacheComparison {

    val results: List<GptCacheResult> by lazy { load() }

    val measuredOn: String by lazy { field("measuredOn") }

    val evaluator: String by lazy { field("evaluator") }

    val decisionRule: String by lazy { field("decisionRule") }

    fun forCorpus(name: String): GptCacheResult =
        results.firstOrNull { it.corpus == name } ?: error("no GPTCache result for $name")

    /** The corpus files this measurement describes must still be the corpus files on disk. */
    fun verifyAgainstCorpora(corpora: List<Corpus>): List<String> = corpora.mapNotNull { corpus ->
        val recorded = forCorpus(corpus.name).corpusSha256
        val actual = sha256Of("/${corpus.name}-corpus.json")
        if (recorded == actual) {
            null
        } else {
            "${corpus.name}-corpus.json has changed since GPTCache was last run against it " +
                "(recorded $recorded, now $actual). Re-run tools/gptcache-comparison/compare.py."
        }
    }

    private fun sha256Of(resource: String): String {
        val bytes = GptCacheComparison::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: error("$resource is missing from the test classpath")
        return MessageDigest.getInstance("SHA-256").digest(bytes)
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

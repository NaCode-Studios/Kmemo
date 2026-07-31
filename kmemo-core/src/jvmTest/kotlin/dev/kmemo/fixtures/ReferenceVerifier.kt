package dev.kmemo.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/** What the reference verifier decided about one lookup. */
data class VerifierVerdict(
    val split: String,
    val shouldMatch: Boolean,
    val query: String,
    val cached: String,
    val score: Double,
    val served: Boolean,
)

/**
 * A named reference `Verifier`, replayed from the verdicts `tools/verifier-catch-rate` recorded.
 *
 * **It records verdicts rather than a rate, and the difference matters.** A verifier's catch rate is a
 * statement about a population — the near misses the guards let through — and that population moves.
 * Improve a prompt-side guard and the residual shrinks, leaving any recorded percentage describing a
 * set that no longer exists. So the file holds one decision per lookup, and `VerifierCatchRateTest`
 * intersects it with the residual it computes on the day it runs.
 *
 * **The model is named because the number is meaningless without it.** `dev.kmemo.Verifier` is a
 * caller-supplied seam; this measures `cross-encoder/quora-distilroberta-base` and nothing else. What
 * it says about your verifier is only how much of the residual is reachable at all by a model reading
 * the two prompts.
 *
 * **Never a floor.** A gate that spends a model call on every build is a gate that gets deleted, and a
 * floor on somebody else's model is a floor on the wrong thing. What CI checks is that the verdicts
 * still describe the current corpus, not that the rate is good.
 */
object ReferenceVerifier {

    val verdicts: List<VerifierVerdict> by lazy { load() }

    val model: String by lazy { field("model") }

    val implementation: String by lazy { field("referenceImplementation") }

    val decisionRule: String by lazy { field("decisionRule") }

    val measuredOn: String by lazy { field("measuredOn") }

    private val byLookup: Map<Pair<String, String>, VerifierVerdict> by lazy {
        verdicts.associateBy { it.query to it.cached }
    }

    /** The verdict for one lookup, or `null` if the reference verifier was never shown it. */
    fun verdictFor(query: String, cached: String): VerifierVerdict? = byLookup[query to cached]

    /** The response corpus this measurement describes must still be the response corpus on disk. */
    fun staleAgainstCorpus(): String? {
        val bytes = ReferenceVerifier::class.java.getResourceAsStream("/response-corpus.json")
            ?.use { it.readBytes() }
            ?: error("/response-corpus.json is missing from the test classpath")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val recorded = field("responseCorpusSha256")
        return if (recorded == actual) {
            null
        } else {
            "response-corpus.json has changed since the reference verifier was last run against it " +
                "(recorded $recorded, now $actual). Re-run tools/verifier-catch-rate/measure.py."
        }
    }

    private val root by lazy {
        val json = ReferenceVerifier::class.java.getResourceAsStream("/verifier-reference.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("/verifier-reference.json is missing from the test classpath")
        Json.parseToJsonElement(json).jsonObject
    }

    private fun field(name: String): String = root.getValue(name).jsonPrimitive.content

    private fun load(): List<VerifierVerdict> = root.getValue("verdicts").jsonArray.map { element ->
        val fields = element.jsonObject
        VerifierVerdict(
            split = fields.getValue("split").jsonPrimitive.content,
            shouldMatch = fields.getValue("shouldMatch").jsonPrimitive.content.toBoolean(),
            query = fields.getValue("query").jsonPrimitive.content,
            cached = fields.getValue("cached").jsonPrimitive.content,
            score = fields.getValue("score").jsonPrimitive.content.toDouble(),
            served = fields.getValue("served").jsonPrimitive.content.toBoolean(),
        )
    }
}

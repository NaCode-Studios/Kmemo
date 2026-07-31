package dev.kmemo.fixtures

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A corpus pair carrying the answer each prompt would have received.
 *
 * [responseA] is what an assistant replied to [a], [responseB] to [b]. For a near miss the two say
 * different things; for a paraphrase they say the same thing in different words, which is the case a
 * response-aware guard has to leave alone.
 */
data class ResponsePair(
    val split: String,
    val category: String,
    val shouldMatch: Boolean,
    val a: String,
    val b: String,
    val responseA: String,
    val responseB: String,
) {

    /**
     * The two ways this pair can reach a cache: either prompt may be the one already stored when the
     * other arrives, and the stored answer travels with the stored prompt.
     */
    fun scenarios(): List<Scenario> = listOf(
        Scenario(this, query = a, cachedPrompt = b, cachedResponse = responseB),
        Scenario(this, query = b, cachedPrompt = a, cachedResponse = responseA),
    )

    /** One lookup as the cache would actually see it: a query, and one stored entry. */
    data class Scenario(
        val pair: ResponsePair,
        val query: String,
        val cachedPrompt: String,
        val cachedResponse: String,
    )
}

/**
 * The answers, for the pairs that get past the prompt-only guards.
 *
 * **This split is in-sample, and that is not a footnote.** [HELD_OUT_CORPUS] and [VALIDATION_CORPUS]
 * are trustworthy because nobody wrote them with a guard in view. The answers here were written for
 * this measurement, because no corpus of real paired answers exists to harvest — a semantic cache
 * corpus records prompts, and the near misses that matter are precisely the ones whose prompts look
 * alike. So the number [dev.kmemo.guard.ResponseGuardTest] reports is a **regression check**, not a
 * blind measurement, and the README says so wherever it is quoted.
 *
 * Two things were done to keep it as honest as an authored corpus can be:
 *
 * - The answers were written **before** the guard was designed, so the guard could not be reverse
 *   engineered from them, and none of them was rephrased afterwards to make a rejection land.
 * - They were written to be **realistic rather than catchable**: what an assistant would actually
 *   reply, including the many answers that never name the term that separates the two questions.
 *   Those are misses, and they are in the denominator.
 *
 * The prompts themselves are unchanged and still belong to the blind splits, which is what
 * `the response corpus quotes its splits verbatim` asserts.
 */
object ResponseCorpus {

    val pairs: List<ResponsePair> by lazy { load() }

    val nearMisses: List<ResponsePair> get() = pairs.filter { !it.shouldMatch }

    val paraphrases: List<ResponsePair> get() = pairs.filter { it.shouldMatch }

    /** The blind corpus each pair was taken from, so its prompts can be checked against the source. */
    fun sourceOf(pair: ResponsePair): Corpus = when (pair.split) {
        "held-out" -> HELD_OUT_CORPUS
        "validation" -> VALIDATION_CORPUS
        else -> error("unknown split ${pair.split}")
    }

    private fun load(): List<ResponsePair> {
        val json = ResponseCorpus::class.java.getResourceAsStream("/response-corpus.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("/response-corpus.json is missing from the test classpath")

        return Json.parseToJsonElement(json).jsonObject
            .getValue("pairs").jsonArray
            .map { element ->
                val fields = element.jsonObject
                ResponsePair(
                    split = fields.getValue("split").jsonPrimitive.content,
                    category = fields.getValue("category").jsonPrimitive.content,
                    shouldMatch = fields.getValue("shouldMatch").jsonPrimitive.content.toBoolean(),
                    a = fields.getValue("a").jsonPrimitive.content,
                    b = fields.getValue("b").jsonPrimitive.content,
                    responseA = fields.getValue("responseA").jsonPrimitive.content,
                    responseB = fields.getValue("responseB").jsonPrimitive.content,
                )
            }
    }
}

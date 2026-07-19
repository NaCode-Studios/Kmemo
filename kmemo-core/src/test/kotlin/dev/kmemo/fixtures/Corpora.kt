package dev.kmemo.fixtures

import dev.kmemo.calibration.PromptPair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Two prompts and a verdict on whether one's cached answer may serve the other. */
data class CorpusPair(
    val a: String,
    val b: String,
    val shouldMatch: Boolean,
    val category: String,
)

/**
 * A labelled set of prompt pairs.
 *
 * kmemo keeps **two**, and the split is the point. Tuning a guard against a corpus and then quoting
 * that corpus as evidence measures the tuning, not the guard — which is exactly what happened here:
 * three rounds of fitting produced a 96% catch rate that fell to 26% the first time anyone tried
 * prompts from outside it.
 */
class Corpus(private val resource: String, val name: String) {

    val pairs: List<CorpusPair> by lazy { load() }

    /** Pairs that must never be served from cache. */
    val nearMisses: List<CorpusPair> get() = pairs.filter { !it.shouldMatch }

    /** Pairs that must stay cacheable, or the cache is worthless. */
    val paraphrases: List<CorpusPair> get() = pairs.filter { it.shouldMatch }

    fun asPromptPairs(): List<PromptPair> =
        pairs.map { PromptPair(a = it.a, b = it.b, shouldMatch = it.shouldMatch, label = it.category) }

    private fun load(): List<CorpusPair> {
        val json = Corpus::class.java.getResourceAsStream(resource)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$resource is missing from the test classpath")

        return Json.parseToJsonElement(json).jsonObject
            .getValue("pairs").jsonArray
            .map { element ->
                val fields = element.jsonObject
                CorpusPair(
                    a = fields.getValue("a").jsonPrimitive.content,
                    b = fields.getValue("b").jsonPrimitive.content,
                    shouldMatch = fields.getValue("shouldMatch").jsonPrimitive.content.toBoolean(),
                    category = fields.getValue("category").jsonPrimitive.content,
                )
            }
    }
}

/**
 * The corpus the guards were built against — 109 pairs, mostly a single token apart.
 *
 * Every guard was written or tuned with these in view, so its numbers are **in-sample** and cannot
 * be read as a measure of quality. It is a regression test: it catches the day a change breaks
 * something that used to work.
 */
val TUNED_CORPUS: Corpus = Corpus("/near-miss-corpus.json", "tuned")

/**
 * The held-out set — 128 pairs, no overlap with [TUNED_CORPUS], covering domains it never touches:
 * clinical dosing, tax jurisdictions, database isolation levels, regex flavours, shell dialects,
 * HTTP status codes, chemistry, sports statistics, gross-versus-net, percent-versus-percentage-point.
 *
 * **Provenance.** Written by an adversarial review that was given the guard sources and asked to
 * break them, then spot-checked by hand. It found the chain rejecting 22 of 86 near misses where the
 * tuned corpus reported 96%, and that gap is the reason this file exists.
 *
 * **Rule for using it: never tune against it.** The moment a guard is adjusted to make a pair here
 * pass, this stops being a held-out set and the project is back to grading its own homework. Tune on
 * [TUNED_CORPUS]; report both.
 */
val HELD_OUT_CORPUS: Corpus = Corpus("/held-out-corpus.json", "held-out")

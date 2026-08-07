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
class Corpus private constructor(
    val name: String,
    val standing: CorpusStanding,
    private val loader: () -> List<CorpusPair>,
) {

    constructor(resource: String, name: String, standing: CorpusStanding) :
        this(name, standing, { fromResource(resource) })

    val pairs: List<CorpusPair> by lazy { loader() }

    /** Pairs that must never be served from cache. */
    val nearMisses: List<CorpusPair> get() = pairs.filter { !it.shouldMatch }

    /** Pairs that must stay cacheable, or the cache is worthless. */
    val paraphrases: List<CorpusPair> get() = pairs.filter { it.shouldMatch }

    fun asPromptPairs(): List<PromptPair> =
        pairs.map { PromptPair(a = it.a, b = it.b, shouldMatch = it.shouldMatch, label = it.category) }

    companion object {

        /**
         * A corpus over pairs already in hand rather than over a resource on the classpath.
         *
         * The external split is fetched at build time and the derived long-prompt split is computed
         * from it, so neither has a resource to name. Both still have to be measurable by the same
         * report as the three committed splits, or the report would describe the short prompts and
         * call itself a measurement of the guards.
         */
        fun of(
            name: String,
            pairs: List<CorpusPair>,
            standing: CorpusStanding = CorpusStanding.BLIND,
        ): Corpus = Corpus(name, standing) { pairs }

        fun fromResource(resource: String): List<CorpusPair> {
            val json = Corpus::class.java.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("$resource is missing from the test classpath")
            return parse(json)
        }

        fun parse(json: String): List<CorpusPair> =
            Json.parseToJsonElement(json).jsonObject
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
val TUNED_CORPUS: Corpus = Corpus("/near-miss-corpus.json", "tuned", CorpusStanding.IN_SAMPLE)

/**
 * The held-out set — 128 pairs, no overlap with [TUNED_CORPUS], covering domains it never touches:
 * clinical dosing, tax jurisdictions, database isolation levels, regex flavours, shell dialects,
 * HTTP status codes, chemistry, sports statistics, gross-versus-net, percent-versus-percentage-point.
 *
 * **Provenance.** Written by an adversarial review that was given the guard sources and asked to
 * break them, then spot-checked by hand. It found the chain rejecting 22 of 86 near misses where the
 * tuned corpus reported 96%, and that gap is the reason this file exists.
 *
 * **Retired, and still enforced.** Its failures were read while guards were being fixed, and there is
 * no way to un-see them, so it stopped being out-of-sample evidence long before M54 named the state.
 * It keeps its floors and stays in every report, because a spent split is a perfectly good regression
 * gate. What it no longer is, is a number to quote as blind. `docs/CORPUS.md` states the policy.
 */
val HELD_OUT_CORPUS: Corpus = Corpus("/held-out-corpus.json", "held-out", CorpusStanding.RETIRED)

/**
 * The validation set — 153 pairs across cooking, gardening, home repair, insurance, travel, pets,
 * cars, music, photography, childcare and more, of which only a sixth is software.
 *
 * **Provenance.** Written blind: the author was given a description of what a semantic cache is and
 * how it fails, and was shown no guard source, no vocabulary and no existing corpus. It was written
 * *before* the guard fixes it grades, so nothing in it could have been aimed at them.
 *
 * **Nine tenths of its prompts are lowercase**, on purpose. Real users type that way, and an earlier
 * measurement showed capitalization was silently carrying a third of the entity catches — a corpus
 * written in tidy prose hides exactly that.
 *
 * **Retired, and the reason is this project's own tooling.** `CorpusTest` printed this split's
 * residual in full on every run, so its failures were in front of anybody who ran the suite, and they
 * were read while M51's substitution floor was being investigated. That is the act `docs/CORPUS.md`
 * prohibits, and the report was doing it by default. M53 stopped the printing and M54 retired the
 * split rather than pretending the reading did not happen.
 *
 * It keeps its floors and stays in every report as a regression gate. The blind evidence now lives in
 * the two fetched splits, which are larger by an order of magnitude and which nobody here can touch.
 */
val VALIDATION_CORPUS: Corpus = Corpus("/validation-corpus.json", "validation", CorpusStanding.RETIRED)

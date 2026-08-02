package dev.kmemo.guard.tck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Two prompts and a verdict on whether one's cached answer may serve the other.
 *
 * @param a one prompt.
 * @param b the other.
 * @param shouldMatch `true` for a **paraphrase** (the two need the same answer, so a guard that
 *   rejects the pair costs a hit), `false` for a **near miss** (they need different answers, so a
 *   guard that abstains lets a wrong answer through).
 * @param category free-form label for grouping a report, such as `numeric` or `dosage`.
 */
public class GuardPair(
    public val a: String,
    public val b: String,
    public val shouldMatch: Boolean,
    public val category: String = "uncategorised",
) {
    override fun toString(): String = "GuardPair(${if (shouldMatch) "paraphrase" else "near-miss"}: $a || $b)"
}

/**
 * A labelled set of prompt pairs a guard can be measured against.
 *
 * Build one from your own domain — that is the point, and it is the half of the measurement this
 * library cannot do for you. The three corpora shipped here are general English; a guard about
 * dosages, jurisdictions or settlement dates will find nothing in them, which is the *right* result
 * and is exactly what makes them useful: they are how you show your guard does no harm outside its
 * domain. Whether it does any good inside its domain is a number only your own corpus can produce.
 */
public class GuardCorpus(
    /** Short name, used in the report. */
    public val name: String,
    /** The labelled pairs. */
    public val pairs: List<GuardPair>,
) {
    /** Pairs that must never be served from cache. A guard's catches come from here. */
    public val nearMisses: List<GuardPair> get() = pairs.filter { !it.shouldMatch }

    /** Pairs that must stay cacheable. A guard's false rejections come from here. */
    public val paraphrases: List<GuardPair> get() = pairs.filter { it.shouldMatch }

    override fun toString(): String = "GuardCorpus($name, ${pairs.size} pairs)"

    public companion object {

        /**
         * Loads a corpus from JSON of the shape kmemo's own corpora use.
         *
         * ```json
         * { "pairs": [ { "a": "...", "b": "...", "shouldMatch": false, "category": "dosage" } ] }
         * ```
         *
         * `category` is optional. Anything else in the file is ignored, so a corpus may carry
         * provenance fields of its own without this refusing to read it.
         */
        public fun fromJson(name: String, json: String): GuardCorpus {
            val pairs = Json.parseToJsonElement(json).jsonObject
                .getValue("pairs").jsonArray
                .map { element ->
                    val fields = element.jsonObject
                    GuardPair(
                        a = fields.getValue("a").jsonPrimitive.content,
                        b = fields.getValue("b").jsonPrimitive.content,
                        shouldMatch = fields.getValue("shouldMatch").jsonPrimitive.content.toBoolean(),
                        category = fields["category"]?.jsonPrimitive?.content ?: "uncategorised",
                    )
                }
            return GuardCorpus(name, pairs)
        }

        /**
         * Loads a corpus from a classpath [resource], for a corpus you ship beside your own tests.
         *
         * @throws IllegalStateException if the resource is not on the classpath, rather than
         *   returning an empty corpus. A guard measured against nothing scores perfectly, and a
         *   perfect score from a missing file is the most misleading number this suite could produce.
         */
        public fun fromResource(name: String, resource: String): GuardCorpus {
            val text = GuardCorpus::class.java.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: error("$resource is not on the test classpath")
            return fromJson(name, text)
        }

        /**
         * The corpus kmemo's guards were written against. **In sample**: every built-in guard was
         * tuned with these pairs in view, so its numbers here measure the fitting rather than the
         * guard. Useful to a third party as a source of hard general-English pairs, not as evidence.
         */
        public fun tuned(): GuardCorpus = fromResource("tuned", "/near-miss-corpus.json")

        /**
         * Written after the built-in guards existed, by an adversarial review given the guard sources
         * and asked to break them. Out of sample for the built-ins, and fully out of sample for yours.
         */
        public fun heldOut(): GuardCorpus = fromResource("held-out", "/held-out-corpus.json")

        /**
         * Written blind, by an author shown no guard source and no other corpus, and nine tenths
         * lowercase because real users type that way. The number kmemo quotes, and the one to quote.
         */
        public fun validation(): GuardCorpus = fromResource("validation", "/validation-corpus.json")

        /**
         * All three shipped splits, in the order kmemo reports them.
         *
         * These are what [MatchGuardContract] measures a candidate guard against by default, and what
         * `docs/CORPUS.md` describes in full.
         */
        public fun shipped(): List<GuardCorpus> = listOf(tuned(), heldOut(), validation())
    }
}

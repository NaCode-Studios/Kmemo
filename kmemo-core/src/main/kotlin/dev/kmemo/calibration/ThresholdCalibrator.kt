package dev.kmemo.calibration

import dev.kmemo.Embedder
import dev.kmemo.Vectors
import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.MatchGuards
import dev.kmemo.internal.Format

/**
 * Two prompts and a verdict on whether one's cached answer may serve the other.
 *
 * @param shouldMatch `true` when serving [a]'s cached response to [b] is correct.
 * @param label optional grouping tag, echoed back in reports — `entity-swap`, `paraphrase`, and so
 *   on — so you can see *which kind* of pair a threshold is getting wrong.
 */
public data class PromptPair(
    public val a: String,
    public val b: String,
    public val shouldMatch: Boolean,
    public val label: String? = null,
)

/** How one threshold performed over the whole set of pairs. */
public data class ThresholdOutcome(
    public val threshold: Double,
    /** Pairs correctly served from cache. */
    public val truePositives: Int,
    /** **False hits**: pairs served a cached answer that does not answer them. */
    public val falsePositives: Int,
    /** Pairs correctly refused. */
    public val trueNegatives: Int,
    /** Pairs refused that could have been served — a wasted API call, nothing worse. */
    public val falseNegatives: Int,
) {
    /** Of everything served from cache, the fraction that was correct. */
    public val precision: Double
        get() = ratio(truePositives, truePositives + falsePositives)

    /** Of everything that could have been served, the fraction that was. Your hit rate ceiling. */
    public val recall: Double
        get() = ratio(truePositives, truePositives + falseNegatives)

    /** Harmonic mean of [precision] and [recall]. */
    public val f1: Double
        get() {
            val denominator = precision + recall
            return if (denominator == 0.0) 0.0 else 2.0 * precision * recall / denominator
        }

    /**
     * Of the pairs that must **not** match, the fraction served anyway.
     *
     * The number to look at first. Recall costs money; this costs correctness.
     */
    public val falseHitRate: Double
        get() = ratio(falsePositives, falsePositives + trueNegatives)

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()
}

/** Result of a threshold sweep. */
public data class CalibrationReport(
    public val pairCount: Int,
    /** Pairs labelled `shouldMatch = true`. */
    public val matchingPairs: Int,
    /** Pairs a guard vetoed, whatever the threshold. */
    public val guardVetoes: Int,
    /** One entry per tested threshold, ascending. */
    public val outcomes: List<ThresholdOutcome>,
    /** The suggested setting: highest recall among thresholds within the false-hit budget. */
    public val recommended: ThresholdOutcome,
    /** Lowest threshold with no false hits at all, if any threshold achieved that. */
    public val safest: ThresholdOutcome?,
    /** Best balance of precision and recall, ignoring the false-hit budget. */
    public val bestF1: ThresholdOutcome,
) {

    /** Printable sweep table, plus the recommendation and how it was chosen. */
    public fun summary(): String = buildString {
        appendLine("Calibration over $pairCount pairs ($matchingPairs should match, $guardVetoes vetoed by guards)")
        appendLine()
        appendLine("threshold   hits   false-hits   missed   precision   recall   false-hit-rate")
        for (outcome in outcomes) {
            appendLine(
                Format.padStart(Format.fixed(outcome.threshold, 2), 9) +
                    Format.padStart(outcome.truePositives.toString(), 7) +
                    Format.padStart(outcome.falsePositives.toString(), 13) +
                    Format.padStart(outcome.falseNegatives.toString(), 9) +
                    Format.padStart(Format.fixed(outcome.precision, 3), 12) +
                    Format.padStart(Format.fixed(outcome.recall, 3), 9) +
                    Format.padStart(Format.fixed(outcome.falseHitRate, 3), 17),
            )
        }
        appendLine()
        appendLine(
            "recommended threshold ${Format.fixed(recommended.threshold, 2)} — " +
                "recall ${Format.fixed(recommended.recall, 3)}, " +
                "false-hit rate ${Format.fixed(recommended.falseHitRate, 3)}",
        )
        if (safest != null) {
            appendLine(
                "lowest threshold with zero false hits: ${Format.fixed(safest.threshold, 2)} " +
                    "(recall ${Format.fixed(safest.recall, 3)})",
            )
        } else {
            appendLine("no threshold in the swept range eliminated false hits entirely")
        }
    }
}

/**
 * Finds the similarity threshold that fits *your* embedding model, by measuring instead of guessing.
 *
 * A threshold copied from a blog post is a threshold tuned to somebody else's model. The same pair
 * of prompts might score 0.86 with one embedding model and 0.94 with another, so a value that is
 * safely tight for one is dangerously loose for the next — and the failure is silent, because a
 * false hit looks exactly like a fast response.
 *
 * Give the calibrator prompt pairs you have labelled and it sweeps the threshold range, reporting
 * how many correct hits and how many wrong answers each setting produces on your own model, with
 * your own guards in the loop:
 *
 * ```kotlin
 * val report = ThresholdCalibrator(myEmbedder).calibrate(myLabelledPairs)
 * println(report.summary())
 * val cache = SemanticCache(myEmbedder, threshold = report.recommended.threshold)
 * ```
 *
 * A hundred pairs drawn from your own traffic is plenty, and half of them should be near-misses:
 * prompts that look alike but need different answers. Pairs that are obviously unrelated teach the
 * calibrator nothing, because no threshold ever confuses them.
 *
 * @param embedder the model you intend to cache with — results do not transfer between models.
 * @param guards the guards you intend to run. Pass [MatchGuards.none] to measure the
 *   similarity-only baseline and see what the guards are buying you.
 */
public class ThresholdCalibrator(
    private val embedder: Embedder,
    private val guards: List<MatchGuard> = MatchGuards.standard(),
) {

    /**
     * Embeds every pair once, then evaluates every threshold in [range].
     *
     * Costs one embedding call per distinct prompt, batched through [Embedder.embedAll].
     *
     * @param maxFalseHitRate the share of near-misses you are willing to serve wrongly.
     *   `0.0` — no wrong answers — is the default, and the right starting point.
     */
    public suspend fun calibrate(
        pairs: List<PromptPair>,
        range: ClosedFloatingPointRange<Double> = DEFAULT_RANGE,
        step: Double = DEFAULT_STEP,
        maxFalseHitRate: Double = 0.0,
    ): CalibrationReport {
        require(pairs.isNotEmpty()) { "need at least one pair to calibrate" }
        require(step > 0.0) { "step must be positive, was $step" }
        require(!range.isEmpty()) { "range must not be empty" }
        require(maxFalseHitRate in 0.0..1.0) {
            "maxFalseHitRate must be within [0.0, 1.0], was $maxFalseHitRate"
        }

        val scored = score(pairs)
        val outcomes = buildList {
            var threshold = range.start
            while (threshold <= range.endInclusive + EPSILON) {
                add(evaluate(scored, threshold))
                threshold += step
            }
        }

        val safest = outcomes.firstOrNull { it.falsePositives == 0 }
        val withinBudget = outcomes.filter { it.falseHitRate <= maxFalseHitRate }
        // Within the budget, the lowest threshold wins: it is the one that caches the most.
        val recommended = withinBudget.maxByOrNull { it.recall }
            ?: outcomes.minByOrNull { it.falseHitRate + (1.0 - it.recall) * TIE_BREAK_WEIGHT }
            ?: outcomes.last()

        return CalibrationReport(
            pairCount = pairs.size,
            matchingPairs = pairs.count { it.shouldMatch },
            guardVetoes = scored.count { it.vetoed },
            outcomes = outcomes,
            recommended = recommended,
            safest = safest,
            bestF1 = outcomes.maxByOrNull { it.f1 } ?: outcomes.last(),
        )
    }

    private suspend fun score(pairs: List<PromptPair>): List<ScoredPair> {
        val texts = pairs.flatMap { listOf(it.a, it.b) }.distinct()
        val vectors = embedder.embedAll(texts)
        check(vectors.size == texts.size) {
            "embedder returned ${vectors.size} vectors for ${texts.size} texts"
        }
        val byText = texts.zip(vectors.map { Vectors.normalize(it) }).toMap()

        return pairs.map { pair ->
            ScoredPair(
                pair = pair,
                similarity = Vectors.dot(byText.getValue(pair.a), byText.getValue(pair.b)),
                vetoed = guards.any { it.evaluate(pair.b, pair.a) is GuardVerdict.Reject },
            )
        }
    }

    private fun evaluate(scored: List<ScoredPair>, threshold: Double): ThresholdOutcome {
        var truePositives = 0
        var falsePositives = 0
        var trueNegatives = 0
        var falseNegatives = 0

        for (entry in scored) {
            val served = entry.similarity >= threshold && !entry.vetoed
            when {
                served && entry.pair.shouldMatch -> truePositives++
                served -> falsePositives++
                entry.pair.shouldMatch -> falseNegatives++
                else -> trueNegatives++
            }
        }

        return ThresholdOutcome(threshold, truePositives, falsePositives, trueNegatives, falseNegatives)
    }

    private class ScoredPair(
        val pair: PromptPair,
        val similarity: Double,
        val vetoed: Boolean,
    )

    public companion object {
        /** Below 0.70 nothing sensible survives; above 0.99 nothing matches. */
        public val DEFAULT_RANGE: ClosedFloatingPointRange<Double> = 0.70..0.99

        /** Finer than the noise in most embedding models. */
        public const val DEFAULT_STEP: Double = 0.01

        private const val EPSILON = 1e-9
        private const val TIE_BREAK_WEIGHT = 0.1
    }
}

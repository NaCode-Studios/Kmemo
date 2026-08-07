package dev.kmemo.guard.tck

import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * One guard's confusion matrix on one corpus.
 *
 * The two numbers that matter are [caught] and [falseRejections], and neither means anything without
 * the other. A guard that catches everything by rejecting everything has turned a cache into an
 * expensive proxy; a guard that never falsely rejects because it never fires does nothing while
 * looking like it does something. Read them as a pair, always.
 */
public class CorpusScore(
    /** The corpus these numbers came from. */
    public val corpus: String,
    /** Pairs whose answers must differ. */
    public val nearMisses: Int,
    /** Pairs whose answers may be shared. */
    public val paraphrases: Int,
    /** Near misses this guard rejected: wrong answers it would have stopped. */
    public val caught: Int,
    /** Paraphrases this guard rejected: real hits it would have cost, one API call each. */
    public val falseRejections: Int,
    /**
     * Pairs where the guard's verdict depends on which prompt arrived first.
     *
     * Reported, never asserted. A directional guard is legitimate, and kmemo's own `subspan` is one,
     * because "the same question plus a narrowing clause" is a relationship with a direction. But an
     * author who did not *mean* to be directional has no other way to find out, and in a cache the
     * consequence is a verdict that changes depending on which of two prompts a user happened to ask
     * first.
     */
    public val directionDisagreements: Int,
) {
    /** Share of near misses rejected, in `[0.0, 1.0]`. `0.0` when the corpus has none. */
    public val catchRate: Double get() = if (nearMisses == 0) 0.0 else caught.toDouble() / nearMisses

    /** Share of paraphrases wrongly rejected, in `[0.0, 1.0]`. `0.0` when the corpus has none. */
    public val falseRejectionRate: Double
        get() = if (paraphrases == 0) 0.0 else falseRejections.toDouble() / paraphrases

    /**
     * [catchRate] with the range the corpus supports.
     *
     * A rate from a hundred pairs and a rate from ten thousand are not the same kind of number, and
     * printing them in the same column says they are. See [ScoreInterval].
     */
    public val catchRateInterval: ScoreInterval get() = ScoreInterval.wilson95(caught, nearMisses)

    /** [falseRejectionRate] with the range the corpus supports. */
    public val falseRejectionRateInterval: ScoreInterval
        get() = ScoreInterval.wilson95(falseRejections, paraphrases)

    override fun toString(): String = String.format(
        Locale.ROOT,
        "%-12s near misses caught %3d/%-3d (%3.0f%% ±%.1f), false rejections %3d/%-3d (%3.0f%% ±%.1f), " +
            "direction disagreements %3d",
        corpus, caught, nearMisses, 100.0 * catchRate, catchRateInterval.halfWidthPoints,
        falseRejections, paraphrases, 100.0 * falseRejectionRate,
        falseRejectionRateInterval.halfWidthPoints,
        directionDisagreements,
    )
}

/**
 * A guard measured across every corpus it was run against, as data and as a printable table.
 *
 * The same confusion matrix kmemo publishes for its own eleven guards, computed by the same code, so
 * an outside guard arrives with a measured number attached rather than with a claim.
 */
public class GuardComplianceReport(
    /** The guard's [MatchGuard.name]. */
    public val guard: String,
    /** One score per corpus, in the order they were given. */
    public val scores: List<CorpusScore>,
) {

    /** The report as JSON, for CI to diff across commits the way kmemo diffs its own. */
    public fun toJson(): JsonObject = buildJsonObject {
        put("guard", guard)
        putJsonArray("corpora") {
            for (score in scores) {
                addJsonObject {
                    put("corpus", score.corpus)
                    put("nearMisses", score.nearMisses)
                    put("paraphrases", score.paraphrases)
                    put("caught", score.caught)
                    put("falseRejections", score.falseRejections)
                    put("directionDisagreements", score.directionDisagreements)
                    // The interval, not only the rate. A number diffed across commits without one
                    // reads as a change every time the corpus is small and the pairs move by two.
                    put("catchRateLow", score.catchRateInterval.low)
                    put("catchRateHigh", score.catchRateInterval.high)
                    put("falseRejectionRateLow", score.falseRejectionRateInterval.low)
                    put("falseRejectionRateHigh", score.falseRejectionRateInterval.high)
                }
            }
        }
    }

    public fun toJsonString(): String = PRETTY.encodeToString(JsonObject.serializer(), toJson())

    /** The human table, one line per corpus, under a heading naming the guard. */
    override fun toString(): String = buildString {
        appendLine("guard '$guard'")
        for (score in scores) appendLine("  $score")
    }

    public companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** Measures [guard] over every corpus in [corpora]. */
        public fun of(guard: MatchGuard, corpora: List<GuardCorpus>): GuardComplianceReport =
            GuardComplianceReport(guard.name, corpora.map { score(guard, it) })

        private fun score(guard: MatchGuard, corpus: GuardCorpus): CorpusScore = CorpusScore(
            corpus = corpus.name,
            nearMisses = corpus.nearMisses.size,
            paraphrases = corpus.paraphrases.size,
            caught = corpus.nearMisses.count { rejects(guard, it) },
            falseRejections = corpus.paraphrases.count { rejects(guard, it) },
            directionDisagreements = corpus.pairs.count { disagrees(guard, it) },
        )

        /**
         * Whether the guard refuses this pair **in either direction**.
         *
         * Either prompt could be the one already in the cache when the other arrives, so a guard that
         * fires one way round protects the pair, and costs the hit, just the same. Measuring one
         * direction would report half of what the guard actually does.
         */
        internal fun rejects(guard: MatchGuard, pair: GuardPair): Boolean =
            guard.evaluate(pair.a, pair.b) is GuardVerdict.Reject ||
                guard.evaluate(pair.b, pair.a) is GuardVerdict.Reject

        internal fun disagrees(guard: MatchGuard, pair: GuardPair): Boolean =
            (guard.evaluate(pair.a, pair.b) is GuardVerdict.Reject) !=
                (guard.evaluate(pair.b, pair.a) is GuardVerdict.Reject)
    }
}

package dev.kmemo.guard

import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.QqpCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.util.Locale
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M52: what the chain costs per candidate, which nothing here had ever measured.
 *
 * The contribution side of a guard has been reported since `1.0` and the cost side never was, so
 * "eleven guards is eleven tokenizations on the read path" was an argument nobody could price. It is
 * a small number and knowing that it is small is the point: a decision to drop a guard for
 * performance should have to survive seeing it.
 *
 * **This is a wall-clock measurement on one JVM and it is labelled as one.** It is not a JMH harness;
 * `kmemo-benchmarks` is where those live. What it can say is the order of magnitude and the split
 * between guards, and both are stable enough across runs to be worth publishing. What it cannot say
 * is a number to compare against somebody else's hardware.
 *
 * **Every guard is run on every candidate here, and the cache does not.** `SemanticCache` stops at
 * the first rejection, so a real lookup pays this only when every guard abstains, which is the
 * case that ends in a hit. The number is therefore the ceiling rather than the average, which is the
 * direction a cost figure should err in.
 */
class GuardChainCostTest {

    /**
     * The cost has to stay small enough that it is never the reason to remove a guard.
     *
     * A ceiling rather than a target, set an order of magnitude above the measurement so it fails on
     * a mechanism change rather than on a noisy machine. A candidate that took a millisecond of
     * lexical work would be a different library.
     */
    @Test
    fun `the chain costs microseconds per candidate, not milliseconds`() {
        val pairs = corpusPairs()
        val nanos = timePerCandidate(MatchGuards.standard(), pairs)
        assertTrue(
            nanos < CEILING_NANOS,
            "the standard chain took ${nanos.toLong()} ns per candidate, past the ${CEILING_NANOS} " +
                "ceiling. That is a mechanism change rather than a slow machine.",
        )
    }

    /** Not an assertion: the table `docs/MEASUREMENTS.md` carries beside the contribution numbers. */
    @Test
    fun `print the per-candidate cost`() {
        val pairs = corpusPairs()
        val guards = MatchGuards.standard()

        val chain = timePerCandidate(guards, pairs)
        println()
        println(
            String.format(
                Locale.ROOT,
                "Guard cost per candidate, over %d pairs, both directions, warmed: chain %.0f ns",
                pairs.size, chain,
            ),
        )
        for (guard in guards) {
            val alone = timePerCandidate(listOf(guard), pairs)
            println(
                String.format(
                    Locale.ROOT,
                    "  %-22s %6.0f ns  (%4.1f%% of the chain)",
                    guard.name, alone, 100.0 * alone / chain,
                ),
            )
        }
        println()
        println(
            String.format(
                Locale.ROOT,
                "Chain without lexical-divergence: %.0f ns. Wall clock on one JVM, not a JMH result.",
                timePerCandidate(guards.filterNot { it is LexicalDivergenceGuard }, pairs),
            ),
        )
        println()
    }

    /**
     * Every committed pair plus a slice of the fetched question split, so the timing is taken over
     * prompts of realistic length rather than over the shortest corpus available.
     */
    private fun corpusPairs(): List<CorpusPair> =
        TUNED_CORPUS.pairs + HELD_OUT_CORPUS.pairs + VALIDATION_CORPUS.pairs +
            (QqpCorpus.pairs()?.take(SAMPLED_QQP_PAIRS) ?: emptyList())

    private fun timePerCandidate(guards: List<MatchGuard>, pairs: List<CorpusPair>): Double {
        repeat(WARMUP_ROUNDS) { evaluateAll(guards, pairs) }
        val best = (1..MEASURED_ROUNDS).minOf { measureNanoTime { evaluateAll(guards, pairs) } }
        // Two evaluations per pair, because a candidate is judged in both directions.
        return best.toDouble() / (pairs.size * 2)
    }

    private fun evaluateAll(guards: List<MatchGuard>, pairs: List<CorpusPair>) {
        var sink = 0
        for (pair in pairs) {
            for (guard in guards) {
                if (guard.evaluate(pair.a, pair.b) is GuardVerdict.Reject) sink++
                if (guard.evaluate(pair.b, pair.a) is GuardVerdict.Reject) sink++
            }
        }
        check(sink >= 0)
    }

    private companion object {
        private const val WARMUP_ROUNDS = 5
        private const val MEASURED_ROUNDS = 7
        private const val SAMPLED_QQP_PAIRS = 2_000
        private const val CEILING_NANOS = 200_000.0
    }
}

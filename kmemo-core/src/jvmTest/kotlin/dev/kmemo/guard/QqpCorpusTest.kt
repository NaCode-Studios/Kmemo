package dev.kmemo.guard

import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.QqpCorpus
import dev.kmemo.guard.tck.ScoreInterval
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M54: the guards measured on questions this repository did not write, at a size where a change is
 * readable.
 *
 * PAWS closed the objection that the same person wrote the pairs and the guards. It left two things
 * open. PAWS is declarative Wikipedia prose and the guards read prompts, so every question-register
 * figure this project published still came from a corpus it wrote. And the two written blind splits
 * hold 86 and 102 near misses, which supports an interval about nine points wide in each direction:
 * at that size a genuine five-point improvement and a lucky run are the same measurement.
 *
 * Quora Question Pairs closes both. The questions were typed by the public on a Q&A site between 2015
 * and 2017 and the duplicate labels were applied by Quora, years before this library existed. After
 * the selection rule in `tools/qqp-corpus/README.md` the split holds 2,500 near misses in the register
 * the guards were built for, which is twenty-five times the validation split.
 *
 * **Its provenance is weaker than PAWS's in one specific way and the difference is not blurred.**
 * Nobody here can add to PAWS at all. Here, Quora wrote and labelled the pairs and this repository
 * chose one selection threshold, once, before running a guard against the result. `docs/CORPUS.md`
 * records that, along with the one guard the selection is not neutral for and what crowd-applied
 * labels are worth.
 *
 * The rule that governs it is the strict one: **no guard may ever be tuned against it, and no failure
 * from it may be read while a guard is being changed.**
 */
class QqpCorpusTest {

    @Test
    fun `the question split does not regress`() {
        val corpus = QqpCorpus.corpus() ?: return
        val guards = MatchGuards.standard()

        val caught = corpus.nearMisses.count { rejects(guards, it) }
        val kept = corpus.paraphrases.count { !rejects(guards, it) }

        assertTrue(
            caught >= QQP_NEAR_MISS_FLOOR,
            "qqp split caught $caught/${corpus.nearMisses.size}, below the $QQP_NEAR_MISS_FLOOR floor",
        )
        assertTrue(
            kept >= QQP_PARAPHRASE_FLOOR,
            "qqp split kept $kept/${corpus.paraphrases.size}, below the $QQP_PARAPHRASE_FLOOR floor",
        )
    }

    /**
     * The size claim, asserted rather than asserted about.
     *
     * M54's exit criterion is that the blind evidence reaches a size where a five-point change in
     * catch rate is distinguishable from noise at 95%. This is that sentence as a check, so that a
     * later change to the selection rule cannot quietly take the split back under the line.
     */
    @Test
    fun `the split is large enough for a five-point change to be readable`() {
        val corpus = QqpCorpus.corpus() ?: return
        val needed = ScoreInterval.trialsToSeparate(points = 5.0, around = 0.68)
        assertTrue(
            corpus.nearMisses.size >= needed,
            "qqp holds ${corpus.nearMisses.size} near misses; a five-point change needs $needed",
        )
    }

    /** Not an assertion: the report the README quotes beside the others. Prints no individual pair. */
    @Test
    fun `print the question corpus report`() {
        val corpus = QqpCorpus.corpus() ?: return
        val guards = MatchGuards.standard()
        val caught = corpus.nearMisses.count { rejects(guards, it) }
        val kept = corpus.paraphrases.count { !rejects(guards, it) }
        val catchInterval = ScoreInterval.wilson95(caught, corpus.nearMisses.size)
        val keptInterval = ScoreInterval.wilson95(kept, corpus.paraphrases.size)

        println()
        println(
            String.format(
                Locale.ROOT,
                "qqp       corpus (blind): %d pairs, near misses rejected %d/%d (%.1f%% ±%.1f), " +
                    "paraphrases kept %d/%d (%.1f%% ±%.1f)",
                corpus.pairs.size,
                caught, corpus.nearMisses.size, 100.0 * caught / corpus.nearMisses.size,
                catchInterval.halfWidthPoints,
                kept, corpus.paraphrases.size, 100.0 * kept / corpus.paraphrases.size,
                keptInterval.halfWidthPoints,
            ),
        )
        println("  per guard: alone, and what the chain would lose without it")
        val report = GuardReport.of(guards, listOf(corpus)).corpora.single()
        for (stat in report.perGuard) {
            println(
                String.format(
                    Locale.ROOT,
                    "    %-22s alone: caught %4d, false rejections %4d   marginal: unique %3d, " +
                        "paraphrases lost to it alone %3d",
                    stat.guard, stat.caught, stat.falseRejections,
                    stat.uniqueCatches, stat.uniqueFalseRejections,
                ),
            )
        }
        println()
        println("Quora Question Pairs, GLUE validation, filtered to pairs sharing at least 60% of their")
        println("character 4-grams. Questions typed by the public and labelled by Quora, years before")
        println("this project existed. The labels are crowd-applied and the dataset's own card calls")
        println("them noisy, which caps any score here in both directions.")
        println()
    }

    /** Either direction, because either question could be the one already cached. */
    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private companion object {
        // Set at the measurement rather than under it, for the same reason the external floors are:
        // nothing here is stochastic, the guards are pure and the dataset is pinned to a commit, so
        // any movement at all is a real change somebody should have to look at. They only move up.
        private const val QQP_NEAR_MISS_FLOOR = 1634
        private const val QQP_PARAPHRASE_FLOOR = 2205
    }
}

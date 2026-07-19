package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.TUNED_CORPUS
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The guard layer measured against both corpora, with no embedder involved.
 *
 * Two numbers are reported for everything, because one of them is not evidence. The guards were
 * written and tuned with [TUNED_CORPUS] in view, so its score measures the fitting. Only
 * [HELD_OUT_CORPUS] says what the guards do to prompts nobody tuned against — and the first time it
 * was run, the catch rate fell from 96% to 26%.
 *
 * Both directions of every pair are evaluated, because either prompt could be the one already in
 * the cache when the other arrives.
 */
class CorpusTest {

    @Test
    fun `the tuned corpus stays where it was, as a regression test`() {
        val guards = MatchGuards.standard()
        val rejectedParaphrases = TUNED_CORPUS.paraphrases.filter { rejectionFor(guards, it) != null }
        assertTrue(
            rejectedParaphrases.isEmpty(),
            "the tuned corpus must keep every paraphrase; these were rejected:\n" +
                rejectedParaphrases.joinToString("\n") { "  ${it.a}  ||  ${it.b}" },
        )

        val caught = TUNED_CORPUS.nearMisses.count { rejectionFor(guards, it) != null }
        assertTrue(
            caught >= TUNED_NEAR_MISS_FLOOR,
            "tuned corpus caught $caught/${TUNED_CORPUS.nearMisses.size}, below the $TUNED_NEAR_MISS_FLOOR floor",
        )
    }

    /**
     * A floor on held-out performance, so a change that only helps the tuned corpus cannot pass
     * unnoticed.
     *
     * The floor is deliberately set just under the current measurement rather than at an aspiration.
     * Its job is to fail when the number moves down, not to assert that the number is good.
     */
    @Test
    fun `held-out performance does not regress`() {
        val guards = MatchGuards.standard()
        val caught = HELD_OUT_CORPUS.nearMisses.count { rejectionFor(guards, it) != null }
        val kept = HELD_OUT_CORPUS.paraphrases.count { rejectionFor(guards, it) == null }

        assertTrue(
            caught >= HELD_OUT_NEAR_MISS_FLOOR,
            "held-out caught $caught/${HELD_OUT_CORPUS.nearMisses.size}, below the $HELD_OUT_NEAR_MISS_FLOOR floor",
        )
        assertTrue(
            kept >= HELD_OUT_PARAPHRASE_FLOOR,
            "held-out kept $kept/${HELD_OUT_CORPUS.paraphrases.size}, below the $HELD_OUT_PARAPHRASE_FLOOR floor",
        )
    }

    @Test
    fun `no guards means no protection at all`() {
        val caught = TUNED_CORPUS.nearMisses.count { rejectionFor(MatchGuards.none(), it) != null }
        assertTrue(caught == 0, "MatchGuards.none() rejected $caught pairs; it must reject nothing")
    }

    /**
     * Not an assertion — the report the README quotes. Run
     * `./gradlew :kmemo-core:test --tests '*CorpusTest*'` to see it.
     */
    @Test
    fun `print corpus report`() {
        println()
        for (corpus in listOf(TUNED_CORPUS, HELD_OUT_CORPUS)) {
            report(corpus)
        }
        println("The tuned corpus is in-sample: the guards were fitted against it. Only the held-out")
        println("numbers describe what the guards do to prompts nobody tuned against.")
        println()
    }

    private fun report(corpus: Corpus) {
        val guards = MatchGuards.standard()
        val caught = corpus.nearMisses.count { rejectionFor(guards, it) != null }
        val kept = corpus.paraphrases.count { rejectionFor(guards, it) == null }

        println(
            String.format(
                Locale.ROOT,
                "%-9s corpus: %3d pairs — near misses rejected %3d/%-3d (%3.0f%%), paraphrases kept %3d/%-3d (%3.0f%%)",
                corpus.name,
                corpus.pairs.size,
                caught,
                corpus.nearMisses.size,
                100.0 * caught / corpus.nearMisses.size,
                kept,
                corpus.paraphrases.size,
                100.0 * kept / corpus.paraphrases.size,
            ),
        )

        println("  per guard, in isolation:")
        for (guard in guards) {
            val guardCaught = corpus.nearMisses.count { rejectionFor(listOf(guard), it) != null }
            val guardRejected = corpus.paraphrases.count { rejectionFor(listOf(guard), it) != null }
            println(
                String.format(
                    Locale.ROOT,
                    "    %-22s caught %3d   false rejections %3d",
                    guard.name,
                    guardCaught,
                    guardRejected,
                ),
            )
        }
        println()
    }

    /** The first guard to veto the pair in either direction, or `null` if all of them abstained. */
    private fun rejectionFor(guards: List<MatchGuard>, pair: CorpusPair): String? {
        for (guard in guards) {
            val forward = guard.evaluate(pair.b, pair.a)
            if (forward is GuardVerdict.Reject) return "${guard.name}: ${forward.reason}"
            val backward = guard.evaluate(pair.a, pair.b)
            if (backward is GuardVerdict.Reject) return "${guard.name}: ${backward.reason}"
        }
        return null
    }

    private companion object {
        private const val TUNED_NEAR_MISS_FLOOR = 64
        private const val HELD_OUT_NEAR_MISS_FLOOR = 58
        private const val HELD_OUT_PARAPHRASE_FLOOR = 25
    }
}

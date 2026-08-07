package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M34: the attempt against a target that was written down first.
 *
 * `docs/MEASUREMENTS.md` registered it before this guard existed: PAWS near-miss rejection reaching 25%,
 * with paraphrase retention on PAWS at or above 79% and no written split losing a catch or a paraphrase.
 * The outcome is whatever this measures, and it is published either way.
 *
 * The guard under test is [WordOrderGuard], and it was chosen from how PAWS is built rather than from
 * what its pairs happen to contain. PAWS is Paraphrase Adversaries from Word Scrambling: its near misses
 * carry the same words in a different arrangement, which is the one shape every guard in the chain is
 * structurally blind to, because all of them but one compare sets and that one requires the order to
 * match.
 */
class PawsTargetTest {

    /**
     * The mechanism, on a pair nobody had to look at a corpus to write. If this stops holding, the guard
     * has stopped doing the thing it was added for and the numbers below are measuring something else.
     */
    @Test
    fun `a reversed relation between two named things is caught`() {
        val guard = WordOrderGuard()
        val reversed = CorpusPair(
            a = "Flights from New York to Miami on Tuesday",
            b = "Flights from Miami to New York on Tuesday",
            shouldMatch = false,
            category = "direction",
        )
        assertTrue(guard.evaluate(reversed.a, reversed.b) is GuardVerdict.Reject)
    }

    @Test
    fun `a reordering that moves no named thing past another is left alone`() {
        val guard = WordOrderGuard()
        // Same words, different arrangement, one anchor. There is no relation between two named things
        // to reverse, so there is no evidence and the guard abstains.
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate("the capital of France today", "today the capital of France"),
        )
    }

    @Test
    fun `two prompts that differ in what they contain are somebody else's evidence`() {
        val guard = WordOrderGuard()
        assertEquals(
            GuardVerdict.Accept,
            guard.evaluate("Flights from New York to Miami", "Flights from New York to Boston"),
        )
    }

    /**
     * The outcome against the registered target, printed rather than asserted into a pass.
     *
     * `./gradlew :kmemo-core:jvmTest --tests '*PawsTargetTest*'`
     */
    @Test
    fun `print the attempt against the registered target`() {
        val external = ExternalCorpus.corpus() ?: return
        val withGuard = MatchGuards.standard() + WordOrderGuard()

        println()
        println("M34, against the target registered in docs/MEASUREMENTS.md before this guard existed.")
        println("  split            near misses caught          paraphrases kept")
        for (corpus in listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS, external)) {
            val before = corpus.nearMisses.count { rejects(MatchGuards.standard(), it) }
            val after = corpus.nearMisses.count { rejects(withGuard, it) }
            val keptBefore = corpus.paraphrases.count { !rejects(MatchGuards.standard(), it) }
            val keptAfter = corpus.paraphrases.count { !rejects(withGuard, it) }
            println(
                String.format(
                    Locale.ROOT,
                    "  %-14s %5d -> %-5d (%4.1f%% -> %4.1f%%)  %5d -> %-5d (%4.1f%% -> %4.1f%%)",
                    corpus.name,
                    before, after,
                    100.0 * before / corpus.nearMisses.size, 100.0 * after / corpus.nearMisses.size,
                    keptBefore, keptAfter,
                    100.0 * keptBefore / corpus.paraphrases.size,
                    100.0 * keptAfter / corpus.paraphrases.size,
                ),
            )
        }
        println()
        println("Target: PAWS rejection >= 25%, PAWS retention >= 79%, no written split regressing.")
        println()
    }

    /**
     * The outcome, pinned so it stays true rather than becoming a sentence nobody rechecks.
     *
     * The attempt landed on the **boundary** case the pre-registration named: PAWS rejection more than
     * doubled, past the 25% target, and it was paid for with paraphrases on PAWS and on held-out. Both
     * halves are asserted, because a future change that made the rejection free would mean the verdict
     * published in `docs/MEASUREMENTS.md` needs revisiting, and a change that lost the rejection would
     * mean the guard has stopped doing what it was built for.
     */
    @Test
    fun `the attempt cleared the target and failed the constraint`() {
        val external = ExternalCorpus.corpus() ?: return
        val withGuard = MatchGuards.standard() + WordOrderGuard()

        val caught = external.nearMisses.count { rejects(withGuard, it) }
        assertTrue(
            caught.toDouble() / external.nearMisses.size >= TARGET_REJECTION,
            "PAWS rejection is now ${caught.toDouble() / external.nearMisses.size}, under the " +
                "$TARGET_REJECTION the target registered. The guard has stopped doing what it was for.",
        )

        val keptBefore = external.paraphrases.count { !rejects(MatchGuards.standard(), it) }
        val keptAfter = external.paraphrases.count { !rejects(withGuard, it) }
        assertTrue(
            keptAfter < keptBefore,
            "the guard now costs no paraphrases on PAWS. That would make it free, which is not what " +
                "docs/MEASUREMENTS.md publishes, so the verdict there needs rewriting rather than " +
                "this assertion relaxing.",
        )
    }

    /**
     * The consequence of the boundary verdict, and the thing that keeps every published figure true. The
     * guard exists and is in no preset, so nothing measured before it was written has moved.
     */
    @Test
    fun `no default preset carries the guard`() {
        val presets = mapOf(
            "standard" to MatchGuards.standard(),
            "strict" to MatchGuards.strict(),
            "responseAware" to MatchGuards.responseAware(),
            "longPrompts" to MatchGuards.longPrompts(),
            "prose" to MatchGuards.prose(),
        )
        for ((name, chain) in presets) {
            assertTrue(
                chain.none { it.name == "word-order" },
                "$name() carries word-order. It was measured as a trade nobody should be opted into.",
            )
        }
    }

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private companion object {
        /** The rejection rate registered as the target, before the guard existed. */
        private const val TARGET_REJECTION = 0.25
    }
}

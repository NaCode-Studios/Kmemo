package dev.kmemo.guard

import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M24: the guards measured on pairs this repository did not write.
 *
 * `docs/CORPUS.md` is careful about how the three internal splits grow and about which one a guard may
 * be fitted to, and that discipline holds. What it cannot answer is the objection that matters most to
 * somebody deciding whether to trust the cache: every pair was written by the same person who wrote
 * the guards, so they test the near misses that were **thought of** rather than the near misses that
 * **exist**.
 *
 * This split answers it. PAWS was built by Google Research in 2019, four years before kmemo, to
 * measure whether a model can tell a paraphrase from a near-paraphrase when word overlap is
 * deliberately high, which is the one case a similarity threshold cannot separate and the case every
 * guard here was built for. Nobody involved had heard of this library.
 *
 * It sits under the validation split's rule and one stricter clause: **no guard may ever be tuned
 * against it, and no failure from it may be read while editing one.** The moment a pair from here
 * guides a fix, the only fully independent number this project has stops being independent.
 *
 * The data is fetched rather than vendored, so the licence stays with the dataset. See
 * `tools/external-corpus/README.md`. Absent, this test says so and skips; in CI, where
 * `-PexternalCorpusRequired=true` is set, absent is a failure. A floor nobody notices has stopped
 * running is not a floor.
 */
class ExternalCorpusTest {

    @Test
    fun `the external split does not regress`() {
        val pairs = externalPairs() ?: return
        val guards = MatchGuards.standard()

        val nearMisses = pairs.filter { !it.shouldMatch }
        val paraphrases = pairs.filter { it.shouldMatch }
        val caught = nearMisses.count { rejects(guards, it) }
        val kept = paraphrases.count { !rejects(guards, it) }

        assertTrue(
            caught >= EXTERNAL_NEAR_MISS_FLOOR,
            "external split caught $caught/${nearMisses.size}, below the $EXTERNAL_NEAR_MISS_FLOOR floor",
        )
        assertTrue(
            kept >= EXTERNAL_PARAPHRASE_FLOOR,
            "external split kept $kept/${paraphrases.size}, below the $EXTERNAL_PARAPHRASE_FLOOR floor",
        )
    }

    /** Not an assertion: the report the README quotes beside the three internal ones. */
    @Test
    fun `print the external corpus report`() {
        val pairs = externalPairs() ?: return
        val guards = MatchGuards.standard()
        val nearMisses = pairs.filter { !it.shouldMatch }
        val paraphrases = pairs.filter { it.shouldMatch }
        val caught = nearMisses.count { rejects(guards, it) }
        val kept = paraphrases.count { !rejects(guards, it) }

        println()
        println(
            String.format(
                Locale.ROOT,
                "external  corpus: %d pairs, near misses rejected %d/%d (%.0f%%), " +
                    "paraphrases kept %d/%d (%.0f%%)",
                pairs.size,
                caught, nearMisses.size, 100.0 * caught / nearMisses.size,
                kept, paraphrases.size, 100.0 * kept / paraphrases.size,
            ),
        )
        println("  per guard, in isolation:")
        for (guard in guards) {
            println(
                String.format(
                    Locale.ROOT,
                    "    %-22s caught %4d   false rejections %4d",
                    guard.name,
                    nearMisses.count { rejects(listOf(guard), it) },
                    paraphrases.count { rejects(listOf(guard), it) },
                ),
            )
        }
        println()
        println("PAWS-Wiki labeled_final, test split. Declarative Wikipedia sentences, not prompts:")
        println("the register is not the one the guards were built for, and the number reflects that")
        println("as well as the guards. It is still the only figure here that nobody could have tuned.")
        println()
    }

    /**
     * The pairs, or `null` when the split is absent and absent is allowed.
     *
     * The two branches are the whole point, and they live in [ExternalCorpus] rather than here because
     * the length report reads the same file: a developer who has never run the fetch script gets a
     * skip and a sentence telling them how; CI gets a failure, because a floor that silently stops
     * being enforced is worse than no floor, since it reads as a passing gate.
     */
    private fun externalPairs(): List<CorpusPair>? = ExternalCorpus.pairs()

    /** Either direction, because either sentence could be the one already cached. */
    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private companion object {
        // Measured against the pinned revision on the day this shipped: 647 of 4,464 near misses
        // rejected, 2,807 of 3,536 paraphrases kept. Set **at** the measurement rather than under it,
        // because nothing here is stochastic. The guards are pure and the dataset is pinned to a
        // commit, so any movement at all is a real change somebody should have to look at.
        //
        // These only ever move up. See docs/CORPUS.md: lowering a floor is erasing the regression it
        // exists to catch.
        private const val EXTERNAL_NEAR_MISS_FLOOR = 647
        private const val EXTERNAL_PARAPHRASE_FLOOR = 2807
    }
}

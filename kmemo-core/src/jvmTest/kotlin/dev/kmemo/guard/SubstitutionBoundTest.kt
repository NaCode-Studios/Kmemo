package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.LongPromptCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What [MatchGuards.longPrompts] costs, measured rather than claimed.
 *
 * M28's finding is that [SubstitutionGuard] rejects genuine paraphrases at a rate that steps up with
 * prompt length and then flattens. The bound is the answer to it, and a bound whose cost is not
 * published is a knob nobody can decide about. So this holds three things at once: that the default
 * chain is unchanged, that the bound was chosen on the split that exists to be fitted rather than on
 * the ones that exist to be measured, and what the bound buys and costs on every split there is.
 */
class SubstitutionBoundTest {

    @Test
    fun `the default chain is untouched`() {
        val standard = MatchGuards.standard().first { it.name == "substitution" }
        val pair = CorpusPair(
            a = "the quarterly filing deadline for a delaware limited liability company in oregon",
            b = "the quarterly filing deadline for a delaware limited liability company in washington",
            shouldMatch = false,
            category = "jurisdiction",
        )
        assertTrue(
            standard.evaluate(pair.a, pair.b) is GuardVerdict.Reject,
            "MatchGuards.standard() must keep rejecting a long substitution; bounding it by default " +
                "would move every published figure at once",
        )
    }

    @Test
    fun `the preset abstains exactly where the bound says it does`() {
        val bounded = MatchGuards.longPrompts().first { it.name == "substitution" }
        val short = CorpusPair(
            a = "the quarterly filing deadline for a delaware limited liability company in oregon",
            b = "the quarterly filing deadline for a delaware limited liability company in washington",
            shouldMatch = false,
            category = "jurisdiction",
        )
        assertTrue(
            bounded.evaluate(short.a, short.b) is GuardVerdict.Reject,
            "the bound is a ceiling, not a replacement: a substitution inside the bound is still a " +
                "substitution",
        )

        val long = CorpusPair(
            a = "what is the combined quarterly filing deadline and estimated payment schedule for a " +
                "delaware limited liability company that also registered to do business in oregon",
            b = "what is the combined quarterly filing deadline and estimated payment schedule for a " +
                "delaware limited liability company that also registered to do business in washington",
            shouldMatch = false,
            category = "jurisdiction",
        )
        assertEquals(
            GuardVerdict.Accept,
            bounded.evaluate(long.a, long.b),
            "past ${SubstitutionGuard.LONG_PROMPT_MAX_TOKENS} content words the preset must abstain",
        )
    }

    /**
     * The bound is chosen on the tuned corpus and must cost it nothing.
     *
     * That ordering is the whole discipline. Reading the external split to place the bound would spend
     * the one measurement in this project nobody could have tuned; reading the tuned one costs nothing,
     * because its numbers were never evidence in the first place.
     */
    @Test
    fun `the bound costs the tuned corpus nothing`() {
        val standard = MatchGuards.standard()
        val bounded = MatchGuards.longPrompts()
        val caughtBefore = TUNED_CORPUS.nearMisses.count { rejects(standard, it) }
        val caughtAfter = TUNED_CORPUS.nearMisses.count { rejects(bounded, it) }
        assertEquals(
            caughtBefore,
            caughtAfter,
            "the bound was placed on this corpus precisely so it would cost nothing here. Losing a " +
                "catch means ${SubstitutionGuard.LONG_PROMPT_MAX_TOKENS} is now the wrong number, not " +
                "that this assertion is.",
        )
    }

    /**
     * The trade the preset exists to make, held to the shape of the argument rather than to a number.
     *
     * Measured on the pinned external split: 12 catches given up for 125 paraphrases kept, and on the
     * derived retrieval ladder between 6 and 8 given up for between 57 and 61 kept. Roughly ten kept
     * for each one lost, everywhere it has been measured. The floor is set at three because the claim
     * in the README is that this is a good trade on long prompts, and a version of the bound that
     * bought two paraphrases per catch would still be positive while no longer being that claim.
     */
    @Test
    fun `the bound keeps far more paraphrases than the catches it gives up`() {
        val external = ExternalCorpus.pairs()?.let { Corpus.of(ExternalCorpus.NAME, it) } ?: return
        val standard = MatchGuards.standard()
        val bounded = MatchGuards.longPrompts()

        val catchesLost = external.nearMisses.count { rejects(standard, it) } -
            external.nearMisses.count { rejects(bounded, it) }
        val paraphrasesGained = external.paraphrases.count { !rejects(bounded, it) } -
            external.paraphrases.count { !rejects(standard, it) }

        assertTrue(catchesLost > 0, "a bound that costs nothing on the external split is not bounding anything")
        assertTrue(
            paraphrasesGained >= MIN_TRADE_RATIO * catchesLost,
            "the bound gave up $catchesLost catches for $paraphrasesGained paraphrases, below the " +
                "$MIN_TRADE_RATIO:1 the README claims for it",
        )
    }

    /** Not an assertion: the table the README quotes for the preset. */
    @Test
    fun `print what the bound buys and costs`() {
        println()
        println("MatchGuards.longPrompts() against MatchGuards.standard(), per split")
        println("  split                near misses caught      paraphrases kept")
        for (corpus in splits()) {
            val standard = MatchGuards.standard()
            val bounded = MatchGuards.longPrompts()
            println(
                String.format(
                    Locale.ROOT,
                    "  %-18s %5d -> %-5d          %5d -> %-5d",
                    corpus.name,
                    corpus.nearMisses.count { rejects(standard, it) },
                    corpus.nearMisses.count { rejects(bounded, it) },
                    corpus.paraphrases.count { !rejects(standard, it) },
                    corpus.paraphrases.count { !rejects(bounded, it) },
                ),
            )
        }
        println()
        println("Caught is protection and kept is hit rate. The bound trades the first for the second,")
        println("and it only ever moves prompts past ${SubstitutionGuard.LONG_PROMPT_MAX_TOKENS} content words.")
        println()
    }

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private fun splits(): List<Corpus> {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)
        val external = ExternalCorpus.pairs() ?: return written
        val derived = LongPromptCorpus
            .ladder(external.take(1_000), ExternalCorpus.NAME)
            .map { (name, pairs) -> Corpus.of(name, pairs) }
        return written + Corpus.of(ExternalCorpus.NAME, external) + derived
    }

    private companion object {
        private const val MIN_TRADE_RATIO = 3
    }
}

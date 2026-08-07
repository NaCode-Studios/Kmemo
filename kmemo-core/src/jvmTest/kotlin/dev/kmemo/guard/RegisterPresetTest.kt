package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.Register
import dev.kmemo.fixtures.Registers
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a register-specific chain buys, measured on every split.
 *
 * M35 asked whether the spread across registers is wide enough to justify a preset. It is, and the
 * evidence is one guard: on declarative prose `substitution` rejects 493 genuine paraphrases to catch
 * 42 near misses, a precision of 8%, where on the written question corpora it runs at 98 to 100%. A
 * guard that is right nineteen times out of twenty on one register and wrong twelve times out of
 * thirteen on another is not a guard with a tuning problem, it is a guard whose mechanism does not hold
 * on that register.
 *
 * **The preset comes from the mechanism, not from the pairs.** [SubstitutionGuard] rejects when two
 * prompts have the same content words in the same order and differ in exactly one position, on the
 * reasoning that a question with one term swapped is a different question. In declarative prose that
 * shape is what a synonym looks like: two sentences making the same claim with one word chosen
 * differently. No PAWS pair was read while deciding this, which is the rule `docs/CORPUS.md` sets and
 * the reason the numbers below can still be quoted.
 */
class RegisterPresetTest {

    @Test
    fun `prose drops the guard whose mechanism does not hold on prose`() {
        assertTrue(
            MatchGuards.standard().any { it.name == "substitution" },
            "the default chain still runs it, because on questions it is the best guard there is",
        )
        assertTrue(
            MatchGuards.prose().none { it.name == "substitution" },
            "and the prose chain does not",
        )
        assertEquals(
            MatchGuards.standard().map { it.name } - "substitution",
            MatchGuards.prose().map { it.name },
            "nothing else differs; a preset that changed several things at once could not be read",
        )
    }

    /**
     * The half of the trade that keeps this a preset. On questions the guard being dropped is the
     * strongest one there is, so the prose chain is worse there and the numbers say how much: two
     * thirds of the protection on both blind splits, for one or two paraphrases.
     */
    @Test
    fun `on the written question corpora the prose chain is much worse`() {
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            val standard = corpus.nearMisses.count { rejects(MatchGuards.standard(), it) }
            val prose = corpus.nearMisses.count { rejects(MatchGuards.prose(), it) }
            assertTrue(
                prose < standard / 2,
                "${corpus.name}: prose() caught $prose of the $standard standard() catches. If that " +
                    "ever stops being much worse, the case for keeping substitution in the default is " +
                    "the thing to re-examine, not this assertion.",
            )
        }
    }

    /**
     * The trade, held to its direction rather than to a number. On the split where the guard misfires,
     * dropping it must give up far less protection than it recovers in hit rate, or the preset is not
     * the improvement it is published as.
     */
    @Test
    fun `on PAWS the prose chain trades a little protection for a lot of hit rate`() {
        val external = ExternalCorpus.corpus() ?: return
        val declarative = Corpus.of(
            "external declarative",
            external.pairs.filter { Registers.of(it) == Register.DECLARATIVE },
        )

        val standardCaught = declarative.nearMisses.count { rejects(MatchGuards.standard(), it) }
        val proseCaught = declarative.nearMisses.count { rejects(MatchGuards.prose(), it) }
        val standardKept = declarative.paraphrases.count { !rejects(MatchGuards.standard(), it) }
        val proseKept = declarative.paraphrases.count { !rejects(MatchGuards.prose(), it) }

        val lost = standardCaught - proseCaught
        val gained = proseKept - standardKept

        assertTrue(lost > 0, "a preset that costs nothing is not making a trade and needs no argument")
        assertTrue(
            gained >= MIN_TRADE_RATIO * lost,
            "the prose chain gave up $lost catches for $gained paraphrases, below the " +
                "$MIN_TRADE_RATIO:1 the README claims",
        )
    }

    /** Not an assertion: the table the README quotes for the preset. */
    @Test
    fun `print what the register presets cost`() {
        println()
        println("MatchGuards.standard() against MatchGuards.prose(), per split")
        println("  split                near misses caught      paraphrases kept")
        for (corpus in splits()) {
            println(
                String.format(
                    Locale.ROOT,
                    "  %-20s %5d -> %-5d          %5d -> %-5d",
                    corpus.name,
                    corpus.nearMisses.count { rejects(MatchGuards.standard(), it) },
                    corpus.nearMisses.count { rejects(MatchGuards.prose(), it) },
                    corpus.paraphrases.count { !rejects(MatchGuards.standard(), it) },
                    corpus.paraphrases.count { !rejects(MatchGuards.prose(), it) },
                ),
            )
        }
        println()
        println("Read the last two rows against each other. On the written corpora, which are questions,")
        println("prose() gives up protection for nothing, which is why it is a preset and not a default.")
        println()
    }

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private fun splits(): List<Corpus> {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)
        val external = ExternalCorpus.corpus() ?: return written
        val declarative = Corpus.of(
            "external declarative",
            external.pairs.filter { Registers.of(it) == Register.DECLARATIVE },
        )
        return written + external + declarative
    }

    private companion object {
        private const val MIN_TRADE_RATIO = 5
    }
}

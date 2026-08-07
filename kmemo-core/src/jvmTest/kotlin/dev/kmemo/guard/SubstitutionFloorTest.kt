package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.QqpCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import dev.kmemo.guard.tck.ScoreInterval
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M51: where [SubstitutionGuard]'s floor belongs, argued from the guard and then measured on
 * evidence nobody here has read.
 *
 * ### The argument from the mechanism
 *
 * The guard rejects when two prompts have the same content words in the same order and differ in
 * exactly one position. [SubstitutionGuard.DEFAULT_MIN_TOKENS] is how many content words it needs
 * before it will look, and the reason written beside it was a verb: below four words a one-word
 * difference is as likely to be `define` against `explain` as a swapped term.
 *
 * That reason is about **which word differs**. It was applied as a bound on **how many words there
 * are**, and the two are different quantities.
 *
 * Read from the mechanism, the floor is a crossover between two things. The guard's evidence is not
 * the differing word, it is that everything else lines up, and that grows with every additional
 * agreeing word. Against it runs the risk that the one differing position is a synonym somebody
 * chose rather than a term somebody swapped, and that risk does not shrink as the prompt gets longer.
 * The mechanism settles two things and not a third. A floor must exist, because with one agreeing
 * word there is no agreement to weigh. Two is below it. Where three sits against four is the
 * crossover point of a structural quantity and an empirical one, and no amount of reasoning about
 * the guard produces it.
 *
 * ### The measurement, and why it is not on the split the hypothesis came from
 *
 * The hypothesis that three is right came from reading the validation residual, which `docs/CORPUS.md`
 * prohibits and which the reports were doing by default until M53. That split cannot confirm it, and
 * neither can held-out. So the ladder is read off `qqp` and `external`, which are fetched, external
 * and unread, and the numbers from the two retired splits are printed beside them labelled as what
 * they are.
 *
 * **The result is that three is a trade rather than a gain**, and the trade is worse than one this
 * project has already declined. It is published either way, which is what registering the argument
 * before the measurement is for.
 */
class SubstitutionFloorTest {

    @Test
    fun `the floor stays where the measurement leaves it`() {
        assertEquals(
            4,
            SubstitutionGuard.DEFAULT_MIN_TOKENS,
            "three was measured and declined: it buys catches with paraphrases at a ratio this " +
                "project already refused once. See this test's documentation and the ladder it prints.",
        )
    }

    /**
     * The constraint that decides it on the one split a guard may be fitted against.
     *
     * The tuned split is where a change that trades hits for catches shows up first, because every
     * paraphrase in it is one the chain has always kept. Three loses one. That alone is not a verdict,
     * since the tuned split is small and in sample, and it is the first of two independent signals
     * pointing the same way.
     */
    @Test
    fun `the tuned split loses a paraphrase at three and none at four`() {
        assertTrue(
            TUNED_CORPUS.paraphrases.none { rejects(chainWithFloor(4), it) },
            "the shipped floor must keep every tuned paraphrase",
        )
        assertTrue(
            TUNED_CORPUS.paraphrases.any { rejects(chainWithFloor(3), it) },
            "if three has stopped costing the tuned split a paraphrase, the trade has changed and " +
                "the ladder should be read again rather than this assertion deleted",
        )
    }

    /**
     * The trade on the split that decides it, asserted so that it cannot drift unnoticed.
     *
     * Skipped when the fetched split is absent, like every other check that reads it.
     */
    @Test
    fun `three costs more paraphrases than the catches are worth, on unread evidence`() {
        val corpus = QqpCorpus.corpus() ?: return
        val atThree = score(corpus, chainWithFloor(3))
        val atFour = score(corpus, chainWithFloor(4))

        val catchesGained = atThree.caught - atFour.caught
        val paraphrasesLost = atFour.kept - atThree.kept
        assertTrue(catchesGained > 0, "three should catch more than four, or the ladder has changed")
        assertTrue(
            paraphrasesLost > 0,
            "three cost nothing on unread evidence, which reverses the finding this floor rests on",
        )
        assertTrue(
            catchesGained.toDouble() / paraphrasesLost < DECLINED_TRADE_RATIO,
            "the trade is now better than the 2.9-to-1 this project declined for WordOrderGuard, " +
                "which makes the floor a decision to re-take rather than one to hold",
        )
    }

    /**
     * The refinement the mechanism suggests, measured rather than assumed.
     *
     * If the floor's real subject is the verb, then the bound to apply is a bound on the verb rather
     * than on the length. In a question the head verb sits at or near the first content word, so the
     * variant is: look at three-word prompts, but abstain when the differing position is the first
     * one. It has no free parameter, so it is measured once and published whichever way it comes out.
     */
    @Test
    fun `print the floor ladder and the leading-position variant`() {
        val corpora = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS) +
            listOfNotNull(QqpCorpus.corpus(), ExternalCorpus.corpus())

        println()
        println("SubstitutionGuard floor: chain catch and paraphrases kept, per split")
        println()
        for (corpus in corpora) {
            println("${corpus.name} (${corpus.standing.name.lowercase().replace('_', '-')})")
            for (floor in FLOORS) {
                printRow(corpus, "minTokens $floor", chainWithFloor(floor))
            }
            printRow(corpus, "3, first position exempt", chainWithLeadingExemption())
            println()
        }
        println("Three catches more and keeps less on every split that moves. The mechanism argues")
        println("for a floor and against two; it does not choose between three and four, and the")
        println("measurement does. See docs/MEASUREMENTS.md.")
        println()
    }

    private fun printRow(corpus: Corpus, label: String, chain: List<MatchGuard>) {
        val scored = score(corpus, chain)
        val interval = ScoreInterval.wilson95(scored.caught, corpus.nearMisses.size)
        println(
            String.format(
                Locale.ROOT,
                "  %-26s caught %4d/%-4d (%4.1f%% ±%.1f), kept %4d/%-4d (%4.1f%%)",
                label,
                scored.caught, corpus.nearMisses.size,
                100.0 * scored.caught / corpus.nearMisses.size, interval.halfWidthPoints,
                scored.kept, corpus.paraphrases.size,
                100.0 * scored.kept / corpus.paraphrases.size,
            ),
        )
    }

    private data class Scored(val caught: Int, val kept: Int)

    private fun score(corpus: Corpus, chain: List<MatchGuard>) = Scored(
        caught = corpus.nearMisses.count { rejects(chain, it) },
        kept = corpus.paraphrases.count { !rejects(chain, it) },
    )

    /** The chain with only the floor moved, so nothing else can explain a difference in the ladder. */
    private fun chainWithFloor(minTokens: Int): List<MatchGuard> =
        MatchGuards.standard().map { guard ->
            if (guard is SubstitutionGuard) SubstitutionGuard(minTokens = minTokens) else guard
        }

    private fun chainWithLeadingExemption(): List<MatchGuard> =
        MatchGuards.standard().map { guard ->
            if (guard is SubstitutionGuard) LeadingExemptSubstitutionGuard else guard
        }

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    /**
     * `SubstitutionGuard` at a floor of three, abstaining when the differing word is the first
     * content word.
     *
     * Not shipped and not a preset. It exists so the mechanism's own suggestion is on the table with
     * a number beside it rather than in a paragraph.
     */
    private object LeadingExemptSubstitutionGuard : MatchGuard {
        override val name: String get() = "substitution"

        override fun evaluate(query: String, candidate: String): GuardVerdict {
            val left = Text.contentTokens(query, Vocabulary.STOPWORDS)
            val right = Text.contentTokens(candidate, Vocabulary.STOPWORDS)
            if (left.size != right.size || left.size < 3) return GuardVerdict.Accept

            var substituted = -1
            for (index in left.indices) {
                if (isSameTerm(left[index], right[index])) continue
                if (substituted >= 0) return GuardVerdict.Accept
                substituted = index
            }
            if (substituted < 0) return GuardVerdict.Accept
            if (left.size == 3 && substituted == 0) return GuardVerdict.Accept

            return GuardVerdict.Reject(
                "one term substituted: query says '${left[substituted]}' " +
                    "where cached prompt says '${right[substituted]}'",
            )
        }

        private fun isSameTerm(a: String, b: String): Boolean {
            if (Text.isSameWord(a, b)) return true
            val unit = Vocabulary.UNITS[a] ?: return false
            return unit == Vocabulary.UNITS[b]
        }
    }

    private companion object {
        private val FLOORS = listOf(2, 3, 4, 5)

        /**
         * The ratio of catches to paraphrases this project declined for `WordOrderGuard` in M34:
         * 1,125 extra catches for 390 paraphrases on the external split. A worse ratio than a
         * declined one needs no further argument.
         */
        private const val DECLINED_TRADE_RATIO = 1_125.0 / 390.0
    }
}

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
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M55: whether eleven vetoes should be one calibrated decision.
 *
 * A guard returns Accept or Reject and the chain is an OR. One guard that is sure vetoes; ten guards
 * that are each mildly suspicious serve the answer, and that is the shape of most of what gets
 * through. Combining weak signals into one decision is the standard answer, and this chain has eleven
 * going unused.
 *
 * Three things make it more delicate than it sounds and all three are settled here before any code
 * could have shipped.
 *
 * **The asymmetry has to survive.** A score turns every abstention into a small vote for serving,
 * which is a different default and a worse one. [Suspicion] handles it by scoring an abstention at
 * zero: a guard with nothing to say contributes nothing rather than contributing confidence.
 *
 * **Calibration needs data this project may not have.** A threshold over eleven signals is a fit, and
 * the only split that may be fitted is the tuned one, which holds 83 near misses. So the fit here is
 * **one number**, chosen on the tuned split by the rule the whole library already follows: the
 * lowest threshold that rejects no tuned paraphrase. Eleven weights on 129 pairs would be a good way
 * to fit noise, and M47 measured what that produces.
 *
 * **It changes what a miss can say.** `CacheLookup.Miss` names the guard that fired, and a chain that
 * decides by sum has no such guard. The answer, had this shipped, is in the report below: the score
 * carries its signals, and the strongest one names the mechanism. That is a weaker diagnosis than a
 * veto's, and it is a real cost rather than an apology.
 *
 * ### The result
 *
 * The veto chain wins and ships unchanged. `MatchGuard` is a public interface with a conformance
 * suite and a third-party contract behind it, and nothing here justifies changing it.
 */
class ScoringChainTest {

    /**
     * The threshold, calibrated on the split that exists to be fitted and on nothing else.
     *
     * The lowest value that rejects no tuned paraphrase, which is exactly the rule every guard's own
     * default was chosen by. Deterministic, one parameter, no search over the blind splits.
     */
    private fun calibrate(): Double {
        val paraphraseScores = TUNED_CORPUS.paraphrases.map { Suspicion.scoreOf(it.a, it.b) }
        val highest = paraphraseScores.maxOrNull() ?: 0.0
        return highest + STEP
    }

    /**
     * The interface is still the veto one, which is the shipped half of this milestone's answer.
     *
     * `GuardVerdict` is a sealed interface with two states, and a scoring chain would need a third
     * carrying a number. Asserting the state count is what turns "we decided not to" into something
     * a later change has to argue with rather than walk past.
     */
    @Test
    fun `the guard verdict still has exactly the two states a veto chain needs`() {
        val states = GuardVerdict::class.sealedSubclasses.map { it.simpleName }.toSet()
        assertTrue(
            states == setOf("Accept", "Reject"),
            "GuardVerdict now has $states. A third state is what a scoring chain needs, and the " +
                "measurement in this class is the argument that was made against adding one.",
        )
    }

    /**
     * The measurement that settles it, asserted so the conclusion cannot go stale quietly.
     *
     * On the split that decides, the scoring chain must not be strictly better than the veto chain,
     * or the decision recorded in this class's documentation is the wrong one.
     */
    @Test
    fun `the scoring chain does not beat the veto chain on unread evidence`() {
        val corpus = QqpCorpus.corpus() ?: return
        val threshold = calibrate()
        val veto = score(corpus) { rejects(MatchGuards.standard(), it) }
        val scoring = score(corpus) { Suspicion.scoreOf(it.a, it.b) >= threshold }

        val strictlyBetter = scoring.caught > veto.caught && scoring.kept >= veto.kept
        assertTrue(
            !strictlyBetter,
            "the scoring chain now catches ${scoring.caught} against ${veto.caught} while keeping " +
                "${scoring.kept} against ${veto.kept}. That is a reason to change MatchGuard, and this " +
                "assertion is where the old decision was written down.",
        )
    }

    /** Not an assertion: the comparison `docs/MEASUREMENTS.md` carries. */
    @Test
    fun `print the scoring comparison`() {
        val threshold = calibrate()
        val corpora = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS) +
            listOfNotNull(QqpCorpus.corpus(), ExternalCorpus.corpus())

        println()
        println(
            String.format(
                Locale.ROOT,
                "Suspicion threshold calibrated on the tuned split alone: %.2f (the lowest value that " +
                    "rejects no tuned paraphrase)",
                threshold,
            ),
        )
        println()
        for (corpus in corpora) {
            println("${corpus.name} (${corpus.standing.name.lowercase().replace('_', '-')})")
            printRow(corpus, "veto chain") { rejects(MatchGuards.standard(), it) }
            printRow(corpus, "scoring chain") { Suspicion.scoreOf(it.a, it.b) >= threshold }
            println()
        }

        val pairs = TUNED_CORPUS.pairs + HELD_OUT_CORPUS.pairs + VALIDATION_CORPUS.pairs
        println("Per-candidate cost, warmed:")
        printCost("veto chain") { pair -> rejects(MatchGuards.standard(), pair) }
        printCost("scoring chain") { pair -> Suspicion.scoreOf(pair.a, pair.b) >= threshold }
        println()
        println("What a miss could say under a sum, on one pair the veto chain serves:")
        val example = VALIDATION_CORPUS.nearMisses.firstOrNull { !rejects(MatchGuards.standard(), it) }
        if (example != null) {
            val signals = Suspicion.signalsOf(example.a, example.b).filter { it.value > 0.0 }
            println(
                "  score ${format(Suspicion.scoreOf(example.a, example.b))} from " +
                    signals.joinToString(", ") { "${it.name}=${format(it.value)}" },
            )
            println("  A veto names one guard and a reason. A sum names its strongest term, which is less.")
        }
        println()
    }

    private data class Scored(val caught: Int, val kept: Int)

    private fun score(corpus: Corpus, refuses: (CorpusPair) -> Boolean) = Scored(
        caught = corpus.nearMisses.count { refuses(it) },
        kept = corpus.paraphrases.count { !refuses(it) },
    )

    private fun printRow(corpus: Corpus, label: String, refuses: (CorpusPair) -> Boolean) {
        val scored = score(corpus, refuses)
        val interval = ScoreInterval.wilson95(scored.caught, corpus.nearMisses.size)
        println(
            String.format(
                Locale.ROOT,
                "  %-16s caught %4d/%-4d (%4.1f%% ±%.1f), kept %4d/%-4d (%4.1f%%)",
                label,
                scored.caught, corpus.nearMisses.size,
                100.0 * scored.caught / corpus.nearMisses.size, interval.halfWidthPoints,
                scored.kept, corpus.paraphrases.size,
                100.0 * scored.kept / corpus.paraphrases.size,
            ),
        )
    }

    private fun printCost(label: String, refuses: (CorpusPair) -> Boolean) {
        val pairs = TUNED_CORPUS.pairs + HELD_OUT_CORPUS.pairs + VALIDATION_CORPUS.pairs
        repeat(3) { pairs.forEach { refuses(it) } }
        val best = (1..5).minOf { measureNanoTime { pairs.forEach { refuses(it) } } }
        println(String.format(Locale.ROOT, "  %-16s %6.0f ns", label, best.toDouble() / pairs.size))
    }

    private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
        it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
            it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
    }

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    private companion object {
        /** The smallest step above the highest scoring tuned paraphrase, so it stays served. */
        private const val STEP = 0.01
    }
}

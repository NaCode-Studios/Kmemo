package dev.kmemo.guard

import dev.kmemo.ConfidenceVerifier
import dev.kmemo.fixtures.ReferenceVerifier
import dev.kmemo.fixtures.ResponseCorpus
import dev.kmemo.fixtures.ResponsePair
import dev.kmemo.fixtures.TUNED_CORPUS
import kotlinx.coroutines.test.runTest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M56: the verifier costs forty-three points of hit rate, and this is where that stops.
 *
 * The verifier stops about four fifths of what the guards let through, and takes paraphrases kept
 * from 88% to **45%** on held-out and to 69% on validation. Read together, those two numbers are
 * uncomfortable: a cache that keeps 45% of its genuine paraphrases is a cache doing half its job, and
 * the library offered no way to make a better trade beyond turning the verifier off entirely.
 *
 * The reason is structural rather than the model being wrong. **The verifier runs on every candidate
 * that cleared similarity and cleared every guard**, which is to say on every case the cheap layers
 * had no opinion about. It is asked to adjudicate pairs the chain was confident enough to serve, and a
 * cross-encoder asked whether two prompts mean the same thing is a stricter judge than a chain of
 * lexical rules and always will be.
 *
 * ### Two directions were measured, and only one of them is a gain
 *
 * **Invoke it where the chain is uncertain rather than where it is silent.** Today "no guard
 * objected" and "several guards nearly objected" are the same state, and [Suspicion] separates them
 * by reading the same mechanisms the guards read. Measured on the residual, gating on that cuts
 * invocations by a quarter and paraphrase losses by a fifth, **and cuts catches by a quarter with
 * them**. It does not work, and the reason is worth more than the attempt: the verifier's catches are
 * not concentrated in the uncertain slice. A quarter of them sit where the chain has no signal at
 * all, which is exactly the population a verifier exists for, because a lexical chain that had a
 * signal there would have used it.
 *
 * **Let the caller put the verifier's refusals on a scale.** That one works, and it is what ships as
 * [dev.kmemo.ConfidenceVerifier]. The frontier is real and it has no free point on it, which is the
 * honest form of this milestone's answer: every wrong answer avoided past the current default costs
 * genuine hits, and how expensive a wrong answer is depends on a deployment rather than on this
 * library.
 *
 * ### What must not happen, and is asserted
 *
 * **The verifier must not quietly become less safe to make a number look better.** It fails closed on
 * a timeout or an error for a reason, and neither a gate nor a threshold is an exception: a check that
 * could not complete still rejects.
 */
class VerifierGateTest {

    /**
     * The gate the first direction would use, from the tuned split alone.
     *
     * Below this score the chain was confident and the verifier would not be consulted. Kept, and
     * measured, because the negative result is only worth publishing with a calibration behind it
     * that somebody would actually have chosen.
     */
    private fun calibrateGate(): Double {
        val residual = TUNED_CORPUS.nearMisses.filterNot { pair ->
            MatchGuards.standard().any {
                it.evaluate(pair.a, pair.b) is GuardVerdict.Reject ||
                    it.evaluate(pair.b, pair.a) is GuardVerdict.Reject
            }
        }
        return residual.map { Suspicion.scoreOf(it.a, it.b) }.sorted()
            .getOrElse(residual.size / QUARTILE) { 0.0 }
    }

    /**
     * The negative result, asserted so it cannot go stale quietly.
     *
     * Uncertainty gating buys invocations with catches on this residual. If that ever stops being
     * true, the first direction becomes available again and this assertion is where the old finding
     * was written down.
     */
    @Test
    fun `gating on uncertainty buys invocations with catches`() {
        val gate = calibrateGate()
        val nearMisses = residual(ResponseCorpus.nearMisses)
        val paraphrases = residual(ResponseCorpus.paraphrases)

        val caughtAll = nearMisses.count { refuses(it) }
        val caughtGated = nearMisses.count { invoked(it, gate) && refuses(it) }
        val invocations = (nearMisses + paraphrases).count { invoked(it, gate) }

        assertTrue(
            invocations < nearMisses.size + paraphrases.size,
            "the gate invoked the verifier on everything, so it measured nothing",
        )
        assertTrue(
            caughtGated < caughtAll,
            "the gate now catches $caughtGated against $caughtAll ungated. That would make uncertainty " +
                "gating a gain rather than the negative result recorded here, and it should ship.",
        )
    }

    /**
     * The frontier the caller now controls, asserted at both ends.
     *
     * A lower threshold must keep more paraphrases and stop fewer near misses. If it ever did both
     * better, there would be a free point on the curve and the current default would simply be wrong.
     */
    @Test
    fun `the confidence threshold trades catches against hits in both directions`() {
        val nearMisses = residual(ResponseCorpus.nearMisses).mapNotNull { scoreOf(it) }
        val paraphrases = residual(ResponseCorpus.paraphrases).mapNotNull { scoreOf(it) }

        val permissive = LOW_THRESHOLD
        val default = ConfidenceVerifier.DEFAULT_THRESHOLD
        val caughtLow = nearMisses.count { it < permissive }
        val caughtDefault = nearMisses.count { it < default }
        val lostLow = paraphrases.count { it < permissive }
        val lostDefault = paraphrases.count { it < default }

        assertTrue(
            caughtLow < caughtDefault,
            "a more permissive threshold stopped $caughtLow near misses against $caughtDefault, which " +
                "is not more permissive",
        )
        assertTrue(
            lostLow < lostDefault,
            "a more permissive threshold refused $lostLow paraphrases against $lostDefault; the whole " +
                "point of the dial is that this number falls",
        )
    }

    /**
     * Fail closed survives the dial.
     *
     * `ConfidenceVerifier.verify` compares a confidence against a threshold and does not catch, so a
     * check that throws propagates and `SemanticCache` refuses. A verifier that returned a neutral
     * number on a timeout would serve an unconfirmed answer whenever a provider was slow.
     */
    @Test
    fun `a confidence verifier that cannot answer still rejects`() = runTest {
        val failing = object : ConfidenceVerifier {
            override suspend fun confidence(query: String, cachedPrompt: String, similarity: Double): Double =
                throw IllegalStateException("the model timed out")
        }
        val failure = runCatching { failing.verify("a", "b", 0.99) }.exceptionOrNull()
        assertTrue(
            failure is IllegalStateException,
            "a confidence that could not be computed must reach the caller as a failure, not as a " +
                "number: $failure",
        )
    }

    /** The threshold really is the decision, not a description printed beside one. */
    @Test
    fun `the threshold decides`() = runTest {
        val fixed = object : ConfidenceVerifier {
            override val threshold: Double get() = 0.10
            override suspend fun confidence(query: String, cachedPrompt: String, similarity: Double) = 0.2
        }
        assertTrue(fixed.verify("a", "b", 0.99), "0.2 is above a threshold of 0.10")
        val strict = object : ConfidenceVerifier {
            override val threshold: Double get() = 0.90
            override suspend fun confidence(query: String, cachedPrompt: String, similarity: Double) = 0.2
        }
        assertTrue(!strict.verify("a", "b", 0.99), "0.2 is below a threshold of 0.90")
    }

    /** Not an assertion: the two frontiers `docs/MEASUREMENTS.md` carries. */
    @Test
    fun `print the verifier frontiers`() {
        val gate = calibrateGate()
        val nearMisses = residual(ResponseCorpus.nearMisses)
        val paraphrases = residual(ResponseCorpus.paraphrases)

        println()
        println("The residual the verifier is shown, on the two written splits.")
        println("  reference: ${ReferenceVerifier.model}")
        println("  rule:      ${ReferenceVerifier.decisionRule}")
        println()
        println("Gating on chain uncertainty, one parameter calibrated on the tuned split:")
        report("both", "every candidate", nearMisses, paraphrases) { true }
        report(
            "both",
            String.format(Locale.ROOT, "suspicion >= %.2f", gate),
            nearMisses, paraphrases,
        ) { invoked(it, gate) }
        println("  It cuts invocations and catches together. The catches are not in the uncertain slice.")
        println()
        println("Putting the verifier's refusals on a scale, which is what ships:")
        val nearScores = nearMisses.mapNotNull { scoreOf(it) }
        val paraScores = paraphrases.mapNotNull { scoreOf(it) }
        for (threshold in THRESHOLDS) {
            val caught = nearScores.count { it < threshold }
            val lost = paraScores.count { it < threshold }
            println(
                String.format(
                    Locale.ROOT,
                    "  serve at confidence >= %.2f: stops %3d/%-3d near misses, refuses %3d/%-3d " +
                        "paraphrases (keeps %3.0f%%)",
                    threshold, caught, nearScores.size, lost, paraScores.size,
                    100.0 * (paraScores.size - lost) / paraScores.size,
                ),
            )
        }
        println("  No free point. Every wrong answer avoided past the default costs genuine hits, and")
        println("  how expensive a wrong answer is depends on a deployment rather than on this library.")
        println()
    }

    private fun report(
        split: String,
        label: String,
        nearMisses: List<ResponsePair.Scenario>,
        paraphrases: List<ResponsePair.Scenario>,
        invoke: (ResponsePair.Scenario) -> Boolean,
    ) {
        val lookups = nearMisses.size + paraphrases.size
        val invocations = (nearMisses + paraphrases).count { invoke(it) }
        val caught = nearMisses.count { invoke(it) && refuses(it) }
        val lost = paraphrases.count { invoke(it) && refuses(it) }
        val kept = paraphrases.size - lost
        println(
            String.format(
                Locale.ROOT,
                "  %-11s %-16s invocations %3d/%-3d (%3.0f%%), caught %3d/%-3d, paraphrases kept %3d/%-3d (%3.0f%%)",
                split, label, invocations, lookups, 100.0 * invocations / lookups,
                caught, nearMisses.size, kept, paraphrases.size,
                100.0 * kept / paraphrases.size,
            ),
        )
    }

    /** The lookups the guard chain still serves: the population a verifier exists for. */
    private fun residual(pairs: List<ResponsePair>): List<ResponsePair.Scenario> =
        pairs.flatMap { it.scenarios() }.filter { scenario ->
            MatchGuards.standard().none {
                it.evaluate(scenario.query, scenario.cachedPrompt) is GuardVerdict.Reject
            }
        }

    private fun invoked(scenario: ResponsePair.Scenario, gate: Double): Boolean =
        Suspicion.scoreOf(scenario.query, scenario.cachedPrompt) >= gate

    /** What the reference verifier decided. Its coverage of the residual has its own test. */
    private fun refuses(scenario: ResponsePair.Scenario): Boolean =
        ReferenceVerifier.verdictFor(scenario.query, scenario.cachedPrompt)?.served == false

    /** The reference verifier's own confidence for this lookup, when it was shown it. */
    private fun scoreOf(scenario: ResponsePair.Scenario): Double? =
        ReferenceVerifier.verdictFor(scenario.query, scenario.cachedPrompt)?.score

    private companion object {
        /** Where the gate sits in the tuned residual's score distribution: the lower quartile. */
        private const val QUARTILE = 4

        /** A deliberately permissive point on the frontier, for the assertion that it is one. */
        private const val LOW_THRESHOLD = 0.10

        private val THRESHOLDS = listOf(0.05, 0.10, 0.20, 0.30, 0.50, 0.70, 0.90)
    }
}

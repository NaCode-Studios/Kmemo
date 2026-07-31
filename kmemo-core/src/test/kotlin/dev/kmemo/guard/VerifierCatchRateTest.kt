package dev.kmemo.guard

import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.ReferenceVerifier
import dev.kmemo.fixtures.ResponseCorpus
import dev.kmemo.fixtures.ResponsePair
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * How much of the guards' residual a verifier actually stops.
 *
 * The README used to say the 67% / 88% figures were guard-only and that the verifier's catch rate on
 * what got through was unmeasured. Honest, and it left out the one number a reader most wants. This is
 * that number, against the named reference implementation in [ReferenceVerifier].
 *
 * **The rate is computed here, not recorded.** The population is the residual, and the residual moves
 * with the guards. The harness records one verdict per lookup and this intersects it with the residual
 * as it stands today, so the published figure cannot quietly come to describe an older set.
 *
 * **There is no floor, on purpose.** A gate that spends a model call on every build is a gate that gets
 * deleted, and a floor on somebody else's model is a floor on the wrong thing. What is asserted is that
 * the verdicts still describe the current corpus and cover every residual lookup — that the number is
 * *about* the right set, never that it is a good number.
 */
class VerifierCatchRateTest {

    @Test
    fun `the recorded verdicts still describe this corpus`() {
        val stale = ReferenceVerifier.staleAgainstCorpus()
        assertTrue(stale == null, stale ?: "")
    }

    /**
     * Every lookup the guards still serve must have been shown to the reference verifier.
     *
     * A residual lookup with no verdict is one the verifier was never asked about. Counting it as
     * uncaught would understate nothing, but leaving it silently out of the denominator is how a
     * coverage gap becomes a published percentage.
     */
    @Test
    fun `every residual lookup has a verdict`() {
        val missing = (residual(ResponseCorpus.nearMisses) + residual(ResponseCorpus.paraphrases))
            .filter { ReferenceVerifier.verdictFor(it.query, it.cachedPrompt) == null }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} lookups the guards still serve have no reference verdict; re-run " +
                "tools/verifier-catch-rate/measure.py:\n" +
                missing.take(10).joinToString("\n") { "  ${it.query}  ||  ${it.cachedPrompt}" },
        )
    }

    /** Not an assertion — the number the README quotes, and a JSON artifact to diff across commits. */
    @Test
    fun `emit the verifier catch rate report`() {
        val nearMisses = residual(ResponseCorpus.nearMisses)
        val paraphrases = residual(ResponseCorpus.paraphrases)
        val caught = nearMisses.count { refuses(it) }
        val lost = paraphrases.count { refuses(it) }

        printHeader()
        printOverall(nearMisses.size, caught, paraphrases.size, lost)
        printPerSplit(nearMisses, paraphrases)

        val out = File("build/reports/guards/verifier-catch-rate.json")
        out.parentFile.mkdirs()
        out.writeText(
            String.format(
                Locale.ROOT,
                """
                {
                  "model": "%s",
                  "isCiFloor": false,
                  "residualNearMissLookups": %d,
                  "residualNearMissLookupsCaught": %d,
                  "residualParaphraseLookups": %d,
                  "residualParaphraseLookupsLost": %d
                }
                """.trimIndent() + "\n",
                ReferenceVerifier.model, nearMisses.size, caught, paraphrases.size, lost,
            ),
        )
        assertTrue(out.length() > 0)
    }

    private fun printHeader() {
        println()
        println("Verifier catch rate on the guard residual.")
        println("  reference: ${ReferenceVerifier.implementation}")
        println("  model:     ${ReferenceVerifier.model}")
        println("  rule:      ${ReferenceVerifier.decisionRule}")
        println("  measured:  ${ReferenceVerifier.measuredOn}")
        println("Your verifier will differ. This says how much of the residual is reachable at all by a")
        println("model that reads the two prompts, not what yours will do.")
        println()
    }

    private fun printOverall(nearMisses: Int, caught: Int, paraphrases: Int, lost: Int) {
        println(
            String.format(
                Locale.ROOT,
                "  of %d near-miss lookups the guards still serve, the verifier stops %d (%.0f%%)",
                nearMisses, caught, 100.0 * caught / nearMisses,
            ),
        )
        println(
            String.format(
                Locale.ROOT,
                "  of %d paraphrase lookups the guards keep, it refuses %d (%.0f%%) — the price",
                paraphrases, lost, 100.0 * lost / paraphrases,
            ),
        )
        println()
    }

    private fun printPerSplit(
        nearMisses: List<ResponsePair.Scenario>,
        paraphrases: List<ResponsePair.Scenario>,
    ) {
        println("  false-hit rate over all near-miss lookups in each blind split:")
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            val lookups = corpus.nearMisses.size * 2
            val served = nearMisses.count { it.pair.split == corpus.name }
            val afterVerifier = nearMisses.count { it.pair.split == corpus.name && !refuses(it) }
            // Paraphrases the guards keep, minus the ones the verifier then takes away.
            val kept = paraphrases.count { it.pair.split == corpus.name && !refuses(it) }
            val allParaphraseLookups = corpus.paraphrases.size * 2
            println(
                String.format(
                    Locale.ROOT,
                    "    %-11s guards %.3f  →  guards + verifier %.3f   (paraphrases kept %d/%d)",
                    corpus.name,
                    served.toDouble() / lookups,
                    afterVerifier.toDouble() / lookups,
                    kept,
                    allParaphraseLookups,
                ),
            )
        }
        println()
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun residual(pairs: List<ResponsePair>): List<ResponsePair.Scenario> =
        pairs.flatMap { it.scenarios() }.filter { scenario ->
            MatchGuards.standard().none {
                it.evaluate(scenario.query, scenario.cachedPrompt) is GuardVerdict.Reject
            }
        }

    /** The reference verifier refusing this lookup. Absent verdicts are covered by their own test. */
    private fun refuses(scenario: ResponsePair.Scenario): Boolean =
        ReferenceVerifier.verdictFor(scenario.query, scenario.cachedPrompt)?.served == false
}

package dev.kmemo.guard

import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.ResponseCorpus
import dev.kmemo.fixtures.ResponsePair
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the response-aware guard is worth, measured before it was allowed anywhere near a default.
 *
 * The question is narrow on purpose. [MatchGuards.standard] already rejects most near misses on the
 * two blind corpora; what is left is the residual, and the residual is where a prompt-only chain is
 * structurally blind. So the measurement is: **of the near misses the standard chain still serves,
 * how many does [AnswerAnchorGuard] catch, and how many genuine paraphrases does it break to do it?**
 *
 * The residual is recomputed here rather than hard-coded, so the day a prompt-side guard improves,
 * this number describes the new residual instead of quietly describing an old one.
 *
 * **The number is in-sample.** [ResponseCorpus] explains why at length: the answers were authored,
 * because a corpus of real paired answers does not exist to harvest. Quoted anywhere, it is labelled
 * a regression check.
 */
class ResponseGuardTest {

    private val anchor = AnswerAnchorGuard()

    /**
     * The response corpus borrows its prompts; it does not own them.
     *
     * Every pair must still be present, verbatim and with the same label, in the blind split it names.
     * Without this the two files drift apart and the response measurement silently starts describing
     * prompts that are no longer in the corpus it claims to extend.
     */
    @Test
    fun `the response corpus quotes its splits verbatim`() {
        for (pair in ResponseCorpus.pairs) {
            val source = ResponseCorpus.sourceOf(pair)
            val match = source.pairs.firstOrNull { it.a == pair.a && it.b == pair.b }
            assertTrue(
                match != null,
                "response corpus has a pair that is not in ${source.name}:\n  ${pair.a}\n  ${pair.b}",
            )
            assertEquals(
                match.shouldMatch,
                pair.shouldMatch,
                "response corpus disagrees with ${source.name} about whether this pair may match:\n" +
                    "  ${pair.a}\n  ${pair.b}",
            )
        }
    }

    @Test
    fun `every pair carries an answer for both prompts`() {
        for (pair in ResponseCorpus.pairs) {
            assertTrue(pair.responseA.isNotBlank(), "no answer for ${pair.a}")
            assertTrue(pair.responseB.isNotBlank(), "no answer for ${pair.b}")
        }
    }

    /**
     * The guard's whole contract when it is shown only prompts: say nothing.
     *
     * This is what makes it safe to put in a list a prompt-only call path will iterate — it abstains
     * rather than guessing from half the evidence.
     */
    @Test
    fun `the anchor guard abstains when it is shown only the two prompts`() {
        val pair = ResponseCorpus.nearMisses.first()
        assertEquals(GuardVerdict.Accept, (anchor as MatchGuard).evaluate(pair.a, pair.b))
    }

    @Test
    fun `the anchor guard rejects an answer that names the word the query replaced`() {
        val verdict = anchor.evaluate(
            query = "what is the capital gains tax rate when i sell a primary residence",
            candidate = "what is the capital gains tax rate when i sell a second home",
            candidateResponse = "Gain on a second home is taxable in full, with no exclusion available.",
        )
        assertTrue(verdict is GuardVerdict.Reject, "expected a rejection, got $verdict")
        assertTrue("second" in verdict.reason, "the reason must name the anchoring word: ${verdict.reason}")
    }

    /**
     * The limit the fuzzy matcher imposes, recorded rather than left to be discovered.
     *
     * `mg` and `mcg` are one insertion apart, so [Text.isSameWord] calls them the same word and no
     * substitution is seen. That tolerance is what keeps `organise` against `organize` out of the
     * rejection path, and this guard cannot have one without the other.
     */
    @Test
    fun `a one-letter unit difference reads as a typo, so the anchor guard does not see it`() {
        assertEquals(
            GuardVerdict.Accept,
            anchor.evaluate(
                query = "is 400 mg of folic acid enough for a pregnant adult",
                candidate = "is 400 mcg of folic acid enough for a pregnant adult",
                candidateResponse = "Yes. 400 mcg daily is the standard recommendation in the first trimester.",
            ),
        )
    }

    @Test
    fun `the anchor guard abstains when the answer never names the substituted word`() {
        assertEquals(
            GuardVerdict.Accept,
            anchor.evaluate(
                query = "what is the boiling point of methanol at sea level",
                candidate = "what is the boiling point of ethanol at sea level",
                candidateResponse = "78.4 degrees Celsius at one atmosphere.",
            ),
        )
    }

    /** An abbreviation expanded is not a substitution, and the expansion will show up in the answer. */
    @Test
    fun `the anchor guard abstains when one prompt expands an abbreviation`() {
        assertEquals(
            GuardVerdict.Accept,
            anchor.evaluate(
                query = "how do i configure CORS on an api gateway",
                candidate = "how do i configure cross origin resource sharing on an api gateway",
                candidateResponse = "Answer the preflight, and set an explicit origin when you send credentials.",
            ),
        )
    }

    /** Both names are in the query, so an answer naming one of them is not anchored to it. */
    @Test
    fun `the anchor guard abstains on a word-order swap`() {
        assertEquals(
            GuardVerdict.Accept,
            anchor.evaluate(
                query = "which is better for session storage memcached or redis",
                candidate = "which is better for session storage redis or memcached",
                candidateResponse = "Redis, in most cases: it persists and replicates.",
            ),
        )
    }

    @Test
    fun `the anchor guard abstains on a spelling variant`() {
        assertEquals(
            GuardVerdict.Accept,
            anchor.evaluate(
                query = "how do i normalize a column of values",
                candidate = "how do i normalise a column of values",
                candidateResponse = "Normalise with (x - min) / (max - min) to rescale between 0 and 1.",
            ),
        )
    }

    /** Past two substituted words the prompts are being reworded, and nothing can be attributed. */
    @Test
    fun `the anchor guard abstains once the prompts differ too widely to attribute`() {
        assertEquals(
            GuardVerdict.Accept,
            anchor.evaluate(
                query = "which cheap laptop suits a student today",
                candidate = "which rugged tablet suits a builder outdoors",
                candidateResponse = "A rugged tablet with an IP68 rating is what a builder wants outdoors.",
            ),
        )
    }

    /**
     * The measurement, with floors.
     *
     * Both floors move in the direction that matters: catches may not fall, and false rejections may
     * not rise. They sit at the current measurement rather than under it, because unlike the blind
     * corpora this data cannot grow — every pair in it is already in the residual.
     */
    @Test
    fun `the anchor guard catches part of the residual without breaking paraphrases`() {
        val caught = residual(ResponseCorpus.nearMisses).count { anchorRejects(it) }
        val broken = residual(ResponseCorpus.paraphrases).count { anchorRejects(it) }

        assertTrue(
            caught >= RESIDUAL_CATCH_FLOOR,
            "the anchor guard caught $caught residual near misses, below the $RESIDUAL_CATCH_FLOOR floor",
        )
        assertTrue(
            broken <= PARAPHRASE_BREAKAGE_CEILING,
            "the anchor guard rejected $broken genuine paraphrases, above the " +
                "$PARAPHRASE_BREAKAGE_CEILING ceiling",
        )
    }

    /**
     * The alternative that looks obvious, measured rather than dismissed in prose.
     *
     * "The prompts share their numbers, so compare the query's numbers against the *answer's*" fails
     * for a reason no tuning fixes: an answer carries the figures that answer the question, and the
     * question rarely carries them. `what is the boiling point of ethanol` names no number at all and
     * is answered with `78.4`, so the comparison refuses it — and refuses the honest paraphrase of it
     * just the same, because the paraphrase names no number either.
     *
     * It is kept as a test because "we tried the obvious thing and it costs more than it catches" is a
     * claim that should fail loudly if it ever stops being true.
     */
    @Test
    fun `comparing the query's numbers against the answer's costs more paraphrases than it catches`() {
        val numeric = NumericGuard()
        fun rejects(scenario: ResponsePair.Scenario) =
            numeric.evaluate(scenario.query, scenario.cachedResponse) is GuardVerdict.Reject

        val numericBroken = residual(ResponseCorpus.paraphrases).count(::rejects)
        val numericCaught = residual(ResponseCorpus.nearMisses).count(::rejects)
        val anchorBroken = residual(ResponseCorpus.paraphrases).count { anchorRejects(it) }
        val anchorCaught = residual(ResponseCorpus.nearMisses).count { anchorRejects(it) }

        assertTrue(
            numericBroken > anchorBroken,
            "the naive numeric comparison broke $numericBroken paraphrases against the anchor " +
                "guard's $anchorBroken; if that has stopped being true, reconsider which one ships",
        )
        assertTrue(
            numericBroken > numericCaught - anchorCaught,
            "the naive numeric comparison broke $numericBroken paraphrases to catch " +
                "${numericCaught - anchorCaught} near misses the anchor guard does not; that trade " +
                "is the reason it was not shipped, and it no longer holds",
        )
    }

    /**
     * The response corpus must cover the residual completely, or the false-hit rate below is a lie.
     *
     * A residual near miss with no authored answer is one the anchor guard is never shown, and
     * counting it as "not caught" understates nothing — but counting it in the denominator while
     * silently never testing it is how a coverage gap turns into a published number. If this fails,
     * pairs were added to a blind split: author their answers, or drop them from the claim.
     */
    @Test
    fun `the response corpus covers every residual near miss`() {
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            val covered = ResponseCorpus.nearMisses
                .filter { it.split == corpus.name }
                .map { it.a to it.b }
                .toSet()
            val uncovered = corpus.nearMisses
                .filter { pair -> standardServes(pair.a, pair.b) || standardServes(pair.b, pair.a) }
                .filterNot { (it.a to it.b) in covered }
            assertTrue(
                uncovered.isEmpty(),
                "${corpus.name} has ${uncovered.size} near misses that standard() still serves and " +
                    "the response corpus has no answers for:\n" +
                    uncovered.joinToString("\n") { "  ${it.a}  ||  ${it.b}" },
            )
        }
    }

    /** Not an assertion — the table the docs quote, plus a JSON artifact to diff across commits. */
    @Test
    fun `emit the response guard report`() {
        val nearMisses = residual(ResponseCorpus.nearMisses)
        val paraphrases = residual(ResponseCorpus.paraphrases)
        val caught = nearMisses.filter { anchorRejects(it) }
        val broken = paraphrases.filter { anchorRejects(it) }

        printFalseHitRates(nearMisses, caught)
        printResidualSummary(nearMisses, caught, paraphrases, broken)
        assertTrue(writeReport(nearMisses.size, caught.size, paraphrases.size, broken.size).length() > 0)
    }

    private fun printFalseHitRates(
        nearMisses: List<ResponsePair.Scenario>,
        caught: List<ResponsePair.Scenario>,
    ) {
        println()
        println("False-hit rate, per blind split, with and without the response-aware guard:")
        println()
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            val lookups = corpus.nearMisses.size * 2
            val served = nearMisses.count { it.pair.split == corpus.name }
            val stillServed = served - caught.count { it.pair.split == corpus.name }
            println(
                String.format(
                    Locale.ROOT,
                    "  %-11s standard() %.3f  →  responseAware() %.3f   (%d of %d near-miss lookups)",
                    corpus.name,
                    served.toDouble() / lookups,
                    stillServed.toDouble() / lookups,
                    stillServed, lookups,
                ),
            )
        }
    }

    private fun printResidualSummary(
        nearMisses: List<ResponsePair.Scenario>,
        caught: List<ResponsePair.Scenario>,
        paraphrases: List<ResponsePair.Scenario>,
        broken: List<ResponsePair.Scenario>,
    ) {
        println()
        println("Response-aware guard, on what MatchGuards.standard() still serves.")
        println("IN-SAMPLE: the answers were authored for this measurement. A regression check, not a")
        println("blind number — see docs/CORPUS.md.")
        println()
        println(
            String.format(
                Locale.ROOT,
                "  near-miss lookups still served: %3d, of which answer-anchor rejects %3d (%3.0f%%)",
                nearMisses.size, caught.size, 100.0 * caught.size / nearMisses.size,
            ),
        )
        println(
            String.format(
                Locale.ROOT,
                "  paraphrase lookups still served: %3d, of which answer-anchor rejects %3d (%3.0f%%)",
                paraphrases.size, broken.size, 100.0 * broken.size / paraphrases.size,
            ),
        )
        println()
        println("  caught, by category:")
        caught.groupBy { it.pair.category }.toSortedMap().forEach { (category, rows) ->
            println(String.format(Locale.ROOT, "    %-16s %2d", category, rows.size))
        }
        if (broken.isNotEmpty()) {
            println()
            println("  paraphrases it costs — each one is an API call that need not have happened:")
            broken.forEach { println("    [${it.pair.category}] ${it.query}  ||  ${it.cachedPrompt}") }
        }
        println()
    }

    private fun writeReport(nearMisses: Int, caught: Int, paraphrases: Int, broken: Int): File {
        val out = File("build/reports/guards/response-guard-report.json")
        out.parentFile.mkdirs()
        out.writeText(
            String.format(
                Locale.ROOT,
                """
                {
                  "inSample": true,
                  "guard": "answer-anchor",
                  "residualNearMissLookups": %d,
                  "nearMissLookupsRejected": %d,
                  "residualParaphraseLookups": %d,
                  "paraphraseLookupsRejected": %d
                }
                """.trimIndent() + "\n",
                nearMisses, caught, paraphrases, broken,
            ),
        )
        return out
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * The lookups [MatchGuards.standard] would serve today — the only ones a response-aware guard can
     * add anything to. Recomputed rather than recorded, so an improvement to a prompt-side guard shows
     * up here as a smaller denominator instead of an unnoticed lie.
     */
    private fun residual(pairs: List<ResponsePair>): List<ResponsePair.Scenario> =
        pairs.flatMap { it.scenarios() }.filter { standardServes(it.query, it.cachedPrompt) }

    private fun standardServes(query: String, cachedPrompt: String): Boolean =
        MatchGuards.standard().none { it.evaluate(query, cachedPrompt) is GuardVerdict.Reject }

    private fun anchorRejects(scenario: ResponsePair.Scenario): Boolean =
        anchor.evaluate(
            scenario.query,
            scenario.cachedPrompt,
            scenario.cachedResponse,
        ) is GuardVerdict.Reject

    private companion object {
        /**
         * 14 of the 118 residual near-miss lookups, at the cost of **none** of the 164 residual
         * paraphrase lookups. Set at the measurement rather than under it: this data cannot grow the
         * way a blind split can, so there is no honest reason for the number to drift down.
         */
        private const val RESIDUAL_CATCH_FLOOR = 14

        /**
         * Zero, and it should stay zero.
         *
         * A guard that rejects a genuine paraphrase costs one API call, which is survivable — but this
         * one is opt-in on the strength of catching a minority of the residual for nothing. The moment
         * it starts costing paraphrases, that argument has to be made again rather than assumed.
         */
        private const val PARAPHRASE_BREAKAGE_CEILING = 0
    }
}

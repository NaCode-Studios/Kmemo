package dev.kmemo.guard

import dev.kmemo.fixtures.BlindPairDisclosure
import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.QqpCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import dev.kmemo.guard.tck.ScoreInterval
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard layer measured against the committed corpora, with no embedder involved.
 *
 * Two numbers are reported for everything, because one of them is not evidence. The guards were
 * written and tuned with [TUNED_CORPUS] in view, so its score measures the fitting. The other two are
 * out-of-sample and **retired**: their failures have been read, so they are regression gates rather
 * than blind evidence, and the blind evidence lives in the two fetched splits. `docs/CORPUS.md` has
 * the policy and `ExternalCorpusTest` and `QqpCorpusTest` hold the fetched ones.
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
     * Floors on the two out-of-sample corpora, so a change that only helps the tuned set cannot
     * pass unnoticed.
     *
     * Each floor sits just under the current measurement rather than at an aspiration. Its job is to
     * fail when the number moves down, not to claim the number is good. Retiring a split does not
     * lower its floor: a spent split is still the best regression gate this repository has, and the
     * only thing retirement changes is what its number may be quoted as.
     */
    @Test
    fun `out-of-sample performance does not regress`() {
        val guards = MatchGuards.standard()
        for ((corpus, floors) in mapOf(
            HELD_OUT_CORPUS to (HELD_OUT_NEAR_MISS_FLOOR to HELD_OUT_PARAPHRASE_FLOOR),
            VALIDATION_CORPUS to (VALIDATION_NEAR_MISS_FLOOR to VALIDATION_PARAPHRASE_FLOOR),
        )) {
            val caught = corpus.nearMisses.count { rejectionFor(guards, it) != null }
            val kept = corpus.paraphrases.count { rejectionFor(guards, it) == null }
            assertTrue(
                caught >= floors.first,
                "${corpus.name} caught $caught/${corpus.nearMisses.size}, below the ${floors.first} floor",
            )
            assertTrue(
                kept >= floors.second,
                "${corpus.name} kept $kept/${corpus.paraphrases.size}, below the ${floors.second} floor",
            )
        }
    }

    @Test
    fun `no guards means no protection at all`() {
        val caught = TUNED_CORPUS.nearMisses.count { rejectionFor(MatchGuards.none(), it) != null }
        assertTrue(caught == 0, "MatchGuards.none() rejected $caught pairs; it must reject nothing")
    }

    /**
     * The size the committed splits are, against the size a five-point change would need.
     *
     * M54's whole argument in one assertion. It is written as a check rather than a comment so that
     * growing a split cannot quietly change the answer without the build saying so, and it asserts
     * the direction rather than the number: these two are too small, and the report prints by how
     * much.
     */
    @Test
    fun `the committed splits are too small to see a five-point change`() {
        val needed = ScoreInterval.trialsToSeparate(points = 5.0, around = 0.68)
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            assertTrue(
                corpus.nearMisses.size < needed,
                "${corpus.name} now holds ${corpus.nearMisses.size} near misses, at or past the $needed " +
                    "a five-point change needs. Its standing should be reconsidered rather than this " +
                    "assertion inverted.",
            )
        }
        println()
        println(
            "A five-point change in catch rate around 68% is distinguishable from noise at 95% from " +
                "$needed near misses. Committed splits: held-out ${HELD_OUT_CORPUS.nearMisses.size}, " +
                "validation ${VALIDATION_CORPUS.nearMisses.size}. Both are regression gates; the blind " +
                "evidence is in the fetched splits.",
        )
    }

    /**
     * Not an assertion — the report the README quotes. Run
     * `./gradlew :kmemo-core:test --tests '*CorpusTest*'` to see it.
     *
     * **It prints no individual pair from a split that is not in sample.** It used to print the whole
     * validation residual on every run, which is the act `docs/CORPUS.md` prohibits, performed by the
     * tooling on behalf of anybody who ran the suite. What a reader of this report actually needs is
     * how many got through and in which categories, and neither of those guides a fix, because nobody
     * can tune a guard towards a category without seeing the pairs.
     */
    @Test
    fun `print corpus report`() {
        println()
        for (corpus in listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            report(corpus)
        }
        residual(VALIDATION_CORPUS)
        println()
        println("The tuned corpus is in-sample: the guards were fitted against it. The other two are")
        println("retired: out of sample once, failures since read, kept as regression gates. The blind")
        println("evidence is the fetched external and qqp splits. See docs/CORPUS.md.")
        println()
    }

    /**
     * Writes the same numbers the report above prints, as JSON, for CI to diff across commits.
     *
     * Asserts structure, not quality — the near-miss and paraphrase *floors* are the regression tests
     * above. Here we only check the artifact is well-formed and internally consistent.
     */
    @Test
    fun `emit a machine-readable guard report`() {
        val corpora = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS) +
            listOfNotNull(QqpCorpus.corpus())
        val report = GuardReport.of(MatchGuards.standard(), corpora)

        assertEquals(corpora.map { it.name }, report.corpora.map { it.corpus })
        for (corpus in report.corpora) {
            assertEquals(MatchGuards.standard().map { it.name }, corpus.perGuard.map { it.guard })
            assertEquals(corpus.pairs, corpus.nearMisses + corpus.paraphrases)
            assertTrue(corpus.nearMissesRejected in 0..corpus.nearMisses)
            assertTrue(corpus.paraphrasesKept in 0..corpus.paraphrases)
            for (stat in corpus.perGuard) {
                assertTrue(
                    stat.uniqueCatches <= stat.caught,
                    "${corpus.corpus}/${stat.guard}: a guard cannot uniquely catch more than it catches",
                )
                assertTrue(
                    stat.uniqueFalseRejections <= stat.falseRejections,
                    "${corpus.corpus}/${stat.guard}: unique false rejections exceed its own rejections",
                )
            }
        }

        val out = File("build/reports/guards/guard-report.json")
        out.parentFile.mkdirs()
        out.writeText(report.toJsonString())
        assertTrue(out.exists() && out.length() > 0, "expected a report at ${out.absolutePath}")
    }

    private fun report(corpus: Corpus) {
        val guards = MatchGuards.standard()
        val caught = corpus.nearMisses.count { rejectionFor(guards, it) != null }
        val kept = corpus.paraphrases.count { rejectionFor(guards, it) == null }
        val catchInterval = ScoreInterval.wilson95(caught, corpus.nearMisses.size)
        val keptInterval = ScoreInterval.wilson95(kept, corpus.paraphrases.size)

        println(
            String.format(
                Locale.ROOT,
                "%-9s corpus (%s): %4d pairs, near misses rejected %4d/%-4d (%3.0f%% ±%.1f), " +
                    "paraphrases kept %4d/%-4d (%3.0f%% ±%.1f)",
                corpus.name,
                corpus.standing.name.lowercase().replace('_', '-'),
                corpus.pairs.size,
                caught,
                corpus.nearMisses.size,
                100.0 * caught / corpus.nearMisses.size,
                catchInterval.halfWidthPoints,
                kept,
                corpus.paraphrases.size,
                100.0 * kept / corpus.paraphrases.size,
                keptInterval.halfWidthPoints,
            ),
        )

        println("  per guard: alone, and what the chain would lose without it")
        val report = GuardReport.of(guards, listOf(corpus)).corpora.single()
        for (stat in report.perGuard) {
            println(
                String.format(
                    Locale.ROOT,
                    "    %-22s alone: caught %4d, false rejections %4d   marginal: unique %3d, " +
                        "paraphrases lost to it alone %3d",
                    stat.guard,
                    stat.caught,
                    stat.falseRejections,
                    stat.uniqueCatches,
                    stat.uniqueFalseRejections,
                ),
            )
        }
        println()
    }

    /**
     * What still gets through, as a count and a distribution rather than as pairs.
     *
     * The distribution is the part that survives M53. It answers the question somebody reading this
     * report has, which is whether the residual is spread out or concentrated somewhere, and it
     * guides nothing, because a category name is not a pair and nobody can aim a guard at one.
     */
    private fun residual(corpus: Corpus) {
        val missed = corpus.nearMisses.filter { rejectionFor(MatchGuards.standard(), it) == null }
        println()
        println(
            "Near misses that still get through on ${corpus.name}: ${missed.size} of " +
                "${corpus.nearMisses.size}, by category",
        )
        missed.groupingBy { it.category }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .forEach { (category, count) -> println(String.format(Locale.ROOT, "  %-14s %3d", category, count)) }

        if (BlindPairDisclosure.mayPrintPairsOf(corpus)) {
            missed.forEach { println("  [${it.category}] ${it.a}  ||  ${it.b}") }
        } else {
            println(BlindPairDisclosure.withheldNotice(corpus, missed.size))
        }
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
        private const val TUNED_NEAR_MISS_FLOOR = 74
        private const val HELD_OUT_NEAR_MISS_FLOOR = 58
        private const val HELD_OUT_PARAPHRASE_FLOOR = 35
        private const val VALIDATION_NEAR_MISS_FLOOR = 68
        private const val VALIDATION_PARAPHRASE_FLOOR = 43
    }
}

package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.LongPromptCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M28: the guards measured against prompt **length**, across every split there is.
 *
 * The external split's headline number came with a breakdown nobody read: `substitution` rejected
 * fourteen percent of its paraphrases against four percent of the validation split's, from a guard
 * that had not changed. "Different register" was offered as the explanation, and it is a label rather
 * than a measurement. It covers at least three things — length, sentence structure, subject matter —
 * that would each need a different answer.
 *
 * This measures the first of them. Every pair in every split is filed by the mean length of its two
 * prompts, and the chain and each guard are measured per band. Two things come out of it that a single
 * averaged figure cannot say: whether a guard's behaviour changes with length *within* one split, and
 * how far up the length axis any measurement reaches at all.
 *
 * The answer to the second is short, and it is why [LongPromptCorpus] exists: every pair this project
 * has, written or fetched, is between 19 and 214 characters. A team caching two-thousand-character RAG
 * prompts was running guards measured on none of their traffic.
 */
class GuardLengthTest {

    /**
     * The derived ladder is expensive and 1,000 pairs is far above the band's readability floor, so it
     * is drawn from a deterministic prefix of the external split rather than all 8,000 of it.
     */
    private val derivedSourceSize = 1_000

    @Test
    fun `emit the length report`() {
        val report = GuardReport.of(MatchGuards.standard(), splits())

        val out = File("build/reports/guards/guard-length-report.json")
        out.parentFile.mkdirs()
        out.writeText(report.toJsonString())
        assertTrue(out.exists() && out.length() > 0, "expected a report at ${out.absolutePath}")

        for (corpus in report.corpora) {
            assertEquals(
                corpus.pairs,
                corpus.byLength.sumOf { it.pairs },
                "${corpus.corpus}: the bands must partition the corpus, not sample it",
            )
            assertEquals(
                corpus.nearMisses,
                corpus.byLength.sumOf { it.nearMisses },
                "${corpus.corpus}: every near miss belongs to exactly one band",
            )
            assertEquals(
                corpus.nearMissesRejected,
                corpus.byLength.sumOf { it.nearMissesRejected },
                "${corpus.corpus}: banding must not change a verdict",
            )
        }
    }

    /**
     * The committed corpora hold no long prompt at all, and that is a fact about the evidence rather
     * than a fact about the guards. It is asserted so that the day somebody adds one, this test fails
     * and the sentence in the README saying nothing above 214 characters was measured gets revisited.
     */
    @Test
    fun `the written corpora reach nowhere near a RAG prompt`() {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)
        val longest = written.flatMap { it.pairs }.maxOf { LengthBucket.lengthOf(it) }
        assertTrue(
            longest < 96,
            "a corpus now holds a $longest-character pair. The README states that the written splits " +
                "stop below 96 characters and that everything above that is the external split or a " +
                "derived one; update it rather than this assertion.",
        )
    }

    /**
     * Not an assertion: the table the README quotes.
     *
     * `./gradlew :kmemo-core:jvmTest --tests '*GuardLengthTest*'`
     */
    @Test
    fun `print the length report`() {
        println()
        for (corpus in GuardReport.of(MatchGuards.standard(), splits()).corpora) {
            println(corpus.corpus)
            println("  band       pairs   near  caught        para   kept")
            for (band in corpus.byLength) {
                if (band.pairs == 0) {
                    println(String.format(Locale.ROOT, "  %-9s      0      -       -           -      -", band.bucket))
                    continue
                }
                println(
                    String.format(
                        Locale.ROOT,
                        "  %-9s %6d %6d %6d %s %6d %6d %s",
                        band.bucket,
                        band.pairs,
                        band.nearMisses,
                        band.nearMissesRejected,
                        rate(band.nearMissesRejected, band.nearMisses, band.readable),
                        band.paraphrases,
                        band.paraphrasesKept,
                        rate(band.paraphrasesKept, band.paraphrases, band.readable),
                    ),
                )
            }
            println("  per guard, false rejections as a share of the band's paraphrases:")
            for (guard in MatchGuards.standard()) {
                val cells = corpus.byLength.joinToString(" ") { band ->
                    val stat = band.perGuard.first { it.guard == guard.name }
                    String.format(
                        Locale.ROOT,
                        "%-9s",
                        if (band.paraphrases == 0) "-" else rate(stat.falseRejections, band.paraphrases, band.readable),
                    )
                }
                println(String.format(Locale.ROOT, "    %-22s %s", guard.name, cells))
            }
            println()
        }
        println("Bands: " + LengthBucket.DEFAULT.joinToString(" ") { it.label })
        println(
            "A band under ${LengthBucket.MIN_PAIRS_FOR_A_RATE} pairs prints its counts and no rate. " +
                "The +rag splits are derived from the external one by wrapping both sides of each pair " +
                "in an identical retrieved-context envelope: they measure dilution, not the guards.",
        )
        println()
    }

    private fun rate(part: Int, whole: Int, readable: Boolean): String = when {
        whole == 0 -> "-"
        !readable -> "(thin)"
        else -> String.format(Locale.ROOT, "%3.0f%%", 100.0 * part / whole)
    }

    /** Every split there is: the three written, the fetched one, and the derived ladder above it. */
    private fun splits(): List<Corpus> {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)
        val external = ExternalCorpus.pairs() ?: return written
        val derived = LongPromptCorpus
            .ladder(external.take(derivedSourceSize), ExternalCorpus.NAME)
            .map { (name, pairs) -> Corpus.of(name, pairs) }
        return written + Corpus.of(ExternalCorpus.NAME, external) + derived
    }
}

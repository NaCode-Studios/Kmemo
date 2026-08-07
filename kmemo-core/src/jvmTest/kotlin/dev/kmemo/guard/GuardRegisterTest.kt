package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.Register
import dev.kmemo.fixtures.Registers
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M35: the guards were tuned on one register and measured on another.
 *
 * The three written corpora are realistic traffic written by the people who wrote the guards. PAWS is
 * Wikipedia in the declarative. The catch rates differ by a factor of nearly five, and until this
 * nobody could say how much of that difference is the register and how much is the difficulty, because
 * no guard had ever been scored per register on any corpus.
 *
 * The measurement is here and the answer to "how much does register account for" is reported in the
 * README. What this file adds beyond the table is the one fact that decides how the table may be read:
 * the four splits barely overlap on this axis at all.
 */
class GuardRegisterTest {

    @Test
    fun `emit the register report`() {
        val report = GuardReport.of(MatchGuards.standard(), splits())

        val out = File("build/reports/guards/guard-register-report.json")
        out.parentFile.mkdirs()
        out.writeText(report.toJsonString())
        assertTrue(out.exists() && out.length() > 0, "expected a report at ${out.absolutePath}")

        for (corpus in report.corpora) {
            assertEquals(
                corpus.pairs,
                corpus.byRegister.sumOf { it.pairs },
                "${corpus.corpus}: the registers must partition the corpus, not sample it",
            )
            assertEquals(
                corpus.nearMissesRejected,
                corpus.byRegister.sumOf { it.nearMissesRejected },
                "${corpus.corpus}: labelling must not change a verdict",
            )
        }
    }

    /**
     * The fact that governs every comparison in the README, asserted so it cannot quietly stop being
     * true. The written splits are questions and PAWS is declarative prose, and the overlap between
     * them is a few dozen pairs out of eight thousand.
     */
    @Test
    fun `the written splits and PAWS barely share a register`() {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS).flatMap { it.pairs }
        val questionShare = written.count { Registers.of(it) == Register.QUESTION }.toDouble() / written.size
        assertTrue(
            questionShare > 0.7,
            "the written corpora are questions; they are ${(questionShare * 100).toInt()}% now",
        )

        val external = ExternalCorpus.pairs() ?: return
        val declarativeShare = external.count { Registers.of(it) == Register.DECLARATIVE }.toDouble() / external.size
        assertTrue(
            declarativeShare > 0.9,
            "PAWS is declarative prose; it is ${(declarativeShare * 100).toInt()}% now",
        )
    }

    /**
     * Not an assertion: the table the README quotes.
     *
     * `./gradlew :kmemo-core:jvmTest --tests '*GuardRegisterTest*'`
     */
    @Test
    fun `print the register report`() {
        println()
        for (corpus in GuardReport.of(MatchGuards.standard(), splits()).corpora) {
            println(corpus.corpus)
            println("  register      pairs   near  caught        para   kept")
            for (band in corpus.byRegister) {
                if (band.pairs == 0) continue
                println(
                    String.format(
                        Locale.ROOT,
                        "  %-12s %6d %6d %6d %s %6d %6d %s",
                        band.register,
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
            println("  per guard, precision and recall in the corpus's largest register:")
            val largest = corpus.byRegister.maxBy { it.pairs }
            for (stat in largest.perGuard) {
                println(
                    String.format(
                        Locale.ROOT,
                        "    %-22s caught %4d  false %4d  precision %-6s recall %-6s",
                        stat.guard,
                        stat.caught,
                        stat.falseRejections,
                        stat.precision()?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) } ?: "-",
                        stat.recall(largest.nearMisses)
                            ?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) } ?: "-",
                    ),
                )
            }
            println()
        }
        println("Registers are derived by the published rules in Registers.kt, not labelled by hand.")
        println("Every error those rules make moves a pair towards declarative, which is the label PAWS")
        println("already carries, so they can only understate the difference between the splits.")
        println()
    }

    private fun rate(part: Int, whole: Int, readable: Boolean): String = when {
        whole == 0 -> "-"
        !readable -> "(thin)"
        else -> String.format(Locale.ROOT, "%3.0f%%", 100.0 * part / whole)
    }

    private fun splits(): List<Corpus> {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)
        val external = ExternalCorpus.corpus() ?: return written
        return written + external
    }
}

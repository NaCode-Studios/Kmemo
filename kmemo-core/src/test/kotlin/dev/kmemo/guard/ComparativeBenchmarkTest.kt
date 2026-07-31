package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M23 — the comparative benchmark: decision quality on identical inputs.
 *
 * Kmemo's claim is that it rejects near misses a threshold-only cache serves. That claim was only ever
 * measured as "how many did the guards catch", which is a statement about the guards rather than a
 * comparison anybody can check. This runs the same blind corpora through three configurations and
 * reports precision, recall, F1 and — the number the whole project turns on — the **false-hit rate**.
 *
 * The baseline is `MatchGuards.none()`, the naive similarity-only configuration most teams actually
 * deploy. It is not a straw man: it is what you get from every "add a semantic cache" tutorial.
 *
 * Deliberately no latency or throughput here. Those live in `kmemo-benchmarks` and are measured across
 * Kmemo's own configurations, because a cross-runtime wall-clock figure compares runtimes while
 * appearing to compare caches.
 */
class ComparativeBenchmarkTest {

    private val configurations = listOf(
        "threshold-only" to MatchGuards.none(),
        "kmemo-standard" to MatchGuards.standard(),
        "kmemo-strict" to MatchGuards.strict(),
    )

    @Test
    fun `the threshold-only baseline serves near misses that the guards refuse`() {
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            val baseline = score(corpus, MatchGuards.none())
            val standard = score(corpus, MatchGuards.standard())

            // The entire positioning of the project, stated as an assertion rather than a paragraph.
            assertTrue(
                standard.falseHitRate < baseline.falseHitRate,
                "on ${corpus.name} the guards must lower the false-hit rate: " +
                    "baseline ${baseline.falseHitRate}, standard ${standard.falseHitRate}",
            )
            assertEquals(
                1.0,
                baseline.falseHitRate,
                "a similarity-only cache accepts every near miss it is shown, by construction",
            )
        }
    }

    @Test
    fun `stricter guards trade recall for a lower false-hit rate`() {
        val standard = score(VALIDATION_CORPUS, MatchGuards.standard())
        val strict = score(VALIDATION_CORPUS, MatchGuards.strict())

        assertTrue(strict.falseHitRate <= standard.falseHitRate, "strict must not serve more near misses")
        assertTrue(strict.recall <= standard.recall, "and it pays for that in paraphrases kept")
    }

    /**
     * Not an assertion — the table the README quotes, and a JSON artifact CI can diff across commits.
     * Run `./gradlew :kmemo-core:test --tests '*ComparativeBenchmarkTest*'` to see it.
     */
    @Test
    fun `emit the comparative report`() {
        val rows = StringBuilder()
        val json = StringBuilder("{\n  \"corpora\": [\n")
        println()
        println("Decision quality on identical inputs. Blind corpora, no verifier in the loop.")
        println()
        println(
            String.format(
                Locale.ROOT, "%-12s %-16s %9s %9s %9s %14s",
                "corpus", "configuration", "precision", "recall", "F1", "false-hit rate",
            ),
        )
        for ((ci, corpus) in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS).withIndex()) {
            json.append("    { \"corpus\": \"${corpus.name}\", \"configurations\": [\n")
            for ((i, config) in configurations.withIndex()) {
                val (name, guards) = config
                val s = score(corpus, guards)
                println(
                    String.format(
                        Locale.ROOT, "%-12s %-16s %9.3f %9.3f %9.3f %14.3f",
                        corpus.name, name, s.precision, s.recall, s.f1, s.falseHitRate,
                    ),
                )
                rows.append("| `${corpus.name}` | `$name` | ").append(
                    String.format(
                        Locale.ROOT, "%.3f | %.3f | %.3f | **%.3f** |%n",
                        s.precision, s.recall, s.f1, s.falseHitRate,
                    ),
                )
                json.append(
                    String.format(
                        Locale.ROOT,
                        "      { \"configuration\": \"%s\", \"precision\": %.4f, \"recall\": %.4f, " +
                            "\"f1\": %.4f, \"falseHitRate\": %.4f }%s%n",
                        name, s.precision, s.recall, s.f1, s.falseHitRate,
                        if (i == configurations.lastIndex) "" else ",",
                    ),
                )
            }
            json.append("    ]}${if (ci == 1) "" else ","}\n")
        }
        json.append("  ]\n}\n")
        println()
        println("false-hit rate is the share of near misses that were served. It is the number that matters.")
        println()

        val out = File("build/reports/guards/comparative-report.json")
        out.parentFile.mkdirs()
        out.writeText(json.toString())
        assertTrue(out.length() > 0)
    }

    // ---- scoring ------------------------------------------------------------------------------

    /**
     * Treats "serve this cached answer" as the positive prediction.
     *
     * A paraphrase served is a true positive, a near miss served is a **false positive** — and a false
     * positive here is a wrong answer reaching a user, which is why [falseHitRate] is reported next to
     * the usual three rather than left to be derived.
     */
    private fun score(corpus: Corpus, guards: List<MatchGuard>): Score {
        val servedParaphrases = corpus.paraphrases.count { rejectionFor(guards, it) == null }
        val servedNearMisses = corpus.nearMisses.count { rejectionFor(guards, it) == null }
        val served = servedParaphrases + servedNearMisses
        val precision = if (served == 0) 1.0 else servedParaphrases.toDouble() / served
        val recall = servedParaphrases.toDouble() / corpus.paraphrases.size
        val f1 = if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
        return Score(precision, recall, f1, servedNearMisses.toDouble() / corpus.nearMisses.size)
    }

    private fun rejectionFor(guards: List<MatchGuard>, pair: dev.kmemo.fixtures.CorpusPair): String? =
        guards.firstNotNullOfOrNull { guard ->
            (guard.evaluate(pair.a, pair.b) as? GuardVerdict.Reject)?.let { "${guard.name}: ${it.reason}" }
        }

    private class Score(
        val precision: Double,
        val recall: Double,
        val f1: Double,
        val falseHitRate: Double,
    )
}

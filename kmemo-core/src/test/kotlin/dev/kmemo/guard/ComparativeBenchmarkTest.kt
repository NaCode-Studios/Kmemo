package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.GptCacheComparison
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
 * **The fourth configuration is GPTCache**, the Python incumbent, scored on the same pairs by its own
 * `OnnxModelEvaluation` under its own default threshold — see [GptCacheComparison] for how the number
 * gets here and what keeps it honest. GPTCache's *default* evaluator is `SearchDistanceEvaluation`,
 * which scores the vector distance the retrieval step already produced; that is the threshold-only row
 * by another name, and re-running it through GPTCache would measure the embedder rather than the
 * cache. The ONNX evaluator is the only configuration where the two projects actually decide
 * differently, so it is the only one worth a second runtime.
 *
 * **The comparison is of the decision, with retrieval factored out.** Both sides are handed the same
 * candidate pair and asked whether to serve it. That is a stronger control than matching embedding
 * models, because it removes the embedder from the result entirely.
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

    /**
     * The committed GPTCache numbers must describe the corpora that are on disk right now.
     *
     * This is the whole reason an out-of-band measurement is allowed to be quoted at all. Without it,
     * growing a corpus silently re-points the README's comparison at data GPTCache was never shown.
     */
    @Test
    fun `the recorded GPTCache measurement still describes these corpora`() {
        val stale = GptCacheComparison.verifyAgainstCorpora(listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS))
        assertTrue(stale.isEmpty(), stale.joinToString("\n"))
    }

    /**
     * The comparison as it actually came out, stated as an assertion so it cannot drift into a nicer
     * claim than the data supports.
     *
     * GPTCache's cross-encoder serves **fewer** near misses than `standard()` on both blind splits. It
     * is not a weaker filter — it is a stricter one, and it pays for that by refusing more than half of
     * the genuine paraphrases it is shown, where `standard()` keeps 88%. So the honest headline is a
     * trade, not a win: kmemo's decision quality is higher by F1 on both splits, and its hit rate is
     * roughly double, while GPTCache's false-hit rate is lower.
     *
     * The claim kmemo makes about the *threshold-only* baseline is unaffected, and is asserted above.
     */
    @Test
    fun `GPTCache buys its lower false-hit rate by refusing half the genuine paraphrases`() {
        for (corpus in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS)) {
            val standard = score(corpus, MatchGuards.standard())
            val gptcache = GptCacheComparison.forCorpus(corpus.name)

            assertTrue(
                gptcache.recall < standard.recall / 1.5,
                "on ${corpus.name} GPTCache kept ${gptcache.recall} of paraphrases against " +
                    "standard()'s ${standard.recall}; the trade this test records has changed shape",
            )
            assertTrue(
                gptcache.f1 < standard.f1,
                "on ${corpus.name} GPTCache's F1 ${gptcache.f1} is no longer below standard()'s " +
                    "${standard.f1}, so the README must stop claiming higher decision quality",
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
        val json = StringBuilder("{\n  \"corpora\": [\n")
        println()
        println("Decision quality on identical inputs. Blind corpora, no verifier in the loop.")
        println("GPTCache row measured ${GptCacheComparison.measuredOn} by tools/gptcache-comparison.")
        println()
        println(
            String.format(
                Locale.ROOT, "%-12s %-16s %9s %9s %9s %14s",
                "corpus", "configuration", "precision", "recall", "F1", "false-hit rate",
            ),
        )
        for ((ci, corpus) in listOf(HELD_OUT_CORPUS, VALIDATION_CORPUS).withIndex()) {
            json.append("    { \"corpus\": \"${corpus.name}\", \"configurations\": [\n")
            for ((name, guards) in configurations) {
                val s = score(corpus, guards)
                emitRow(json, corpus.name, name, s.precision, s.recall, s.f1, s.falseHitRate)
            }
            val g = GptCacheComparison.forCorpus(corpus.name)
            emitRow(json, corpus.name, "gptcache-onnx", g.precision, g.recall, g.f1, g.falseHitRate, last = true)
            json.append("    ]}${if (ci == 1) "" else ","}\n")
        }
        json.append("  ]\n}\n")
        println()
        println("false-hit rate is the share of near misses that were served. It is the number that matters,")
        println("but not the only one: gptcache-onnx serves fewer of them than kmemo and refuses more than")
        println("half the genuine paraphrases to do it, which is why recall is printed beside it.")
        println()

        val out = File("build/reports/guards/comparative-report.json")
        out.parentFile.mkdirs()
        out.writeText(json.toString())
        assertTrue(out.length() > 0)
    }

    @Suppress("LongParameterList")
    private fun emitRow(
        json: StringBuilder,
        corpus: String,
        configuration: String,
        precision: Double,
        recall: Double,
        f1: Double,
        falseHitRate: Double,
        last: Boolean = false,
    ) {
        println(
            String.format(
                Locale.ROOT, "%-12s %-16s %9.3f %9.3f %9.3f %14.3f",
                corpus, configuration, precision, recall, f1, falseHitRate,
            ),
        )
        json.append(
            String.format(
                Locale.ROOT,
                "      { \"configuration\": \"%s\", \"precision\": %.4f, \"recall\": %.4f, " +
                    "\"f1\": %.4f, \"falseHitRate\": %.4f }%s%n",
                configuration, precision, recall, f1, falseHitRate, if (last) "" else ",",
            ),
        )
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

package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.ExternalCorpus
import dev.kmemo.fixtures.HELD_OUT_CORPUS
import dev.kmemo.fixtures.ReferenceVerifier
import dev.kmemo.fixtures.ResponseCorpus
import dev.kmemo.fixtures.TUNED_CORPUS
import dev.kmemo.fixtures.VALIDATION_CORPUS
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M36: the verifier's catch rate was published and its price was not.
 *
 * A cache sold on saving model calls owes the reader the cost of its safety net. The verifier runs on
 * candidates that cleared similarity and cleared the guards, which is to say on the cases the cheap
 * layers could not settle, and every one of those is a call this library exists to avoid. The catch rate
 * says how many wrong answers it prevents. It does not say how many extra calls were spent preventing
 * them, and the two numbers together are the only form in which the feature can be evaluated.
 *
 * A reader could construct the worst case and had no way to rule it out: a verifier that fires on most
 * candidates and catches few costs more than the false hits it prevents, unless a false hit is very
 * expensive, and how expensive a false hit is depends on the deployment rather than on this library.
 *
 * ### What is counted, and in what unit
 *
 * **Invocations per lookup.** A lookup reaches the verifier when a candidate cleared the threshold and
 * survived every guard. Measured here as the share of corpus lookups the guard chain still serves, which
 * is the residual by definition.
 *
 * **Tokens per invocation.** The reference implementation is a cross-encoder, so one invocation reads
 * both prompts once. Tokens are counted the way the guards tokenize, which is the count this repository
 * can state exactly; a provider's own tokenizer will differ by a constant nobody here can know.
 *
 * **Cost per avoided false hit.** Tokens spent across every invocation, divided by the near misses the
 * verifier refused. That is a number a caller can convert to their own currency and compare against what
 * a wrong answer costs them, which is the judgement this milestone exists to hand back to them.
 */
class VerifierCostTest {

    @Test
    fun `emit the verifier cost report`() {
        val report = splits().map { cost(it) }

        val out = File("build/reports/guards/verifier-cost.json")
        out.parentFile.mkdirs()
        out.writeText(
            buildString {
                append("{\n  \"model\": \"${ReferenceVerifier.model}\",\n  \"splits\": [\n")
                append(
                    report.joinToString(",\n") { c ->
                        String.format(
                            Locale.ROOT,
                            """    {"split": "%s", "lookups": %d, "invocations": %d, """ +
                                """"invocationsPerLookup": %.4f, "tokensPerInvocation": %.1f, """ +
                                """"tokensSpent": %d}""",
                            c.split, c.lookups, c.invocations, c.invocationsPerLookup,
                            c.tokensPerInvocation, c.tokensSpent,
                        )
                    },
                )
                append("\n  ]\n}\n")
            },
        )
        assertTrue(out.exists() && out.length() > 0)
    }

    /**
     * The guards must remove work from the expensive layer, on every split.
     *
     * Not "the verifier runs on a minority of lookups", which was the first thing asserted here and is
     * false: on validation it runs on 51% and on PAWS on 83%. That is a fact about the corpora rather
     * than about the chain, and it is the caveat the published figures carry. These splits are 56% to
     * 67% near misses by construction, because a corpus of realistic traffic would be almost all
     * paraphrases and would measure nothing. Every invocation rate below is therefore an **upper
     * bound**: real traffic whose near-miss share is a few per cent sends the verifier a few per cent
     * of its lookups.
     *
     * What must hold whatever the corpus is that the cheap layers are doing something, and that the
     * cost of a caught false hit stays in the range the documentation publishes.
     */
    @Test
    fun `the guards remove work from the verifier and the cost per catch stays bounded`() {
        for (c in splits().map { cost(it) }) {
            assertTrue(
                c.invocations < c.lookups,
                "${c.split}: every lookup reached the verifier, so the guards removed no work at all.",
            )
        }

        val residual = ResponseCorpus.nearMisses
            .flatMap { it.scenarios() }
            .filter { serves(it.query, it.cachedPrompt) }
        val caught = residual.count { ReferenceVerifier.verdictFor(it.query, it.cachedPrompt)?.served == false }
        val tokens = residual.sumOf { tokensOf(it.query, it.cachedPrompt) }
        assertTrue(caught > 0, "the reference verifier caught nothing; the measurement describes nothing")
        assertTrue(
            tokens / caught <= MAX_TOKENS_PER_CATCH,
            "a caught false hit now costs ${tokens / caught} tokens, above the " +
                "$MAX_TOKENS_PER_CATCH docs/MEASUREMENTS.md publishes.",
        )
    }

    /** Not an assertion: the figures the documentation quotes beside the catch rate. */
    @Test
    fun `print what the verifier costs`() {
        println()
        println("Verifier cost, against ${ReferenceVerifier.model}.")
        println("  split          lookups  invocations  per lookup  tokens/call  tokens spent")
        for (c in splits().map { cost(it) }) {
            println(
                String.format(
                    Locale.ROOT,
                    "  %-14s %7d %12d %11.2f %12.1f %13d",
                    c.split, c.lookups, c.invocations, c.invocationsPerLookup,
                    c.tokensPerInvocation, c.tokensSpent,
                ),
            )
        }

        // The residual the reference verifier was actually shown, which is the only split where the
        // avoided false hits are a measurement rather than an extrapolation.
        val residual = ResponseCorpus.nearMisses
            .flatMap { it.scenarios() }
            .filter { serves(it.query, it.cachedPrompt) }
        val caught = residual.count { ReferenceVerifier.verdictFor(it.query, it.cachedPrompt)?.served == false }
        val tokens = residual.sumOf { tokensOf(it.query, it.cachedPrompt) }
        println()
        println("  On the residual the reference verifier was shown, across held-out and validation:")
        println("    invocations                 ${residual.size}")
        println("    false hits avoided          $caught")
        println("    tokens spent                $tokens")
        if (caught > 0) {
            println("    tokens per avoided false hit ${tokens / caught}")
        }
        println()
        println("  Tokens are counted the way the guards tokenize. A provider's tokenizer differs by a")
        println("  constant this repository cannot know, so convert with your own before comparing.")
        println()
    }

    private data class Cost(
        val split: String,
        val lookups: Int,
        val invocations: Int,
        val invocationsPerLookup: Double,
        val tokensPerInvocation: Double,
        val tokensSpent: Long,
    )

    /**
     * What the verifier would cost on one split.
     *
     * A lookup reaches the verifier when the guard chain served it, in either direction, which is the
     * definition of the residual. Both directions are counted because either prompt could be the one
     * already cached when the other arrives, which is the rule every corpus measurement here uses.
     */
    private fun cost(corpus: Corpus): Cost {
        val reaching = corpus.pairs.filter { serves(it.a, it.b) }
        val tokens = reaching.sumOf { tokensOf(it.a, it.b) }
        return Cost(
            split = corpus.name,
            lookups = corpus.pairs.size,
            invocations = reaching.size,
            invocationsPerLookup = reaching.size.toDouble() / corpus.pairs.size,
            tokensPerInvocation = if (reaching.isEmpty()) 0.0 else tokens.toDouble() / reaching.size,
            tokensSpent = tokens,
        )
    }

    /** Whether the guard chain serves this pair, which is what sends it to the verifier. */
    private fun serves(query: String, cached: String): Boolean = MatchGuards.standard().none {
        it.evaluate(query, cached) is GuardVerdict.Reject || it.evaluate(cached, query) is GuardVerdict.Reject
    }

    /** One cross-encoder invocation reads both prompts once. */
    private fun tokensOf(query: String, cached: String): Long =
        (Text.tokens(query).size + Text.tokens(cached).size).toLong()

    private fun splits(): List<Corpus> {
        val written = listOf(TUNED_CORPUS, HELD_OUT_CORPUS, VALIDATION_CORPUS)
        val external = ExternalCorpus.corpus() ?: return written
        return written + external
    }

    private companion object {
        /** Measured at 21 on the day this shipped. The ceiling is where the published claim would break. */
        private const val MAX_TOKENS_PER_CATCH = 40
    }
}

package dev.kmemo.examples

import dev.dokimos.core.EvalTestCase
import dev.dokimos.core.Evaluator
import dev.dokimos.kotlin.dsl.exactMatch
import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M40: an evaluation suite is the workload a semantic cache is best at, measured rather than asserted.
 *
 * [Dokimos](https://github.com/dokimos-dev/dokimos) is the LLM and agent evaluation framework for the
 * JVM. An evaluation suite runs the same prompts against the same model on every push, so a golden set
 * of five hundred cases is five hundred model calls per run and the bill grows with the team rather
 * than with the product. That is not an incidental cost: it is the reason evaluation suites get moved
 * to a nightly job, then to a manual one, then stop running.
 *
 * The fit is cleaner here than in production, and it is worth being precise about why. An evaluation
 * replays **identical** prompts by construction, so the hit rate is high and the false-hit risk this
 * library exists to guard against is at its lowest: the guards are being asked to compare a prompt with
 * itself.
 *
 * ### There is no adapter, and that is the finding
 *
 * Dokimos plugs in at a Spring AI `ChatModel`, a LangChain4j model, a Koog agent or a plain lambda, and
 * `kmemo-spring-ai` and `kmemo-langchain4j` already sit at exactly those seams. The system under test is
 * the caller's own code, so a cache goes in front of the caller's own model client, which is what this
 * test does with a lambda. An adapter in either repository would wrap a seam that is already wrapped.
 *
 * The judge is the second place it pays, and the same shape: `JudgeLM` is a lambda, so a cached judge is
 * a cached lambda. Judging the same input and output pair on every run is the same identical work the
 * task is.
 */
class DokimosEvaluationCacheTest {

    /** A golden set: the shape an evaluation suite has, small enough to read. */
    private val goldenSet = listOf(
        "What is the capital of France?" to "Paris",
        "What is 2+2?" to "4",
        "Who wrote Hamlet?" to "Shakespeare",
        "What is the boiling point of water at sea level in Celsius?" to "100",
        "What is the largest planet in the solar system?" to "Jupiter",
        "In which year did the Berlin Wall fall?" to "1989",
        "What is the chemical symbol for gold?" to "Au",
        "How many continents are there?" to "7",
    )

    @Test
    fun `the second run of an evaluation suite makes no model calls`() = runTest {
        val model = CountingModel()
        val cache = SemanticCache(bagOfWords, InMemoryStore())
        val evaluator = exactMatch {
            name = "Exact match"
            threshold = 1.0
        }

        val first = evaluate(cache, model, evaluator)
        val callsInFirstRun = model.calls
        val second = evaluate(cache, model, evaluator)
        val callsInSecondRun = model.calls - callsInFirstRun

        println()
        println("Dokimos golden set of ${goldenSet.size} cases, run twice through one SemanticCache")
        println("  model calls, first run    $callsInFirstRun")
        println("  model calls, second run   $callsInSecondRun")
        println("  pass rate                 $first then $second")
        println()

        assertEquals(goldenSet.size, callsInFirstRun, "a cold cache pays for the whole suite")
        assertEquals(0, callsInSecondRun, "and the second run is entirely lookups")
        assertEquals(first, second, "the suite reaches the same verdict either way, or the saving is a lie")
        assertTrue(first == 1.0, "the fixture answers correctly, so the pass rate is 1.0")
    }

    /**
     * The property that makes it safe to do this at all. An evaluation replays identical prompts, so the
     * cache is being asked to serve a prompt for itself, and a suite whose verdicts moved because a cache
     * was added would be worse than a suite that costs money.
     */
    @Test
    fun `caching does not change what the suite decides`() = runTest {
        val evaluator = exactMatch {
            name = "Exact match"
            threshold = 1.0
        }
        val uncached = evaluate(null, CountingModel(), evaluator)
        val cached = evaluate(SemanticCache(bagOfWords, InMemoryStore()), CountingModel(), evaluator)

        assertEquals(uncached, cached)
    }

    /** Runs the golden set once and returns the pass rate. */
    private suspend fun evaluate(
        cache: SemanticCache?,
        model: CountingModel,
        evaluator: Evaluator,
    ): Double {
        var passed = 0
        for ((input, expected) in goldenSet) {
            val actual = cache?.getOrPut(input) { model.answer(it) } ?: model.answer(input)
            val testCase = EvalTestCase.of(input, expected, actual)
            if (evaluator.evaluate(testCase).success()) passed++
        }
        return passed.toDouble() / goldenSet.size
    }

    /** Stands in for the system under test, and counts what it would have cost. */
    private class CountingModel {
        var calls: Int = 0
            private set

        fun answer(prompt: String): String {
            calls++
            return ANSWERS.getValue(prompt)
        }

        companion object {
            private val ANSWERS = mapOf(
                "What is the capital of France?" to "Paris",
                "What is 2+2?" to "4",
                "Who wrote Hamlet?" to "Shakespeare",
                "What is the boiling point of water at sea level in Celsius?" to "100",
                "What is the largest planet in the solar system?" to "Jupiter",
                "In which year did the Berlin Wall fall?" to "1989",
                "What is the chemical symbol for gold?" to "Au",
                "How many continents are there?" to "7",
            )
        }
    }

    /**
     * A deterministic bag-of-words embedder, so the measurement is about the cache rather than about
     * somebody's embedding API. An evaluation replays identical prompts, so an identical prompt scoring
     * 1.0 against itself is all the embedding has to get right here.
     */
    private val bagOfWords = Embedder { text ->
        FloatArray(256).also { vector ->
            for (token in text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }) {
                vector[((token.hashCode() % 256) + 256) % 256] += 1.0f
            }
        }
    }
}

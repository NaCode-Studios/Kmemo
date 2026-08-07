package dev.kmemo.examples

import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import dev.kmemo.guard.MatchGuards
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M42: what the cache removes from a retrieval-augmented pipeline, and what it gets wrong there.
 *
 * Every other number this repository publishes comes from a benchmark it wrote for itself, against
 * corpora chosen to exercise the guards. None of them answers the question a reader arrives with: on a
 * RAG pipeline, how much does this remove, and how much of what it serves is wrong because the retrieved
 * context differed while the question did not.
 *
 * That second failure is one this project's own benchmarks are **structurally unable to produce**. Their
 * pairs are two prompts and a verdict; a RAG false hit needs two prompts that are the same and two
 * documents that are not.
 *
 * ### The pipeline
 *
 * SQuAD v1.1 dev, fetched by `tools/rag-corpus/fetch.py`: paragraphs from Wikipedia, questions asked
 * about them, and the answer marked inside the paragraph. That is a retrieval-augmented pipeline with
 * the retrieval already labelled, so whether a generated answer was right is a lookup rather than a
 * judgement and no model has to be trusted or paid.
 *
 * Retrieval picks the nearest paragraph to the question. Generation returns the labelled answer for the
 * paragraph that was retrieved. The cache sits in front of generation, which is where a cache can act
 * and where the retrieved context makes two near-identical questions into genuinely different prompts.
 *
 * ### The three configurations, and why all three are here
 *
 * **Keyed on the question with no guards** is the threshold-only cache every "add a semantic cache"
 * tutorial builds, and it is the baseline the wrong answers are counted against.
 *
 * **Keyed on the question, guarded** is what a team gets by adding this library and changing nothing
 * else. Whether the guards survive contact with retrieved context was unknown and unknowable from
 * anything published here, and the difference between this row and the one above it is the answer.
 *
 * **Keyed on the question with the retrieved document folded in**, which is what `context` on
 * `getOrPut` is for, is the configuration that is correct on this workload. What is left after it is
 * the residual nothing prompt-side can reach: questions that are genuinely identical, asked of
 * different paragraphs.
 */
class RagPipelineTest {

    @Test
    fun `print what a cache removes from a RAG pipeline`() = runTest {
        val corpus = corpus() ?: return@runTest

        val configurations = listOf(
            "question, no guards" to run(corpus, foldContextIntoKey = false, guarded = false),
            "question, guarded" to run(corpus, foldContextIntoKey = false, guarded = true),
            "question + context" to run(corpus, foldContextIntoKey = true, guarded = true),
        )

        println()
        println("RAG over SQuAD v1.1 dev: ${corpus.documents.size} paragraphs, ${corpus.questions.size} questions.")
        println("Retrieval is nearest-paragraph; generation returns the labelled answer for what it retrieved.")
        println()
        println("  keyed on            model calls cold  model calls warm  removed  wrong answers served")
        for ((label, result) in configurations) {
            println(
                String.format(
                    Locale.ROOT,
                    "  %-19s %16d %17d %7.0f%% %22d",
                    label,
                    result.coldCalls,
                    result.warmCalls,
                    100.0 * (result.coldCalls - result.warmCalls) / result.coldCalls,
                    result.falseHits,
                ),
            )
        }
        println()
        println("A wrong answer served is a cache hit whose answer is not the labelled answer for the")
        println("question that was asked. It is a false hit with a cache hit's latency and no log line,")
        println("and it is the failure this project's own corpora cannot produce, because it needs two")
        println("prompts that are the same and two documents that are not.")
        println()
    }

    /**
     * The finding, held to its direction rather than to its numbers.
     *
     * A threshold-only cache must serve wrong answers on this corpus or the pipeline is measuring
     * nothing. The guards must not make it worse, which is the claim of this whole library on the
     * workload it is most often put in front of. And folding the retrieved document into the key must
     * not make it worse than leaving it out.
     */
    @Test
    fun `the guards survive contact with retrieved context`() = runTest {
        val corpus = corpus() ?: return@runTest

        val unguarded = run(corpus, foldContextIntoKey = false, guarded = false)
        val guarded = run(corpus, foldContextIntoKey = false, guarded = true)
        val contextual = run(corpus, foldContextIntoKey = true, guarded = true)

        assertTrue(
            unguarded.falseHits > 0,
            "a threshold-only cache served no wrong answer on ${corpus.questions.size} questions over " +
                "${corpus.documents.size} paragraphs. Either the corpus stopped containing questions " +
                "asked of more than one paragraph or the pipeline stopped retrieving, and neither " +
                "leaves this measuring anything.",
        )
        assertTrue(
            guarded.falseHits <= unguarded.falseHits,
            "the guards made a retrieval pipeline worse: ${guarded.falseHits} wrong answers against " +
                "${unguarded.falseHits} without them. That is the claim of this whole library failing " +
                "on the workload it is most often put in front of.",
        )
        assertTrue(
            contextual.falseHits <= guarded.falseHits,
            "folding the retrieved document into the key left ${contextual.falseHits} wrong answers " +
                "against ${guarded.falseHits} without it",
        )
        assertTrue(
            contextual.warmCalls < contextual.coldCalls,
            "the warm run removed no model calls, so the cache did nothing at all",
        )
    }

    private data class Result(val coldCalls: Int, val warmCalls: Int, val falseHits: Int)

    /**
     * One cold pass and one warm pass over every question, counting generations and wrong answers.
     *
     * The warm pass is the same questions in the same order, which is what a pipeline serving repeated
     * traffic looks like and what makes the difference between the two counts the saving.
     */
    private suspend fun run(corpus: Corpus, foldContextIntoKey: Boolean, guarded: Boolean): Result {
        val embedder = bagOfWords
        val cache = SemanticCache(
            embedder = embedder,
            store = InMemoryStore(maxEntries = 100_000),
            // A threshold a retrieval deployment would actually run. The library's default is
            // deliberately tight, and a tight threshold would hide the failure this is looking for by
            // refusing every near-identical question before a guard ever saw it.
            threshold = 0.90,
            guards = if (guarded) MatchGuards.standard() else MatchGuards.none(),
        )
        val index = corpus.documents.map { it to embedder.embed(it.text) }

        var calls = 0
        var coldCalls = 0
        var falseHits = 0

        repeat(2) { pass ->
            if (pass == 1) coldCalls = calls
            for (question in corpus.questions) {
                val retrieved = nearest(index, embedder.embed(question.question))
                val context = if (foldContextIntoKey) listOf(retrieved.id) else emptyList()
                val answer = cache.getOrPut(question.question, context) {
                    calls++
                    // The generation step: the labelled answer for the paragraph that was retrieved.
                    // Deterministic on purpose, so every number here is about the cache.
                    corpus.answerFor(question.question, retrieved.id) ?: NOT_IN_THIS_DOCUMENT
                }
                if (pass == 1 && answer != question.answer && retrieved.id == question.documentId) {
                    // Retrieval found the right paragraph and the cache still served another answer.
                    // Anything else would be measuring retrieval rather than the cache.
                    falseHits++
                }
            }
        }
        return Result(coldCalls, calls - coldCalls, falseHits)
    }

    private fun nearest(index: List<Pair<Document, FloatArray>>, query: FloatArray): Document {
        var best = index.first()
        var bestScore = -Double.MAX_VALUE
        for (candidate in index) {
            val score = dot(candidate.second, query)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best.first
    }

    private fun dot(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            sum += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return sum / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB))
    }

    private data class Document(val id: String, val text: String)

    private data class Question(val documentId: String, val question: String, val answer: String)

    private class Corpus(val documents: List<Document>, val questions: List<Question>) {
        private val byQuestionAndDocument = questions.associateBy { it.question to it.documentId }

        fun answerFor(question: String, documentId: String): String? =
            byQuestionAndDocument[question to documentId]?.answer
    }

    /**
     * The corpus, or `null` when it is absent and absent is allowed.
     *
     * The same policy the external guard split has, for the same reason: a developer who has never run
     * the fetch gets a skip and a sentence telling them how, and CI gets a failure, because a
     * measurement that silently stops running reads as a passing one.
     */
    private fun corpus(): Corpus? {
        val path = System.getProperty(PATH_PROPERTY)
        val required = System.getProperty(REQUIRED_PROPERTY).toBoolean()
        val file = path?.let { File(it) }
        if (file == null || !file.isFile) {
            val where = path ?: "<no $PATH_PROPERTY set>"
            if (required) {
                fail(
                    "the retrieval corpus is required here and is not at $where. Run " +
                        "tools/rag-corpus/fetch.py, and see its README.",
                )
            }
            println("skipping the RAG measurement: nothing at $where (tools/rag-corpus/fetch.py)")
            return null
        }
        val json = Json.parseToJsonElement(file.readText()).jsonObject
        return Corpus(
            documents = json.getValue("documents").jsonArray.map {
                Document(
                    it.jsonObject.getValue("id").jsonPrimitive.content,
                    it.jsonObject.getValue("text").jsonPrimitive.content,
                )
            },
            questions = json.getValue("questions").jsonArray.map {
                Question(
                    it.jsonObject.getValue("documentId").jsonPrimitive.content,
                    it.jsonObject.getValue("question").jsonPrimitive.content,
                    it.jsonObject.getValue("answer").jsonPrimitive.content,
                )
            },
        )
    }

    /** Deterministic, so the measurement is about the cache rather than about somebody's embedding API. */
    private val bagOfWords = Embedder { text ->
        FloatArray(512).also { vector ->
            for (token in text.lowercase().split(Regex("\\W+")).filter { it.length > 2 }) {
                vector[((token.hashCode() % 512) + 512) % 512] += 1.0f
            }
        }
    }

    private companion object {
        private const val PATH_PROPERTY = "kmemo.ragCorpus"
        private const val REQUIRED_PROPERTY = "kmemo.ragCorpus.required"
        private const val NOT_IN_THIS_DOCUMENT = "the retrieved paragraph does not answer this question"
    }
}

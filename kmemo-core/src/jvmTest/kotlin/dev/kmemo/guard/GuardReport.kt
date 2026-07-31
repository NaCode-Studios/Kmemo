package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** How one guard fares on one corpus, measured **in isolation** from the rest of the chain. */
data class GuardStat(
    val guard: String,
    /** Near misses this guard alone rejects — its contribution to the chain's protection. */
    val caught: Int,
    /** Paraphrases this guard alone rejects — the hits it would cost. Must be read next to [caught]. */
    val falseRejections: Int,
)

/** The guard chain measured against one [Corpus], as data rather than as a printed line. */
data class CorpusReport(
    val corpus: String,
    val pairs: Int,
    val nearMisses: Int,
    val paraphrases: Int,
    /** Near misses the whole chain rejects — the protection the corpus actually gets. */
    val nearMissesRejected: Int,
    /** Paraphrases the chain keeps. Equals [paraphrases] for a chain that rejects no paraphrase. */
    val paraphrasesKept: Int,
    /** Every guard measured alone, in chain order, so a guard that never contributes is visible. */
    val perGuard: List<GuardStat>,
)

/**
 * A machine-readable measurement of the guard chain across the corpora.
 *
 * [CorpusTest] prints a human report; this is the same numbers as data, written to
 * `build/reports/guards/guard-report.json`. A printed table is for a person reading one build; a JSON
 * artifact is for CI to diff across commits, so a guard whose catch rate slips shows up as a field
 * that changed rather than a wall of text nobody re-reads.
 *
 * Both directions of every pair are evaluated, because either prompt could be the one already cached
 * when the other arrives — the same rule the corpus regression tests use.
 */
data class GuardReport(val corpora: List<CorpusReport>) {

    /** The report as a `JsonObject`, ready to encode or assert on. */
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("corpora") {
            for (corpus in corpora) {
                addJsonObject {
                    put("corpus", corpus.corpus)
                    put("pairs", corpus.pairs)
                    put("nearMisses", corpus.nearMisses)
                    put("paraphrases", corpus.paraphrases)
                    put("nearMissesRejected", corpus.nearMissesRejected)
                    put("paraphrasesKept", corpus.paraphrasesKept)
                    putJsonArray("perGuard") {
                        for (stat in corpus.perGuard) {
                            addJsonObject {
                                put("guard", stat.guard)
                                put("caught", stat.caught)
                                put("falseRejections", stat.falseRejections)
                            }
                        }
                    }
                }
            }
        }
    }

    fun toJsonString(): String = PRETTY.encodeToString(JsonObject.serializer(), toJson())

    companion object {
        private val PRETTY = Json { prettyPrint = true }

        /** Measures [guards] as a chain — and each guard alone — over every corpus in [corpora]. */
        fun of(guards: List<MatchGuard>, corpora: List<Corpus>): GuardReport =
            GuardReport(corpora.map { corpusReport(guards, it) })

        private fun corpusReport(guards: List<MatchGuard>, corpus: Corpus): CorpusReport =
            CorpusReport(
                corpus = corpus.name,
                pairs = corpus.pairs.size,
                nearMisses = corpus.nearMisses.size,
                paraphrases = corpus.paraphrases.size,
                nearMissesRejected = corpus.nearMisses.count { rejects(guards, it) },
                paraphrasesKept = corpus.paraphrases.count { !rejects(guards, it) },
                perGuard = guards.map { guard ->
                    GuardStat(
                        guard = guard.name,
                        caught = corpus.nearMisses.count { rejects(listOf(guard), it) },
                        falseRejections = corpus.paraphrases.count { rejects(listOf(guard), it) },
                    )
                },
            )

        private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
            it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
                it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
        }
    }
}

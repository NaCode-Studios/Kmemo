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

/**
 * One band of prompt length, in characters.
 *
 * The bands double, which is the only spacing that shows a cliff wherever it happens to be: a linear
 * scale over prompts that run from twenty characters to two thousand spends most of its buckets on
 * the range nothing lives in.
 */
data class LengthBucket(val label: String, val from: Int, val untilExclusive: Int) {

    fun holds(pair: CorpusPair): Boolean = lengthOf(pair) in from until untilExclusive

    companion object {

        /**
         * The length a pair is filed under: the mean of its two prompts, in characters.
         *
         * The mean rather than the longer of the two. Every guard here reads both prompts and most of
         * them compare token counts, so a pair is only meaningfully "long" when both sides are, and
         * filing a 20-character prompt paired with a 200-character one under 200 would report a length
         * effect where what is really being measured is the gap between them — which is
         * [LengthRatioGuard]'s subject and not this one.
         *
         * Characters rather than tokens, even though the guards tokenize. A caller deciding whether
         * this library suits their traffic knows their prompts are two thousand characters; they do
         * not know how many content words survive stopword removal.
         */
        fun lengthOf(pair: CorpusPair): Int = (pair.a.length + pair.b.length) / 2

        /**
         * The bands the reports use.
         *
         * They run to 1536+ rather than stopping where the committed corpora stop, so the table says
         * out loud that the top bands are empty. An axis that ends at the last measurement reads as
         * coverage; one that ends past it reads as the gap it is.
         */
        val DEFAULT: List<LengthBucket> = listOf(
            LengthBucket("<48", 0, 48),
            LengthBucket("48-95", 48, 96),
            LengthBucket("96-191", 96, 192),
            LengthBucket("192-383", 192, 384),
            LengthBucket("384-767", 384, 768),
            LengthBucket("768-1535", 768, 1536),
            LengthBucket("1536+", 1536, Int.MAX_VALUE),
        )

        /**
         * Pairs a band needs before its rates are worth reading.
         *
         * Below this the band is reported with its counts and no percentage. Two pairs out of two is
         * not a hundred percent of anything, and a table that prints it as one is how a corpus with a
         * thin tail turns into a claim about long prompts.
         */
        const val MIN_PAIRS_FOR_A_RATE: Int = 30
    }
}

/** The guard chain measured against one band of one corpus. */
data class BucketReport(
    val bucket: String,
    val pairs: Int,
    val nearMisses: Int,
    val paraphrases: Int,
    val nearMissesRejected: Int,
    val paraphrasesKept: Int,
    val perGuard: List<GuardStat>,
) {
    /** Whether this band holds enough pairs for its rates to mean anything. See [LengthBucket]. */
    val readable: Boolean get() = pairs >= LengthBucket.MIN_PAIRS_FOR_A_RATE
}

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
    /**
     * The same measurement again, split by prompt length.
     *
     * The headline numbers above average over whatever length distribution the corpus happens to
     * have, and every corpus this project wrote is one narrow band of short prompts. A single figure
     * over that says what the guards do to short prompts and is read as what the guards do. Bands
     * with no pairs are kept rather than dropped: an empty row is the honest report of a length nobody
     * has measured.
     */
    val byLength: List<BucketReport> = emptyList(),
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
                    putJsonArray("byLength") {
                        for (bucket in corpus.byLength) {
                            addJsonObject {
                                put("bucket", bucket.bucket)
                                put("pairs", bucket.pairs)
                                put("nearMisses", bucket.nearMisses)
                                put("paraphrases", bucket.paraphrases)
                                put("nearMissesRejected", bucket.nearMissesRejected)
                                put("paraphrasesKept", bucket.paraphrasesKept)
                                put("readable", bucket.readable)
                                putJsonArray("perGuard") {
                                    for (stat in bucket.perGuard) {
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
                perGuard = perGuard(guards, corpus.nearMisses, corpus.paraphrases),
                byLength = LengthBucket.DEFAULT.map { bucket ->
                    val near = corpus.nearMisses.filter { bucket.holds(it) }
                    val para = corpus.paraphrases.filter { bucket.holds(it) }
                    BucketReport(
                        bucket = bucket.label,
                        pairs = near.size + para.size,
                        nearMisses = near.size,
                        paraphrases = para.size,
                        nearMissesRejected = near.count { rejects(guards, it) },
                        paraphrasesKept = para.count { !rejects(guards, it) },
                        perGuard = perGuard(guards, near, para),
                    )
                },
            )

        private fun perGuard(
            guards: List<MatchGuard>,
            nearMisses: List<CorpusPair>,
            paraphrases: List<CorpusPair>,
        ): List<GuardStat> = guards.map { guard ->
            GuardStat(
                guard = guard.name,
                caught = nearMisses.count { rejects(listOf(guard), it) },
                falseRejections = paraphrases.count { rejects(listOf(guard), it) },
            )
        }

        private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
            it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
                it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
        }
    }
}

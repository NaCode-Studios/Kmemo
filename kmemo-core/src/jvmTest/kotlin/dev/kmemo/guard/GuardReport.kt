package dev.kmemo.guard

import dev.kmemo.fixtures.Corpus
import dev.kmemo.fixtures.CorpusPair
import dev.kmemo.fixtures.Register
import dev.kmemo.fixtures.Registers
import dev.kmemo.guard.tck.ScoreInterval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * How one guard fares on one corpus, in isolation and inside the chain.
 *
 * The two measurements answer different questions and M52 exists because only the first was ever
 * reported. [caught] is what the guard finds when it is the only guard running, which is what a
 * third party installing it alone would get. [uniqueCatches] is what the chain would lose if this
 * guard were removed from it, which is the number that decides whether it earns its place. A guard
 * can be excellent on the first and worth nothing on the second, and eight of the eleven are.
 */
data class GuardStat(
    val guard: String,
    /** Near misses this guard alone rejects — its contribution to the chain's protection. */
    val caught: Int,
    /** Paraphrases this guard alone rejects — the hits it would cost. Must be read next to [caught]. */
    val falseRejections: Int,
    /** Near misses this guard catches that no other guard in the chain also catches. */
    val uniqueCatches: Int = 0,
    /** Paraphrases lost to this guard alone: the chain would keep them without it. */
    val uniqueFalseRejections: Int = 0,
) {
    /**
     * Of everything this guard rejected, the share that deserved it.
     *
     * `null` when the guard rejected nothing, which is not a precision of zero: a guard that abstains
     * has made no claim to be right or wrong about, and printing 0% for it would read as a failure
     * rather than as a silence.
     */
    fun precision(): Double? {
        val rejected = caught + falseRejections
        return if (rejected == 0) null else caught.toDouble() / rejected
    }

    /** Of the near misses in front of it, the share this guard caught. */
    fun recall(nearMisses: Int): Double? =
        if (nearMisses == 0) null else caught.toDouble() / nearMisses
}

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

/**
 * The guard chain measured against one register of one corpus.
 *
 * Same shape as [BucketReport] and a different axis. Length is a property of a prompt; register is a
 * property of a deployment, which is why this one can end in a preset and the other one did.
 */
data class RegisterReport(
    val register: String,
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
    /**
     * What this corpus's number is worth: `IN_SAMPLE`, `RETIRED` or `BLIND`.
     *
     * Carried in the artifact rather than left to whoever reads it, because a rate from a fitted
     * split and a rate from an untouched one are two different claims and JSON has no tone of voice.
     */
    val standing: String,
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
    /**
     * The same measurement again, split by register.
     *
     * The four splits do not overlap on this axis: the three written here are questions and PAWS is
     * declarative prose. That is the point of reporting it, because a single figure over a corpus that
     * is 99% one register describes that register and is read as describing the guards.
     */
    val byRegister: List<RegisterReport> = emptyList(),
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
/** One guard's numbers, both measurements and both intervals, in the shape the artifact carries. */
private fun JsonObjectBuilder.putStat(stat: GuardStat, nearMisses: Int) {
    put("guard", stat.guard)
    put("caught", stat.caught)
    put("falseRejections", stat.falseRejections)
    put("uniqueCatches", stat.uniqueCatches)
    put("uniqueFalseRejections", stat.uniqueFalseRejections)
    stat.precision()?.let { put("precision", it) }
    stat.recall(nearMisses)?.let { put("recall", it) }
}

/** A rate with the range the sample supports, so a diff of the artifact can tell noise from movement. */
private fun JsonObjectBuilder.putInterval(name: String, successes: Int, trials: Int) {
    val interval = ScoreInterval.wilson95(successes, trials)
    interval.rate?.let { put(name, it) }
    put("${name}Low", interval.low)
    put("${name}High", interval.high)
}

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
                    put("standing", corpus.standing)
                    putInterval("catchRate", corpus.nearMissesRejected, corpus.nearMisses)
                    putInterval("paraphrasesKeptRate", corpus.paraphrasesKept, corpus.paraphrases)
                    putJsonArray("perGuard") {
                        for (stat in corpus.perGuard) {
                            addJsonObject { putStat(stat, corpus.nearMisses) }
                        }
                    }
                    putJsonArray("byRegister") {
                        for (band in corpus.byRegister) {
                            addJsonObject {
                                put("register", band.register)
                                put("pairs", band.pairs)
                                put("nearMisses", band.nearMisses)
                                put("paraphrases", band.paraphrases)
                                put("nearMissesRejected", band.nearMissesRejected)
                                put("paraphrasesKept", band.paraphrasesKept)
                                put("readable", band.readable)
                                putJsonArray("perGuard") {
                                    for (stat in band.perGuard) {
                                        addJsonObject { putStat(stat, band.nearMisses) }
                                    }
                                }
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
                                        addJsonObject { putStat(stat, bucket.nearMisses) }
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
                standing = corpus.standing.name,
                pairs = corpus.pairs.size,
                nearMisses = corpus.nearMisses.size,
                paraphrases = corpus.paraphrases.size,
                nearMissesRejected = corpus.nearMisses.count { rejects(guards, it) },
                paraphrasesKept = corpus.paraphrases.count { !rejects(guards, it) },
                perGuard = perGuard(guards, corpus.nearMisses, corpus.paraphrases),
                byRegister = Register.entries.map { register ->
                    val near = corpus.nearMisses.filter { Registers.of(it) == register }
                    val para = corpus.paraphrases.filter { Registers.of(it) == register }
                    RegisterReport(
                        register = register.name.lowercase(),
                        pairs = near.size + para.size,
                        nearMisses = near.size,
                        paraphrases = para.size,
                        nearMissesRejected = near.count { rejects(guards, it) },
                        paraphrasesKept = para.count { !rejects(guards, it) },
                        perGuard = perGuard(guards, near, para),
                    )
                },
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

        /**
         * Every guard scored alone and again as a member, in chain order.
         *
         * The marginal half is computed by holding the pair against the chain with this guard taken
         * out, which is the only construction that answers "what would removing it cost". Counting
         * the pairs where a guard fires first would answer "what does chain order do", and chain
         * order is an optimisation.
         */
        private fun perGuard(
            guards: List<MatchGuard>,
            nearMisses: List<CorpusPair>,
            paraphrases: List<CorpusPair>,
        ): List<GuardStat> = guards.map { guard ->
            val rest = guards.filter { it !== guard }
            GuardStat(
                guard = guard.name,
                caught = nearMisses.count { rejects(listOf(guard), it) },
                falseRejections = paraphrases.count { rejects(listOf(guard), it) },
                uniqueCatches = nearMisses.count { rejects(listOf(guard), it) && !rejects(rest, it) },
                uniqueFalseRejections =
                paraphrases.count { rejects(listOf(guard), it) && !rejects(rest, it) },
            )
        }

        private fun rejects(guards: List<MatchGuard>, pair: CorpusPair): Boolean = guards.any {
            it.evaluate(pair.b, pair.a) is GuardVerdict.Reject ||
                it.evaluate(pair.a, pair.b) is GuardVerdict.Reject
        }
    }
}

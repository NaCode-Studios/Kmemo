package dev.kmemo.fixtures

/**
 * The external prose split (M24): PAWS-Wiki `labeled_final`, test split, fetched rather than
 * vendored.
 *
 * See `tools/external-corpus/README.md`. The loading and absence policy is [FetchedSplit]'s, shared
 * with the question split so that neither copy can stop enforcing it quietly.
 */
object ExternalCorpus {

    const val NAME: String = "external"

    private val split = FetchedSplit(
        name = NAME,
        pathProperty = "kmemo.externalCorpus",
        requiredProperty = "kmemo.externalCorpus.required",
        fetchScript = "tools/external-corpus/fetch.py",
    )

    /** The pairs, or `null` when the split is absent and absent is allowed. */
    fun pairs(): List<CorpusPair>? = split.pairs()

    /** The same pairs as a [Corpus], so the shared reports can measure it beside the committed three. */
    fun corpus(): Corpus? = split.corpus()
}

/**
 * The external question split (M54): Quora Question Pairs, GLUE validation, filtered to the pairs a
 * similarity threshold would surface.
 *
 * The split that makes a five-point change readable. The two blind splits written here hold 86 and
 * 102 near misses, and at that size a catch rate carries a 95% interval nine points wide in each
 * direction, so a real improvement and a lucky run are the same measurement. This one holds 2,500
 * near misses in the register the guards were built for.
 *
 * **Its provenance is not PAWS's and the difference is stated rather than blurred.** Quora's users
 * wrote the questions and Quora applied the duplicate labels, years before this project existed. The
 * selection rule that reduces 40,430 pairs to the high-overlap subset was written here, once, before
 * a guard was run against the result. `tools/qqp-corpus/README.md` states the rule, the one guard it
 * is not neutral for, and what the crowd-applied labels are worth.
 */
object QqpCorpus {

    const val NAME: String = "qqp"

    private val split = FetchedSplit(
        name = NAME,
        pathProperty = "kmemo.qqpCorpus",
        requiredProperty = "kmemo.qqpCorpus.required",
        fetchScript = "tools/qqp-corpus/fetch.py",
    )

    /** The pairs, or `null` when the split is absent and absent is allowed. */
    fun pairs(): List<CorpusPair>? = split.pairs()

    /** The same pairs as a [Corpus], so the shared reports can measure it beside the committed three. */
    fun corpus(): Corpus? = split.corpus()
}

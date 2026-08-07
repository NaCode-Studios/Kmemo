package dev.kmemo.fixtures

/**
 * What a split's number is worth, which is not the same question as what the number is.
 *
 * Every rate this project publishes carries one of these, because the three states are three
 * different claims and a table that prints them in one column asserts they are the same claim.
 * `docs/CORPUS.md` states the rules; this is the machine-readable half of them.
 */
enum class CorpusStanding {

    /**
     * Fitted against. The guards were written with these pairs in view, so the score measures the
     * fitting. A regression test, never evidence.
     */
    IN_SAMPLE,

    /**
     * Out of sample once, and its failures have since been read.
     *
     * The state M54 added, and it exists because the alternative was to keep quoting a number that
     * had stopped meaning what it said. A blind split is spent the moment somebody reads which pairs
     * it fails on, and there is no way to un-see them: from then on every subsequent guard change has
     * had the opportunity to be aimed at it. Retiring is not deleting. A retired split is still a
     * perfectly good regression gate, still carries its floor, and still gets published. What it
     * stops being is the number to quote.
     */
    RETIRED,

    /**
     * Nobody here can add to it and no failure from it has been read.
     *
     * The only state that supports a claim about what the guards do to prompts nobody tuned against.
     * Both splits in it are fetched from datasets assembled elsewhere, years earlier, by people who
     * had never heard of this project.
     */
    BLIND,
}

/**
 * Whether the reports may print individual pairs from a split whose failures are not already known.
 *
 * `CorpusTest` used to print the validation residual in full on every run, which is the exact act
 * `docs/CORPUS.md` prohibits, and the tooling won by default every time anybody ran the suite. It is
 * how the validation split was spent.
 *
 * So the residual pairs are behind this, and the property is named for what passing it costs rather
 * than for what it shows. `-PspendABlindSplit=true` is a sentence somebody has to mean.
 */
object BlindPairDisclosure {

    private const val PROPERTY = "kmemo.blindSplits.spend"

    /** True only when the build was told, deliberately, to spend a split. */
    val allowed: Boolean get() = System.getProperty(PROPERTY).toBoolean()

    /**
     * Whether the individual pairs of [corpus] may be printed.
     *
     * The tuned split is always printable: it is in sample by construction and reading its failures
     * is how a guard gets written. Everything else needs [allowed].
     */
    fun mayPrintPairsOf(corpus: Corpus): Boolean =
        corpus.standing == CorpusStanding.IN_SAMPLE || allowed

    /** The sentence a report prints where [withheld] pairs from [corpus] would have gone. */
    fun withheldNotice(corpus: Corpus, withheld: Int): String =
        "  ($withheld pairs withheld: ${corpus.name} is ${corpus.standing.name.lowercase().replace('_', '-')}. " +
            "Rerun with -PspendABlindSplit=true to print them, and read docs/CORPUS.md first.)"
}

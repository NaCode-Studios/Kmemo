package dev.kmemo.guard

/**
 * Ready-made guard sets for [dev.kmemo.SemanticCache].
 *
 * Pick by how much a wrong answer costs you:
 *
 * | Preset      | Use when                                                                          |
 * |-------------|-----------------------------------------------------------------------------------|
 * | [standard]  | Default. Every guard that pays for itself, tuned to reject no genuine paraphrase.  |
 * | [strict]    | A wrong answer is expensive. Trades hit rate for margin.                           |
 * | [none]      | Similarity alone. Only with a [dev.kmemo.Verifier], or a private benchmark. |
 *
 * [standard] takes an optional [GuardVocabulary] or language code, so the same guards run against another
 * language's markers — see [Vocabularies] for the packs that ship.
 */
public object MatchGuards {

    /**
     * The default set: one guard per way a near-miss slips past a similarity threshold, wired to the
     * English [GuardVocabulary].
     *
     * Ordered cheapest and most decisive first, since [dev.kmemo.SemanticCache] stops at
     * the first rejection.
     */
    public fun standard(): List<MatchGuard> = standard(GuardVocabulary.ENGLISH)

    /**
     * [standard], but reading every marker from [vocabulary] — the way to run the guards against a
     * non-English language, or a customized marker set. [NumericGuard] is language-agnostic and takes
     * no markers; every other guard is fed from the pack.
     */
    public fun standard(vocabulary: GuardVocabulary): List<MatchGuard> = listOf(
        NumericGuard(),
        UnitGuard(vocabulary.units),
        TemporalGuard(vocabulary.temporalMarkers, vocabulary.stopwords),
        NegationGuard(vocabulary.negationMarkers, vocabulary.stopwords),
        AntonymGuard(vocabulary.antonyms),
        EntityGuard(vocabulary.stopwords, vocabulary.sentenceOpeners, vocabulary.nonEntityCapitals),
        SubstitutionGuard(stopwords = vocabulary.stopwords, units = vocabulary.units),
        ScopeGuard(vocabulary.scopeMarkers),
        DirectionGuard(vocabulary.directionalCues, vocabulary.stopwords),
        SubSpanGuard(vocabulary.stopwords, vocabulary.qualifierOpeners),
        LexicalDivergenceGuard(stopwords = vocabulary.stopwords),
    )

    /**
     * [standard] for an ISO 639 language code, using the shipped [Vocabularies] pack.
     *
     * ```kotlin
     * val cache = SemanticCache(embedder, guards = MatchGuards.standard("it"))
     * ```
     *
     * On the JVM there is an overload taking a `java.util.Locale`.
     *
     * @throws IllegalArgumentException if no pack ships for the language — see
     *   [Vocabularies.forLanguage] for the supported set. Pass a [GuardVocabulary] directly to use your own.
     */
    public fun standard(language: String): List<MatchGuard> = standard(Vocabularies.forLanguage(language))

    /**
     * [standard] plus [AnswerAnchorGuard], the one guard that reads the candidate's stored answer.
     *
     * **Opt in deliberately.** Every guard in [standard] is measured on corpora written before it
     * existed; this one is measured on answers written for the purpose, because no corpus of real
     * paired answers exists to measure it against. The number is a regression check rather than a
     * blind measurement, and mixing the two under one default would quietly downgrade the evidence
     * behind everything else. `docs/CORPUS.md` states the difference and `ResponseGuardTest` holds
     * the numbers.
     *
     * It costs nothing extra to run: the response is already on the candidate entry, and the guard
     * only reads it once the prompts have proved to differ by a substitution.
     */
    public fun responseAware(): List<MatchGuard> = responseAware(GuardVocabulary.ENGLISH)

    /** [responseAware], reading every marker from [vocabulary]. */
    public fun responseAware(vocabulary: GuardVocabulary): List<MatchGuard> =
        standard(vocabulary) + AnswerAnchorGuard(vocabulary.stopwords)

    /**
     * [standard] with the tolerant edges pulled in: prompts must share meaningfully more wording,
     * and a large length gap is enough to refuse on its own.
     *
     * Expect a lower hit rate. That is the trade you are making — every extra rejection is one API
     * call you pay for instead of one wrong answer you ship.
     */
    public fun strict(): List<MatchGuard> = listOf(
        NumericGuard(),
        UnitGuard(),
        TemporalGuard(),
        NegationGuard(),
        AntonymGuard(),
        EntityGuard(),
        SubstitutionGuard(),
        ScopeGuard(),
        DirectionGuard(),
        SubSpanGuard(),
        LexicalDivergenceGuard(minOverlap = 0.35, minTokens = 4),
        LengthRatioGuard(maxRatio = 4.0),
    )

    /**
     * No guards: the similarity threshold decides alone.
     *
     * This is how most hand-rolled semantic caches work, and it is why they return the exchange rate
     * for 250 USD to someone who asked about 100. Use it to reproduce that baseline, or when a
     * [dev.kmemo.Verifier] is checking every candidate instead.
     */
    public fun none(): List<MatchGuard> = emptyList()
}

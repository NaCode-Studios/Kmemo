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
 */
public object MatchGuards {

    /**
     * The default set: one guard per way a near-miss slips past a similarity threshold.
     *
     * Ordered cheapest and most decisive first, since [dev.kmemo.SemanticCache] stops at
     * the first rejection.
     */
    public fun standard(): List<MatchGuard> = listOf(
        NumericGuard(),
        UnitGuard(),
        TemporalGuard(),
        NegationGuard(),
        AntonymGuard(),
        EntityGuard(),
        SubstitutionGuard(),
        ScopeGuard(),
        DirectionGuard(),
        LexicalDivergenceGuard(),
    )

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

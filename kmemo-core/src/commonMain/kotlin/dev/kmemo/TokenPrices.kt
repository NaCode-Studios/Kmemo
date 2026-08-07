package dev.kmemo

/**
 * What one model call in a scope costs, declared by the caller.
 *
 * The README does arithmetic that turns a hit rate into money, by hand, in prose, with numbers the
 * reader has to supply from their own invoice. That is the calculation anybody deciding whether to
 * adopt a cache actually cares about, and until this the library could not do it: [CacheStats] counted
 * lookups, hits, misses and rejections, and none of that is money. A hit on a two hundred token answer
 * from a cheap model and a hit on a four thousand token answer from an expensive one were one
 * increment each.
 *
 * **The price comes from you, not from here.** kmemo ships no table of provider prices, for the same
 * reason it ships no embedding model: prices change weekly, a vendored price list is wrong the month
 * after it ships, and a library that quietly reports the wrong saving is worse than one that reports
 * none. You state the number, the cache multiplies, and [Savings] carries the number back out with the
 * figure so nobody reads the total without its assumption.
 *
 * **The token counts come from your metadata.** [CacheEntry.metadata] is free-form caller data returned
 * untouched on a hit, and token counts are what people put in it. The cache reads them by key from the
 * entry that was served, so the saving is the cost of the call that was actually avoided rather than an
 * average applied to a hit count.
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     prices["gpt-4o"] = TokenPrices(
 *         currency = "USD",
 *         perInputToken = 2.50 / 1_000_000,
 *         perOutputToken = 10.00 / 1_000_000,
 *     )
 * }
 * cache.getOrPut(prompt, scope = "gpt-4o", metadata = mapOf(
 *     "inputTokens" to usage.input.toString(),
 *     "outputTokens" to usage.output.toString(),
 * )) { llm.complete(it) }
 *
 * cache.stats().savings["gpt-4o"]   // Savings(amount=..., currency=USD, hits=..., ...)
 * ```
 *
 * @param currency the unit the three prices are in. Free-form and never interpreted: it is carried
 *   through to [Savings] so a total is never read without one. Two scopes may use different ones, and
 *   the cache will not add them together.
 * @param perInputToken price of one prompt token.
 * @param perOutputToken price of one completion token.
 * @param perCall a flat charge per request, for providers that levy one. Counted on every hit whether
 *   or not the entry carries token counts, since it did not depend on them.
 * @param inputTokensKey metadata key holding the prompt token count of the call that was cached.
 * @param outputTokensKey metadata key holding its completion token count.
 */
public data class TokenPrices(
    public val currency: String,
    public val perInputToken: Double = 0.0,
    public val perOutputToken: Double = 0.0,
    public val perCall: Double = 0.0,
    public val inputTokensKey: String = DEFAULT_INPUT_TOKENS_KEY,
    public val outputTokensKey: String = DEFAULT_OUTPUT_TOKENS_KEY,
) {
    init {
        require(currency.isNotBlank()) { "currency must not be blank" }
        require(perInputToken >= 0.0) { "perInputToken must not be negative, was $perInputToken" }
        require(perOutputToken >= 0.0) { "perOutputToken must not be negative, was $perOutputToken" }
        require(perCall >= 0.0) { "perCall must not be negative, was $perCall" }
        require(inputTokensKey.isNotBlank()) { "inputTokensKey must not be blank" }
        require(outputTokensKey.isNotBlank()) { "outputTokensKey must not be blank" }
    }

    public companion object {
        /** Metadata key read for the prompt token count unless another is named. */
        public const val DEFAULT_INPUT_TOKENS_KEY: String = "inputTokens"

        /** Metadata key read for the completion token count unless another is named. */
        public const val DEFAULT_OUTPUT_TOKENS_KEY: String = "outputTokens"
    }
}

/**
 * What one scope's hits did not cost, with everything the figure rests on.
 *
 * The inputs travel with the number on purpose. A saving with no price per token attached is a
 * marketing claim rather than a measurement, which is the failure this whole library is against, so
 * there is no way to read [amount] without also having [prices] in hand.
 *
 * @param prices exactly what the caller declared for this scope.
 * @param hits hits served in this scope since the cache was created. Every one of them is a model call
 *   that was not made; a hit is the only event that adds to a saving, so a cache that is filling up
 *   reports nothing until it starts answering.
 * @param inputTokens prompt tokens read from the served entries' metadata, summed.
 * @param outputTokens completion tokens read from the served entries' metadata, summed.
 * @param hitsMissingTokenCounts hits whose entry carried neither token count. A number close to [hits]
 *   means the metadata keys are not the ones being written, and [amount] is then only the flat
 *   per-call charge rather than the saving. That is the one way this figure can be quietly wrong, so
 *   it is reported next to it rather than left to be discovered.
 */
public data class Savings(
    public val prices: TokenPrices,
    public val hits: Long,
    public val inputTokens: Long,
    public val outputTokens: Long,
    public val hitsMissingTokenCounts: Long = 0,
) {
    /** The unit [amount] is in, as the caller declared it. */
    public val currency: String get() = prices.currency

    /**
     * What those hits would have cost, in [currency].
     *
     * Computed rather than accumulated, from token counts that are integers, so reading it twice gives
     * the same answer and no rounding accumulates across a million hits.
     */
    public val amount: Double
        get() = inputTokens * prices.perInputToken +
            outputTokens * prices.perOutputToken +
            hits * prices.perCall
}

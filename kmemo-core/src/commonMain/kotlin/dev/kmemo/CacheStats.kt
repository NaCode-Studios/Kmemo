package dev.kmemo

/**
 * Counters for one [SemanticCache] instance, from creation to now.
 *
 * The breakdown of misses is the part worth watching. A high [belowThreshold] means your traffic
 * repeats less than you assumed, or your threshold is too tight for your embedding model. A high
 * [guardRejections] means the opposite: prompts are landing close together and the guards are
 * catching near-misses your threshold alone would have served.
 */
public data class CacheStats(
    /** Total [SemanticCache.lookup] and [SemanticCache.getOrPut] calls. */
    public val lookups: Long,
    /** Lookups that served a cached response. */
    public val hits: Long,
    /** Lookups that did not. Always `lookups - hits`. */
    public val misses: Long,
    /** Misses where the best candidate scored below the threshold. */
    public val belowThreshold: Long,
    /** Misses where a guard vetoed a candidate that had cleared the threshold. */
    public val guardRejections: Long,
    /** Misses where the verifier vetoed a candidate that had cleared threshold and guards. */
    public val verifierRejections: Long,
    /** Entries written through [SemanticCache.put] or [SemanticCache.getOrPut]. */
    public val writes: Long,
    /**
     * [guardRejections] broken down by the [dev.kmemo.guard.MatchGuard.name] that fired.
     *
     * The values sum to [guardRejections] — each guard-rejected miss is attributed to the single
     * guard that vetoed it first, matching the order guards run in. Every configured guard is a key,
     * so one that has never fired shows up as `0` rather than being absent: a guard stuck at `0` is
     * either redundant for your traffic or ordered behind one that always rejects first, and only the
     * breakdown tells you which. Empty when the cache runs with [dev.kmemo.guard.MatchGuards.none].
     */
    public val guardRejectionsByGuard: Map<String, Long> = emptyMap(),
    /**
     * Calls that ran uncached because the [Embedder] threw and
     * [EmbedFailurePolicy.FALL_BACK_TO_COMPUTE] stepped aside.
     *
     * Counted per call, not per prompt: one degraded [SemanticCache.getOrPutAll] of fifty prompts is
     * `1`. These are **not** in [lookups] — no lookup happened — so a rising number here alongside a
     * flat [lookups] is the cache quietly turning into a pass-through, which is otherwise the one
     * failure mode with no signal attached to it. Stays `0` under
     * [EmbedFailurePolicy.PROPAGATE], where the caller sees the exception instead.
     */
    public val degradedLookups: Long = 0,
    /**
     * Writes a [CachePolicy] refused.
     *
     * Not a failure and not a miss: the call returned its response, the cache just did not keep it.
     * A number that is `0` when you expect it to move usually means the policy is not wired up at all.
     */
    public val writesVetoed: Long = 0,
    /**
     * Hits served by the exact-match layer, without embedding the prompt or searching the store.
     *
     * A subset of [hits], so `exactHits / hits` is the share of your traffic that was a byte-for-byte
     * repeat. A number close to zero means the layer is costing memory for nothing and its size can go
     * back to `0`; a large one is the embedding calls it saved. Stays `0` unless
     * `exactCacheSize` is positive.
     */
    public val exactHits: Long = 0,
    /**
     * Lookups that met a candidate above the threshold written by a different [Embedder.identity].
     *
     * Counted per lookup rather than per candidate, and counted whatever the lookup went on to decide
     * A lookup that refused a stale entry and then served the next one is still a lookup that met a
     * stale entry. So this is not a slice of [misses] the way the three above it are; it is a census of
     * an incident.
     *
     * The one counter here that is not a tuning signal. Any number above zero says the store holds
     * vectors from a model that is no longer running, and it stays above zero until those entries are
     * re-embedded or expire. Alert on it rather than graphing it: unlike [belowThreshold] and
     * [guardRejections], there is no healthy level of this. Stays `0` for a cache whose embedder
     * declares no identity.
     */
    public val embedderMismatches: Long = 0,
) {
    /** Fraction of lookups served from cache, in `[0.0, 1.0]`. `0.0` when nothing has been looked up. */
    public val hitRate: Double
        get() = if (lookups == 0L) 0.0 else hits.toDouble() / lookups.toDouble()

    /**
     * Fraction of lookups that reached a guard and were stopped there, in `[0.0, 1.0]`.
     *
     * Each one is a wrong answer that was not served — and, unlike a threshold miss, one your
     * embedding model was ready to hand over.
     */
    public val guardRejectionRate: Double
        get() = if (lookups == 0L) 0.0 else guardRejections.toDouble() / lookups.toDouble()
}

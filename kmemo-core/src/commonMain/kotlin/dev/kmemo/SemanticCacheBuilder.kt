package dev.kmemo

import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.MatchGuards
import dev.kmemo.store.InMemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * Builds a [SemanticCache] with a DSL instead of a long positional constructor call.
 *
 * The constructor has grown a parameter per capability — resilience, observability, write-behind — and
 * a call that sets three of them ends up threading past the ten it does not. This reads them by name,
 * one per line, and leaves the rest at their defaults:
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     threshold = 0.9
 *     store = InMemoryStore(maxEntries = 50_000)
 *     negativeCacheSize = 10_000
 *     listeners = listOf(metrics)
 * }
 * ```
 *
 * Every property mirrors the [SemanticCache] constructor parameter of the same name and carries the
 * same default and the same meaning — see [SemanticCache] for what each one does. [build] validates
 * exactly as the constructor does.
 */
public class SemanticCacheBuilder(private val embedder: Embedder) {

    /** @see SemanticCache */
    public var store: CacheStore = InMemoryStore()

    /** @see SemanticCache */
    public var threshold: Double = SemanticCache.DEFAULT_THRESHOLD

    /** @see SemanticCache */
    public var guards: List<MatchGuard> = MatchGuards.standard()

    /** @see SemanticCache */
    public var verifier: Verifier? = null

    /** @see SemanticCache */
    public var verifierTimeout: Duration? = null

    /** @see SemanticCache */
    public var candidates: Int = SemanticCache.DEFAULT_CANDIDATES

    /** @see SemanticCache */
    public var coalesceConcurrentMisses: Boolean = true

    /** @see SemanticCache */
    public var embedFailurePolicy: EmbedFailurePolicy = EmbedFailurePolicy.PROPAGATE

    /** @see SemanticCache */
    public var negativeCacheSize: Int = 0

    /** @see SemanticCache */
    public var negativeCacheTtl: Duration? = null

    /** @see SemanticCache */
    public var listeners: List<CacheListener> = emptyList()

    /** @see SemanticCache */
    public var writeBehindScope: CoroutineScope? = null

    /** @see SemanticCache */
    public var writeBehindCapacity: Int = SemanticCache.DEFAULT_WRITE_BEHIND_CAPACITY

    /** @see SemanticCache */
    public var clock: Clock = Clock.System

    /** @see SemanticCache */
    public var cachePolicy: CachePolicy? = null

    /** @see SemanticCache */
    public var exactCacheSize: Int = 0

    /** @see SemanticCache */
    public var exactCacheTtl: Duration? = null

    /** @see SemanticCache */
    public var thresholds: Map<String, Double> = emptyMap()

    /** @see SemanticCache */
    public var shadowThresholds: List<Double> = emptyList()

    /** @see SemanticCache */
    public var reranker: CandidateReranker? = null

    /** @see SemanticCache */
    public var deduplicateWrites: Double? = null

    /** @see SemanticCache */
    public var adaptiveThresholds: AdaptiveThresholds? = null

    /**
     * What a model call costs, per scope. A mutable map so a builder block can fill it in place:
     * `prices["gpt-4o"] = TokenPrices(...)`.
     *
     * @see SemanticCache
     */
    public var prices: MutableMap<String, TokenPrices> = mutableMapOf()

    /** @see SemanticCache */
    public var admissionPolicy: AdmissionPolicy? = null

    /** @see SemanticCache */
    public var requireTenant: Boolean = false

    /** Constructs the [SemanticCache] from the current settings. */
    public fun build(): SemanticCache = SemanticCache(
        embedder = embedder,
        store = store,
        threshold = threshold,
        guards = guards,
        verifier = verifier,
        verifierTimeout = verifierTimeout,
        candidates = candidates,
        coalesceConcurrentMisses = coalesceConcurrentMisses,
        embedFailurePolicy = embedFailurePolicy,
        negativeCacheSize = negativeCacheSize,
        negativeCacheTtl = negativeCacheTtl,
        listeners = listeners,
        writeBehindScope = writeBehindScope,
        writeBehindCapacity = writeBehindCapacity,
        clock = clock,
        cachePolicy = cachePolicy,
        exactCacheSize = exactCacheSize,
        exactCacheTtl = exactCacheTtl,
        thresholds = thresholds,
        shadowThresholds = shadowThresholds,
        reranker = reranker,
        deduplicateWrites = deduplicateWrites,
        adaptiveThresholds = adaptiveThresholds,
        prices = prices.toMap(),
        admissionPolicy = admissionPolicy,
        requireTenant = requireTenant,
    )
}

/**
 * Builds a [SemanticCache] with the [SemanticCacheBuilder] DSL.
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     threshold = 0.9
 *     negativeCacheSize = 10_000
 * }
 * ```
 */
public fun semanticCache(
    embedder: Embedder,
    configure: SemanticCacheBuilder.() -> Unit = {},
): SemanticCache = SemanticCacheBuilder(embedder).apply(configure).build()

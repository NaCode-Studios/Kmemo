package dev.kmemo

/**
 * What [SemanticCache.getOrPut] does when the [Embedder] throws.
 *
 * The embedder is a network call the cache makes on **every** lookup, so it is the cache's most
 * frequent point of failure. The two policies encode the two honest answers to "the embedder is
 * down": surface it, or route around it.
 *
 * This only governs [SemanticCache.getOrPut], the one entry point that has something to fall back
 * *to* — a [compute] block that can produce the answer without the cache. [SemanticCache.lookup],
 * [SemanticCache.get] and [SemanticCache.put] have no such fallback and always propagate an embed
 * failure regardless of this setting.
 *
 * A [kotlin.coroutines.cancellation.CancellationException] is never a "failure" in this sense: it is
 * always re-thrown so coroutine cancellation keeps working, whichever policy is set.
 */
public enum class EmbedFailurePolicy {

    /**
     * Re-throw the embedder's exception to the caller (the default).
     *
     * The safe choice when a failed lookup should be visible: you would rather a burst of errors
     * surfaced in your own metrics and retry logic than have the cache quietly turn into a plain
     * pass-through the moment the embedding provider has a bad minute.
     */
    PROPAGATE,

    /**
     * Swallow the embedder's exception and call [compute] as if the lookup had missed.
     *
     * "Never fail a `getOrPut` that could have just called the model." With this policy a
     * [SemanticCache.getOrPut] degrades to an uncached model call while the embedder is unavailable,
     * so the feature the cache fronts keeps working. The trade-off is explicit: the computed answer
     * **cannot be written back** — there is no embedding to key it by — so nothing is cached until the
     * embedder recovers, and the failure is invisible unless you are watching a [CacheListener] or the
     * embedder's own instrumentation.
     */
    FALL_BACK_TO_COMPUTE,
}

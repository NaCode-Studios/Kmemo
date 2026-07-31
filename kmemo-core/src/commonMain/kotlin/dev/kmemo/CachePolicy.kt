package dev.kmemo

/**
 * Decides whether a prompt and its computed response may be **persisted** at all.
 *
 * `kmemo-slf4j` can redact a prompt on its way to a log line, but the cache itself stores prompts and
 * responses verbatim. For a regulated adopter the first question is not how accurate the guards are, it
 * is whether they can prove a given class of data never got written. Without this seam the answer is to
 * wrap [SemanticCache.getOrPut] by hand and re-implement the compute path around it.
 *
 * A policy is consulted once per write, never on the read path, so it may do real work — a regex sweep,
 * a classifier, a call to a detection service — without touching lookup latency. Returning
 * [PolicyVerdict.Veto] drops the write; the call that triggered it still returns its computed response
 * normally, because a vetoed write is a policy decision, not a failure. The caller is not told to retry
 * and nothing is thrown.
 *
 * ```kotlin
 * val cache = semanticCache(embedder) {
 *     cachePolicy = CachePolicy { prompt, response, _ ->
 *         if (IBAN.containsMatchIn(prompt) || IBAN.containsMatchIn(response)) {
 *             PolicyVerdict.Veto("iban")
 *         } else {
 *             PolicyVerdict.Store
 *         }
 *     }
 * }
 * ```
 *
 * kmemo ships the seam and no detector, for the same reason [Embedder] and [Verifier] are seams:
 * `kmemo-core` depends on `kotlinx-coroutines-core` and nothing else, and what counts as sensitive is a
 * property of your jurisdiction and your data, not of a cache library.
 *
 * ### What it does and does not cover
 *
 * Every write path is covered, including [SemanticCache.warm] and the write-behind queue, because a
 * guarantee with one path around it is not a guarantee. Under write-behind the verdict is reached on the
 * queue's worker rather than on the caller's coroutine, so the write is still never made, but the
 * [CacheEvent.WriteVetoed] arrives after the call that produced it has returned.
 *
 * Isolation between tenants is a **different** problem and is already solved: pass a `scope` per tenant
 * and entries cannot cross, which the store TCK has enforced on every store since M4. Use a policy for
 * what must never be written at all, not for who may read it.
 */
public fun interface CachePolicy {

    /**
     * Judges writing [response] under [prompt] in [scope].
     *
     * @return [PolicyVerdict.Veto] only with a concrete reason; [PolicyVerdict.Store] otherwise.
     */
    public suspend fun evaluate(prompt: String, response: String, scope: String): PolicyVerdict
}

/** The outcome of a [CachePolicy] decision. */
public sealed interface PolicyVerdict {

    /** The entry may be written. */
    public data object Store : PolicyVerdict

    /**
     * The entry must not be written.
     *
     * @param reason short stable identifier for *which* rule fired, surfaced on
     *   [CacheEvent.WriteVetoed.reason] and usable as a metrics tag. Keep it low-cardinality
     *   (`"iban"`, `"pii.email"`), not a message containing the matched text — a reason that quotes the
     *   data it vetoed puts that data straight into your logs and metrics.
     */
    public data class Veto(public val reason: String) : PolicyVerdict
}

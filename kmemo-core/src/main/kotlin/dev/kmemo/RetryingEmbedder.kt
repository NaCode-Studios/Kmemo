package dev.kmemo

import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * An [Embedder] that retries a failing delegate with exponential backoff and jitter.
 *
 * The embedder is a network call on every lookup, so a transient blip — a dropped connection, a
 * `429`, a provider hiccup — should not become a cache miss (or, under
 * [EmbedFailurePolicy.PROPAGATE], a caller-visible error) when a second attempt a moment later would
 * have succeeded. This wraps any [Embedder] and re-issues [embed] / [embedAll] up to [maxAttempts]
 * times, backing off between tries.
 *
 * ```kotlin
 * val embedder = Embedder { text -> openAi.embed(text) }.retrying(maxAttempts = 4)
 * val cache = SemanticCache(embedder)
 * ```
 *
 * Opt-in on purpose: the default cache issues exactly one embed call per lookup, and retries — which
 * add latency and can amplify load on a provider that is already struggling — are a decision the
 * integrator makes, not a default paid by everyone. Retrying is transparent to [SemanticCache]; it is
 * just an [Embedder], so it composes with everything else (including a batch endpoint override).
 *
 * A [CancellationException] is never retried and never delayed — it propagates immediately, so
 * coroutine cancellation is unaffected. Which *other* throwables are worth retrying is the caller's
 * call via [retryOn]; the default retries anything that is not a cancellation, on the assumption that
 * an embedder either has an idempotent read or has been made safe to call twice.
 *
 * @param delegate the embedder whose calls are retried.
 * @param maxAttempts total attempts including the first, so `1` disables retrying. Must be positive.
 * @param initialDelay backoff before the second attempt; each further wait multiplies by [factor].
 * @param maxDelay ceiling on a single backoff wait, so exponential growth cannot run away.
 * @param factor multiplier applied to the delay after each failed attempt (`2.0` doubles it).
 * @param jitter fraction of each computed delay that is randomized away, in `[0.0, 1.0]`. `0.0` waits
 *   the exact backoff; the default `0.5` waits a random span in `[0.5, 1.0]` of it, so a fleet whose
 *   calls all failed together does not retry in lockstep and hammer the provider on the same tick.
 * @param retryOn decides whether a given failure is worth another attempt. Returning `false` re-throws
 *   immediately. [CancellationException] is excluded before this is ever consulted.
 */
public class RetryingEmbedder(
    private val delegate: Embedder,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val initialDelay: Duration = DEFAULT_INITIAL_DELAY,
    private val maxDelay: Duration = DEFAULT_MAX_DELAY,
    private val factor: Double = DEFAULT_FACTOR,
    private val jitter: Double = DEFAULT_JITTER,
    private val retryOn: (Throwable) -> Boolean = { true },
) : Embedder {

    init {
        require(maxAttempts > 0) { "maxAttempts must be positive, was $maxAttempts" }
        require(initialDelay.isPositive()) { "initialDelay must be positive, was $initialDelay" }
        require(maxDelay >= initialDelay) { "maxDelay ($maxDelay) must be >= initialDelay ($initialDelay)" }
        require(factor >= 1.0) { "factor must be >= 1.0, was $factor" }
        require(jitter in 0.0..1.0) { "jitter must be within [0.0, 1.0], was $jitter" }
    }

    override suspend fun embed(text: String): FloatArray = withRetry { delegate.embed(text) }

    override suspend fun embedAll(texts: List<String>): List<FloatArray> =
        withRetry { delegate.embedAll(texts) }

    private suspend inline fun <T> withRetry(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: CancellationException) {
                // Cancellation is not a failure to retry; propagating it is how the caller stops us.
                throw e
            } catch (e: Throwable) {
                attempt++
                if (attempt >= maxAttempts || !retryOn(e)) throw e
                delay(backoffFor(attempt))
            }
        }
    }

    /**
     * Backoff before the [attempt]-th retry (1-based): `initialDelay * factor^(attempt-1)`, capped at
     * [maxDelay], then reduced by up to [jitter] of itself. Computed in [Double] milliseconds so the
     * exponential does not overflow [Duration] on a high attempt count.
     */
    private fun backoffFor(attempt: Int): Duration {
        val exponential = initialDelay.inWholeMilliseconds.toDouble() * Math.pow(factor, (attempt - 1).toDouble())
        val capped = min(exponential, maxDelay.inWholeMilliseconds.toDouble())
        val randomized = capped * (1.0 - jitter * Random.nextDouble())
        return randomized.milliseconds
    }

    public companion object {
        /** Total attempts including the first. */
        public const val DEFAULT_MAX_ATTEMPTS: Int = 3

        /** Multiplier applied to the backoff after each failed attempt. */
        public const val DEFAULT_FACTOR: Double = 2.0

        /** Fraction of each backoff that is randomized away, to de-correlate a fleet's retries. */
        public const val DEFAULT_JITTER: Double = 0.5

        /** Backoff before the second attempt. */
        public val DEFAULT_INITIAL_DELAY: Duration = 100.milliseconds

        /** Ceiling on a single backoff wait. */
        public val DEFAULT_MAX_DELAY: Duration = 5.seconds
    }
}

/**
 * Wraps this embedder in a [RetryingEmbedder] that retries transient failures with jittered backoff.
 *
 * ```kotlin
 * val embedder = Embedder { text -> openAi.embed(text) }.retrying(maxAttempts = 4)
 * ```
 *
 * See [RetryingEmbedder] for the semantics; [CancellationException] is never retried.
 */
public fun Embedder.retrying(
    maxAttempts: Int = RetryingEmbedder.DEFAULT_MAX_ATTEMPTS,
    initialDelay: Duration = RetryingEmbedder.DEFAULT_INITIAL_DELAY,
    maxDelay: Duration = RetryingEmbedder.DEFAULT_MAX_DELAY,
    factor: Double = RetryingEmbedder.DEFAULT_FACTOR,
    jitter: Double = RetryingEmbedder.DEFAULT_JITTER,
    retryOn: (Throwable) -> Boolean = { true },
): RetryingEmbedder = RetryingEmbedder(this, maxAttempts, initialDelay, maxDelay, factor, jitter, retryOn)

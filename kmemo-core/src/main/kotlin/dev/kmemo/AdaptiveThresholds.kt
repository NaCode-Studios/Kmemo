package dev.kmemo

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Moves each scope's similarity threshold towards the value its own traffic justifies.
 *
 * ## When this is safe, which is the only part that matters
 *
 * A threshold decides what gets served. Moving it automatically on unlabelled traffic is guessing with
 * correctness, and this library does not do that — a cache has no way to know that a hit it served was
 * right. So adaptation is refused outright unless a [Verifier] is configured, and
 * [SemanticCache] throws at construction rather than quietly running without one.
 *
 * **With a verifier in the loop the threshold stops being a correctness knob and becomes a cost knob.**
 * Everything above it is checked by something that can tell right from wrong; the threshold only
 * decides how many candidates get that far, and therefore how many model calls the verifier makes. That
 * is a quantity a cache *can* observe about itself, and it is what this adapts on.
 *
 * ## What it reads
 *
 * Per scope, the outcome of every candidate that reached the verifier: served, or refused. Two rules,
 * and both point the same way:
 *
 * - The verifier refusing more than [targetRejectionRate] of what it sees means the threshold is
 *   letting through candidates that were never going to be served. Raise it, and pay for fewer calls.
 * - The verifier refusing far less than that means the threshold is stricter than the verifier needs it
 *   to be, and entries that would have been served are not being looked at. Lower it.
 *
 * The second rule is the one that would be reckless without a verifier, and is why the requirement is
 * not negotiable.
 *
 * ## What it deliberately does not do
 *
 * It does not move on a handful of lookups: nothing happens in a scope until [minimumSamples]
 * candidates have been verified there, and the window resets after each move so the next decision is
 * made on the traffic that followed it. It never leaves `[floor, ceiling]`, which are yours to set.
 * And it adapts one [step] at a time rather than jumping to a computed optimum, because the measurement
 * is of the traffic that arrived under the *current* threshold and says nothing about traffic three
 * steps away.
 *
 * ```kotlin
 * val adaptive = AdaptiveThresholds(floor = 0.86, ceiling = 0.97)
 * val cache = SemanticCache(
 *     embedder = embedder,
 *     verifier = verifier,
 *     adaptiveThresholds = adaptive,
 *     listeners = listOf(adaptive),
 * )
 * ```
 *
 * The same object is the listener and the source: it has to see the outcomes to have an opinion about
 * them. Passing it only as a listener gives you [recommendationFor] to read without the cache acting
 * on it, which is the way to watch what it *would* do before letting it.
 *
 * @param floor the lowest threshold it may recommend. Set it where you would be uncomfortable going
 *   lower by hand.
 * @param ceiling the highest. A scope pinned at the ceiling for a long time is a scope whose traffic is
 *   not cacheable at any threshold, which is worth knowing.
 * @param targetRejectionRate the share of verified candidates the verifier may refuse before the
 *   threshold is judged too loose.
 * @param minimumSamples verified candidates required in a scope before it moves at all.
 * @param step how far it moves each time.
 */
public class AdaptiveThresholds(
    private val floor: Double,
    private val ceiling: Double,
    private val targetRejectionRate: Double = DEFAULT_TARGET_REJECTION_RATE,
    private val minimumSamples: Int = DEFAULT_MINIMUM_SAMPLES,
    private val step: Double = DEFAULT_STEP,
) : CacheListener {

    init {
        require(floor in 0.0..1.0) { "floor must be in [0.0, 1.0], was $floor" }
        require(ceiling in 0.0..1.0) { "ceiling must be in [0.0, 1.0], was $ceiling" }
        require(floor <= ceiling) { "floor $floor is above ceiling $ceiling" }
        require(targetRejectionRate in 0.0..1.0) {
            "targetRejectionRate must be in [0.0, 1.0], was $targetRejectionRate"
        }
        require(minimumSamples > 0) { "minimumSamples must be positive, was $minimumSamples" }
        require(step > 0.0) { "step must be positive, was $step" }
    }

    private val states = ConcurrentHashMap<String, State>()

    /**
     * The threshold this scope's traffic justifies, or `null` while it has not seen enough of it.
     *
     * `null` is the honest answer to "what should the threshold be" before there is evidence, and
     * [SemanticCache] reads it as "leave the configured value alone".
     */
    public fun recommendationFor(scope: String): Double? = states[scope]?.recommended

    /** Every scope with a recommendation, for a dashboard or a log line. */
    public fun recommendations(): Map<String, Double> =
        states.mapNotNull { (scope, state) -> state.recommended?.let { scope to it } }.toMap()

    override fun onEvent(event: CacheEvent) {
        when (event) {
            // A hit is a candidate the verifier agreed to serve, when there is a verifier.
            is CacheEvent.Hit -> record(event.scope, refused = false)
            is CacheEvent.Miss ->
                if (event.reason == MissReason.REJECTED_BY_VERIFIER) record(event.scope, refused = true)
            else -> Unit
        }
    }

    private fun record(scope: String, refused: Boolean) {
        val state = states.computeIfAbsent(scope) { State() }
        if (refused) state.refused.incrementAndGet()
        val seen = state.seen.incrementAndGet()
        if (seen < minimumSamples) return

        // One mover per window. Losing a race here costs a sample, not correctness, and a lock on the
        // event path would be worse than the thing it protects.
        synchronized(state) {
            if (state.seen.get() < minimumSamples) return
            val rate = state.refused.get().toDouble() / state.seen.get()
            val current = state.recommended ?: startingPoint()
            state.recommended = when {
                rate > targetRejectionRate -> (current + step).coerceAtMost(ceiling)
                // Half the target, not the target itself: moving down the moment the rate dips below
                // its goal would oscillate around it forever.
                rate < targetRejectionRate / 2 -> (current - step).coerceAtLeast(floor)
                else -> current
            }
            state.seen.set(0)
            state.refused.set(0)
        }
    }

    /** Mid-range, so the first move has room in both directions whatever the traffic turns out to be. */
    private fun startingPoint(): Double = (floor + ceiling) / 2

    private class State {
        val seen = AtomicLong()
        val refused = AtomicLong()

        @Volatile
        var recommended: Double? = null
    }

    private companion object {
        private const val DEFAULT_TARGET_REJECTION_RATE = 0.1
        private const val DEFAULT_MINIMUM_SAMPLES = 200
        private const val DEFAULT_STEP = 0.005
    }
}

package dev.kmemo.internal

import dev.kmemo.CacheEntry
import dev.kmemo.CandidateReranker
import dev.kmemo.ScoredEntry
import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.ResponseAwareGuard

/** A guard's veto, with the guard that cast it. */
internal class GuardRejection(val guardName: String, val reason: String)

/**
 * The guard chain: which guard, if any, refuses a candidate, and what every guard thinks of it.
 *
 * Extracted from `SemanticCache` because it is the one piece of the lookup with no state, no clock and
 * no store behind it — given a query and an entry it always answers the same thing, which makes it the
 * part worth being able to reason about on its own.
 */
internal class GuardChain(private val guards: List<MatchGuard>) {

    /** The first guard to veto, or `null` if every one of them abstained. */
    fun firstRejection(prompt: String, candidate: CacheEntry): GuardRejection? {
        for (guard in guards) {
            val verdict = verdictOf(guard, prompt, candidate)
            if (verdict is GuardVerdict.Reject) return GuardRejection(guard.name, verdict.reason)
        }
        return null
    }

    /** Every guard's verdict, without stopping at the first rejection. For `explain`. */
    fun verdicts(prompt: String, candidate: CacheEntry): Map<String, GuardVerdict> {
        val result = LinkedHashMap<String, GuardVerdict>(guards.size)
        for (guard in guards) result[guard.name] = verdictOf(guard, prompt, candidate)
        return result
    }

    /**
     * Asks one guard about one candidate, handing a [ResponseAwareGuard] the stored answer as well.
     *
     * The response is already in memory — it is the thing the cache would serve — so the extra argument
     * costs nothing, and a guard that does not want it never sees it.
     */
    private fun verdictOf(guard: MatchGuard, prompt: String, candidate: CacheEntry): GuardVerdict =
        if (guard is ResponseAwareGuard) {
            guard.evaluate(prompt, candidate.prompt, candidate.response)
        } else {
            guard.evaluate(prompt, candidate.prompt)
        }
}

/**
 * Which candidates are worth trying, and in what order.
 *
 * Filtering comes first and reranking second, and that order is the whole reason a [reranker] is safe
 * to add. `search` returns entries best-first, so the first one below the threshold means every
 * remaining one is too — a `takeWhile`, not a scan. Reranking before that filter would put a
 * below-threshold entry ahead of an above-threshold one and quietly turn the cheap exit into a wrong
 * answer. Reranking after it reorders only entries that already qualify, so nothing a reranker does can
 * change *which* entries are eligible, only the order they are tried in.
 */
internal class CandidateOrder(private val reranker: CandidateReranker?) {

    fun considered(embedding: FloatArray, found: List<ScoredEntry>, threshold: Double): List<ScoredEntry> {
        val eligible = found.takeWhile { it.similarity >= threshold }
        val reranker = reranker ?: return eligible
        if (eligible.size <= MINIMUM_TO_REORDER) return eligible
        val reranked = reranker.rerank(embedding, eligible)
        // A reranker that dropped or invented a candidate would change what the cache may serve, which
        // is not its job. Cheap to check, and the alternative is a silent correctness change.
        require(reranked.size == eligible.size) {
            "${reranker::class.simpleName} returned ${reranked.size} candidates for ${eligible.size}; " +
                "a reranker reorders and never filters"
        }
        return reranked
    }

    private companion object {
        /** Below three there is no ordering a reranker could change that matters. */
        private const val MINIMUM_TO_REORDER = 2
    }
}

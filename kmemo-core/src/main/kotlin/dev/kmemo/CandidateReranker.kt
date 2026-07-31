package dev.kmemo

import kotlin.math.max

/**
 * Reorders the candidates that cleared the similarity threshold, before the guards see them.
 *
 * It reorders and never rescores. A reranker that changed similarities would change what clears the
 * threshold, and the threshold is the one number a caller calibrated for their own embedding model.
 *
 * **This is not the place for a cross-encoder.** A reranker that reads the prompts with a model is
 * exactly what [Verifier] already is, and putting one here would mean paying for it on candidates the
 * guards were about to reject for free. Rerankers here work on the vectors the search already
 * produced, and cost nothing per lookup beyond arithmetic.
 */
public fun interface CandidateReranker {

    /**
     * Returns [candidates] in the order the cache should try them.
     *
     * Implementations must return the same entries, no more and no fewer, with their similarities
     * untouched. [query] is unit-normalized, as is every [CacheEntry.embedding].
     */
    public fun rerank(query: FloatArray, candidates: List<ScoredEntry>): List<ScoredEntry>
}

/**
 * Maximal Marginal Relevance: prefers a candidate that is close to the query *and* unlike the
 * candidates already chosen.
 *
 * ## What it is for, stated so it can be argued with
 *
 * A nearest-neighbour search over a cache that has been running for a while returns near-duplicates of
 * each other. Ask "how do I exit vim" and the five nearest entries may be five phrasings of the same
 * question — which is fine when the answer is servable, and expensive when it is not. The cache tries
 * candidates in order, and a [Verifier] costs a model call each time. Five paid calls that all inspect
 * what is effectively one entry is four calls wasted, and the genuinely different candidate that would
 * have been served is tried last.
 *
 * MMR reorders so that each candidate the cache tries adds something the previous ones did not. The
 * effect is fewer verifier calls before a servable candidate is reached. It does **not** change which
 * entries are considered — the store already chose those — and with no verifier configured and every
 * candidate iterated, it changes only which of several servable entries is served.
 *
 * ## What it costs
 *
 * One pass per selected candidate over the remaining ones, so quadratic in the candidate count. That
 * count is single digits by default, and the arithmetic is a dot product over vectors already in
 * memory.
 *
 * @param lambda how much relevance is worth against diversity, in `[0.0, 1.0]`. At `1.0` this is the
 *   identity ordering; at `0.0` relevance is ignored entirely and only novelty counts. The default
 *   leans towards relevance, because the candidates are already the nearest ones and the goal is to
 *   break up duplicates rather than to go looking for variety.
 */
public class MmrReranker(private val lambda: Double = DEFAULT_LAMBDA) : CandidateReranker {

    init {
        require(lambda in 0.0..1.0) { "lambda must be in [0.0, 1.0], was $lambda" }
    }

    override fun rerank(query: FloatArray, candidates: List<ScoredEntry>): List<ScoredEntry> {
        if (candidates.size <= 2) return candidates

        val remaining = candidates.toMutableList()
        val selected = ArrayList<ScoredEntry>(candidates.size)
        // The nearest candidate is always first: with nothing selected there is nothing to be
        // redundant with, and starting anywhere else would be diversity for its own sake.
        selected.add(remaining.removeAt(0))

        while (remaining.isNotEmpty()) {
            var bestIndex = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for ((index, candidate) in remaining.withIndex()) {
                var closestSelected = -1.0
                for (chosen in selected) {
                    closestSelected = max(
                        closestSelected,
                        Vectors.dot(candidate.entry.embedding, chosen.entry.embedding),
                    )
                }
                val score = lambda * candidate.similarity - (1 - lambda) * closestSelected
                if (score > bestScore) {
                    bestScore = score
                    bestIndex = index
                }
            }
            selected.add(remaining.removeAt(bestIndex))
        }
        return selected
    }

    private companion object {
        private const val DEFAULT_LAMBDA = 0.7
    }
}

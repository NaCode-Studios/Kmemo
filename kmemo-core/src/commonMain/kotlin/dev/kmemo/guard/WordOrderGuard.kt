package dev.kmemo.guard

/**
 * Rejects matches whose prompts name the same things in a different order.
 *
 * The near miss every other guard in the chain is structurally unable to see. `Flights from New York to
 * Miami` and `Flights from Miami to New York` have the same words, the same numbers, the same entities,
 * the same units and the same length. Every set-based guard compares two identical sets and abstains
 * correctly, and [SubstitutionGuard], the one guard that reads order, requires the order to be the
 * **same** and differ in one position, so a swap is invisible to it too. What changed is not a token, it
 * is which token is the subject.
 *
 * ### The one condition that keeps it from eating paraphrases
 *
 * English paraphrases reorder constantly. Active becomes passive, a clause moves to the front, a
 * modifier trails instead of leading, and none of that changes the answer. A guard that rejected on
 * reordering would reject most of the paraphrases it was shown.
 *
 * So it does not read reordering. It reads **two anchors that swapped past each other**. An anchor is a
 * token that names something specific: a capitalized entity or a number. Reordering a sentence moves
 * phrases around; reversing a relation moves two named things past each other, and only the second is
 * evidence. `Flights from New York to Miami` has anchors `New York` before `Miami`; the other prompt has
 * them the other way round, and that is what fires.
 *
 * Both prompts must also carry the **same multiset of content words**. A pair that differs in what it
 * says is somebody else's job, and this guard has nothing to add to it.
 *
 * ### Not in any default preset
 *
 * It is opt-in, and `docs/MEASUREMENTS.md` carries the reason with the numbers. It was built to answer a
 * pre-registered target on an adversarial corpus, and it is reported there whether it met it or not.
 *
 * @param minAnchors anchors both prompts must carry before an order difference is read as evidence. Two
 *   is the minimum that can express a relation at all; higher makes the guard quieter still.
 */
public class WordOrderGuard(
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
    private val sentenceOpeners: Set<String> = Vocabulary.SENTENCE_OPENERS,
    private val nonEntityCapitals: Set<String> = Vocabulary.NON_ENTITY_CAPITALS,
    private val minAnchors: Int = DEFAULT_MIN_ANCHORS,
) : MatchGuard {

    init {
        require(minAnchors >= 2) { "minAnchors must be at least 2, was $minAnchors" }
    }

    override val name: String get() = "word-order"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)
        // Same words, different arrangement. A pair that differs in what it contains is a different
        // guard's evidence, and adding this one's on top would double-count it.
        if (queryTokens.sorted() != candidateTokens.sorted()) return GuardVerdict.Accept

        val anchors = anchorsIn(query).intersect(anchorsIn(candidate))
        if (anchors.size < minAnchors) return GuardVerdict.Accept

        val inQuery = queryTokens.withIndex().filter { it.value in anchors }
        val inCandidate = candidateTokens.withIndex().filter { it.value in anchors }
        val queryOrder = inQuery.map { it.value }
        val candidateOrder = inCandidate.map { it.value }
        if (queryOrder == candidateOrder) return GuardVerdict.Accept

        val swapped = firstSwap(queryOrder, candidateOrder) ?: return GuardVerdict.Accept
        return GuardVerdict.Reject(
            "named things reordered: query puts '${swapped.first}' before '${swapped.second}' " +
                "where the cached prompt puts them the other way round",
        )
    }

    /**
     * The first pair of anchors that appear in opposite relative order, or `null` when none does.
     *
     * A pair rather than a count, because the reason a guard gives is the evidence a caller acts on, and
     * "the order differs" is not something anybody can check.
     */
    private fun firstSwap(query: List<String>, candidate: List<String>): Pair<String, String>? {
        for (first in query.indices) {
            for (second in first + 1 until query.size) {
                val a = query[first]
                val b = query[second]
                val aInCandidate = candidate.indexOf(a)
                val bInCandidate = candidate.indexOf(b)
                if (aInCandidate >= 0 && bInCandidate >= 0 && aInCandidate > bInCandidate) return a to b
            }
        }
        return null
    }

    /** Tokens that name something specific: a capitalized entity, or a number. */
    private fun anchorsIn(text: String): Set<String> {
        val entities = Text.entityTokens(text, sentenceOpeners, nonEntityCapitals)
            .map { it.lowercase() }
        val numbers = Text.tokens(text).filter { token -> token.any { it.isDigit() } }
        return (entities + numbers).toSet()
    }

    public companion object {
        /** Anchors both prompts must carry. Two is the fewest that can express a relation. */
        public const val DEFAULT_MIN_ANCHORS: Int = 2
    }
}

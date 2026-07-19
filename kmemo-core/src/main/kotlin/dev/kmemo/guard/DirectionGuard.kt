package dev.kmemo.guard

/**
 * Rejects matches where the same words appear in an order that reverses the question.
 *
 * "Is Postgres better than MySQL?" and "Is MySQL better than Postgres?" contain an identical bag of
 * words. Every set-based check — word overlap, entity comparison, embedding similarity — sees two
 * copies of the same question, because in the bag-of-words view that is exactly what they are.
 *
 * Word order alone cannot be the signal, though: "In Python, how do I sort a dictionary by value?"
 * and "How do I sort a dictionary by value in Python?" are also a reordering, and they must stay a
 * hit. Two conditions narrow the guard to real swaps.
 *
 * **A cue must be present** — a comparison or a conversion, the constructions where argument order
 * carries meaning. And **the words must be the same**; different words are somebody else's problem,
 * which keeps "how many miles is 50 km" matching "convert 50 km to miles".
 *
 * **The reordering must not be a rotation.** Moving a phrase from the end of a sentence to the
 * front rotates the token list, and it never changes the question: "How do I migrate a file in
 * Linux?" and "In Linux, how do I migrate a file?" are one prompt. Swapping two arguments around a
 * cue is a different permutation entirely — no rotation turns `[postgres, better, mysql]` into
 * `[mysql, better, postgres]`. Without this test the guard rejects every fronted prepositional
 * phrase whose sentence happens to contain a cue word, which is a paid API call for nothing.
 */
public class DirectionGuard(
    private val cues: Set<String> = Vocabulary.DIRECTIONAL_CUES,
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
) : MatchGuard {

    override val name: String get() = "direction"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        if (!hasDirectionalCue(query) && !hasDirectionalCue(candidate)) return GuardVerdict.Accept
        if (isSymmetricComparison(query) || isSymmetricComparison(candidate)) return GuardVerdict.Accept

        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)
        if (queryTokens == candidateTokens) return GuardVerdict.Accept
        if (queryTokens.toSet() != candidateTokens.toSet()) return GuardVerdict.Accept
        if (isRotationOf(queryTokens, candidateTokens)) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "same terms in reversed order around a comparison or conversion: " +
                "$queryTokens vs $candidateTokens",
        )
    }

    private fun hasDirectionalCue(text: String): Boolean = Text.tokens(text).any { it in cues }

    /**
     * Whether the prompt asks the reader to *choose between* two things rather than to judge one
     * against the other.
     *
     * "Is Postgres better than MySQL?" is a yes/no question, and reversing the operands asks the
     * opposite. "Which is better, Redis or Memcached?" and "Vim vs Emacs for large files" ask for a
     * winner, and the order the candidates are listed in changes nothing — those are the same
     * question and must stay a hit.
     *
     * `than` is what marks the asymmetric form, so a comparison without it is a selection.
     */
    private fun isSymmetricComparison(text: String): Boolean {
        val tokens = Text.tokens(text)
        if ("than" in tokens) return false
        return tokens.any { it in SYMMETRIC_COORDINATORS }
    }

    /**
     * Whether [b] is [a] with some prefix moved to the end — a fronted phrase, not a swap.
     *
     * Two tokens are the awkward case, because there every permutation is a rotation. What
     * separates the two readings is *where the cue is*. In "are there more cats than dogs" the cue
     * words are function words, and the two content tokens are the things being compared — swapping
     * them reverses the question. In "how do I migrate in Rails" the cue is one of the two content
     * tokens, so the other cannot be its counterpart, and "In Rails, how do I migrate" is the same
     * question with the phrase moved.
     */
    private companion object {
        /** Words that list alternatives rather than rank one against another. */
        private val SYMMETRIC_COORDINATORS = setOf("or", "vs", "versus", "between")
    }

    private fun isRotationOf(a: List<String>, b: List<String>): Boolean {
        if (a.size != b.size) return false
        if (a.size < 2) return false
        if (a.size == 2) return a.any { it in cues }
        return (a + a).windowed(b.size).any { it == b }
    }
}

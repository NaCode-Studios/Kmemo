package dev.kmemo.guard

import java.util.Locale

/**
 * Rejects matches whose prompts share too few meaningful words. The backstop under the specialised
 * guards: it catches the swaps nobody wrote a rule for.
 *
 * Function words and politeness filler are stripped first, so "How do I kill a process on a port?"
 * and "Hi, could you please tell me how to kill a process on a port? Thanks!" reduce to nearly the
 * same set. What remains is compared with Jaccard overlap.
 *
 * Two details do most of the work:
 *
 * **Typos are matched fuzzily.** `instal` counts as `install`, so "how do i instal numpy wiht pip"
 * still matches. Matching is capped at one edit between tokens of five characters or more, which is
 * deliberately too strict to merge `Austria` with `Australia`.
 *
 * **[minOverlap] defaults low.** Genuine paraphrases can share surprisingly few words — "How do I
 * undo my last git commit?" and "I committed by mistake in git, how do I take that commit back?"
 * overlap by about a quarter — so a threshold tuned to catch entity swaps on its own would reject
 * real hits. The specialised guards handle the precise cases; this one only fires when two prompts
 * have almost nothing in common and the embedding still claimed a match.
 */
public class LexicalDivergenceGuard(
    private val minOverlap: Double = DEFAULT_MIN_OVERLAP,
    private val minTokens: Int = DEFAULT_MIN_TOKENS,
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
) : MatchGuard {

    init {
        require(minOverlap in 0.0..1.0) { "minOverlap must be within [0.0, 1.0], was $minOverlap" }
        require(minTokens >= 0) { "minTokens must not be negative, was $minTokens" }
    }

    override val name: String get() = "lexical-divergence"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)
        // Too few words on one side for an overlap ratio to mean anything. "375 f to c" shares one
        // token out of three with "What is 375 degrees Fahrenheit in Celsius?", and they are the
        // same question. Below minTokens there is no evidence here, so the guard says nothing and
        // leaves the decision to the guards that read specific things.
        if (queryTokens.size < minTokens || candidateTokens.size < minTokens) return GuardVerdict.Accept

        val shared = countShared(queryTokens, candidateTokens)
        val union = queryTokens.size + candidateTokens.size - shared
        // Only reachable with minTokens = 0 and two prompts made entirely of stopwords. Without
        // this, the ratio is NaN, NaN >= minOverlap is false, and the guard rejects on no evidence.
        if (union == 0) return GuardVerdict.Accept
        val overlap = shared.toDouble() / union.toDouble()
        if (overlap >= minOverlap) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "content-word overlap ${format(overlap)} below ${format(minOverlap)} " +
                "(query: $queryTokens, cached prompt: $candidateTokens)",
        )
    }

    /** Greedy one-to-one pairing, so a token is never counted as matching two different tokens. */
    private fun countShared(queryTokens: List<String>, candidateTokens: List<String>): Int {
        val available = candidateTokens.toMutableList()
        var shared = 0
        for (token in queryTokens) {
            val index = available.indexOfFirst { it == token || Text.isSameWord(it, token) }
            if (index >= 0) {
                available.removeAt(index)
                shared++
            }
        }
        return shared
    }

    private fun format(value: Double): String = "%.2f".format(Locale.ROOT, value)

    public companion object {
        /** Tuned on the near-miss corpus: the lowest value that rejects no genuine paraphrase. */
        public const val DEFAULT_MIN_OVERLAP: Double = 0.25

        /** Content words needed on both sides before an overlap ratio is worth trusting. */
        public const val DEFAULT_MIN_TOKENS: Int = 5
    }
}

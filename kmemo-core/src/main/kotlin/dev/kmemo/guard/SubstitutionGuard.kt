package dev.kmemo.guard

/**
 * Rejects matches whose prompts are identical except for one word.
 *
 * The guard that does not need a vocabulary. [EntityGuard] catches a swapped name only when the name
 * is capitalized, which is a convention rather than a fact about meaning — real traffic is full of
 * "sales tax in oregon" against "sales tax in washington", where every other guard has nothing to
 * say. Measured on prompts nobody tuned against, capitalization was carrying about a third of the
 * entity catches; lowercase them and the protection disappears.
 *
 * So this reads structure instead of spelling. If two prompts have the same content words in the
 * same order and differ in exactly one position, one term was substituted for another, whatever it
 * was and whatever case it was written in. `postgres` for `mysql`, `ibuprofen` for `naproxen`,
 * `.heic` for `.webp`.
 *
 * Three conditions keep it from eating real paraphrases:
 *
 * **Same length, same order.** A paraphrase almost never preserves word order exactly while changing
 * one word; it adds, drops or reorders. "How do I merge two hashes in Ruby?" against "How do I
 * combine two hashes into one in Ruby?" differs in length and is left alone.
 *
 * **The differing words must be genuinely different.** [Text.isSameWord] absorbs typos, spelling
 * variants and inflections first, so `organise`/`organize` and `raed`/`read` are not a substitution.
 *
 * **Enough words to be sure.** Below [minTokens] content words, a one-word difference is as likely
 * to be a verb synonym — "define recursion" against "explain recursion" — as a substituted term.
 */
public class SubstitutionGuard(
    private val minTokens: Int = DEFAULT_MIN_TOKENS,
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
    private val units: Map<String, String> = Vocabulary.UNITS,
) : MatchGuard {

    init {
        require(minTokens >= 2) { "minTokens must be at least 2, was $minTokens" }
    }

    override val name: String get() = "substitution"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)
        if (queryTokens.size != candidateTokens.size) return GuardVerdict.Accept
        if (queryTokens.size < minTokens) return GuardVerdict.Accept

        var substituted = -1
        for (index in queryTokens.indices) {
            if (isSameTerm(queryTokens[index], candidateTokens[index])) continue
            if (substituted >= 0) return GuardVerdict.Accept
            substituted = index
        }
        if (substituted < 0) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "one term substituted: query says '${queryTokens[substituted]}' " +
                "where cached prompt says '${candidateTokens[substituted]}'",
        )
    }

    /**
     * Whether two tokens name the same thing — the same word written differently, or two spellings
     * of one unit.
     *
     * The unit check keeps this guard consistent with [UnitGuard], which already knows that `utc`
     * and `gmt` are one offset and `km` and `kilometers` one distance. Without it, the two guards
     * would disagree about the same pair of tokens, and the stricter one would win.
     */
    private fun isSameTerm(a: String, b: String): Boolean {
        if (Text.isSameWord(a, b)) return true
        val unitA = units[a] ?: return false
        return unitA == units[b]
    }

    public companion object {
        /** Content words needed on both sides before a single difference is treated as a swap. */
        public const val DEFAULT_MIN_TOKENS: Int = 4
    }
}

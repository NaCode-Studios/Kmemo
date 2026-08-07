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
 *
 * ### And, optionally, few enough words for one of them to matter
 *
 * [minTokens] has a mirror image that shipped for two major versions without one, and M28 measured
 * what it costs. The false rejection rate of this guard on genuine paraphrases is 0% on pairs under
 * 48 characters, 12% between 48 and 95, and 15% from 96 characters up, where it flattens and stays at
 * 15% all the way to two-thousand-character prompts. Most of what looked like a register gap between
 * the external split and the written ones was this: the written splits are almost entirely under 48
 * characters and the external one is almost entirely above 96, so the two averages were describing
 * different lengths rather than different subject matter. In the one band where they overlap they
 * read 10% and 12%.
 *
 * The mechanism is the guard's own. One differing content word out of five is a term somebody
 * swapped; one out of forty is a word somebody chose differently, and this guard cannot tell the
 * difference because it only ever looks at how many positions differ, never at how much of the prompt
 * that position is. [maxTokens] is the bound that says so: above it, the guard abstains.
 *
 * It is `null` by default. Turning it on by default would move every published figure at once, and
 * this guard's catches are worth more than its false rejections on the traffic the defaults were
 * measured for. [MatchGuards.longPrompts] is the chain that sets it, and the README carries the cost.
 */
public class SubstitutionGuard(
    private val minTokens: Int = DEFAULT_MIN_TOKENS,
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
    private val units: Map<String, MeasurementUnit> = Vocabulary.UNITS,
    private val maxTokens: Int? = null,
) : MatchGuard {

    init {
        require(minTokens >= 2) { "minTokens must be at least 2, was $minTokens" }
        require(maxTokens == null || maxTokens >= minTokens) {
            "maxTokens must be at least minTokens ($minTokens), was $maxTokens"
        }
    }

    override val name: String get() = "substitution"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)
        if (queryTokens.size != candidateTokens.size) return GuardVerdict.Accept
        if (queryTokens.size < minTokens) return GuardVerdict.Accept
        if (maxTokens != null && queryTokens.size > maxTokens) return GuardVerdict.Accept

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

        /**
         * The bound [MatchGuards.longPrompts] sets: past this many content words, one differing word
         * is not evidence of a substitution.
         *
         * Chosen on the tuned corpus, which is the split that exists to be fitted, and then measured
         * everywhere else — not chosen by reading where the external split's failures happen to sit,
         * which would be tuning against the one number in this project nobody could have tuned. It
         * costs the tuned corpus nothing: every substitution catch there is a question of a dozen
         * content words or fewer, because a question with a swappable term in it is short. The cost on
         * the other splits is measured, published in the README and asserted in
         * `SubstitutionBoundTest`.
         */
        public const val LONG_PROMPT_MAX_TOKENS: Int = 12
    }
}

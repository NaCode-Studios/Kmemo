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
public class SubstitutionGuard private constructor(
    private val minTokens: Int,
    private val stopwords: Set<String>,
    private val units: Map<String, MeasurementUnit>,
    private val maxTokens: Int?,
    /**
     * `null` means "the same floor everywhere", which is the shape the public constructor has always
     * had. Nullable rather than defaulted so that this constructor and the public one do not collide
     * on one JVM signature.
     */
    private val headFloor: Int?,
) : MatchGuard {

    /**
     * The guard as it has always been constructed: one floor, applied wherever the difference sits.
     *
     * Kept as the only public constructor so that the head floor could be added without changing a
     * signature somebody has already compiled against. Reach it through [withHeadFloor].
     */
    public constructor(
        minTokens: Int = DEFAULT_MIN_TOKENS,
        stopwords: Set<String> = Vocabulary.STOPWORDS,
        units: Map<String, MeasurementUnit> = Vocabulary.UNITS,
        maxTokens: Int? = null,
    ) : this(minTokens, stopwords, units, maxTokens, null)

    /**
     * The defaults, as a constructor a caller can already have compiled against.
     *
     * Spelled out because a secondary constructor whose parameters all have defaults does not get the
     * no-argument overload a primary one gets, and dropping it would break `new SubstitutionGuard()`
     * for a Java caller who never asked for any of this.
     */
    public constructor() : this(DEFAULT_MIN_TOKENS, Vocabulary.STOPWORDS, Vocabulary.UNITS, null, null)

    private val headMinTokens: Int get() = headFloor ?: minTokens

    init {
        require(minTokens >= 2) { "minTokens must be at least 2, was $minTokens" }
        require(headFloor == null || headFloor >= minTokens) {
            "headMinTokens must be at least minTokens ($minTokens), was $headFloor"
        }
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
        // The head carries its own floor. In a question the first content word is usually the verb,
        // and a verb is what the floor was written about: `define recursion` against `explain
        // recursion` is a synonym, not a swap. Everywhere else the two floors are the same number and
        // this costs nothing.
        if (substituted == 0 && queryTokens.size < headMinTokens) return GuardVerdict.Accept

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

        /**
         * This guard with a separate, higher floor for a difference in the **first** content word.
         *
         * The shape M51's measurement pointed at. The floor's stated reason has always been the verb,
         * and in a question the verb is at or near the head, so a floor on the head is the reason
         * applied to the quantity it was about instead of to prompt length. Below [headMinTokens]
         * content words a difference in the first position is treated as a synonym and the guard
         * abstains; a difference anywhere else needs only [minTokens].
         *
         * At `minTokens = 3, headMinTokens = 4` it costs the tuned split nothing, gains catches on
         * both retired splits for no paraphrase at all, and on the external question split trades 65
         * catches for 36 paraphrases. That last figure is why [MatchGuards.standard] does not carry
         * it: this project counts a paraphrase given up as a cost rather than as a rounding error, and
         * the ratio is worse than one it has already declined. [MatchGuards.shortQuestions] is the
         * chain that opts in, and `docs/MEASUREMENTS.md` publishes the trade on every split.
         *
         * @throws IllegalArgumentException if [headMinTokens] is below [minTokens].
         */
        public fun withHeadFloor(
            minTokens: Int = SHORT_QUESTION_MIN_TOKENS,
            headMinTokens: Int = DEFAULT_MIN_TOKENS,
            stopwords: Set<String> = Vocabulary.STOPWORDS,
            units: Map<String, MeasurementUnit> = Vocabulary.UNITS,
            maxTokens: Int? = null,
        ): SubstitutionGuard =
            SubstitutionGuard(minTokens, stopwords, units, maxTokens, headMinTokens)

        /** The floor [withHeadFloor] uses off the head: two agreeing content words in order. */
        public const val SHORT_QUESTION_MIN_TOKENS: Int = 3

        /**
         * Content words needed on both sides before a single difference is treated as a swap.
         *
         * Four, and M51 is the argument for why it stays there rather than the argument that put it
         * there. Read from the mechanism, the floor is a crossover: the guard's evidence is the
         * *agreeing* part, which grows with every extra word, against the risk that the one differing
         * position is a synonym somebody chose rather than a term somebody swapped, which does not
         * shrink with length. The mechanism fixes that a floor must exist and that two is below it,
         * because one agreeing word is no agreement at all. It does not fix three against four:
         * where the two quantities cross is an empirical property of the language, not a structural
         * one.
         *
         * So it was measured, on splits nobody here can tune. Three buys 77 more catches on the
         * external question split and pays 63 genuine paraphrases for them, and costs the tuned split
         * a paraphrase it has never lost. A trade at a ratio of 1.2 to 1 is the boundary case this
         * project already declined once at 2.9 to 1: it moves cost from the wrong-answer column to
         * the API-bill column, and those two are not interchangeable. `SubstitutionFloorTest` carries
         * the ladder and `docs/MEASUREMENTS.md` publishes it.
         */
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

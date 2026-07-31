package dev.kmemo.guard

/**
 * Rejects a candidate whose stored answer repeats the very word that the two prompts disagree on.
 *
 * The near miss this exists for is the one the prompt-side chain is blind to by construction: two
 * prompts that differ by a single substituted term the guards have no vocabulary for, so nothing
 * fires, and similarity is high because the wording is otherwise identical. `what is the capital gains
 * tax rate when i sell a second home` against `…a primary residence` clears every guard in
 * [MatchGuards.standard]. The evidence that they need different answers is not in either prompt — it
 * is in the cached answer, which opens "Gain on a second home is taxable in full".
 *
 * So the rule is: when the answer names the term the query replaced, the answer was written for the
 * other question.
 *
 * ## What has to hold before it will reject
 *
 * Three conditions, and each one is load-bearing:
 *
 * 1. **The prompts differ only by a substitution.** Same number of content words, differing in at most
 *    [maxSubstitutions] positions, compared with [Text.isSameWord] so a typo or a spelling variant is
 *    not a substitution. This is the same rule [Text.differsOnlyBy] applies to marker guards, for the
 *    same reason: a word only counts as evidence when everything around it matches. Without it,
 *    `How do I configure CORS on an API gateway?` against `How do I configure cross origin resource
 *    sharing on an API gateway?` is refused, because the answer to the second says "origin" — an
 *    expansion read as a swap, and a genuine paraphrase thrown away.
 * 2. **The substituted word appears in the response.** Matching is [Text.isSameWord] again, so
 *    `residence` in the prompt is found as `residences` in the answer.
 * 3. **The query does not use that word anywhere.** `Which is better for session storage, Redis or
 *    Memcached?` against the same two names swapped is a substitution at two positions, and the answer
 *    names both — but the query names both too, so the answer is not anchored to one of them. Dropping
 *    this condition refuses every word-order pair in the corpus.
 *
 * ## What it deliberately does not do, and what it cannot do
 *
 * It does not read numbers out of the answer. A response carries the figures that answer the
 * question, and the question rarely carries them — `what is the boiling point of ethanol` names no
 * number and is answered with `78.4` — so a numeric comparison against the response refuses honest
 * paraphrases in bulk. `ResponseGuardTest` measures that rather than asserting it in prose. This guard
 * reads only the words the two prompts have already singled out, which also keeps it linear in the
 * response length with no parsing.
 *
 * It also inherits [Text.isSameWord]'s tolerance in both directions. `mg` against `mcg` is one
 * insertion, so it reads as a typo and no substitution is seen — that near miss is not caught here.
 * The same tolerance is what stops `organise` against `organize` being read as a swap, and this guard
 * cannot have the second without the first.
 *
 * ## It is opt-in, and the reason is the measurement
 *
 * Of the 118 near-miss lookups that [MatchGuards.standard] still serves on the two blind corpora it
 * refuses 14, and it refuses **none** of the 164 paraphrase lookups still being served. That moves the
 * false-hit rate from 0.291 to 0.238 on the held-out split and from 0.333 to 0.309 on validation.
 *
 * A guard that catches an eighth of the residual for no measured cost would normally go straight into
 * the default set. This one does not, and the reason is the evidence rather than the result: those
 * numbers are **in-sample**. The answers behind them were authored for this measurement, because no
 * corpus of real paired answers exists to harvest, so it is a regression check rather than the blind
 * measurement every other guard is held to. See `docs/CORPUS.md` and [MatchGuards.responseAware].
 *
 * @param stopwords function words removed before the prompts are compared.
 * @param maxSubstitutions how many positions the prompts may differ in and still count as a
 *   substitution rather than a rewording. Two, so that `a second home` against `a primary residence`
 *   is one substitution of a two-word term; at three the guard starts attributing meaning to prompts
 *   that were simply reworded.
 */
public class AnswerAnchorGuard(
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
    private val maxSubstitutions: Int = 2,
) : ResponseAwareGuard {

    override val name: String get() = "answer-anchor"

    override fun evaluate(query: String, candidate: String, candidateResponse: String): GuardVerdict {
        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)
        if (queryTokens.size != candidateTokens.size) return GuardVerdict.Accept

        val substituted = candidateTokens.indices.filterNot {
            Text.isSameWord(queryTokens[it], candidateTokens[it])
        }
        if (substituted.isEmpty() || substituted.size > maxSubstitutions) return GuardVerdict.Accept

        val responseTokens = Text.tokens(candidateResponse)
        for (index in substituted) {
            val word = candidateTokens[index]
            if (queryTokens.any { Text.isSameWord(it, word) }) continue
            if (responseTokens.none { Text.isSameWord(it, word) }) continue
            return GuardVerdict.Reject(
                "the cached answer is about \"$word\", which the query replaced with " +
                    "\"${queryTokens[index]}\"",
            )
        }
        return GuardVerdict.Accept
    }
}

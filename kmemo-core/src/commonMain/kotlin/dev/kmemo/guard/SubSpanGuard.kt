package dev.kmemo.guard

/**
 * Rejects a match where one prompt is the other plus a clause that narrows the question.
 *
 * `How do I deploy a Rails app` and `How do I deploy a Rails app on Heroku` share every word the
 * shorter one has, so overlap is perfect and [LexicalDivergenceGuard] sees nothing. They embed close
 * for the same reason. And they need different answers, because the second is asking about Heroku.
 *
 * This is the gap between the lexical guards and the [dev.kmemo.Verifier]. The other guards look for a
 * word that *changed*; nothing here changed, something was *added*, and what was added is the part that
 * decides the answer.
 *
 * ## The three conditions, and why a looser rule does not work
 *
 * The naive version — "some clause in one prompt has no counterpart in the other" — refuses genuine
 * paraphrases in bulk, because people frame questions differently: `I am working on a REST client and
 * need to parse an ISO 8601 timestamp in Java` is the same question as `How do I parse an ISO 8601
 * timestamp in Java`, and its first clause matches nothing. So all three of these must hold:
 *
 * 1. **One prompt's content words contain the other's**, compared with [Text.isSameWord] so a typo or a
 *    spelling variant is not a difference. Two prompts that were merely reworded fail here, which is
 *    what keeps `My connection to the server keeps dropping after a few minutes of sitting idle` from
 *    being read as a narrowed form of `How do I stop my SSH session from timing out`.
 * 2. **The added words all sit in one span.** Framing spreads across a sentence; a qualifier is local.
 *    The REST client example adds words to two different spans and is left alone.
 * 3. **That span opens with a qualifier**, one of [Vocabulary.QUALIFIER_OPENERS] — `on`, `for`, `with`,
 *    `without`, `after`, and the rest. `on Heroku` qualifies the question. `I am working on a REST
 *    client` opens with a pronoun and does not.
 *
 * Each condition is a way of demanding that the addition be *the answer-bearing kind* rather than
 * merely present, which is the same asymmetry every guard here is built on: a wrong rejection costs one
 * API call, a wrong acceptance costs a wrong answer.
 *
 * @param stopwords function words removed before the prompts are compared.
 * @param qualifierOpeners words that begin a clause narrowing a question rather than framing it.
 */
public class SubSpanGuard(
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
    private val qualifierOpeners: Set<String> = Vocabulary.QUALIFIER_OPENERS,
) : MatchGuard {

    override val name: String get() = "sub-span"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryTokens = Text.contentTokens(query, stopwords)
        val candidateTokens = Text.contentTokens(candidate, stopwords)

        val narrowed = when {
            containsAll(queryTokens, candidateTokens) && queryTokens.size > candidateTokens.size ->
                query to candidateTokens
            containsAll(candidateTokens, queryTokens) && candidateTokens.size > queryTokens.size ->
                candidate to queryTokens
            else -> return GuardVerdict.Accept
        }
        val (longer, shorterTokens) = narrowed

        val spans = spans(longer)
        val added = spans
            .filter { span -> span.tokens.any { token -> shorterTokens.none { Text.isSameWord(it, token) } } }
            .singleOrNull()
            ?: return GuardVerdict.Accept

        if (added.opener == null) return GuardVerdict.Accept
        // A condition is attached to the end of a question; a preamble is put in front of it. "I am on
        // Ubuntu and I want to compress a folder" opens with a qualifier and narrows nothing.
        if (added !== spans.last()) return GuardVerdict.Accept
        // "for my exam tomorrow" and "using Homebrew or otherwise" both look like conditions and are
        // not: one qualifies the asker, the other explicitly declines to qualify the answer.
        if (added.framing) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "one prompt narrows the question with \"${added.opener} ${added.tokens.joinToString(" ")}\" " +
                "and the other does not",
        )
    }

    /** Whether every token of [subset] has a counterpart in [superset], typos and inflections allowed. */
    private fun containsAll(superset: List<String>, subset: List<String>): Boolean =
        subset.all { token -> superset.any { Text.isSameWord(it, token) } }

    /**
     * Clauses, each with the qualifier that opened it if one did.
     *
     * A span ends at `,` `;` `:` or at the next qualifier opener, so `deploy a Rails app on Heroku`
     * splits into the question and the thing qualifying it. Stopwords are dropped from a span's tokens
     * but the opener itself is kept separately, because it is the evidence.
     */
    private fun spans(text: String): List<Span> {
        val result = mutableListOf<Span>()
        var current = Builder()

        fun flush() {
            current.build()?.let { result += it }
            current = Builder()
        }

        for (chunk in CLAUSE_PUNCTUATION.split(text)) {
            for (token in Text.tokens(chunk)) {
                if (token in qualifierOpeners) {
                    flush()
                    current.opener = token
                } else {
                    current.add(token, stopwords)
                }
            }
            flush()
        }
        return result.filter { it.tokens.isNotEmpty() }
    }

    private class Builder {
        var opener: String? = null
        private val tokens = mutableListOf<String>()
        private var framing = false

        fun add(token: String, stopwords: Set<String>) {
            if (token in FRAMING_MARKERS) framing = true
            if (token !in stopwords) tokens += token
        }

        fun build(): Span? =
            if (tokens.isEmpty() && opener == null) null else Span(opener, tokens.toList(), framing)
    }

    private class Span(val opener: String?, val tokens: List<String>, val framing: Boolean)

    private companion object {
        private val CLAUSE_PUNCTUATION = Regex("""[,;:]|\s[-—]\s""")

        /**
         * Words that make a clause about the asker or explicitly refuse to constrain the answer.
         *
         * First person, because "for my exam tomorrow" says who is asking rather than what is being
         * asked; and the hedges, because "using Homebrew or otherwise" is a clause whose whole content
         * is that it does not narrow anything.
         */
        private val FRAMING_MARKERS = setOf(
            "i", "me", "my", "mine", "we", "us", "our", "ours",
            "or", "either", "otherwise", "whatever", "anything", "any",
        )
    }
}

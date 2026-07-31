package dev.kmemo.guard

/**
 * Rejects matches that talk about different named things.
 *
 * "What is the capital of Australia" and "what is the capital of Austria" differ by two letters and
 * one continent. Embedding models place them extremely close — both are capital-city questions
 * about a European-sounding place — which makes this the classic false hit. Capitalization is the
 * cheapest available signal that a token names something specific, so the guard compares the
 * capitalized words of the two prompts.
 *
 * Three rules keep it from misfiring:
 *
 * **The opening capital is ignored.** `What` in "What is Docker" is grammar, not an entity. Only
 * the first word is exempt — see [Text.entityTokens] for why treating every `.` `:` `;` as a
 * sentence boundary silently disables this guard on exactly the prompts it exists for.
 *
 * **It rejects on substitution, not on addition.** Each prompt must name something the other does
 * not: `Austria` here, `Australia` there. One prompt merely mentioning an extra entity is not
 * evidence of a different question — "What does SOLID stand for in OOP?" and "what does each letter
 * of SOLID mean in object oriented design?" want the same answer, and only one of them abbreviates.
 *
 * **Pronouns and initials do not count.** English capitalizes `I`, which would otherwise make every
 * first-person question look like it named something.
 */
public class EntityGuard(
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
    private val sentenceOpeners: Set<String> = Vocabulary.SENTENCE_OPENERS,
    private val nonEntityCapitals: Set<String> = Vocabulary.NON_ENTITY_CAPITALS,
) : MatchGuard {

    override val name: String get() = "entity"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryEntities = Text.entityTokens(query, sentenceOpeners, nonEntityCapitals)
        val candidateEntities = Text.entityTokens(candidate, sentenceOpeners, nonEntityCapitals)
        if (queryEntities.isEmpty() || candidateEntities.isEmpty()) return GuardVerdict.Accept

        val onlyInQuery = notSpelledOutIn(queryEntities - candidateEntities, candidate)
        val onlyInCandidate = notSpelledOutIn(candidateEntities - queryEntities, query)
        if (onlyInQuery.isEmpty() || onlyInCandidate.isEmpty()) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "named entities swapped: query says $onlyInQuery where cached prompt says $onlyInCandidate",
        )
    }

    /**
     * Drops entities that the other prompt writes out in full.
     *
     * An acronym and its expansion are the same thing, and the expansion is capitalized precisely
     * because it is a proper noun — so "What does GDPR require…" against "What does the General Data
     * Protection Regulation require…" looks like the strongest possible entity swap while being one
     * question. The test is structural: does some run of consecutive words on the other side have
     * these initials, in order?
     */
    private fun notSpelledOutIn(entities: Set<String>, other: String): Set<String> {
        if (entities.isEmpty()) return entities
        val tokens = Text.tokens(other)
        return entities.filterNotTo(LinkedHashSet()) { isSpelledOutBy(it, tokens) }
    }

    /**
     * Whether some run of consecutive words has exactly these initials.
     *
     * Two constraints keep the test from matching noise, and both are load-bearing. Initials alone
     * are extremely weak evidence: at two letters, `US` is "spelled out by" *use software* and `OS`
     * by *on servers*, so every `US`/`UK` and `OS`/`DB` swap would be waved through — and short
     * acronyms are most of what a cache actually sees. Three letters is the shortest length where a
     * chance match stops being routine.
     *
     * The matched words must also all be content words. Otherwise `API` finds *a programming
     * interface* and the guard disables itself on a stray article.
     */
    private fun isSpelledOutBy(acronym: String, tokens: List<String>): Boolean {
        if (acronym.length !in MIN_ACRONYM..MAX_ACRONYM) return false
        if (tokens.size < acronym.length) return false
        for (start in 0..tokens.size - acronym.length) {
            val run = tokens.subList(start, start + acronym.length)
            if (run.any { it in stopwords }) continue
            if (acronym.indices.all { run[it].firstOrNull() == acronym[it] }) return true
        }
        return false
    }

    private companion object {
        private const val MIN_ACRONYM = 3
        private const val MAX_ACRONYM = 6
    }
}

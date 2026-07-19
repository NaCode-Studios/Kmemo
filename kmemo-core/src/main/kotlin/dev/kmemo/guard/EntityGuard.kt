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
public class EntityGuard : MatchGuard {

    override val name: String get() = "entity"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryEntities = Text.entityTokens(query)
        val candidateEntities = Text.entityTokens(candidate)
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

    private fun isSpelledOutBy(acronym: String, tokens: List<String>): Boolean {
        if (acronym.length !in MIN_ACRONYM..MAX_ACRONYM) return false
        if (tokens.size < acronym.length) return false
        for (start in 0..tokens.size - acronym.length) {
            if (acronym.indices.all { tokens[start + it].firstOrNull() == acronym[it] }) return true
        }
        return false
    }

    private companion object {
        private const val MIN_ACRONYM = 2
        private const val MAX_ACRONYM = 6
    }
}

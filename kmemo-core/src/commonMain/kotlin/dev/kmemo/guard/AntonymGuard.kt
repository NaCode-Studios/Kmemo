package dev.kmemo.guard

/**
 * Rejects matches where one prompt asks for the opposite of the other.
 *
 * "How do I enable two factor authentication on GitHub?" and "How do I disable two factor
 * authentication on GitHub?" differ in a single token out of nine. Every other signal a cache can
 * read — length, structure, topic, word overlap — says these are the same question. They are the
 * same question with the answer inverted.
 *
 * The guard looks for a **flip**, not merely a difference: one prompt must use a word *more* than
 * the other while the other uses its opposite *more*. Two things follow from counting rather than
 * testing membership.
 *
 * It stops false rejections. "Run this before deploy" and "run this prior to deploy" differ by the
 * word `before`, but nothing anywhere says `after`, so there is no flip and no rejection.
 *
 * And it still catches flips hidden by an incidental repeat. "How do I turn on format on save in VS
 * Code?" and "How do I turn off format on save in VS Code?" both contain `on` — the second one in
 * "format on save" — so asking whether `on` appears in both says they agree. Counting says the query
 * uses `on` twice to the candidate's once while the candidate uses `off` and the query never does,
 * which is exactly the swap that matters.
 */
public class AntonymGuard(
    antonyms: Set<Pair<String, String>> = Vocabulary.ANTONYMS,
) : MatchGuard {

    /** Both directions of every pair, so a single lookup answers "what is the opposite of x". */
    private val opposites: Map<String, Set<String>> = buildMap<String, MutableSet<String>> {
        for ((left, right) in antonyms) {
            getOrPut(left) { mutableSetOf() }.add(right)
            getOrPut(right) { mutableSetOf() }.add(left)
        }
    }

    override val name: String get() = "antonym"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryCounts = countTokens(query)
        val candidateCounts = countTokens(candidate)

        for ((word, count) in queryCounts) {
            val oppositeWords = opposites[word] ?: continue
            if (count <= (candidateCounts[word] ?: 0)) continue
            val flipped = oppositeWords.firstOrNull {
                (candidateCounts[it] ?: 0) > (queryCounts[it] ?: 0)
            } ?: continue
            return GuardVerdict.Reject("opposite terms: query says '$word', cached prompt says '$flipped'")
        }
        return GuardVerdict.Accept
    }

    private fun countTokens(text: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (token in Text.tokens(text)) {
            if (token in opposites) counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }
}

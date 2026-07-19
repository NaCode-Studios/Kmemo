package dev.kmemo.guard

/**
 * Rejects matches that pin the question to different moments in time.
 *
 * "What is the weather in Chicago today?" and "What is the weather in Chicago tomorrow?" are the
 * same question about a different day, and a cache that cannot tell them apart will confidently
 * serve yesterday's forecast forever.
 *
 * Only absolute references count — see [Vocabulary.TEMPORAL_MARKERS] for why `next` and `last` are
 * excluded. Year numbers are already handled by [NumericGuard], so this guard covers the words that
 * carry a date without carrying a digit.
 */
public class TemporalGuard(
    private val markers: Set<String> = Vocabulary.TEMPORAL_MARKERS,
    private val stopwords: Set<String> = Vocabulary.STOPWORDS,
) : MatchGuard {

    override val name: String get() = "temporal"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryMarkers = markersIn(query)
        val candidateMarkers = markersIn(candidate)
        if (queryMarkers == candidateMarkers) return GuardVerdict.Accept

        // `current` and `latest` are time references in "the current CEO" and fixed technical terms
        // in "the current branch" or "the current working directory". Nothing in a word list can
        // tell those apart, so the marker only counts when the rest of the prompt is identical.
        if (!Text.differsOnlyBy(query, candidate, markers, stopwords)) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "time references differ: ${queryMarkers.ifEmpty { setOf("none") }} vs " +
                "${candidateMarkers.ifEmpty { setOf("none") }}",
        )
    }

    private fun markersIn(text: String): Set<String> =
        Text.tokens(text).filterTo(LinkedHashSet()) { it in markers }
}

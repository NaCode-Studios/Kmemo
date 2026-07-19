package dev.kmemo.guard

/**
 * Rejects matches that ask for the same subject in a different shape.
 *
 * "Write a haiku about the ocean" and "Write a sonnet about the ocean" differ in one word and share
 * a topic, so they sit close together in embedding space. They are not the same request. Nor are
 * "Give me a code example of a Python decorator" and "Explain the theory behind Python decorators".
 *
 * What changed in each case is not the subject but the **answer being asked for**: its format, its
 * length, its depth. Those words are a small, closed set, so the guard reads them directly.
 *
 * Like [EntityGuard] and [UnitGuard], it rejects a **substitution** and not an addition — both
 * prompts must name a shape, and the shapes must differ. Firing on one-sided evidence sounds
 * stricter and is simply wrong: "how do I rotate an SSH host key" and "what are the steps to rotate
 * an SSH host key" are one question, and refusing them buys nothing but an extra API call. A
 * genuine depth request that only one side spells out — "how does HTTPS work" against "how does
 * HTTPS work at the packet level" — is the residue this guard does not cover, and is what a
 * [dev.kmemo.Verifier] is for.
 */
public class ScopeGuard(
    private val markers: Set<String> = Vocabulary.SCOPE_MARKERS,
) : MatchGuard {

    override val name: String get() = "scope"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryMarkers = markersIn(query)
        val candidateMarkers = markersIn(candidate)
        if (queryMarkers == candidateMarkers) return GuardVerdict.Accept
        if (queryMarkers.isEmpty() || candidateMarkers.isEmpty()) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "the answers asked for differ in format or depth: $queryMarkers vs $candidateMarkers",
        )
    }

    private fun markersIn(text: String): Set<String> =
        Text.tokens(text).filterTo(LinkedHashSet()) { it in markers }
}

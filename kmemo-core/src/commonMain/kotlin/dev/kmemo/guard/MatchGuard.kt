package dev.kmemo.guard

/** A guard's decision about one candidate match. */
public sealed interface GuardVerdict {

    /** The guard found nothing wrong. It is *not* a claim that the match is good. */
    public data object Accept : GuardVerdict

    /** The guard found a concrete reason these two prompts need different answers. */
    public data class Reject(public val reason: String) : GuardVerdict
}

/**
 * A veto on a candidate match that already cleared the similarity threshold.
 *
 * Guards exist because cosine similarity is the wrong tool for the last 5% of the decision.
 * `Convert 100 USD to EUR` and `Convert 250 USD to EUR` embed at roughly 0.99 with every mainstream
 * model — no threshold you can set will separate them, and serving the wrong one is a silently
 * incorrect answer rather than a slow one. A guard reads the two prompts as text and looks for
 * concrete evidence that the answers differ: a different number, a different unit, a flipped
 * comparison, a swapped entity.
 *
 * Guards are intentionally **asymmetric in cost**. A guard that wrongly rejects costs one API call,
 * the exact call you would have made without a cache. A guard that wrongly accepts returns a wrong
 * answer to a user. So a guard should reject on positive evidence, and [GuardVerdict.Accept] when
 * it has nothing to say — abstaining is always the right move over guessing.
 *
 * Implementations must be pure, fast (they run on every candidate) and thread-safe.
 */
public interface MatchGuard {

    /** Short stable identifier, surfaced in [dev.kmemo.CacheLookup.Miss.detail]. */
    public val name: String

    /**
     * Judges serving the response cached for [candidate] in answer to [query].
     *
     * @return [GuardVerdict.Reject] only with a concrete reason; [GuardVerdict.Accept] otherwise.
     */
    public fun evaluate(query: String, candidate: String): GuardVerdict
}

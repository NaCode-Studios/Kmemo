package dev.kmemo

/**
 * What the cache **would** have done for one prompt, at each threshold it was asked about.
 *
 * `ThresholdCalibrator` answers "which threshold is right?" but needs a labelled set, so the honest
 * answer to a team standing at the door is "measure it, on data you first have to build". That first
 * step, not the cache, is what stops a semantic cache going in front of production traffic.
 *
 * Shadow mode removes it. The cache runs the whole lookup against real traffic, **serves nothing**, and
 * reports the decision at every threshold in one pass, so the output is the team's own precision and
 * recall curve against their own questions rather than somebody else's corpus.
 */
public class ShadowReport(
    /** The scope the shadow lookup ran in. */
    public val scope: String,
    /** The prompt that was judged. */
    public val prompt: String,
    /** One decision per configured threshold, in the order they were configured. */
    public val decisions: List<ShadowDecision>,
) {
    override fun toString(): String = "ShadowReport(scope=$scope, decisions=${decisions.size})"
}

/** What one threshold would have decided, and why. */
public class ShadowDecision(
    /** The threshold this decision was judged at. */
    public val threshold: Double,
    /** Whether a candidate would have been served at [threshold]. */
    public val wouldHit: Boolean,
    /** Why nothing would have been served, or `null` when [wouldHit] is `true`. */
    public val reason: MissReason?,
    /** Similarity of the closest candidate considered, or `null` when the scope was empty. */
    public val bestSimilarity: Double?,
    /** The prompt that would have been served, or that was refused. `null` when the scope was empty. */
    public val matchedPrompt: String?,
    /** The guard that vetoed, when [reason] is [MissReason.REJECTED_BY_GUARD]. */
    public val guardName: String?,
) {
    override fun toString(): String =
        "ShadowDecision(threshold=$threshold, wouldHit=$wouldHit, reason=$reason)"
}

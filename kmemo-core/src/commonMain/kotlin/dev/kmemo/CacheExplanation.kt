package dev.kmemo

import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard

/**
 * A read-only trace of how [SemanticCache.explain] evaluated one prompt — the tool you reach for when
 * a hit you expected did not happen, or one you did not expect did.
 *
 * Unlike [SemanticCache.lookup], producing an explanation changes nothing: no counter in [CacheStats]
 * moves, no entry is marked recently-used, and the [Verifier] is never called. A diagnostic that
 * mutated the statistics you are trying to read, or made a model call, would be worse than useless.
 * What it reports is the threshold-and-guard decision — the layer you actually tune.
 *
 * ```kotlin
 * val why = cache.explain("Convert 250 USD to EUR")
 * println(why.decision)                     // REJECTED_BY_GUARD
 * why.candidates.first().rejectingGuards     // [numeric]
 * ```
 */
public class CacheExplanation(
    /** The prompt that was explained. */
    public val prompt: String,
    /** The scope it was evaluated in. */
    public val scope: String,
    /** The similarity threshold in force for this cache. */
    public val threshold: Double,
    /**
     * The nearest entries considered, best-first — at most `candidates` of them, and empty when the
     * scope holds nothing yet. Every guard is evaluated on every one of these, even the ones already
     * below the threshold, so you can see a guard verdict that a real lookup would never have reached.
     */
    public val candidates: List<CandidateTrace>,
) {
    /**
     * What a [SemanticCache.lookup] of this prompt would decide, up to — but not including — the verifier.
     *
     * `null` means it would be served (a hit). Otherwise it is the [MissReason] a lookup would report,
     * with one caveat: [MissReason.REJECTED_BY_VERIFIER] never appears here, because [explain][SemanticCache.explain]
     * does not run the [Verifier]. So a `null` here with a verifier configured means "the guards would
     * pass it; the verifier still gets the last word." Every other outcome matches a real lookup exactly.
     */
    public val decision: MissReason?
        get() {
            if (candidates.isEmpty()) return MissReason.EMPTY_SCOPE
            if (candidates.any { it.wouldServe }) return null
            // The nearest candidate that cleared the threshold is the one a lookup would have refused
            // first, and a lookup reports the first refusal — so its own reason is the answer, not
            // whichever reason happens to appear anywhere in the list.
            val refused = candidates.firstOrNull { it.aboveThreshold } ?: return MissReason.BELOW_THRESHOLD
            return if (refused.embedderMatches) MissReason.REJECTED_BY_GUARD else MissReason.EMBEDDER_MISMATCH
        }

    override fun toString(): String =
        "CacheExplanation(decision=$decision, candidates=${candidates.size}, threshold=$threshold)"
}

/**
 * One candidate entry and every guard's verdict on it, from a [CacheExplanation].
 *
 * The difference from a real lookup is that [guardVerdicts] holds *every* guard's verdict, not just
 * the first rejection. A lookup stops at the first guard that objects, because one veto is enough to
 * refuse; an explanation keeps going, because when you are tuning you want to know that three guards
 * objected and which three, not only that the first did.
 */
public class CandidateTrace(
    /** The cached prompt this candidate holds. */
    public val prompt: String,
    /** Its similarity to the query, in `[-1.0, 1.0]`. */
    public val similarity: Double,
    /** Whether [similarity] cleared the threshold. A guard verdict on a candidate below it is moot. */
    public val aboveThreshold: Boolean,
    /**
     * Every guard's verdict, in the order the guards run, keyed by [MatchGuard.name]. The guards are
     * evaluated in the same direction a lookup uses them — query against this cached prompt.
     */
    public val guardVerdicts: Map<String, GuardVerdict>,
    /**
     * Whether this entry was written by the [Embedder.identity] the cache is running now.
     *
     * `false` makes [similarity] a number about two different vector spaces, so read it as noise
     * rather than as a near miss: a lookup refuses such a candidate unscored and reports
     * [MissReason.EMBEDDER_MISMATCH]. The guard verdicts beside it were still computed, because they
     * read the prompts as text and the text is real even when the score is not.
     */
    public val embedderMatches: Boolean = true,
) {
    /** The guards that rejected this candidate, in guard order. Empty when every guard abstained. */
    public val rejectingGuards: List<String>
        get() = guardVerdicts.entries.filter { it.value is GuardVerdict.Reject }.map { it.key }

    /**
     * True when this candidate cleared the threshold, was written by the embedder now running, and no
     * guard rejected it.
     *
     * A configured [Verifier] could still refuse it at lookup time; [wouldServe] describes the
     * threshold-and-guard layer, which is where explanations live.
     */
    public val wouldServe: Boolean
        get() = aboveThreshold && embedderMatches && rejectingGuards.isEmpty()

    override fun toString(): String =
        "CandidateTrace(similarity=$similarity, aboveThreshold=$aboveThreshold, " +
            "embedderMatches=$embedderMatches, rejectingGuards=$rejectingGuards)"
}

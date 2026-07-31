package dev.kmemo

import kotlin.time.Duration

/**
 * Outcome of [SemanticCache.lookup].
 *
 * A semantic cache that only answers "hit or miss" is impossible to tune: when your hit rate is 4%
 * you need to know whether prompts are landing just under the threshold or being vetoed by a guard,
 * because the fix is opposite in each case. Every miss therefore carries the reason and the best
 * candidate that was considered.
 */
public sealed interface CacheLookup {

    /** A cached response was judged safe to serve. */
    public class Hit(
        /** The cached response. */
        public val response: String,
        /** The prompt this response was originally produced for — rarely identical to the query. */
        public val matchedPrompt: String,
        /** Similarity between query and [matchedPrompt], in `[-1.0, 1.0]`. */
        public val similarity: Double,
        /** Id of the entry that matched, for [SemanticCache.invalidate]. */
        public val entryId: String,
        /** How long ago the entry was written. */
        public val age: Duration,
        /** Metadata stored alongside the entry. */
        public val metadata: Map<String, String>,
    ) : CacheLookup {
        override fun toString(): String =
            "Hit(similarity=$similarity, age=$age, matchedPrompt=$matchedPrompt)"
    }

    /** No cached response was safe to serve. */
    public class Miss(
        /** Why nothing was served. */
        public val reason: MissReason,
        /** Similarity of the closest candidate considered, or `null` when the scope was empty. */
        public val bestSimilarity: Double?,
        /** Prompt of the closest candidate considered, or `null` when the scope was empty. */
        public val bestPrompt: String?,
        /** Human-readable explanation — for a guard rejection, which guard fired and why. */
        public val detail: String?,
    ) : CacheLookup {
        override fun toString(): String =
            "Miss(reason=$reason, bestSimilarity=$bestSimilarity, detail=$detail)"
    }
}

/** Why a [CacheLookup.Miss] happened. */
public enum class MissReason {
    /** Nothing has been cached in this scope yet. */
    EMPTY_SCOPE,

    /** The closest entry scored below the configured threshold. Lower it, or accept the miss. */
    BELOW_THRESHOLD,

    /**
     * A candidate cleared the threshold but a [dev.kmemo.guard.MatchGuard] vetoed it.
     * This is the cache doing its job: the embedding said "close enough", the guard found a
     * concrete reason the answers would differ.
     */
    REJECTED_BY_GUARD,

    /** A candidate cleared threshold and guards, but the configured [Verifier] rejected it. */
    REJECTED_BY_VERIFIER,
}

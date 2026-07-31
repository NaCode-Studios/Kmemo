package dev.kmemo

/**
 * Something a [SemanticCache] (or its [CacheStore]) did, delivered to a [CacheListener] the moment it
 * happens.
 *
 * The counters in [CacheStats] tell you the *totals*; events tell you the *stream*. A dashboard that
 * wants a hit-rate gauge reads [CacheStats]; a metrics exporter that wants a per-lookup latency
 * histogram, a log line per decision, or a live tap on evictions subscribes to these instead of
 * polling. Emitting is opt-in — a cache with no listeners builds no events and measures no timings, so
 * the default hot path is unchanged.
 *
 * Every event names the [scope] it happened in, so a subscriber can tag or filter by scope without
 * threading it separately.
 */
public sealed interface CacheEvent {

    /** The scope the event happened in. */
    public val scope: String

    /** A lookup that was served from the cache. */
    public class Hit(
        override val scope: String,
        /** The query prompt that was looked up. */
        public val prompt: String,
        /** The cached prompt whose response was served — rarely identical to [prompt]. */
        public val matchedPrompt: String,
        /** Similarity between [prompt] and [matchedPrompt], in `[-1.0, 1.0]`. */
        public val similarity: Double,
        /** Id of the entry that matched. */
        public val entryId: String,
        /** How long each stage of the lookup took. */
        public val timings: EventTimings,
    ) : CacheEvent {
        override fun toString(): String = "CacheEvent.Hit(scope=$scope, similarity=$similarity)"
    }

    /** A lookup that could not be served, with the same reason [CacheLookup.Miss] reports. */
    public class Miss(
        override val scope: String,
        /** The query prompt that was looked up. */
        public val prompt: String,
        /** Why nothing was served. */
        public val reason: MissReason,
        /** Similarity of the closest candidate considered, or `null` when the scope was empty. */
        public val bestSimilarity: Double?,
        /** Human-readable explanation — for a guard rejection, which guard fired and why. */
        public val detail: String?,
        /**
         * The [dev.kmemo.guard.MatchGuard.name] that vetoed the candidate, when [reason] is
         * [MissReason.REJECTED_BY_GUARD]; `null` for every other reason. Exposed as a field, not only
         * inside [detail], so a metrics adapter can tag by guard without parsing text.
         */
        public val guardName: String?,
        /** How long each stage of the lookup took. */
        public val timings: EventTimings,
    ) : CacheEvent {
        override fun toString(): String = "CacheEvent.Miss(scope=$scope, reason=$reason)"
    }

    /** A new entry was written, through `put`, `getOrPut`, or `warm`. */
    public class Write(
        override val scope: String,
        /** The prompt the entry was written for. */
        public val prompt: String,
        /** Id assigned to the new entry. */
        public val entryId: String,
    ) : CacheEvent {
        override fun toString(): String = "CacheEvent.Write(scope=$scope, entryId=$entryId)"
    }

    /**
     * A write the cache declined to make because a [CachePolicy] vetoed it.
     *
     * Distinct from [Miss] on purpose: nothing was looked up and nothing failed. The call that produced
     * the response returned it normally; the cache simply did not keep a copy. Counted in
     * [CacheStats.writesVetoed].
     */
    public class WriteVetoed(
        override val scope: String,
        /** The prompt whose entry was not written. */
        public val prompt: String,
        /** The [PolicyVerdict.Veto.reason] the policy gave, suitable as a low-cardinality metrics tag. */
        public val reason: String,
    ) : CacheEvent {
        override fun toString(): String = "CacheEvent.WriteVetoed(scope=$scope, reason=$reason)"
    }

    /**
     * The [Embedder] failed and [EmbedFailurePolicy.FALL_BACK_TO_COMPUTE] stepped aside, so the call ran
     * uncached.
     *
     * This is the one failure mode that otherwise leaves no trace. A rejected candidate produces a
     * [MissReason] and moves a counter; a fall-back produces neither, because it is not a miss — no
     * lookup ever happened. A team running with [EmbedFailurePolicy.FALL_BACK_TO_COMPUTE] and a flapping
     * embedder would watch its hit rate collapse with nothing naming the cause, and would reasonably
     * suspect the part that *is* instrumented, the guards. Counted in [CacheStats.degradedLookups].
     *
     * A [RetryingEmbedder] that exhausts its attempts arrives here too: retrying is transparent to the
     * cache, so the final throwable is what [cause] carries. There is no separate "retries exhausted"
     * event, because the cache cannot see that a retry happened at all.
     */
    public class Degraded(
        override val scope: String,
        /**
         * The prompts the failed call covered — one element for the single-prompt entry points, the
         * whole batch for [SemanticCache.getOrPutAll]. Never empty.
         */
        public val prompts: List<String>,
        /** Which entry point stepped aside. */
        public val operation: DegradedOperation,
        /** The policy that decided to step aside, always [EmbedFailurePolicy.FALL_BACK_TO_COMPUTE] today. */
        public val policy: EmbedFailurePolicy,
        /** The embedder failure that triggered it. */
        public val cause: Throwable,
    ) : CacheEvent {
        override fun toString(): String =
            "CacheEvent.Degraded(scope=$scope, operation=$operation, cause=${cause::class.simpleName})"
    }

    /**
     * A shadow-mode lookup: what the cache **would** have decided, at every configured threshold.
     *
     * Emitted instead of a [Hit] or a [Miss], because in shadow mode neither happened — nothing was
     * served. Subscribe to this to build your own precision and recall curve from live traffic before
     * committing to a threshold.
     */
    public class Shadow(
        /** The full report, one decision per configured threshold. */
        public val report: ShadowReport,
    ) : CacheEvent {
        override val scope: String get() = report.scope

        override fun toString(): String = "CacheEvent.Shadow(scope=$scope, thresholds=${report.decisions.size})"
    }

    /** An entry left the store, either evicted for capacity/memory or dropped past its TTL. */
    public class Eviction(
        override val scope: String,
        /** The prompt of the entry that left. */
        public val prompt: String,
        /** Id of the entry that left. */
        public val entryId: String,
        /** Whether it was evicted to make room or dropped for being expired. */
        public val cause: EvictionCause,
    ) : CacheEvent {
        override fun toString(): String = "CacheEvent.Eviction(scope=$scope, cause=$cause)"
    }
}

/**
 * Wall-clock nanoseconds spent in each stage of a single lookup, for latency metrics.
 *
 * A stage that did not run reports `0` — most obviously [verifierNanos] when no [Verifier] is
 * configured or no candidate reached it. [embedNanos] can be near-zero when a negative-cache hit
 * supplied the embedding, which is the honest number: no embedding call was made.
 */
public class EventTimings(
    /** Time in [Embedder.embed] for this lookup (near-zero when the negative cache supplied the vector). */
    public val embedNanos: Long,
    /** Time in [CacheStore.search] for this lookup. */
    public val searchNanos: Long,
    /** Total time in [Verifier.verify] for this lookup, across every candidate checked. `0` if none ran. */
    public val verifierNanos: Long,
) {
    override fun toString(): String =
        "EventTimings(embed=${embedNanos}ns, search=${searchNanos}ns, verifier=${verifierNanos}ns)"

    public companion object {
        /** All-zero timings, for events emitted off the measured lookup path. */
        public val NONE: EventTimings = EventTimings(0, 0, 0)
    }
}

/** Which entry point a [CacheEvent.Degraded] came from. */
public enum class DegradedOperation {
    /** [SemanticCache.getOrPut], including the typed overload that wraps it. */
    GET_OR_PUT,

    /** [SemanticCache.getOrPutAll]; the whole batch degraded together on one failed batch embed. */
    GET_OR_PUT_ALL,

    /** [SemanticCache.getOrPutStreaming]; the upstream flow was returned uncached. */
    GET_OR_PUT_STREAMING,
}

/** Why a [CacheEvent.Eviction] happened. */
public enum class EvictionCause {
    /** Dropped to stay within the store's entry-count bound. */
    CAPACITY,

    /** Dropped to stay within the store's memory (byte) bound. */
    MEMORY,

    /** Dropped for being past its TTL. */
    EXPIRED,

    /**
     * Dropped because a new entry says the same thing.
     *
     * Unlike the other three this is not the store running out of room: the entry was still live and
     * still correct. It was replaced because a cache that has answered one question in six phrasings
     * stores six copies of one answer and pays to score all six on every later lookup. Only ever
     * emitted when `SemanticCache(deduplicateWrites = …)` is set, and only for an entry the guards
     * agreed was interchangeable with the one replacing it.
     */
    NEAR_DUPLICATE,
}

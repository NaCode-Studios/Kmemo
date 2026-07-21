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

/** Why a [CacheEvent.Eviction] happened. */
public enum class EvictionCause {
    /** Dropped to stay within the store's entry-count bound. */
    CAPACITY,

    /** Dropped to stay within the store's memory (byte) bound. */
    MEMORY,

    /** Dropped for being past its TTL. */
    EXPIRED,
}

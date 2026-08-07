package dev.kmemo.otel

import dev.kmemo.CacheEvent
import dev.kmemo.CacheListener
import dev.kmemo.SemanticCache
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.DoubleHistogram
import io.opentelemetry.api.metrics.LongCounter
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import java.util.concurrent.TimeUnit

/**
 * Publishes a [SemanticCache]'s behaviour as OpenTelemetry metrics and spans.
 *
 * ```kotlin
 * val telemetry = KmemoTelemetry(openTelemetry)
 * val cache = SemanticCache(embedder, listeners = listOf(telemetry))
 * ```
 *
 * ### Why this exists beside `kmemo-micrometer`
 *
 * Micrometer is a good answer to a question the ecosystem has since standardised somewhere else. A
 * platform team asked what a cache emits expects OpenTelemetry, because that is what their collector
 * already speaks and their vendor already ingests, and Micrometer covers metrics only.
 *
 * ### The spans, and why they are built after the fact
 *
 * A lookup has stages with real latencies, already measured and already on `EventTimings`, and a span
 * per lookup with a child per stage answers "why was this request slow" in a way a counter cannot.
 *
 * A [CacheListener] is called **after** the lookup, so the spans here are recorded with explicit
 * timestamps rather than wrapped around live work. That is not a workaround, it is what makes them
 * correct: `onEvent` runs inline on the calling coroutine, so [Context.current] is the caller's
 * context, and the lookup appears under whatever span the caller was already in. A verifier call, a
 * model call inside somebody's request, shows up in their trace as one.
 *
 * The end timestamp is now and the start is now minus the stage total, so the durations are exact and
 * the placement on the timeline is within the listener's own overhead of the truth. Stages that did
 * not run report zero on the event and produce no child span, which is the honest report of an
 * embedding a negative cache supplied or a verifier nobody configured.
 *
 * ### The attributes are a proposal
 *
 * There is no OpenTelemetry semantic convention for a semantic cache, because there has been no
 * semantic cache worth writing one for. The attributes this module emits are named in
 * `docs/OTEL-CONVENTIONS.md` with the argument for each, as a proposal rather than as an
 * implementation detail. [Conventions] is the same list in code, so a caller building their own
 * exporter uses the same keys rather than inventing a second set.
 *
 * ### Cardinality
 *
 * Metrics are **not** tagged by cache scope, and neither is the tenant. Both are caller-defined and
 * routinely unbounded, one per tenant or per model version, and an unbounded attribute is how a
 * metrics bill blows up. Both are on the **span**, where per-request cardinality is the point and
 * sampling is the control. That split is the one design decision here worth arguing with.
 *
 * Thread-safe: OpenTelemetry instruments are, and this does nothing else.
 *
 * @param openTelemetry the configured SDK, or `OpenTelemetry.noop()` to disable everything at once.
 * @param spans whether to record spans. Metrics alone is a reasonable configuration for a service
 *   that samples traces aggressively.
 */
public class KmemoTelemetry @JvmOverloads constructor(
    openTelemetry: OpenTelemetry,
    private val spans: Boolean = true,
) : CacheListener {

    private val tracer: Tracer = openTelemetry.getTracer(INSTRUMENTATION_NAME, INSTRUMENTATION_VERSION)
    private val meter = openTelemetry.getMeter(INSTRUMENTATION_NAME)

    private val lookups: LongCounter = meter.counterBuilder(Conventions.METRIC_LOOKUPS)
        .setDescription("Lookups the cache decided, hits and misses together.")
        .setUnit("{lookup}")
        .build()

    private val writes: LongCounter = meter.counterBuilder(Conventions.METRIC_WRITES)
        .setDescription("Entries written, including those a policy later vetoed, tagged by outcome.")
        .setUnit("{entry}")
        .build()

    private val evictions: LongCounter = meter.counterBuilder(Conventions.METRIC_EVICTIONS)
        .setDescription("Entries that left the store, evicted for capacity or dropped past their TTL.")
        .setUnit("{entry}")
        .build()

    private val saved: DoubleHistogram = meter.histogramBuilder(Conventions.METRIC_SAVED)
        .setDescription("What one hit did not cost, in the currency the scope declares.")
        .setUnit("{currency}")
        .build()

    private val duration: DoubleHistogram = meter.histogramBuilder(Conventions.METRIC_DURATION)
        .setDescription("Time spent in one stage of one lookup.")
        .setUnit("s")
        .build()

    override fun onEvent(event: CacheEvent) {
        when (event) {
            is CacheEvent.Hit -> onHit(event)
            is CacheEvent.Miss -> onMiss(event)
            is CacheEvent.Write -> writes.add(1, Attributes.of(Conventions.OUTCOME, "written"))
            is CacheEvent.WriteVetoed -> writes.add(
                1,
                Attributes.builder()
                    .put(Conventions.OUTCOME, "vetoed")
                    .put(Conventions.VETO_REASON, event.reason)
                    .build(),
            )
            is CacheEvent.Eviction -> evictions.add(
                1,
                Attributes.of(Conventions.EVICTION_CAUSE, event.cause.name.lowercase()),
            )
            is CacheEvent.Degraded -> lookups.add(
                1,
                Attributes.builder()
                    .put(Conventions.OUTCOME, "degraded")
                    .put(Conventions.DEGRADED_OPERATION, event.operation.name.lowercase())
                    .build(),
            )
            is CacheEvent.EmbedderMismatch -> lookups.add(
                1,
                Attributes.builder()
                    .put(Conventions.OUTCOME, "embedder_mismatch")
                    .put(Conventions.EMBEDDER_EXPECTED, event.expected)
                    .put(Conventions.EMBEDDER_FOUND, event.found)
                    .build(),
            )
            is CacheEvent.Shadow -> Unit
        }
    }

    private fun onHit(event: CacheEvent.Hit) {
        lookups.add(1, Attributes.of(Conventions.OUTCOME, "hit"))
        recordStages(event.timings.embedNanos, event.timings.searchNanos, event.timings.verifierNanos)
        event.currency?.let {
            saved.record(event.saved, Attributes.of(Conventions.CURRENCY, it))
        }
        if (!spans) return

        val attributes = Attributes.builder()
            .put(Conventions.SYSTEM, SYSTEM)
            .put(Conventions.SCOPE, event.scope)
            .put(Conventions.HIT, true)
            .put(Conventions.SIMILARITY, event.similarity)
            .put(Conventions.ENTRY_ID, event.entryId)
            .build()
        recordLookupSpan("hit", attributes, event.timings.embedNanos, event.timings.searchNanos,
            event.timings.verifierNanos, error = null)
    }

    private fun onMiss(event: CacheEvent.Miss) {
        val outcome = Attributes.builder()
            .put(Conventions.OUTCOME, "miss")
            .put(Conventions.MISS_REASON, event.reason.name.lowercase())
            .apply { event.guardName?.let { put(Conventions.GUARD, it) } }
            .build()
        lookups.add(1, outcome)
        recordStages(event.timings.embedNanos, event.timings.searchNanos, event.timings.verifierNanos)
        if (!spans) return

        val attributes = Attributes.builder()
            .put(Conventions.SYSTEM, SYSTEM)
            .put(Conventions.SCOPE, event.scope)
            .put(Conventions.HIT, false)
            .put(Conventions.MISS_REASON, event.reason.name.lowercase())
            .apply {
                event.guardName?.let { put(Conventions.GUARD, it) }
                event.bestSimilarity?.let { put(Conventions.SIMILARITY, it) }
            }
            .build()
        recordLookupSpan("miss", attributes, event.timings.embedNanos, event.timings.searchNanos,
            event.timings.verifierNanos, error = event.detail)
    }

    private fun recordStages(embedNanos: Long, searchNanos: Long, verifierNanos: Long) {
        for ((stage, nanos) in stages(embedNanos, searchNanos, verifierNanos)) {
            duration.record(nanos / NANOS_PER_SECOND, Attributes.of(Conventions.STAGE, stage))
        }
    }

    /**
     * One span for the lookup, with a child per stage that ran, placed on the timeline by working
     * backwards from now.
     *
     * The parent's start is now minus the sum of the stages, which is a lower bound on the lookup's
     * real duration: the guard chain and the store's own bookkeeping are not timed and so are not
     * claimed. A span that overstated the duration would be worse than one that understates it, since
     * the number a reader takes from a trace is the one they act on.
     */
    private fun recordLookupSpan(
        outcome: String,
        attributes: Attributes,
        embedNanos: Long,
        searchNanos: Long,
        verifierNanos: Long,
        error: String?,
    ) {
        val stages = stages(embedNanos, searchNanos, verifierNanos)
        val end = System.nanoTime()
        val endEpochNanos = epochNanos()
        val total = stages.sumOf { it.second }

        val parent = tracer.spanBuilder(SPAN_NAME)
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(Context.current())
            .setStartTimestamp(endEpochNanos - total, TimeUnit.NANOSECONDS)
            .setAllAttributes(attributes)
            .startSpan()
        parent.setAttribute(Conventions.OUTCOME, outcome)
        if (error != null && outcome != "hit") parent.setStatus(StatusCode.UNSET, error)

        var cursor = endEpochNanos - total
        for ((stage, nanos) in stages) {
            childSpan(parent, stage, cursor, nanos)
            cursor += nanos
        }
        parent.end(endEpochNanos, TimeUnit.NANOSECONDS)
        check(end <= System.nanoTime()) { "the monotonic clock went backwards" }
    }

    private fun childSpan(parent: Span, stage: String, startEpochNanos: Long, nanos: Long) {
        tracer.spanBuilder("$SPAN_NAME $stage")
            .setSpanKind(SpanKind.INTERNAL)
            .setParent(Context.current().with(parent))
            .setAttribute(Conventions.SYSTEM, SYSTEM)
            .setAttribute(Conventions.STAGE, stage)
            .setStartTimestamp(startEpochNanos, TimeUnit.NANOSECONDS)
            .startSpan()
            .end(startEpochNanos + nanos, TimeUnit.NANOSECONDS)
    }

    /** The stages that actually ran, in the order the lookup runs them. A zero means it did not. */
    private fun stages(embedNanos: Long, searchNanos: Long, verifierNanos: Long): List<Pair<String, Long>> =
        listOf(
            Conventions.STAGE_EMBED to embedNanos,
            Conventions.STAGE_SEARCH to searchNanos,
            Conventions.STAGE_VERIFY to verifierNanos,
        ).filter { it.second > 0 }

    private fun epochNanos(): Long = System.currentTimeMillis() * NANOS_PER_MILLI

    private companion object {
        private const val INSTRUMENTATION_NAME = "dev.kmemo"
        private const val INSTRUMENTATION_VERSION = "2.3.0"
        private const val SYSTEM = "kmemo"
        private const val SPAN_NAME = "cache.lookup"
        private const val NANOS_PER_SECOND = 1_000_000_000.0
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * The attribute and instrument names this module emits, as a **proposed** semantic convention.
 *
 * OpenTelemetry's value is that two libraries doing the same thing produce comparable telemetry, and
 * that only happens when the names are agreed rather than invented per project. There is no
 * convention for a semantic cache, so these are named here, argued in `docs/OTEL-CONVENTIONS.md`, and
 * exposed as constants so that a caller writing their own exporter, on a platform this module does
 * not reach, emits the same names rather than a second set.
 *
 * They sit under `gen_ai.cache.*` rather than under a namespace of their own. A semantic cache is not
 * a database cache: it exists in front of a model call, its hit is judged by meaning rather than by
 * key equality, and the thing it saves is a token bill. `gen_ai.*` is where the rest of that story is
 * already being written.
 */
public object Conventions {

    /** `gen_ai.cache.system`: which cache produced this. Constant per library. */
    public val SYSTEM: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.system")

    /** `gen_ai.cache.scope`: the partition the lookup ran in. Span only: it is unbounded. */
    public val SCOPE: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.scope")

    /** `gen_ai.cache.hit`: whether an answer was served. The one attribute every cache needs. */
    public val HIT: AttributeKey<Boolean> = AttributeKey.booleanKey("gen_ai.cache.hit")

    /** `gen_ai.cache.outcome`: hit, miss, degraded, embedder_mismatch, written, vetoed. */
    public val OUTCOME: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.outcome")

    /**
     * `gen_ai.cache.miss.reason`: why nothing was served.
     *
     * The attribute that makes a semantic cache tunable. A hit rate of 4% has opposite fixes for a
     * threshold miss and a guard rejection, and a counter that does not split them is a dashboard
     * nobody can act on.
     */
    public val MISS_REASON: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.miss.reason")

    /**
     * `gen_ai.cache.guard`: which check refused the candidate.
     *
     * Low cardinality by construction: a guard chain is a fixed list. It is the attribute that turns
     * "the cache is rejecting things" into "the numeric guard is rejecting things", which is a
     * different conversation.
     */
    public val GUARD: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.guard")

    /** `gen_ai.cache.similarity`: the score the decision was taken at. Span only: continuous. */
    public val SIMILARITY: AttributeKey<Double> = AttributeKey.doubleKey("gen_ai.cache.similarity")

    /** `gen_ai.cache.entry.id`: which stored entry was served. Span only: unbounded by definition. */
    public val ENTRY_ID: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.entry.id")

    /** `gen_ai.cache.stage`: embed, search or verify. What makes a latency histogram readable. */
    public val STAGE: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.stage")

    /** `gen_ai.cache.embedder.expected`: the embedding identity this cache is running. */
    public val EMBEDDER_EXPECTED: AttributeKey<String> =
        AttributeKey.stringKey("gen_ai.cache.embedder.expected")

    /** `gen_ai.cache.embedder.found`: the identity recorded on a refused entry. */
    public val EMBEDDER_FOUND: AttributeKey<String> =
        AttributeKey.stringKey("gen_ai.cache.embedder.found")

    /** `gen_ai.cache.veto.reason`: why a write was not made. Caller-defined and meant to be bounded. */
    public val VETO_REASON: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.veto.reason")

    /** `gen_ai.cache.eviction.cause`: evicted for room, or dropped past its TTL. */
    public val EVICTION_CAUSE: AttributeKey<String> =
        AttributeKey.stringKey("gen_ai.cache.eviction.cause")

    /** `gen_ai.cache.degraded.operation`: which entry point ran uncached after an embedder failure. */
    public val DEGRADED_OPERATION: AttributeKey<String> =
        AttributeKey.stringKey("gen_ai.cache.degraded.operation")

    /** `gen_ai.cache.currency`: the unit a saving is in. A number without its unit is not a measurement. */
    public val CURRENCY: AttributeKey<String> = AttributeKey.stringKey("gen_ai.cache.currency")

    /** `gen_ai.cache.lookups`: lookups decided, split by outcome. */
    public const val METRIC_LOOKUPS: String = "gen_ai.cache.lookups"

    /** `gen_ai.cache.writes`: entries written or refused, split by outcome. */
    public const val METRIC_WRITES: String = "gen_ai.cache.writes"

    /** `gen_ai.cache.evictions`: entries that left the store. */
    public const val METRIC_EVICTIONS: String = "gen_ai.cache.evictions"

    /** `gen_ai.cache.duration`: seconds spent in one stage of one lookup. */
    public const val METRIC_DURATION: String = "gen_ai.cache.duration"

    /** `gen_ai.cache.saved`: what one hit did not cost, in the declared currency. */
    public const val METRIC_SAVED: String = "gen_ai.cache.saved"

    /** The stage a lookup spends time in: the embedding call. */
    public const val STAGE_EMBED: String = "embed"

    /** The stage a lookup spends time in: the store search. */
    public const val STAGE_SEARCH: String = "search"

    /** The stage a lookup spends time in: the optional verifier, which is itself a model call. */
    public const val STAGE_VERIFY: String = "verify"
}

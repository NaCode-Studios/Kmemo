package dev.kmemo.micrometer

import dev.kmemo.CacheEvent
import dev.kmemo.CacheListener
import dev.kmemo.EvictionCause
import dev.kmemo.MissReason
import dev.kmemo.SemanticCache
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Publishes a [SemanticCache]'s behaviour to a Micrometer registry — so the cache shows up in the same
 * Prometheus / Datadog / CloudWatch dashboards as everything else your service runs.
 *
 * It is both a [MeterBinder] (register it with your registry) and a [CacheListener] (give it to the
 * cache), and the two halves are wired together by you:
 *
 * ```kotlin
 * val metrics = KmemoMetrics(tags = Tags.of("cache", "faq"))
 * val cache = SemanticCache(embedder, listeners = listOf(metrics))
 * metrics.bindTo(registry) // Spring Boot Actuator calls this for you
 * ```
 *
 * Every meter is driven by the [CacheEvent] stream, which is exact: the cache emits one event per
 * counted lookup, so the counters here equal [dev.kmemo.CacheStats] to the event. Events that arrive
 * **before** [bindTo] are ignored — metrics begin the moment you bind, which for a service is startup,
 * before traffic. Meters registered:
 *
 * | Meter | Type | Tags | Meaning |
 * | --- | --- | --- | --- |
 * | `kmemo.cache.lookups` | counter | — | total lookups (hits + misses) |
 * | `kmemo.cache.hits` | counter | — | lookups served from cache |
 * | `kmemo.cache.misses` | counter | `reason` | misses, split by [MissReason] |
 * | `kmemo.cache.guard.rejections` | counter | `guard` | guard rejections, split by guard name |
 * | `kmemo.cache.writes` | counter | — | entries written |
 * | `kmemo.cache.evictions` | counter | `cause` | evictions, split by [EvictionCause] (needs a store listener) |
 * | `kmemo.cache.hit.ratio` | gauge | — | hits / lookups |
 * | `kmemo.cache.embed` | timer | — | [dev.kmemo.Embedder] latency per lookup |
 * | `kmemo.cache.search` | timer | — | [dev.kmemo.CacheStore] search latency per lookup |
 * | `kmemo.cache.verify` | timer | — | [dev.kmemo.Verifier] latency, when one runs |
 *
 * Eviction counters only move if the *store* is also given this instance as its listener (evictions
 * are the store's business); with `InMemoryStore(listener = metrics)` they light up too.
 *
 * ### Cardinality
 *
 * Meters here are **not** tagged by cache scope. Scope is caller-defined and often unbounded (one per
 * tenant, per user, per model-version), and an unbounded tag is how a metrics bill or a Prometheus
 * head-block blows up. The scope is on every [CacheEvent] for anyone who wants to build a deliberately
 * bounded per-scope meter with their own [CacheListener]; this adapter stays safe by default.
 *
 * Thread-safe: Micrometer meters are, and the listener does nothing else.
 *
 * @param tags base tags added to every meter, e.g. the cache's name when a service runs several.
 */
public class KmemoMetrics @JvmOverloads constructor(
    tags: Iterable<Tag> = emptyList(),
) : MeterBinder, CacheListener {

    private val baseTags: Tags = Tags.of(tags)

    // Set once by bindTo. Volatile so the listener thread sees the meters the binding thread created;
    // null until bound, which is how early events are dropped rather than NPE'd.
    @Volatile
    private var meters: Meters? = null

    override fun bindTo(registry: MeterRegistry) {
        meters = Meters(registry, baseTags)
    }

    override fun onEvent(event: CacheEvent) {
        val m = meters ?: return
        when (event) {
            is CacheEvent.Hit -> {
                m.lookups.increment()
                m.hits.increment()
                recordTimings(m, event.timings)
            }
            is CacheEvent.Miss -> {
                m.lookups.increment()
                m.missReason(event.reason).increment()
                event.guardName?.let { m.guardRejection(it).increment() }
                recordTimings(m, event.timings)
            }
            is CacheEvent.Write -> m.writes.increment()
            is CacheEvent.Eviction -> m.eviction(event.cause).increment()
        }
    }

    private fun recordTimings(m: Meters, timings: dev.kmemo.EventTimings) {
        m.embed.record(timings.embedNanos, TimeUnit.NANOSECONDS)
        m.search.record(timings.searchNanos, TimeUnit.NANOSECONDS)
        // Only a lookup where a verifier actually ran gets a verify sample — otherwise the timer would
        // fill with zero-duration samples for the vast majority of lookups that never reach one.
        if (timings.verifierNanos > 0) m.verify.record(timings.verifierNanos, TimeUnit.NANOSECONDS)
    }

    /** The concrete meters, created against one registry when [bindTo] runs. */
    private class Meters(private val registry: MeterRegistry, private val tags: Tags) {
        val lookups: Counter = counter("kmemo.cache.lookups", "Total cache lookups (hits + misses).")
        val hits: Counter = counter("kmemo.cache.hits", "Lookups served from the cache.")
        val writes: Counter = counter("kmemo.cache.writes", "Entries written to the cache.")

        val embed: Timer = timer("kmemo.cache.embed", "Time spent embedding the prompt, per lookup.")
        val search: Timer = timer("kmemo.cache.search", "Time spent searching the store, per lookup.")
        val verify: Timer = timer("kmemo.cache.verify", "Time spent in the verifier, when one runs.")

        private val missReasons = ConcurrentHashMap<MissReason, Counter>()
        private val evictionCauses = ConcurrentHashMap<EvictionCause, Counter>()
        private val guardRejections = ConcurrentHashMap<String, Counter>()

        init {
            Gauge.builder("kmemo.cache.hit.ratio") { hitRatio() }
                .tags(tags)
                .description("Fraction of lookups served from the cache.")
                .register(registry)
            // Pre-register the fixed dimensions so a reason or cause that has not happened yet reads 0
            // rather than being absent — an alert on a rare miss reason needs the series to exist.
            MissReason.entries.forEach { missReason(it) }
            EvictionCause.entries.forEach { eviction(it) }
        }

        private fun hitRatio(): Double {
            val total = lookups.count()
            return if (total == 0.0) 0.0 else hits.count() / total
        }

        fun missReason(reason: MissReason): Counter = missReasons.getOrPut(reason) {
            Counter.builder("kmemo.cache.misses")
                .tags(tags)
                .tag("reason", reason.name.lowercase())
                .description("Cache misses, split by reason.")
                .register(registry)
        }

        fun eviction(cause: EvictionCause): Counter = evictionCauses.getOrPut(cause) {
            Counter.builder("kmemo.cache.evictions")
                .tags(tags)
                .tag("cause", cause.name.lowercase())
                .description("Entries removed by the store, split by cause.")
                .register(registry)
        }

        fun guardRejection(guard: String): Counter = guardRejections.getOrPut(guard) {
            Counter.builder("kmemo.cache.guard.rejections")
                .tags(tags)
                .tag("guard", guard)
                .description("Guard rejections, split by the guard that fired.")
                .register(registry)
        }

        private fun counter(name: String, description: String): Counter =
            Counter.builder(name).tags(tags).description(description).register(registry)

        private fun timer(name: String, description: String): Timer =
            Timer.builder(name).tags(tags).description(description).register(registry)
    }
}

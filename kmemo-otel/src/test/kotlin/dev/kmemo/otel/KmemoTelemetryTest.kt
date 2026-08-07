package dev.kmemo.otel

import dev.kmemo.CacheEvent
import dev.kmemo.EventTimings
import dev.kmemo.MissReason
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the adapter emits, read back from an in-memory SDK.
 *
 * The assertions are about names and shape rather than about values, because the names are the whole
 * proposal: an attribute somebody renames is an attribute that stops joining with everybody else's.
 */
class KmemoTelemetryTest {

    private val spans = InMemorySpanExporter.create()
    private val metrics = InMemoryMetricReader.create()
    private val sdk = OpenTelemetrySdk.builder()
        .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(spans)).build())
        .setMeterProvider(SdkMeterProvider.builder().registerMetricReader(metrics).build())
        .build()

    private val telemetry = KmemoTelemetry(sdk)

    @Test
    fun `a hit is one lookup, one span, and a child per stage that ran`() {
        telemetry.onEvent(
            CacheEvent.Hit(
                scope = "default",
                prompt = "how do I reverse a list in python",
                matchedPrompt = "python list reverse",
                similarity = 0.97,
                entryId = "e1",
                timings = EventTimings(embedNanos = 5_000_000, searchNanos = 1_000_000, verifierNanos = 0),
                saved = 0.002,
                currency = "USD",
            ),
        )

        val finished = spans.finishedSpanItems
        assertEquals(3, finished.size, "one lookup span and one child per stage that ran")
        val parent = finished.single { it.name == "cache.lookup" }
        assertEquals("hit", parent.attributes.get(Conventions.OUTCOME))
        assertEquals(true, parent.attributes.get(Conventions.HIT))
        assertEquals("kmemo", parent.attributes.get(Conventions.SYSTEM))
        assertEquals(0.97, parent.attributes.get(Conventions.SIMILARITY))
        assertTrue(finished.any { it.name == "cache.lookup embed" })
        assertTrue(finished.any { it.name == "cache.lookup search" })
        assertTrue(finished.none { it.name == "cache.lookup verify" }, "a stage that did not run is not a span")

        val names = metrics.collectAllMetrics().map { it.name }.toSet()
        assertTrue(Conventions.METRIC_LOOKUPS in names)
        assertTrue(Conventions.METRIC_DURATION in names)
        assertTrue(Conventions.METRIC_SAVED in names)
    }

    @Test
    fun `a guard rejection names the guard on the span and on the counter`() {
        telemetry.onEvent(
            CacheEvent.Miss(
                scope = "default",
                prompt = "convert 100 usd to eur",
                reason = MissReason.REJECTED_BY_GUARD,
                bestSimilarity = 0.99,
                detail = "numeric: numbers differ",
                guardName = "numeric",
                timings = EventTimings(embedNanos = 4_000_000, searchNanos = 900_000, verifierNanos = 0),
            ),
        )

        val parent = spans.finishedSpanItems.single { it.name == "cache.lookup" }
        assertEquals("miss", parent.attributes.get(Conventions.OUTCOME))
        assertEquals("rejected_by_guard", parent.attributes.get(Conventions.MISS_REASON))
        assertEquals("numeric", parent.attributes.get(Conventions.GUARD))

        val lookups = metrics.collectAllMetrics().single { it.name == Conventions.METRIC_LOOKUPS }
        val point = lookups.longSumData.points.single()
        assertEquals("numeric", point.attributes.get(Conventions.GUARD))
    }

    /** Metrics without traces is a real configuration for a service that samples aggressively. */
    @Test
    fun `spans can be turned off without losing the metrics`() {
        KmemoTelemetry(sdk, spans = false).onEvent(
            CacheEvent.Miss(
                scope = "default",
                prompt = "anything",
                reason = MissReason.BELOW_THRESHOLD,
                bestSimilarity = 0.4,
                detail = null,
                guardName = null,
                timings = EventTimings(embedNanos = 1_000_000, searchNanos = 1, verifierNanos = 0),
            ),
        )
        assertTrue(spans.finishedSpanItems.isEmpty(), "no span was asked for")
        assertTrue(metrics.collectAllMetrics().any { it.name == Conventions.METRIC_LOOKUPS })
    }
}

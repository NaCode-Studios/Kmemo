package dev.kmemo

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A [CacheListener] that republishes events as a [Flow] you can `collect`.
 *
 * The bridge from the cache's synchronous, inline callback to idiomatic coroutine consumption:
 *
 * ```kotlin
 * val events = CacheEvents()
 * val cache = SemanticCache(embedder, listeners = listOf(events))
 *
 * scope.launch {
 *     events.events.collect { event -> /* ship it somewhere */ }
 * }
 * ```
 *
 * Delivery is **best-effort and non-blocking**: the underlying [MutableSharedFlow] has a bounded
 * buffer and drops the **oldest** buffered event when a subscriber cannot keep up, so a slow (or
 * absent) collector can never stall a lookup. This is the right trade-off for telemetry — losing an
 * event under load is fine, adding latency to the request path is not. Size the buffer for your burst
 * with [bufferCapacity], or subscribe from a fast consumer that offloads its own work.
 *
 * Events published before anyone subscribes are dropped (`replay = 0`): a subscriber sees events from
 * the moment it starts collecting, not the cache's history. Safe to register on more than one cache.
 *
 * @param bufferCapacity how many events to hold for a lagging subscriber before dropping the oldest.
 */
public class CacheEvents(bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY) : CacheListener {

    init {
        require(bufferCapacity > 0) { "bufferCapacity must be positive, was $bufferCapacity" }
    }

    private val _events = MutableSharedFlow<CacheEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** The live stream of events. Cold until collected; a collector sees only events emitted while it runs. */
    public val events: SharedFlow<CacheEvent> = _events.asSharedFlow()

    override fun onEvent(event: CacheEvent) {
        // tryEmit never suspends; with DROP_OLDEST it always succeeds, so the hot path never blocks
        // on a slow subscriber.
        _events.tryEmit(event)
    }

    public companion object {
        /** Buffered events held for a lagging subscriber before the oldest is dropped. */
        public const val DEFAULT_BUFFER_CAPACITY: Int = 256
    }
}

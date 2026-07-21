package dev.kmemo

/**
 * A sink for [CacheEvent]s, called inline the moment each one happens.
 *
 * This is the zero-dependency observability primitive the metrics and logging adapters are built on:
 * `kmemo-micrometer` is a listener that records meters, `kmemo-slf4j` is a listener that writes log
 * lines, and [CacheEvents] is a listener that republishes to a [kotlinx.coroutines.flow.Flow].
 *
 * ### Contract
 *
 * [onEvent] runs **on the calling coroutine, inside the lookup**, so it must be **fast and
 * non-blocking** — record a number, offer to a buffer, return. Do not do I/O or call back into the
 * cache from it. It must also be **thread-safe**: lookups run concurrently, so events arrive
 * concurrently. An exception thrown from [onEvent] is caught and swallowed by [SemanticCache] — a
 * broken listener must never take a lookup down with it — so a listener that needs to know about its
 * own failures has to catch them itself.
 */
public fun interface CacheListener {

    /** Handles one [event]. Must be fast, thread-safe, and must not throw (thrown errors are dropped). */
    public fun onEvent(event: CacheEvent)
}

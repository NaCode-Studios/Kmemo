package dev.kmemo.tck

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A [Clock] that only moves when a test tells it to, so TTL tests never sleep.
 *
 * A store under test must use this clock as its time source (see [CacheStoreContract.createStore]),
 * which is what lets one TTL test drive expiry deterministically across an in-memory map, a Redis
 * key, or a Postgres `expires_at` column — no real waiting, no flakiness.
 */
public class FakeClock(
    private var current: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock {

    override fun now(): Instant = current

    /** Moves the clock forward by [duration]. */
    public fun advance(duration: Duration) {
        current += duration
    }
}

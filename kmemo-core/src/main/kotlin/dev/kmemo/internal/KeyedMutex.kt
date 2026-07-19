package dev.kmemo.internal

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Serializes work per key, holding a lock only while someone is actually using it.
 *
 * A plain `Map<String, Mutex>` leaks: every distinct prompt ever seen leaves a mutex behind, and a
 * cache's whole job is to see a great many distinct prompts. Striping into a fixed array fixes the
 * leak and creates a worse problem — unrelated prompts collide and serialize behind each other,
 * which for work measured in seconds of model latency is a throughput cliff.
 *
 * So entries are reference-counted and removed when the last waiter leaves. The bookkeeping runs
 * under [NonCancellable] because it lives in a `finally`, and a suspending cleanup in a cancelled
 * coroutine would otherwise be skipped — leaking the very entry it exists to remove.
 */
internal class KeyedMutex {

    private class Holder {
        val mutex = Mutex()
        var waiters = 0
    }

    private val guard = Mutex()
    private val holders = HashMap<String, Holder>()

    suspend fun <T> withKeyLock(key: String, block: suspend () -> T): T {
        val holder = guard.withLock { holders.getOrPut(key) { Holder() }.also { it.waiters++ } }
        try {
            return holder.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                guard.withLock {
                    if (--holder.waiters == 0) holders.remove(key)
                }
            }
        }
    }

    /** Keys currently held or waited on. For tests: it must return to zero. */
    suspend fun size(): Int = guard.withLock { holders.size }
}

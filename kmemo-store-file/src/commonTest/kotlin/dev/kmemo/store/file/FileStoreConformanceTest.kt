package dev.kmemo.store.file

import dev.kmemo.CacheStore
import dev.kmemo.tck.CacheStoreContract
import kotlin.time.Duration

/**
 * The shared store contract, run against [FileStore] on **every target this module claims**.
 *
 * That is the point of the module and the reason the contract suite became multiplatform to carry it. A
 * store that is conformant on the JVM and untested on iOS is a store that will serve a wrong answer on
 * a phone first, and a phone is exactly where this store is for.
 *
 * Each call to [createStore] gets its own journal, because a file is state that outlives an object and
 * a suite that shared one path between tests would be measuring the previous test.
 */
class FileStoreConformanceTest : CacheStoreContract() {

    override fun createStore(ttl: Duration?): CacheStore =
        FileStore(path = journalPath("conformance"), ttl = ttl, clock = clock)
}

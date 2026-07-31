package dev.kmemo.internal

/**
 * A map that remembers which key was used least recently, on every platform.
 *
 * The JVM has this built in: `LinkedHashMap(capacity, loadFactor, accessOrder = true)` reorders itself
 * on every read. That third argument does not exist in the common standard library, and it is what
 * three of this cache's layers are built on — the store's eviction, the exact-match layer, and the
 * verifier's memo.
 *
 * The portable version is the same idea done by hand: keep an insertion-ordered map, and on a *use*,
 * remove the key and put it back so it lands at the end. Iteration order is therefore
 * least-recently-used first, and [eldest] is the entry to drop.
 *
 * **Reading through [get] counts as a use; reading through [peek] does not.** That distinction is
 * load-bearing rather than a nicety: [dev.kmemo.store.InMemoryStore] scans every entry in the scope on
 * every search, and if scanning counted as use, every search would reset the whole eviction order and
 * the cache would evict at random. The scan uses [entries], which does not reorder.
 *
 * Not thread-safe. Every caller holds a mutex already, and adding a second lock inside would be a lock
 * held under a lock.
 */
internal class LruMap<K, V>(initialCapacity: Int = 16) {

    private val backing = LinkedHashMap<K, V>(initialCapacity)

    val size: Int get() = backing.size

    val keys: Set<K> get() = backing.keys

    val values: Collection<V> get() = backing.values

    /** Insertion-ordered, least recently used first. Iterating does **not** count as use. */
    val entries: Set<Map.Entry<K, V>> get() = backing.entries

    fun isEmpty(): Boolean = backing.isEmpty()

    /** The value for [key], counting as a use: it moves to the back of the eviction queue. */
    fun get(key: K): V? {
        val value = backing.remove(key) ?: return null
        backing[key] = value
        return value
    }

    /** The value for [key] without touching the eviction order. */
    fun peek(key: K): V? = backing[key]

    operator fun contains(key: K): Boolean = backing.containsKey(key)

    /** Writes [value], as the most recently used entry. Returns whatever [key] held before. */
    fun put(key: K, value: V): V? {
        val previous = backing.remove(key)
        backing[key] = value
        return previous
    }

    fun remove(key: K): V? = backing.remove(key)

    /** Drops every entry [predicate] accepts, and returns how many went. Does not reorder. */
    fun removeAll(predicate: (K, V) -> Boolean): Int {
        val doomed = backing.entries.filter { predicate(it.key, it.value) }.map { it.key }
        for (key in doomed) backing.remove(key)
        return doomed.size
    }

    fun clear() {
        backing.clear()
    }

    /** The least recently used key, or `null` when empty. The one to evict. */
    fun eldest(): K? = backing.keys.firstOrNull()
}

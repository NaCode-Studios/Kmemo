package dev.kmemo.store.hnsw

import dev.kmemo.Vectors
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.ln

/**
 * A compact HNSW (Hierarchical Navigable Small World) graph over unit-normalized vectors.
 *
 * This is deliberately a *candidate generator*, not the whole store: [add] and [search] work in terms
 * of caller ids, [search] returns ids ranked by proximity, and [HnswStore] rescores those candidates
 * exactly and applies scope/TTL. Approximation therefore only affects recall, never correctness — and
 * the exact [dev.kmemo.store.InMemoryStore] remains the reference.
 *
 * Not thread-safe; [HnswStore] serializes access under a mutex.
 *
 * @param m neighbours kept per node per layer (layer 0 keeps `2*m`); the classic HNSW `M`.
 * @param efConstruction candidate-list width while inserting — higher builds a better graph, slower.
 * @param efSearch default candidate-list width while querying — higher recalls more, slower.
 * @param seed level-assignment RNG seed; fixed by default so a rebuilt index is reproducible.
 */
internal class HnswIndex(
    private val m: Int = 16,
    private val efConstruction: Int = 200,
    private val efSearch: Int = 64,
    seed: Long = 42L,
) {
    private val mMax0 = m * 2
    private val levelMultiplier = 1.0 / ln(m.toDouble())
    private val random = Random(seed)

    private val ids = ArrayList<String>()
    private val vectors = ArrayList<FloatArray>()

    /** `links[node][layer]` = neighbour node ids of `node` at `layer`. */
    private val links = ArrayList<Array<MutableList<Int>>>()

    private var entryPoint = -1
    private var topLayer = -1

    fun size(): Int = ids.size

    fun add(id: String, vector: FloatArray) {
        val node = ids.size
        val layer = randomLevel()
        ids.add(id)
        vectors.add(vector)
        links.add(Array(layer + 1) { ArrayList() })

        if (entryPoint == -1) {
            entryPoint = node
            topLayer = layer
            return
        }

        // Descend greedily from the top down to just above the new node's layer.
        var current = entryPoint
        var lc = topLayer
        while (lc > layer) {
            current = greedyDescend(vector, current, lc)
            lc--
        }

        // Then, from the new node's top layer down to 0, find neighbours and wire them both ways.
        var entryPoints = listOf(current)
        lc = minOf(layer, topLayer)
        while (lc >= 0) {
            val candidates = searchLayer(vector, entryPoints, efConstruction, lc)
            val neighbours = candidates.take(m)
            val maxLinks = if (lc == 0) mMax0 else m
            for (neighbour in neighbours) {
                links[node][lc].add(neighbour)
                links[neighbour][lc].add(node)
                if (links[neighbour][lc].size > maxLinks) {
                    links[neighbour][lc] = nearest(vectors[neighbour], links[neighbour][lc], maxLinks).toMutableList()
                }
            }
            entryPoints = candidates
            lc--
        }

        if (layer > topLayer) {
            entryPoint = node
            topLayer = layer
        }
    }

    /** Returns up to [k] stored ids closest to [query], nearest first. */
    fun search(query: FloatArray, k: Int): List<String> {
        if (entryPoint == -1 || k <= 0) return emptyList()
        var current = entryPoint
        var lc = topLayer
        while (lc > 0) {
            current = greedyDescend(query, current, lc)
            lc--
        }
        val found = searchLayer(query, listOf(current), maxOf(efSearch, k), 0)
        return found.take(k).map { ids[it] }
    }

    // ---- graph navigation ------------------------------------------------------------------------

    /** Hill-climbs from [start] to the locally closest node to [query] within [layer]. */
    private fun greedyDescend(query: FloatArray, start: Int, layer: Int): Int {
        var current = start
        var currentDistance = distance(query, current)
        var improved = true
        while (improved) {
            improved = false
            for (neighbour in neighboursOf(current, layer)) {
                val d = distance(query, neighbour)
                if (d < currentDistance) {
                    currentDistance = d
                    current = neighbour
                    improved = true
                }
            }
        }
        return current
    }

    /** Best-first search of one layer, returning up to [ef] node ids sorted nearest-first. */
    private fun searchLayer(query: FloatArray, entryPoints: List<Int>, ef: Int, layer: Int): List<Int> {
        val visited = HashSet<Int>()
        val toExplore = PriorityQueue<Candidate>(compareBy { it.distance })
        val best = PriorityQueue<Candidate>(compareByDescending { it.distance })

        for (ep in entryPoints) {
            if (visited.add(ep)) {
                val candidate = Candidate(ep, distance(query, ep))
                toExplore.add(candidate)
                best.add(candidate)
            }
        }

        while (toExplore.isNotEmpty()) {
            val nearest = toExplore.poll()
            if (best.size >= ef && nearest.distance > best.peek().distance) break
            for (neighbour in neighboursOf(nearest.node, layer)) {
                if (visited.add(neighbour)) {
                    val d = distance(query, neighbour)
                    if (best.size < ef || d < best.peek().distance) {
                        val candidate = Candidate(neighbour, d)
                        toExplore.add(candidate)
                        best.add(candidate)
                        if (best.size > ef) best.poll()
                    }
                }
            }
        }

        return best.sortedBy { it.distance }.map { it.node }
    }

    private fun neighboursOf(node: Int, layer: Int): List<Int> =
        links[node].getOrNull(layer) ?: emptyList()

    private fun nearest(query: FloatArray, nodes: List<Int>, k: Int): List<Int> =
        nodes.distinct().sortedBy { distance(query, it) }.take(k)

    /** Cosine distance in `[0, 2]`; vectors are unit-normalized, so it is `1 - dot`. */
    private fun distance(query: FloatArray, node: Int): Double = 1.0 - Vectors.dot(query, vectors[node])

    private fun randomLevel(): Int {
        val uniform = random.nextDouble().coerceIn(MIN_UNIFORM, 1.0)
        return (-ln(uniform) * levelMultiplier).toInt()
    }

    private data class Candidate(val node: Int, val distance: Double)

    private companion object {
        private const val MIN_UNIFORM = 1e-12
    }
}

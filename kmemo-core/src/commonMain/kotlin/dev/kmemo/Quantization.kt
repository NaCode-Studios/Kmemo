package dev.kmemo

/**
 * How a [CacheStore] may compress vectors **for the scan only**.
 *
 * ## The discipline, which is the whole point
 *
 * A quantized vector is a cheaper approximation, and an approximation in a cache that exists to avoid
 * wrong answers has to be contained. So the rule is absolute: quantization decides **which candidates
 * are looked at**, never **whether one is served**. Survivors of the compressed scan are rescored
 * against the full-precision vectors, and the number that meets the threshold, the guards and the
 * verifier is always the exact one.
 *
 * That confines the error to a recall effect. The worst a bad quantization can do is fail to surface an
 * entry that would have been served — a miss, which costs one API call. It cannot move a similarity
 * across the threshold, because the similarity it produced was thrown away before the threshold was
 * consulted.
 *
 * The [dev.kmemo.store.InMemoryStore] applies it; a store backed by a database that quantizes for you
 * should follow the same rule or say that it does not.
 *
 * ## Choosing one
 *
 * [NONE] until the scan is actually the problem. A linear scan over ten thousand 1,536-dimensional
 * vectors is well under a millisecond, and the network call it replaces is a hundred times that.
 */
public enum class Quantization {

    /** Full precision. The scan reads the same vectors the decision uses. */
    NONE,

    /**
     * One signed byte per dimension: a quarter of the memory traffic, integer arithmetic in the scan.
     *
     * Vectors are unit-normalized, so every component is already in `[-1, 1]` and scaling by 127 uses
     * the whole range without a per-vector scale factor to store or reload.
     */
    INT8,

    /**
     * One bit per dimension, the sign: 1/32 of the memory traffic, and the scan becomes popcounts.
     *
     * Far coarser than [INT8] — it keeps direction and discards magnitude entirely — and the
     * measurement says so plainly. At the same oversampling that makes [INT8] exact it recovers only
     * about 85% of the entries an exact scan would have found. Reaching 98% takes six times the
     * shortlist, which is what it is set to, and that is the honest cost: the scan is 32 times cheaper
     * and the rescoring pass is six times longer. Worth it when the scope is large enough that the scan
     * dominates, and not otherwise.
     */
    BINARY,
    ;

    /**
     * How many entries to carry into the exact rescoring pass, per entry the caller asked for.
     *
     * This is the recall knob, and it is fixed rather than exposed because getting it wrong is
     * invisible: too low and the store quietly stops finding entries it holds, with nothing in the logs
     * and a hit rate that drifts down.
     *
     * The values come from `M18MatchingTest`, measured against an exact scan at 64, 256, 768 and 1,536
     * dimensions. [INT8] recovers everything at four; [BINARY] recovers 82% to 89% there, 94% to 97% at
     * sixteen, and 99% or better at twenty-four.
     */
    internal val rescoreFactor: Int
        get() = when (this) {
            NONE -> 1
            INT8 -> 4
            BINARY -> 24
        }
}

package dev.kmemo.guard.tck

import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One expected decision: a named guard, two prompts, and whether it rejects them. */
public class ConformanceVector(
    /** The guard this vector is about, by [MatchGuard.name]. */
    public val guard: String,
    /** The incoming prompt. */
    public val query: String,
    /** The prompt already in the cache. */
    public val candidate: String,
    /** Whether a conformant implementation must reject. */
    public val reject: Boolean,
    /**
     * The reason the reference implementation gave, or `null` when it accepted.
     *
     * **Informative, never checked.** A reason is prose meant for a human reading a miss, and two
     * conformant implementations may word one differently or in another language. What conformance
     * means is that the *verdicts* agree.
     */
    public val reason: String?,
) {
    override fun toString(): String =
        "$guard: ${if (reject) "reject" else "accept"} [$query || $candidate]"
}

/** One vector that a guard did not reproduce. */
public class VectorMismatch(
    public val vector: ConformanceVector,
    /** What the guard under test actually decided. */
    public val actual: Boolean,
) {
    override fun toString(): String =
        "expected ${if (vector.reject) "reject" else "accept"}, got ${if (actual) "reject" else "accept"}" +
            ": '${vector.query}' || '${vector.candidate}'"
}

/**
 * The conformance vectors: what each guard in the specification decides, pair by pair.
 *
 * `spec/guards/SPEC.md` states each rule in prose that a Python, Go or TypeScript implementer can
 * work from. Prose about tokenization is where two implementations quietly diverge, so the rules
 * come with this: a file of decided cases, which is a disagreement that fails a build instead of one
 * that surfaces as a wrong answer eighteen months later.
 *
 * The vectors are generated from the shipped guards and committed, in the way the public API dumps
 * are, so a change to a rule shows up as a diff somebody has to approve rather than as a number that
 * moved.
 *
 * **A vector is a verdict, not a mechanism.** Nothing here requires a particular tokenizer, a
 * particular reason string, or a particular internal structure. The specification names the guards
 * whose rule could not be stated without reference to this tokenizer, and those carry a marker
 * saying so.
 */
public object ConformanceVectors {

    /** Where the shipped vector file sits on the classpath. */
    public const val RESOURCE: String = "/guard-vectors.json"

    /** Parses a vector file. See `spec/guards/vectors.json` for the shape. */
    public fun parse(json: String): List<ConformanceVector> =
        Json.parseToJsonElement(json).jsonObject
            .getValue("vectors").jsonArray
            .map { element ->
                val fields = element.jsonObject
                ConformanceVector(
                    guard = fields.getValue("guard").jsonPrimitive.content,
                    query = fields.getValue("query").jsonPrimitive.content,
                    candidate = fields.getValue("candidate").jsonPrimitive.content,
                    reject = fields.getValue("reject").jsonPrimitive.content.toBoolean(),
                    reason = fields["reason"]?.jsonPrimitive?.contentOrNullSafe(),
                )
            }

    /**
     * The vectors that ship with this artifact.
     *
     * @throws IllegalStateException if the file is not on the classpath. A guard checked against no
     *   vectors conforms to everything, which is the most misleading result this could produce.
     */
    public fun shipped(): List<ConformanceVector> {
        val text = ConformanceVectors::class.java.getResourceAsStream(RESOURCE)
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("$RESOURCE is not on the classpath")
        return parse(text)
    }

    /** Every vector for one guard name. */
    public fun forGuard(vectors: List<ConformanceVector>, name: String): List<ConformanceVector> =
        vectors.filter { it.guard == name }

    /**
     * Runs [guard] against every vector naming it and returns the ones it did not reproduce.
     *
     * An empty list is conformance. Anything else is a disagreement about what the rule decides, and
     * the vector says which pair.
     */
    public fun check(guard: MatchGuard, vectors: List<ConformanceVector>): List<VectorMismatch> =
        forGuard(vectors, guard.name).mapNotNull { vector ->
            val actual = guard.evaluate(vector.query, vector.candidate) is GuardVerdict.Reject
            if (actual == vector.reject) null else VectorMismatch(vector, actual)
        }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content
}

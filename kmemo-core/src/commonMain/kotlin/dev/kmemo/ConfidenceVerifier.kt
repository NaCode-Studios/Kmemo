package dev.kmemo

/**
 * A [Verifier] that reports **how sure** it is, so the caller sets where the line falls.
 *
 * ### Why this exists
 *
 * The verifier stops about four fifths of what the guards let through and takes paraphrases kept from
 * 88% to 45% on one blind split. Those two numbers belong together, and read together they say a
 * cache running a verifier is doing half its job on hit rate. Until `2.3.0` the only dial was off.
 *
 * That is a consequence of the return type rather than of any model. A cross-encoder asked whether
 * two prompts mean the same thing produces a probability and then throws it away at a threshold
 * somebody else chose. M56 measured what that discarded number is worth on the residual the guards
 * still serve, against a named reference model:
 *
 * | Serve when confidence is at least | Near misses stopped | Paraphrases refused |
 * | --- | --- | --- |
 * | 0.05 | 69 of 116 | 39 of 164 |
 * | 0.10 | 80 of 116 | 45 of 164 |
 * | 0.30 | 87 of 116 | 55 of 164 |
 * | 0.50, the usual default | 91 of 116 | 56 of 164 |
 *
 * There is no free point on that curve, and that is the finding rather than a disappointment: every
 * wrong answer avoided past this point costs genuine hits. What a library can do is stop choosing for
 * the caller, because how expensive a wrong answer is depends on the deployment and on nothing this
 * code can see. The similarity threshold has always been the caller's; this makes the verifier's the
 * same kind of thing.
 *
 * ### Using it
 *
 * ```kotlin
 * val verifier = object : ConfidenceVerifier {
 *     override val threshold = 0.10   // keep more hits, stop fewer near misses
 *     override suspend fun confidence(query: String, cachedPrompt: String, similarity: Double): Double =
 *         crossEncoder.duplicateProbability(cachedPrompt, query)
 * }
 * val cache = semanticCache(embedder) { this.verifier = verifier }
 * ```
 *
 * ### Fail closed, which is not negotiable
 *
 * [confidence] may throw, and a check that could not complete rejects: [verify] does not catch, and
 * `SemanticCache` treats a failed verification as a refusal. A verifier that returned a neutral
 * number on a timeout would be a verifier that serves an unconfirmed answer whenever a provider is
 * slow, which is the failure this whole layer exists to prevent.
 *
 * Extending [Verifier] rather than replacing it, so a caller with a boolean verifier keeps it and
 * `SemanticCache` needs to know about neither.
 */
public interface ConfidenceVerifier : Verifier {

    /**
     * Serve when [confidence] reaches this, in `[0.0, 1.0]`.
     *
     * Lower keeps more genuine hits and stops fewer near misses; higher does the reverse. `0.5` is
     * the conventional midpoint and is a convention rather than a measurement: pick it from what a
     * wrong answer costs you, which is the input no benchmark can supply.
     */
    public val threshold: Double get() = DEFAULT_THRESHOLD

    /**
     * How sure this verifier is that the response cached for [cachedPrompt] answers [query], in
     * `[0.0, 1.0]`.
     *
     * @param similarity the score that got this candidate here, useful for staged strategies that
     *   only spend a model call in a narrow band.
     */
    public suspend fun confidence(query: String, cachedPrompt: String, similarity: Double): Double

    /**
     * [confidence] against [threshold]. Not open for overriding in spirit: a subclass that decided
     * differently would make the reported confidence a description rather than the decision.
     */
    override suspend fun verify(query: String, cachedPrompt: String, similarity: Double): Boolean =
        confidence(query, cachedPrompt, similarity) >= threshold

    public companion object {
        /** The conventional midpoint, and a convention rather than a measurement. */
        public const val DEFAULT_THRESHOLD: Double = 0.5
    }
}

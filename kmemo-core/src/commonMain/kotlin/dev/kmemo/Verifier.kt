package dev.kmemo

/**
 * Last line of defence before a cached response is served.
 *
 * Guards are lexical and free; a verifier is whatever you are willing to pay for. The usual shape is
 * a cheap model asked a yes/no question, which still costs far less than the call being avoided:
 *
 * ```kotlin
 * val verifier = Verifier { query, cached, _ ->
 *     haiku.complete("Do these two questions have the same correct answer? Reply YES or NO.\nA: $cached\nB: $query")
 *         .trim().startsWith("YES")
 * }
 * ```
 *
 * A verifier only ever sees candidates that already cleared the similarity threshold and every
 * guard, so it runs on a small fraction of lookups. Returning `false` turns the lookup into a
 * [MissReason.REJECTED_BY_VERIFIER] miss.
 */
public fun interface Verifier {

    /**
     * Returns `true` if the response cached for [cachedPrompt] is a correct answer to [query].
     *
     * @param similarity the score that got this candidate here, useful for staged strategies that
     *   only spend a model call in a narrow band.
     */
    public suspend fun verify(query: String, cachedPrompt: String, similarity: Double): Boolean
}

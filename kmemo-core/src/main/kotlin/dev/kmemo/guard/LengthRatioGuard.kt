package dev.kmemo.guard

import dev.kmemo.internal.Format

/**
 * Rejects matches where one prompt is far longer than the other.
 *
 * A one-line question and a three-paragraph one rarely want the same answer even when they cover
 * the same topic, and length is a decent proxy for how much detail was asked for. It is only a
 * proxy, though — "python list reverse" and "How do I reverse a list in Python?" are a four-fold
 * difference in tokens and the same question — so this guard is not part of
 * [MatchGuards.standard]. Reach for it through [MatchGuards.strict], or add it directly, when
 * your traffic mixes short queries with long ones and you would rather pay for the call.
 */
public class LengthRatioGuard(
    private val maxRatio: Double = DEFAULT_MAX_RATIO,
) : MatchGuard {

    init {
        require(maxRatio >= 1.0) { "maxRatio must be at least 1.0, was $maxRatio" }
    }

    override val name: String get() = "length-ratio"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryLength = Text.tokens(query).size
        val candidateLength = Text.tokens(candidate).size
        if (queryLength == 0 || candidateLength == 0) return GuardVerdict.Accept

        val longer = maxOf(queryLength, candidateLength).toDouble()
        val shorter = minOf(queryLength, candidateLength).toDouble()
        val ratio = longer / shorter
        if (ratio <= maxRatio) return GuardVerdict.Accept

        return GuardVerdict.Reject(
            "length ratio ${Format.fixed(ratio, 1)} exceeds " +
                "${Format.fixed(maxRatio, 1)} ($queryLength vs $candidateLength tokens)",
        )
    }

    public companion object {
        /** Four-to-one, roughly where terse phrasing stops explaining the gap. */
        public const val DEFAULT_MAX_RATIO: Double = 4.0
    }
}

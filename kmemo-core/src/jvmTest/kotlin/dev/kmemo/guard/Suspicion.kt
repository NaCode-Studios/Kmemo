package dev.kmemo.guard

import kotlin.math.abs
import kotlin.math.max

/**
 * How close the chain came to rejecting, as one number rather than as eleven silences.
 *
 * A guard returns Accept or Reject and the chain is an OR, so one guard that is sure vetoes and ten
 * guards that are each mildly suspicious serve the answer. That is the shape of most of what gets
 * through: `how much caffeine is in a shot of espresso` against `how much caffeine is in a cup of
 * drip coffee` is a pair where several guards have something to say and none has enough to say it
 * alone. Each abstains correctly under its own rule, and the answer is wrong.
 *
 * This reads the same mechanisms the guards read and reports what each of them nearly concluded. It
 * is test-side on purpose: M55 exists to find out whether a scoring chain is worth changing
 * `MatchGuard` for, and building the surface first would have answered the question by assuming it.
 *
 * **The asymmetry has to survive, and this is where it would be lost.** A guard rejects on positive
 * evidence and abstains otherwise, because a wrong rejection costs one API call and a wrong
 * acceptance costs a wrong answer. A score turns every abstention into a small vote for serving,
 * which is a different default. So every signal below is zero when its mechanism found nothing: an
 * abstention contributes nothing rather than contributing a little confidence.
 */
object Suspicion {

    /** One signal, named so a score can say which mechanisms produced it. */
    data class Signal(val name: String, val value: Double)

    /**
     * The signals for one ordered pair, each in `[0.0, 1.0]`.
     *
     * Scored in one direction. [scoreOf] takes the stronger of the two directions, for the same
     * reason every rate here evaluates both: either prompt could be the one already cached.
     */
    fun signalsOf(query: String, candidate: String): List<Signal> {
        val stopwords = Vocabulary.STOPWORDS
        val left = Text.contentTokens(query, stopwords)
        val right = Text.contentTokens(candidate, stopwords)
        val longer = max(left.size, right.size).coerceAtLeast(1)

        return listOf(
            Signal("substitution", substitution(left, right)),
            Signal("entity", entity(query, candidate)),
            Signal("numeric", if (NumericGuard().evaluate(query, candidate) is GuardVerdict.Reject) 1.0 else 0.0),
            Signal("unit", unit(query, candidate)),
            Signal("divergence", divergence(left, right, longer)),
            Signal("subspan", subspan(left, right)),
            Signal("order", if (left.toSet() == right.toSet() && left != right) ORDER_SIGNAL else 0.0),
            Signal("lengthGap", (abs(left.size - right.size).toDouble() / longer) * LENGTH_SIGNAL),
        )
    }

    /** The stronger of the two directions, summed over the signals. */
    fun scoreOf(a: String, b: String): Double =
        max(signalsOf(a, b).sumOf { it.value }, signalsOf(b, a).sumOf { it.value })

    /**
     * What `SubstitutionGuard` nearly concluded.
     *
     * One differing position out of four or more is what it rejects on. One out of two or three is
     * what its floor refuses to judge, and two out of many is the case just past its rule. Both are
     * evidence, and neither is enough for a veto, which is exactly the kind of signal a sum exists to
     * add up.
     */
    private fun substitution(left: List<String>, right: List<String>): Double {
        if (left.size != right.size || left.isEmpty()) return 0.0
        val differing = left.indices.count { !Text.isSameWord(left[it], right[it]) }
        return when {
            differing == 1 && left.size >= SubstitutionGuard.DEFAULT_MIN_TOKENS -> 1.0
            differing == 1 -> BELOW_FLOOR_SIGNAL
            differing == 2 && left.size >= SubstitutionGuard.DEFAULT_MIN_TOKENS -> TWO_POSITIONS_SIGNAL
            else -> 0.0
        }
    }

    /** What `EntityGuard` nearly concluded: one side naming something the other does not. */
    private fun entity(query: String, candidate: String): Double {
        val left = Text.entityTokens(query)
        val right = Text.entityTokens(candidate)
        if (left.isEmpty() && right.isEmpty()) return 0.0
        val onlyLeft = left - right
        val onlyRight = right - left
        return when {
            onlyLeft.isNotEmpty() && onlyRight.isNotEmpty() -> 1.0
            onlyLeft.isNotEmpty() || onlyRight.isNotEmpty() -> ONE_SIDED_SIGNAL
            else -> 0.0
        }
    }

    /** What `UnitGuard` nearly concluded, including the cross-dimension case it declines. */
    private fun unit(query: String, candidate: String): Double {
        val left = Text.tokens(query).mapNotNull { Vocabulary.UNITS[it] }.toSet()
        val right = Text.tokens(candidate).mapNotNull { Vocabulary.UNITS[it] }.toSet()
        val onlyLeft = left - right
        val onlyRight = right - left
        if (onlyLeft.isEmpty() || onlyRight.isEmpty()) return 0.0
        val sameDimension = onlyLeft.any { l -> onlyRight.any { it.dimension == l.dimension } }
        return if (sameDimension) 1.0 else ONE_SIDED_SIGNAL
    }

    /** What `LexicalDivergenceGuard` nearly concluded, as a slope rather than as its cliff. */
    private fun divergence(left: List<String>, right: List<String>, longer: Int): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { token -> right.any { Text.isSameWord(it, token) } }
        val union = left.size + right.size - shared
        if (union == 0) return 0.0
        val overlap = shared.toDouble() / union
        if (overlap >= DIVERGENCE_CEILING) return 0.0
        return ((DIVERGENCE_CEILING - overlap) / DIVERGENCE_CEILING) * DIVERGENCE_SIGNAL
    }

    /** What `SubSpanGuard` nearly concluded: one prompt containing the other and adding something. */
    private fun subspan(left: List<String>, right: List<String>): Double {
        val contains = left.all { token -> right.any { Text.isSameWord(it, token) } } ||
            right.all { token -> left.any { Text.isSameWord(it, token) } }
        if (!contains || left.size == right.size) return 0.0
        return SUBSPAN_SIGNAL
    }

    private const val BELOW_FLOOR_SIGNAL = 0.7
    private const val TWO_POSITIONS_SIGNAL = 0.5
    private const val ONE_SIDED_SIGNAL = 0.35
    private const val ORDER_SIGNAL = 0.4
    private const val LENGTH_SIGNAL = 0.3
    private const val SUBSPAN_SIGNAL = 0.5
    private const val DIVERGENCE_CEILING = 0.5
    private const val DIVERGENCE_SIGNAL = 0.6
}

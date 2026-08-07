package dev.kmemo.guard.tck

import java.util.Locale
import kotlin.math.sqrt

/**
 * A measured rate with the range the sample it came from actually supports.
 *
 * A catch rate is an estimate from a finite corpus, and a corpus of a hundred pairs supports an
 * estimate about nine points wide in each direction. Printing `68%` beside `73%` invites the reader
 * to conclude that something improved, when at that sample size the two are the same measurement.
 * Every rate this project publishes carries one of these for that reason.
 *
 * The interval is **Wilson's score interval**, not the textbook normal approximation. The normal one
 * is wrong exactly where these numbers live: it produces bounds below zero or above one for rates
 * near the edges, and it collapses to a zero-width interval when a guard catches nothing or catches
 * everything, which is the one case where the sample says least. Wilson stays inside `[0, 1]` and
 * stays wide at the edges.
 */
public class ScoreInterval(
    /** Successes observed. */
    public val successes: Int,
    /** Trials observed. Zero means nothing was measured, and [rate] is then `null`. */
    public val trials: Int,
    /** Lower bound of the interval, in `[0.0, 1.0]`. */
    public val low: Double,
    /** Upper bound of the interval, in `[0.0, 1.0]`. */
    public val high: Double,
) {

    /** The point estimate, or `null` when nothing was measured. Zero of zero is not a rate of zero. */
    public val rate: Double? get() = if (trials == 0) null else successes.toDouble() / trials

    /** Half the interval's width, in percentage points. The number to quote as "plus or minus". */
    public val halfWidthPoints: Double get() = 100.0 * (high - low) / 2.0

    /**
     * Whether this rate and [other] are far enough apart that the difference is not the sample.
     *
     * Non-overlapping Wilson intervals are a deliberately conservative test: two intervals can
     * overlap slightly and the difference still be significant. Reading it the strict way costs a
     * true finding occasionally and never manufactures one, which is the direction this project
     * errs in everywhere else.
     */
    public fun separatedFrom(other: ScoreInterval): Boolean = high < other.low || other.high < low

    override fun toString(): String = if (trials == 0) {
        "n/a (0 trials)"
    } else {
        String.format(
            Locale.ROOT,
            "%.1f%% [%.1f, %.1f] (%d/%d)",
            100.0 * (rate ?: 0.0), 100.0 * low, 100.0 * high, successes, trials,
        )
    }

    public companion object {

        /** The two-sided 95% normal quantile, which is the confidence level every report here uses. */
        public const val Z_95: Double = 1.959964

        /**
         * The Wilson score interval for [successes] out of [trials] at 95%.
         *
         * With no trials the interval is the whole range, which is the honest report of a rate nobody
         * measured rather than a rate of zero.
         */
        public fun wilson95(successes: Int, trials: Int): ScoreInterval = wilson(successes, trials, Z_95)

        /** [wilson95] at an arbitrary normal quantile [z]. */
        public fun wilson(successes: Int, trials: Int, z: Double): ScoreInterval {
            require(successes >= 0) { "successes must not be negative, was $successes" }
            require(trials >= successes) { "trials ($trials) must be at least successes ($successes)" }
            if (trials == 0) return ScoreInterval(0, 0, 0.0, 1.0)

            val n = trials.toDouble()
            val p = successes.toDouble() / n
            val z2 = z * z
            val denominator = 1.0 + z2 / n
            val centre = (p + z2 / (2 * n)) / denominator
            val spread = z / denominator * sqrt(p * (1 - p) / n + z2 / (4 * n * n))
            return ScoreInterval(
                successes = successes,
                trials = trials,
                low = (centre - spread).coerceIn(0.0, 1.0),
                high = (centre + spread).coerceIn(0.0, 1.0),
            )
        }

        /**
         * The number of trials at which a difference of [points] percentage points around [around]
         * stops overlapping, at 95%.
         *
         * The question M54 asks in reverse: not "how wide is this interval" but "how many pairs would
         * a split need before an improvement this size were readable". Returns `Int.MAX_VALUE` when
         * no sample size within reason separates them.
         */
        public fun trialsToSeparate(points: Double, around: Double = 0.68): Int {
            require(points > 0.0) { "points must be positive, was $points" }
            val low = (around - points / 200.0).coerceIn(0.0, 1.0)
            val high = (around + points / 200.0).coerceIn(0.0, 1.0)
            var n = 10
            while (n <= 1_000_000) {
                val a = wilson95((low * n).toInt(), n)
                val b = wilson95((high * n).toInt(), n)
                if (a.separatedFrom(b)) return n
                n += 10
            }
            return Int.MAX_VALUE
        }
    }
}

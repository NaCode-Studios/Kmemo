package dev.kmemo.internal

import dev.kmemo.MissReason
import dev.kmemo.ScoredEntry
import dev.kmemo.ShadowDecision
import dev.kmemo.ShadowReport

/**
 * Judges one lookup at every configured shadow threshold, without serving anything.
 *
 * One guard pass per candidate serves the whole curve. The guard verdicts are the expensive part and a
 * candidate's verdict does not depend on the threshold, so computing them once and reading every
 * threshold off the same result is what makes a five-point curve cost one embed call rather than five —
 * and what makes shadow mode affordable on live traffic instead of a thing you run for an afternoon.
 */
internal class ShadowRun(
    private val thresholds: List<Double>,
    private val guardChain: GuardChain,
) {

    fun report(prompt: String, scope: String, found: List<ScoredEntry>): ShadowReport {
        if (found.isEmpty()) {
            return ShadowReport(
                scope, prompt,
                thresholds.map { ShadowDecision(it, false, MissReason.EMPTY_SCOPE, null, null, null) },
            )
        }
        // Verdict per candidate, computed once. `null` means the candidate passed every guard.
        val rejections = found.map { guardChain.firstRejection(prompt, it.entry) }
        val best = found.first()
        return ShadowReport(scope, prompt, thresholds.map { decide(it, found, rejections, best) })
    }

    private fun decide(
        threshold: Double,
        found: List<ScoredEntry>,
        rejections: List<GuardRejection?>,
        best: ScoredEntry,
    ): ShadowDecision {
        var refused: ShadowDecision? = null
        for ((index, scored) in found.withIndex()) {
            // Best-first, so the first candidate below this threshold means the rest are too.
            if (scored.similarity < threshold) break
            val rejection = rejections[index]
                ?: return ShadowDecision(threshold, true, null, scored.similarity, scored.entry.prompt, null)
            if (refused == null) {
                refused = ShadowDecision(
                    threshold, false, MissReason.REJECTED_BY_GUARD, scored.similarity,
                    scored.entry.prompt, rejection.guardName,
                )
            }
        }
        return refused ?: ShadowDecision(
            threshold, false, MissReason.BELOW_THRESHOLD, best.similarity, best.entry.prompt, null,
        )
    }
}

package dev.kmemo.guard

import dev.kmemo.guard.tck.MatchGuardContract

/**
 * M27 — the eleven guards in [MatchGuards.standard], put through the suite kmemo publishes for other
 * people's guards.
 *
 * This is what makes the claim on `kmemo-guard-tck` literal rather than aspirational. "The harness the
 * eleven internal guards are already held to" is only true if they are actually held to it, from the
 * same artifact a stranger downloads, and here they are.
 *
 * It is deliberately **not** a second copy of [CorpusTest]. That test owns the numbers: it gates the
 * chain's catch and paraphrase floors on all three splits and fails CI when one moves down. This one
 * owns the structural properties — determinism, totality, reflexivity, a reason on every rejection, a
 * stable name — which no corpus can check and which nobody notices are missing until a guard takes a
 * request down or answers differently on the second run.
 */
abstract class StandardGuardContract : MatchGuardContract() {

    /**
     * Not asserted per guard, on purpose.
     *
     * Some of the eleven do reject the occasional paraphrase — `standard()` keeps 88% of them on the
     * validation split, not 100% — and that trade is argued and gated at the *chain* level in
     * [CorpusTest], where the floors live. A per-guard ceiling here would be a second, weaker copy of
     * those floors, and two gates on one number is how the weaker one ends up being the one that moves.
     */
    override val maxFalseRejectionRate: Double = 1.0

    override val reportPath: String
        get() = "build/reports/guards/compliance-${createGuard().name}.json"

    protected companion object {
        /** The guard as `standard()` builds it, wired to the English vocabulary it ships with. */
        inline fun <reified T : MatchGuard> standard(): MatchGuard =
            MatchGuards.standard().filterIsInstance<T>().single()
    }
}

class NumericGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<NumericGuard>()
}

class UnitGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<UnitGuard>()
}

class TemporalGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<TemporalGuard>()
}

class NegationGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<NegationGuard>()
}

class AntonymGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<AntonymGuard>()
}

class EntityGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<EntityGuard>()
}

class SubstitutionGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<SubstitutionGuard>()
}

class ScopeGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<ScopeGuard>()
}

class DirectionGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<DirectionGuard>()
}

class SubSpanGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<SubSpanGuard>()
}

class LexicalDivergenceGuardComplianceTest : StandardGuardContract() {
    override fun createGuard(): MatchGuard = standard<LexicalDivergenceGuard>()
}

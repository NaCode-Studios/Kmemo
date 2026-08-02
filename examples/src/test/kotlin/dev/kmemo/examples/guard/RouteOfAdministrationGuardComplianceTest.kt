package dev.kmemo.examples.guard

import dev.kmemo.guard.MatchGuard
import dev.kmemo.guard.tck.GuardCorpus
import dev.kmemo.guard.tck.MatchGuardContract

/**
 * A guard written outside `kmemo-core`, held to the same suite the eleven built-in guards are.
 *
 * This whole file is nineteen lines, and that is the point of M27: everything a guard author has to
 * write in order to arrive with a measured number is here. One test dependency, one subclass, two
 * overrides.
 *
 * Run it with one command:
 *
 * ```
 * ./gradlew :examples:test --tests '*RouteOfAdministrationGuardComplianceTest*'
 * ```
 *
 * The output has two halves and they answer different questions. Against kmemo's three shipped
 * corpora the guard must catch nothing and cost nothing — that is the *does no harm* number, and the
 * suite asserts it. Against `route-corpus.json`, which ships beside this test because only a domain
 * author can write it, the guard must catch what it claims to.
 */
class RouteOfAdministrationGuardComplianceTest : MatchGuardContract() {

    override fun createGuard(): MatchGuard = RouteOfAdministrationGuard()

    override fun domainCorpora(): List<GuardCorpus> =
        listOf(GuardCorpus.fromResource("route", "/route-corpus.json"))

    /**
     * Every near miss in the domain corpus names two different routes explicitly, so the guard should
     * take all of them. A floor rather than an assertion of perfection: it is here to fail the day a
     * vocabulary edit quietly turns the guard into a no-op.
     */
    override val minDomainCatchRate: Double = 1.0
}

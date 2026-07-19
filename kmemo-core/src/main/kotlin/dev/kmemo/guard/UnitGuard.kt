package dev.kmemo.guard

/**
 * Rejects matches whose prompts mention different units or currencies.
 *
 * `Convert 50 km to miles` and `Convert 50 km to meters` share their number, share four of five
 * words, and embed almost identically — [NumericGuard] sees nothing wrong and word overlap is high
 * enough to pass any sane lexical check. The only thing that differs is the unit, so that is what
 * this guard reads.
 *
 * Comparison is by set, not by sequence: "convert 50 km to miles" and "how many miles is 50 km"
 * mention the same two units and mean the same thing. Direction swaps like `EUR to USD` against
 * `USD to EUR` are [DirectionGuard]'s job.
 *
 * Like [EntityGuard], it rejects a **substitution** and not an addition. Each prompt must name a
 * unit the other does not, so "375 f to c" still matches "What is 375 degrees Fahrenheit in
 * Celsius?" — one of them simply spells the units out.
 *
 * Units are compared by their canonical name, so `km` and `kilometers` are one unit rather than a
 * swap. See [Vocabulary.UNITS].
 */
public class UnitGuard(
    private val units: Map<String, MeasurementUnit> = Vocabulary.UNITS,
) : MatchGuard {

    override val name: String get() = "unit"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryUnits = unitsIn(query)
        val candidateUnits = unitsIn(candidate)
        val onlyInQuery = queryUnits - candidateUnits
        val onlyInCandidate = candidateUnits - queryUnits
        if (onlyInQuery.isEmpty() || onlyInCandidate.isEmpty()) return GuardVerdict.Accept

        // Only units of the same kind are comparable. A mass appearing where a currency appears is
        // two ways of writing one question — "250 euros in British pounds" against "250 EUR in
        // GBP" — not a swapped unit.
        val dimensions = onlyInQuery.map { it.dimension }.toSet()
        val swapped = onlyInQuery.filter { left -> onlyInCandidate.any { it.dimension == left.dimension } }
        if (swapped.isEmpty()) return GuardVerdict.Accept

        val counterparts = onlyInCandidate.filter { it.dimension in dimensions }
        return GuardVerdict.Reject(
            "units swapped: query says ${swapped.map { it.canonical }} " +
                "where cached prompt says ${counterparts.map { it.canonical }}",
        )
    }

    private fun unitsIn(text: String): Set<MeasurementUnit> =
        Text.tokens(text).mapNotNullTo(LinkedHashSet()) { units[it] }
}

package dev.kmemo.guard

/**
 * Rejects matches whose prompts do not contain the same numbers.
 *
 * The highest-value guard in the set, because numbers are where embeddings are weakest. `Convert 100
 * USD to EUR` and `Convert 250 USD to EUR` are ~99% cosine-similar with every mainstream embedding
 * model: the sentences are near-identical and the model was never trained to treat magnitude as
 * meaning. No threshold separates them. Reading the digits does, exactly and for free.
 *
 * Numbers are compared as a sorted multiset, so re-ordering a prompt does not trigger a rejection,
 * and thousands separators are stripped so `1,000` and `1000` are the same number. A number present
 * on one side only also counts as a difference — that is what catches `Explain OAuth 2.0` against
 * `Explain OAuth 2.0 to a 5 year old`.
 *
 * A comma is read by what follows it: exactly three digits means grouping and the comma is dropped,
 * anything else means a decimal point and the comma becomes one. So `1,000` is a thousand and `3,5`
 * is three and a half.
 *
 * Both halves of that rule are load-bearing, and each covers a false hit the other lets through.
 * Dropping every comma turns `3,5` into `35`, so "Convert 3,5 km to miles" gets the answer cached
 * for 35 km. Merely *splitting* on the comma instead turns `3,5` into the two numbers `3` and `5` —
 * which, compared as an unordered multiset, is indistinguishable from `5,3`, so "3,5 kg in pounds"
 * gets the answer for 5,3 kg. Only parsing the comma produces one number that differs from both.
 */
public class NumericGuard : MatchGuard {

    override val name: String get() = "numeric"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryNumbers = numbersIn(query)
        val candidateNumbers = numbersIn(candidate)
        if (queryNumbers == candidateNumbers) return GuardVerdict.Accept
        return GuardVerdict.Reject(
            "numbers differ: ${queryNumbers.ifEmpty { listOf("none") }} vs " +
                "${candidateNumbers.ifEmpty { listOf("none") }}",
        )
    }

    private fun numbersIn(text: String): List<String> =
        NUMBER.findAll(text)
            .map { normalize(it.value) }
            .sorted()
            .toList()

    /** Drops grouping commas, then reads any comma that is left as a decimal point. */
    private fun normalize(number: String): String =
        number.replace(GROUPING_COMMA, "$1").replace(',', '.')

    private companion object {
        /**
         * Digits with any number of `.` or `,` separators between digit groups.
         *
         * Deliberately permissive: the separators are interpreted in [normalize], not here, so that
         * `3,5` stays a single number rather than being split into two. A trailing comma in prose
         * ("port 3000, how do I…") is still excluded, since nothing follows it.
         */
        private val NUMBER = Regex("""\d+(?:[.,]\d+)*""")

        /** A comma followed by exactly three digits: grouping, not a decimal point. */
        private val GROUPING_COMMA = Regex(""",(\d{3})(?!\d)""")
    }
}

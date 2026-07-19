package dev.nacode.kmemo.guard

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
 * A comma only counts as a thousands separator when exactly three digits follow it. Stripping every
 * comma is the obvious implementation and it quietly breaks the guard outside the US: `3,5` would
 * become `35`, and "Convert 3,5 km to miles" would be served the answer cached for 35 km — the
 * precise failure this guard exists to prevent, reintroduced by the normalization meant to help.
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
            .map { it.value.replace(",", "") }
            .sorted()
            .toList()

    private companion object {
        /**
         * Digits, with commas accepted only in true grouping position — followed by exactly three
         * more digits.
         *
         * A looser `\d[\d,]*` swallows any comma next to a digit, which makes `3,5` a single number
         * that normalizes to `35`, and makes a trailing comma in prose ("port 3000, how do I…")
         * part of the number. Here `3,5` is read as two numbers instead, which still differs from
         * the one number in `35` — the guard rejects either way, without inventing a value.
         */
        private val NUMBER = Regex("""\d+(?:,\d{3})*(?:\.\d+)?""")
    }
}

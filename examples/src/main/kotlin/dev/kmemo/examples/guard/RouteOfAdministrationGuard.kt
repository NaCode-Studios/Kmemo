package dev.kmemo.examples.guard

import dev.kmemo.guard.GuardVerdict
import dev.kmemo.guard.MatchGuard

/**
 * A worked example of a domain guard: same drug, same dose, **different route**, different answer.
 *
 * kmemo's eleven built-in guards are about general English, and one of them already catches "500 mg"
 * against "250 mg", since a number changed is a number changed in any domain. None of them can see the
 * near miss that clinical text is full of and ordinary English is not:
 *
 * > *what is the onset time for 4 mg ondansetron given orally*
 * > *what is the onset time for 4 mg ondansetron given intravenously*
 *
 * Every word overlaps, every number matches, and the answers differ by about half an hour. No lexical
 * guard fires, because nothing was substituted that a general vocabulary knows to look at. That is the
 * shape of every real domain guard: the distinguishing feature is ordinary vocabulary *somewhere
 * else*, and only someone who knows the domain knows that it decides the answer.
 *
 * ### The rule, and why it is this narrow
 *
 * Reject when both prompts name a route of administration and the routes differ. Abstain otherwise,
 * including when only one prompt names a route. One prompt naming a route is no evidence that the
 * other means a different one. That is the guard contract's asymmetry of cost: a wrong rejection
 * costs one API call, a wrong acceptance costs a wrong clinical answer.
 *
 * ### What the compliance suite says, and what it does not
 *
 * Measured through `kmemo-guard-tck`: **zero** false rejections across all three of kmemo's corpora,
 * and all ten route near misses caught on the domain corpus beside this test. The first number is the
 * one worth reading: a domain guard catching nothing in general English is expected, a domain guard
 * *costing* something there is the failure that looks like success.
 *
 * It is also the number to read narrowly. The vocabulary carries the abbreviations clinicians write,
 * `po`, `iv` and `im`, and `im` is a word real users type for "I'm". kmemo's validation split is nine
 * tenths lowercase precisely because real users type that way, and it still shows no rejection, so
 * the risk is real and unrealised rather than measured away. A clinical deployment should put its own
 * traffic through this suite before trusting that, which is the whole reason the suite takes a corpus
 * from the author.
 *
 * **This is an example, not a medical device.** It exists to show what a domain guard looks like and
 * to be measured. A real clinical cache needs a real vocabulary, a real corpus and a real review.
 */
public class RouteOfAdministrationGuard : MatchGuard {

    override val name: String = "route-of-administration"

    override fun evaluate(query: String, candidate: String): GuardVerdict {
        val queryRoutes = routesIn(query)
        val candidateRoutes = routesIn(candidate)
        // Silence is not evidence. A prompt that names no route may well mean the same one.
        if (queryRoutes.isEmpty() || candidateRoutes.isEmpty()) return GuardVerdict.Accept
        // An overlap is enough to abstain: "iv or oral" against "oral" is a question the cached answer
        // may genuinely cover, and a guard rejects only on evidence that the answers must differ.
        if (queryRoutes.intersect(candidateRoutes).isNotEmpty()) return GuardVerdict.Accept
        return GuardVerdict.Reject(
            "route of administration differs: ${queryRoutes.sorted()} vs ${candidateRoutes.sorted()}",
        )
    }

    /** Canonical routes named in [text], matched on whole words so `morally` is not `orally`. */
    private fun routesIn(text: String): Set<String> {
        val padded = " ${text.lowercase().replace(NON_WORD, " ").trim()} "
        return ROUTES.entries.filter { padded.contains(" ${it.key} ") }.map { it.value }.toSet()
    }

    private companion object {
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Phrase to canonical route. Several phrases map to one route on purpose: `orally`, `by mouth`
         * and `oral` are the same instruction, and a guard that treated them as different would reject
         * the paraphrase it exists to allow.
         */
        private val ROUTES: Map<String, String> = mapOf(
            "orally" to "oral",
            "oral" to "oral",
            "by mouth" to "oral",
            "po" to "oral",
            "intravenously" to "intravenous",
            "intravenous" to "intravenous",
            "iv" to "intravenous",
            "intramuscularly" to "intramuscular",
            "intramuscular" to "intramuscular",
            "im" to "intramuscular",
            "subcutaneously" to "subcutaneous",
            "subcutaneous" to "subcutaneous",
            "topically" to "topical",
            "topical" to "topical",
            "rectally" to "rectal",
            "rectal" to "rectal",
            "inhaled" to "inhaled",
            "nebulised" to "inhaled",
            "nebulized" to "inhaled",
        )
    }
}

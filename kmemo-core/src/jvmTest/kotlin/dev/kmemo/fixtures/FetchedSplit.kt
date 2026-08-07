package dev.kmemo.fixtures

import java.io.File
import kotlin.test.fail

/**
 * A split that is fetched at build time rather than committed, and the policy for what happens when
 * it is absent.
 *
 * The policy is the interesting part and it is shared rather than duplicated, because several tests
 * read each of these files and a second copy of the policy is a second chance for one of them to stop
 * enforcing it quietly. A developer who has never run the fetch script gets a skip and a sentence
 * telling them how; CI, which sets the required property, gets a failure. A floor that silently stops
 * being enforced is worse than no floor, because it reads as a passing gate.
 *
 * Both splits stay out of the repository for the same reason: the pairs are somebody else's work
 * under somebody else's licence, so the licence stays with the dataset.
 */
class FetchedSplit(
    val name: String,
    private val pathProperty: String,
    private val requiredProperty: String,
    private val fetchScript: String,
) {

    /** The pairs, or `null` when the split is absent and absent is allowed. */
    fun pairs(): List<CorpusPair>? {
        val path = System.getProperty(pathProperty)
        val required = System.getProperty(requiredProperty).toBoolean()
        val file = path?.let { File(it) }

        if (file == null || !file.isFile) {
            val where = path ?: "<no $pathProperty set>"
            if (required) {
                fail(
                    "the $name corpus is required here and is not at $where. Run $fetchScript, and " +
                        "see its README. A floor nobody notices has stopped running is not a floor.",
                )
            }
            println("skipping the $name corpus: nothing at $where ($fetchScript)")
            return null
        }

        return Corpus.parse(file.readText())
    }

    /** The same pairs as a [Corpus], so the shared reports can measure it beside the committed ones. */
    fun corpus(): Corpus? = pairs()?.let { Corpus.of(name, it) }
}

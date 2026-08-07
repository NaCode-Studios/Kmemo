package dev.kmemo.fixtures

import java.io.File
import kotlin.test.fail

/**
 * The external split (M24): PAWS-Wiki `labeled_final`, test split, fetched rather than vendored.
 *
 * The loading policy is the interesting part and it is shared rather than duplicated, because two
 * tests now read this file and a second copy of the policy is a second chance for one of them to stop
 * enforcing it quietly. A developer who has never run the fetch script gets a skip and a sentence
 * telling them how; CI, where `-PexternalCorpusRequired=true` is set, gets a failure. A floor that
 * silently stops being enforced is worse than no floor, because it reads as a passing gate.
 *
 * See `tools/external-corpus/README.md`. The licence stays with the dataset, which is why the file is
 * never committed.
 */
object ExternalCorpus {

    const val NAME: String = "external"

    private const val PATH_PROPERTY = "kmemo.externalCorpus"
    private const val REQUIRED_PROPERTY = "kmemo.externalCorpus.required"

    /** The pairs, or `null` when the split is absent and absent is allowed. */
    fun pairs(): List<CorpusPair>? {
        val path = System.getProperty(PATH_PROPERTY)
        val required = System.getProperty(REQUIRED_PROPERTY).toBoolean()
        val file = path?.let { File(it) }

        if (file == null || !file.isFile) {
            val where = path ?: "<no $PATH_PROPERTY set>"
            if (required) {
                fail(
                    "the external corpus is required here and is not at $where. Run " +
                        "tools/external-corpus/fetch.py, and see its README. A floor nobody notices has " +
                        "stopped running is not a floor.",
                )
            }
            println("skipping the external corpus: nothing at $where (tools/external-corpus/fetch.py)")
            return null
        }

        return Corpus.parse(file.readText())
    }

    /** The same pairs as a [Corpus], so the shared reports can measure it beside the committed three. */
    fun corpus(): Corpus? = pairs()?.let { Corpus.of(NAME, it) }
}

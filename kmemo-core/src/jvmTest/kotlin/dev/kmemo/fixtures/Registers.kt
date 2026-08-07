package dev.kmemo.fixtures

/**
 * What kind of utterance a prompt is.
 *
 * Register is not a property of a benchmark, it is a property of a deployment. A support assistant sees
 * questions. A command palette sees imperatives. A search box sees noun phrases. A retrieval-augmented
 * pipeline sees retrieved passages, which are prose, which is what PAWS is. Those are four different
 * guard problems being served by one tuned chain, and the chain was tuned on one of them.
 */
enum class Register {
    /** "how do I reverse a list in kotlin", "what is the capital of Austria". */
    QUESTION,

    /** "explain recursion", "convert 100 USD to EUR". */
    IMPERATIVE,

    /** "kotlin list reverse", "capital gains tax second home": a search query, not a sentence. */
    FRAGMENT,

    /** A statement. Encyclopedic prose, retrieved passages, documentation. */
    DECLARATIVE,

    /** The two sides of a pair are not the same kind of utterance. */
    MIXED,
}

/**
 * A deterministic register classifier, with its rules stated rather than learned.
 *
 * **It is a heuristic and it is published as one.** Labelling 8,410 pairs by hand is not something this
 * project can do honestly, and a model that labelled them would put an unmeasured component inside a
 * measurement. What is left is a rule set short enough that a reader can disagree with it line by line
 * and rerun the report, which is the standard the derived length splits are held to as well.
 *
 * The rules, in order, on the first word of the prompt:
 *
 *  1. A prompt ending in `?`, or opening with a wh-word or an auxiliary, is a [Register.QUESTION].
 *  2. A prompt opening with a verb from [INSTRUCTIONS] is an [Register.IMPERATIVE].
 *  3. A prompt of six words or fewer with no verb marker in it is a [Register.FRAGMENT].
 *  4. Everything else is [Register.DECLARATIVE].
 *
 * The costs of getting it wrong are worth naming. The imperative list is closed, so an instruction verb
 * nobody thought of is read as declarative. The verb marker is a suffix test, so an English plural noun
 * looks like a verb and pulls a fragment into declarative. Both errors move pairs **towards**
 * declarative, which is the label PAWS already has, so the classifier cannot inflate the difference
 * between PAWS and the written corpora. It can only understate it.
 */
object Registers {

    private val WH = setOf("what", "who", "whom", "whose", "where", "when", "why", "which", "how")

    private val AUXILIARIES = setOf(
        "is", "are", "was", "were", "am", "be",
        "do", "does", "did",
        "can", "could", "should", "would", "will", "shall", "may", "might",
        "has", "have", "had",
    )

    /** Closed on purpose. A verb nobody listed reads as declarative, which understates the difference. */
    private val INSTRUCTIONS = setOf(
        "explain", "define", "list", "show", "give", "write", "convert", "calculate", "find",
        "compare", "translate", "summarize", "summarise", "describe", "tell", "name", "create",
        "make", "generate", "set", "add", "remove", "install", "configure", "fix", "recommend",
        "suggest", "draft", "rewrite", "outline", "analyse", "analyze", "check", "review",
    )

    private val WORD = Regex("[\\p{L}\\p{N}']+")

    private val VERB_MARKER = Regex(
        "\\b(is|are|was|were|be|been|being|has|have|had|do|does|did|will|would|can|could|should|" +
            "may|might|\\w+ed|\\w+s)\\b",
    )

    private const val FRAGMENT_WORDS = 6

    fun of(text: String): Register {
        val trimmed = text.trim()
        val words = WORD.findAll(trimmed.lowercase()).map { it.value }.toList()
        if (words.isEmpty()) return Register.FRAGMENT
        if (trimmed.endsWith("?") || words[0] in WH || words[0] in AUXILIARIES) return Register.QUESTION
        if (words[0] in INSTRUCTIONS) return Register.IMPERATIVE
        if (words.size <= FRAGMENT_WORDS && !VERB_MARKER.containsMatchIn(trimmed.lowercase())) {
            return Register.FRAGMENT
        }
        return Register.DECLARATIVE
    }

    /**
     * The register of a pair, or [Register.MIXED] when its two sides disagree.
     *
     * Mixed is kept as its own band rather than resolved to one side. A pair whose two prompts are
     * different kinds of utterance is a real case, and it is the case where a guard tuned on one kind is
     * reading the other, so folding it into either would hide exactly the thing being measured.
     */
    fun of(pair: CorpusPair): Register {
        val a = of(pair.a)
        val b = of(pair.b)
        return if (a == b) a else Register.MIXED
    }
}

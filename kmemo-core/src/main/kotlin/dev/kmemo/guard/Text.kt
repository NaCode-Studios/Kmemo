package dev.kmemo.guard

/** Tokenization helpers shared by the built-in guards. */
internal object Text {

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Capitalized by grammar rather than by reference. English has exactly one that matters. */
    private val NON_ENTITY_CAPITALS = setOf("i")

    /** A colon or semicolon never ends a sentence, so neither belongs here. */
    private val SENTENCE_END = setOf('.', '?', '!')

    /** Shortest token allowed to fuzzy-match: below five, one edit separates `cat` from `cut`. */
    private const val MIN_FUZZY_LENGTH = 5

    /** Longest suffix an inflection may add: enough for `-ed`, `-ing`, `-s`, not for a new word. */
    private const val MAX_SUFFIX_GROWTH = 3

    /** Below four letters, an anagram is as likely to be a different word as a typo. */
    private const val MIN_REARRANGEMENT_LENGTH = 4

    /** Words whose trailing period abbreviates rather than terminates. */
    private val ABBREVIATIONS = setOf(
        "vs", "etc", "eg", "ie", "mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr",
        "inc", "ltd", "co", "fig", "vol", "no", "approx", "al",
    )

    /** All word tokens, lowercased, in order. */
    fun tokens(text: String): List<String> =
        TOKEN.findAll(text).map { it.value.lowercase() }.toList()

    /** Tokens with [stopwords] removed, in order, duplicates dropped (first occurrence wins). */
    fun contentTokens(text: String, stopwords: Set<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (token in tokens(text)) {
            if (token !in stopwords) seen.add(token)
        }
        return seen.toList()
    }

    /**
     * Tokens that look like named entities: capitalized, minus the words that are capitalized purely
     * by grammar. `France`, `GitHub` and `OAuth` qualify in "what is the capital of France"; the
     * leading `What` does not.
     *
     * Deciding which capitals are grammar is the whole difficulty, and both obvious answers are
     * wrong.
     *
     * Treating `.` `:` `;` as sentence boundaries disables the guard on exactly the prompts it
     * exists for: "Compare Python vs. Java" loses `Java` to the abbreviation period, and a templated
     * "Country: Austria. Give me the capital." loses `Austria` to the colon — the one field that
     * varies between two prompts.
     *
     * Exempting only the very first token of the prompt goes wrong in the other direction: the
     * opening word of every *later* sentence becomes an entity, so "…in CSS? Show me an example."
     * and "…in CSS? Give me an example." look like an entity swap and a genuine paraphrase is
     * refused.
     *
     * So: only `.` `?` `!` end a sentence, never `:` or `;`; a period does not end one when the word
     * before it is an abbreviation or a single letter, which covers `vs.`, `e.g.` and initials; and
     * a word that opens a sentence is excused **only if it is a word we know to be ordinary**.
     *
     * That last rule is the one that matters. Excusing every sentence-opening capital loses the
     * entity in "I am planning a holiday. Austria is where I want to go." — a false hit, the
     * expensive direction. Excusing none of them refuses "…? Show me an example." against "…? Give
     * me an example." — a false rejection, which costs one API call. So the default is to treat a
     * sentence-opening capital as an entity, and [Vocabulary.SENTENCE_OPENERS] carves out the
     * grammar words. An unusual opener costs a call; a missed entity costs a wrong answer.
     *
     * Returned lowercased, so `GitHub` and `Github` are the same entity.
     */
    fun entityTokens(text: String): Set<String> {
        val matches = TOKEN.findAll(text).toList()
        val result = LinkedHashSet<String>()
        for ((index, match) in matches.withIndex()) {
            val token = match.value
            if (index == 0) continue
            if (token.length < 2) continue
            if (!token.first().isUpperCase()) continue
            if (token.lowercase() in NON_ENTITY_CAPITALS) continue
            if (token.lowercase() in Vocabulary.SENTENCE_OPENERS && opensSentence(text, matches, index)) continue
            result.add(token.lowercase())
        }
        return result
    }

    private fun opensSentence(text: String, matches: List<MatchResult>, index: Int): Boolean {
        val previous = matches[index - 1]
        val between = text.substring(previous.range.last + 1, matches[index].range.first)
        val terminator = between.firstOrNull { it in SENTENCE_END } ?: return false
        if (terminator != '.') return true

        val previousToken = previous.value.lowercase()
        return previousToken.length > 1 && previousToken !in ABBREVIATIONS
    }

    /**
     * Whether two tokens are the same word written differently: a typo, a transposition, a spelling
     * variant, or an inflection.
     *
     * `organise`/`organize` and `colour`/`color` are one edit apart. `raed`/`read` is a
     * transposition, which [withinOneTypo] only sees for tokens long enough to fuzzy-match, so short
     * tokens fall back to comparing letters — a rearrangement of the same letters is a typo far more
     * often than it is a different word. `commit`/`committed` is neither, so inflection is handled
     * separately by strict prefix.
     *
     * None of these rules can merge `austria` with `australia`, or `oregon` with `washington`.
     */
    fun isSameWord(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < MIN_FUZZY_LENGTH || b.length < MIN_FUZZY_LENGTH) {
            // Short tokens get the two edits that cannot turn one word into another one: dropping or
            // adding a letter (`mak`/`make`), and reordering them (`raed`/`read`). Substitution is
            // withheld, because at this length it is the whole difference between `cat` and `cut`.
            return isRearrangementOf(a, b) || (a.length != b.length && withinOneTypo(a, b))
        }
        if (withinOneTypo(a, b)) return true
        if (isRearrangementOf(a, b)) return true

        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        return longer.length - shorter.length <= MAX_SUFFIX_GROWTH && longer.startsWith(shorter)
    }

    /** Same letters in a different order, for tokens long enough for that to be meaningful. */
    private fun isRearrangementOf(a: String, b: String): Boolean {
        if (a.length != b.length || a.length < MIN_REARRANGEMENT_LENGTH) return false
        if (a.none { it.isLetter() }) return false
        return a.toCharArray().sorted() == b.toCharArray().sorted()
    }

    /**
     * Whether [a] and [b] are one typo apart: a single insertion, deletion, substitution, or a swap
     * of two adjacent characters.
     *
     * Transpositions are included because they are the most common way people mistype — `clera` for
     * `clear`, `cahce` for `cache` — and plain edit distance scores them as two changes, which would
     * put ordinary typos out of reach.
     *
     * Deliberately capped at one edit. At two, `Austria` and `Australia` collapse into the same
     * token and the guard built on top of this waves through the exact swap it exists to catch.
     */
    fun withinOneTypo(a: String, b: String): Boolean {
        if (a == b) return true
        val lengthDelta = a.length - b.length
        if (lengthDelta > 1 || lengthDelta < -1) return false

        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        val sameLength = shorter.length == longer.length

        var i = 0
        while (i < shorter.length && shorter[i] == longer[i]) i++
        if (i == shorter.length) return true

        if (sameLength &&
            i + 1 < shorter.length &&
            shorter[i] == longer[i + 1] &&
            shorter[i + 1] == longer[i]
        ) {
            // Adjacent swap: skip both characters and require the rest to be identical.
            return shorter.regionMatches(i + 2, longer, i + 2, shorter.length - i - 2)
        }

        // Substitution keeps both cursors aligned; an insertion advances only the longer one.
        val shorterRest = if (sameLength) i + 1 else i
        return shorter.regionMatches(shorterRest, longer, i + 1, shorter.length - shorterRest)
    }
}

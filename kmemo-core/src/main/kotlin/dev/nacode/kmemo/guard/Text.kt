package dev.nacode.kmemo.guard

/** Tokenization helpers shared by the built-in guards. */
internal object Text {

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    /** Capitalized by grammar rather than by reference. English has exactly one that matters. */
    private val NON_ENTITY_CAPITALS = setOf("i")

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
     * Tokens that look like named entities: capitalized, minus the one word that is capitalized
     * purely because it starts the prompt. `France`, `GitHub` and `OAuth` qualify in "what is the
     * capital of France"; the leading `What` does not.
     *
     * Only the very first token is exempt, not the first token of every sentence. Skipping capitals
     * after `.` `:` `;` sounds more correct and is much worse in practice, because the punctuation
     * that precedes an entity is usually not a sentence boundary at all: "Compare Python vs. Java"
     * would lose `Java`, and a templated "Country: Austria. Give me the capital." would lose
     * `Austria` — the exact field that varies between two prompts, and the only reason to run this
     * guard. A stray sentence-opening word costs nothing here, since [EntityGuard] only rejects on
     * entities unique to *both* sides and a word both prompts share cancels out.
     *
     * Returned lowercased, so `GitHub` and `Github` are the same entity.
     */
    fun entityTokens(text: String): Set<String> {
        val result = LinkedHashSet<String>()
        var first = true
        for (match in TOKEN.findAll(text)) {
            val token = match.value
            val leading = first
            first = false
            if (leading) continue
            if (token.length < 2) continue
            if (!token.first().isUpperCase()) continue
            if (token.lowercase() in NON_ENTITY_CAPITALS) continue
            result.add(token.lowercase())
        }
        return result
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

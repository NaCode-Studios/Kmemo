package dev.kmemo.guard

/**
 * Word lists the built-in guards read from.
 *
 * Every list is English-only and every guard takes its list as a constructor parameter, so adapting
 * kmemo to another language means passing your own sets rather than forking the guards.
 *
 * The lists are deliberately conservative. A word earns its place here only if including it is
 * unlikely to reject a genuine paraphrase: `today` is in [TEMPORAL_MARKERS], `next` is not, because
 * "the next step" has nothing to do with time. Same reasoning kept the ambiguous unit abbreviations
 * (`in`, `m`, `s`) out of [UNITS] — they collide with ordinary English words far too often.
 */
/**
 * A unit of measure: its canonical name, and the kind of quantity it measures.
 *
 * [dimension] is what stops [UnitGuard] comparing across kinds. `pound` is a mass and `gbp` is a
 * currency, so "250 euros in British pounds" and "250 EUR in GBP" look like a unit swap while being
 * the same question written two ways. Units are only comparable when they measure the same thing.
 */
public data class MeasurementUnit(
    /** Name shared by every spelling of this unit. */
    public val canonical: String,
    /** The kind of quantity — `length`, `mass`, `currency`, `timezone`, and so on. */
    public val dimension: String,
)

public object Vocabulary {

    /**
     * Function words, question words, politeness fillers and the fragments left behind when a
     * tokenizer splits contractions (`don't` → `don`, `t`).
     *
     * Only [LexicalDivergenceGuard] removes these. The other guards read raw tokens, so putting
     * `not` or `before` here does not blind them.
     */
    public val STOPWORDS: Set<String> = setOf(
        // articles, conjunctions, prepositions
        "a", "an", "the", "and", "or", "but", "nor", "so", "yet", "of", "at", "by", "for", "with",
        "about", "against", "between", "into", "through", "during", "above", "below", "from", "up",
        "down", "in", "out", "on", "off", "over", "under", "again", "then", "once", "here", "there",
        "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "only", "own",
        "same", "too", "very", "as", "if", "than", "that", "this", "these", "those", "to", "until",
        "while", "because", "since", "also", "just", "still", "even", "ever",
        // pronouns
        "i", "me", "my", "mine", "myself", "we", "us", "our", "ours", "you", "your", "yours", "he",
        "him", "his", "she", "her", "hers", "it", "its", "they", "them", "their", "theirs", "who",
        "whom", "whose", "which", "what", "when", "where", "why", "how", "anyone", "someone",
        // auxiliaries and their contraction fragments
        "am", "is", "are", "was", "were", "be", "been", "being", "have", "has", "had", "having",
        "do", "does", "did", "doing", "will", "would", "shall", "should", "can", "could", "may",
        "might", "must", "cant", "dont", "doesnt", "isnt", "arent", "wasnt", "werent", "didnt",
        "wont", "couldnt", "shouldnt", "wouldnt", "hasnt", "havent", "hadnt", "im", "ive", "id",
        "youre", "theyre", "lets", "s", "t", "d", "ll", "re", "ve", "m",
        // What a tokenizer that splits on apostrophes leaves behind: "doesn't" becomes "doesn" and
        // "t". Without these, the stem counts as a content word and every contraction looks like a
        // prompt with an extra term in it.
        "doesn", "don", "isn", "aren", "wasn", "weren", "didn", "won", "couldn", "shouldn",
        "wouldn", "hasn", "haven", "hadn", "ain", "shan", "mustn", "needn",
        // politeness and conversational filler
        "please", "thanks", "thank", "thankyou", "hi", "hello", "hey", "kindly", "sorry", "ok",
        "okay", "sure", "well", "actually", "basically", "really", "maybe",
        // low-signal verbs that survive most rewordings
        "tell", "know", "want", "need", "get", "give", "show", "help", "let", "say", "ask", "wonder",
        "wondering", "looking", "trying", "try", "possible", "way", "ways", "thing", "things",
    )

    /**
     * Words that may open a sentence without naming anything, read by [Text.entityTokens].
     *
     * English capitalizes the first word of every sentence, so a multi-sentence prompt hands the
     * entity guard a capital that means nothing. Excusing all of them loses real entities —
     * "…holiday. Austria is where I want to go." — so only words on this list are excused, and
     * anything else that opens a sentence is still treated as a name.
     */
    public val SENTENCE_OPENERS: Set<String> = STOPWORDS + setOf(
        "explain", "describe", "list", "write", "create", "make", "find", "compare", "summarize",
        "summarise", "consider", "imagine", "suppose", "note", "see", "check", "use", "add",
        "remove", "build", "generate", "draft", "outline", "walk", "assume", "given", "please",
        "also", "then", "finally", "first", "second", "next", "lastly", "additionally", "however",
    )

    /**
     * Unit and currency tokens, read by [UnitGuard], each mapped to a canonical name.
     *
     * The canonical form is what makes `km` and `kilometers` the same unit. A flat set would treat
     * every abbreviation-versus-spelling difference as a unit swap and refuse "Convert 50 km to
     * miles" against "Convert 50 kilometers to miles" — two ways of writing one question.
     *
     * Two tokens may share a canonical name only when they denote the **same quantity**. Grouping by
     * "sounds like the same unit" turns this map into a source of false hits: `ton` and `tonne`
     * differ by 9%, `est` and `edt` by an hour, and collapsing either pair means the cache answers a
     * question nobody asked. Spelling variants alias; near-synonyms do not.
     *
     * Ambiguous abbreviations are excluded on purpose: `in` (inch), `m` (metre), `s` (second),
     * `try` (Turkish lira) and `real` (Brazilian real) all appear far more often as ordinary words
     * than as units, and a guard that fires on "how do I try this" is worse than no guard.
     */
    public val UNITS: Map<String, MeasurementUnit> = buildMap {
        // length
        unit("length", "mm")
        unit("length", "cm")
        unit("length", "km", "kilometer", "kilometers", "kilometre", "kilometres")
        unit("length", "meter", "meters", "metre", "metres")
        unit("length", "inch", "inches")
        unit("length", "ft", "foot", "feet")
        unit("length", "yard", "yards")
        unit("length", "mile", "mi", "miles")
        // mass
        unit("mass", "mg")
        unit("mass", "gram", "grams")
        unit("mass", "kg", "kilogram", "kilograms")
        unit("mass", "pound", "lb", "lbs", "pounds")
        unit("mass", "ounce", "oz", "ounces")
        // Short ton (907 kg) and metric tonne (1000 kg) are different units, not spellings.
        unit("mass", "ton", "tons")
        unit("mass", "tonne", "tonnes")
        unit("mass", "stone", "stones")
        // volume
        unit("volume", "ml")
        unit("volume", "cl")
        unit("volume", "dl")
        unit("volume", "liter", "liters", "litre", "litres")
        unit("volume", "gallon", "gallons")
        unit("volume", "pint", "pints")
        unit("volume", "quart", "quarts")
        unit("volume", "cup", "cups")
        unit("volume", "tbsp", "tablespoon", "tablespoons")
        unit("volume", "tsp", "teaspoon", "teaspoons")
        // temperature
        unit("temperature", "celsius", "centigrade")
        unit("temperature", "fahrenheit")
        unit("temperature", "kelvin")
        // currency
        unit("currency", "usd", "dollar", "dollars")
        unit("currency", "eur", "euro", "euros")
        unit("currency", "gbp")
        unit("currency", "jpy", "yen")
        unit("currency", "chf", "franc", "francs")
        unit("currency", "cad")
        unit("currency", "aud")
        unit("currency", "nzd")
        unit("currency", "inr", "rupee", "rupees")
        unit("currency", "cny")
        unit("currency", "hkd")
        unit("currency", "sgd")
        unit("currency", "sek")
        unit("currency", "nok")
        unit("currency", "dkk")
        unit("currency", "pln")
        unit("currency", "czk")
        unit("currency", "huf")
        unit("currency", "zar")
        unit("currency", "brl")
        unit("currency", "mxn", "peso", "pesos")
        unit("currency", "krw")
        unit("currency", "btc", "bitcoin")
        unit("currency", "eth", "ether")
        // digital storage
        unit("data", "kb", "kilobyte", "kilobytes")
        unit("data", "mb", "megabyte", "megabytes")
        unit("data", "gb", "gigabyte", "gigabytes")
        unit("data", "tb", "terabyte", "terabytes")
        unit("data", "pb")
        unit("data", "kib")
        unit("data", "mib")
        unit("data", "gib")
        unit("data", "tib")
        unit("data", "byte", "bytes")
        // time and speed
        unit("time", "ms", "millisecond", "milliseconds")
        unit("time", "second", "seconds")
        unit("time", "minute", "minutes")
        unit("time", "hour", "hours")
        unit("time", "day", "days")
        unit("time", "week", "weeks")
        unit("time", "month", "months")
        unit("time", "year", "years")
        unit("speed", "mph")
        unit("speed", "kph", "kmh")
        unit("speed", "knots")
        // Time zones. UTC and GMT are the same offset; standard and daylight time are one hour
        // apart, so each keeps its own canonical name.
        unit("timezone", "utc", "gmt")
        unit("timezone", "cet")
        unit("timezone", "cest")
        unit("timezone", "est")
        unit("timezone", "edt")
        unit("timezone", "pst")
        unit("timezone", "pdt")
        unit("timezone", "bst")
    }

    private fun MutableMap<String, MeasurementUnit>.unit(
        dimension: String,
        canonical: String,
        vararg variants: String,
    ) {
        val value = MeasurementUnit(canonical, dimension)
        put(canonical, value)
        for (variant in variants) put(variant, value)
    }

    /**
     * Words that flip an answer, read by [AntonymGuard]. Symmetric: listing `enable to disable`
     * also covers the reverse.
     *
     * Inflected forms are listed explicitly rather than stemmed, because stemming `less` to `les`
     * to make it pair with `more` is the kind of clever that breaks quietly.
     */
    public val ANTONYMS: Set<Pair<String, String>> = setOf(
        "enable" to "disable", "enabled" to "disabled", "enabling" to "disabling",
        "increase" to "decrease", "increasing" to "decreasing", "increment" to "decrement",
        "raise" to "lower", "add" to "remove", "adding" to "removing", "install" to "uninstall",
        "start" to "stop", "starting" to "stopping", "open" to "close", "opening" to "closing",
        "connect" to "disconnect", "encrypt" to "decrypt", "encode" to "decode",
        "compress" to "decompress", "mount" to "unmount", "lock" to "unlock", "allow" to "block",
        "allow" to "deny", "include" to "exclude", "import" to "export", "upload" to "download",
        "show" to "hide", "expand" to "collapse", "attach" to "detach",
        "subscribe" to "unsubscribe", "activate" to "deactivate", "enter" to "exit",
        "push" to "pull", "serialize" to "deserialize", "commit" to "rollback",
        "create" to "delete", "insert" to "delete", "grant" to "revoke", "accept" to "reject",
        "approve" to "reject", "join" to "leave", "merge" to "split",
        "minimum" to "maximum", "min" to "max", "ascending" to "descending", "asc" to "desc",
        "before" to "after", "first" to "last", "oldest" to "newest", "best" to "worst",
        "advantage" to "disadvantage", "advantages" to "disadvantages", "pros" to "cons",
        "benefit" to "drawback", "benefits" to "drawbacks", "buy" to "sell", "buying" to "selling",
        "deposit" to "withdraw", "credit" to "debit", "profit" to "loss", "gains" to "losses",
        "more" to "less", "more" to "fewer", "faster" to "slower", "cheaper" to "pricier",
        "up" to "down", "on" to "off", "true" to "false", "valid" to "invalid",
        "safe" to "unsafe", "secure" to "insecure", "public" to "private",
        "synchronous" to "asynchronous", "sync" to "async", "mutable" to "immutable",
        "nullable" to "nonnullable", "positive" to "negative", "left" to "right",
        "next" to "last", "next" to "previous", "gain" to "lose", "gaining" to "losing",
        "hot" to "cold", "warm" to "cool", "humid" to "dry", "wet" to "dry", "heavy" to "light",
        "long" to "short", "high" to "low", "fast" to "slow", "big" to "small", "old" to "new",
        "strong" to "weak", "cheap" to "expensive", "full" to "empty", "thick" to "thin",
    )

    /**
     * Negation markers, read by [NegationGuard]. Contractions are caught separately by an `n't`
     * substring test, so `isn't` needs no entry.
     */
    public val NEGATION_MARKERS: Set<String> = setOf(
        "not", "no", "never", "without", "cannot", "none", "neither", "nor", "nothing", "non",
        "unable", "lacks", "lacking",
    )

    /**
     * Absolute time references, read by [TemporalGuard].
     *
     * Relative words like `next`, `last` and `this` are excluded: "the last commit" and "the next
     * step" are not time references, and treating them as such rejects ordinary paraphrases.
     */
    public val TEMPORAL_MARKERS: Set<String> = setOf(
        "today", "tomorrow", "yesterday", "tonight", "now", "currently", "current", "latest",
        "nowadays", "presently", "upcoming",
    )

    /**
     * Words that describe the *shape* of the answer rather than its subject, read by [ScopeGuard]:
     * output format, length and depth.
     *
     * Ambiguous ones are excluded. `code`, `list` and `table` name output formats but appear far
     * more often as the thing being asked about — "how do I sort a list" is not a request for a
     * list-shaped answer.
     */
    public val SCOPE_MARKERS: Set<String> = setOf(
        // requested format
        "example", "examples", "snippet", "theory", "proof", "derive", "derivation", "diagram",
        "bullet", "bullets", "timeline", "essay", "haiku", "sonnet", "poem", "story", "summary",
        "summarize", "summarise", "tldr", "outline", "checklist", "tutorial", "walkthrough",
        // requested depth and length
        "detailed", "detail", "details", "depth", "brief", "briefly", "concise", "comprehensive", "thorough",
        "exhaustive", "overview", "advanced", "beginner", "eli5", "extensive",
        "sentence", "sentences", "paragraph", "paragraphs",
    )

    /**
     * Cues that make argument order significant, read by [DirectionGuard]: comparisons and
     * conversions. "A vs B" and "B vs A" are different questions; "sort a list in Python" and "in
     * Python, sort a list" are not.
     */
    public val DIRECTIONAL_CUES: Set<String> = setOf(
        // comparisons
        "than", "versus", "vs", "better", "worse", "faster", "slower", "cheaper", "pricier",
        "safer", "stronger", "weaker", "bigger", "smaller", "larger", "higher", "lower",
        "superior", "inferior", "outperforms", "beats", "preferable", "prefer",
        // conversions and moves, where the source and the target are not interchangeable
        "convert", "converts", "converting", "conversion", "exchange", "translate", "migrate",
        "migrating", "migration", "port", "porting", "switch", "switching", "move", "moving",
        "transfer", "upgrade", "downgrade", "rename", "map",
    )
}

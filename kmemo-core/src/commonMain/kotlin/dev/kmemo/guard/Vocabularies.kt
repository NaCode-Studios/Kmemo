package dev.kmemo.guard


/**
 * Curated [GuardVocabulary] packs for the highest-traffic languages, and a [forLocale] lookup.
 *
 * ```kotlin
 * val cache = SemanticCache(embedder, guards = MatchGuards.standard(Vocabularies.ITALIAN))
 * // or, equivalently, MatchGuards.standard(Locale.ITALIAN)
 * ```
 *
 * ### The bar these packs are held to
 *
 * The same one as English: a marker earns its place only if adding it will not reject a genuine
 * paraphrase, and two units alias only when they are spellings of the **same** quantity. The packs are
 * deliberately conservative — an incomplete marker set makes the guards *miss* a near-miss (lower
 * recall), which costs one model call; it never turns into a false hit. Grow a pack from your own
 * traffic when you need more, and measure the additions the way the English list was measured.
 *
 * Each pack is validated against a small language-specific near-miss corpus (see the guard tests): the
 * near-misses are caught and the paraphrases are kept. That is the floor, not a claim of parity with
 * the heavily-tuned English list.
 */
public object Vocabularies {

    /** The English pack, for symmetry with the others; identical to [GuardVocabulary.ENGLISH]. */
    public val ENGLISH: GuardVocabulary = GuardVocabulary.ENGLISH

    /** Italian. */
    public val ITALIAN: GuardVocabulary = GuardVocabulary(
        stopwords = setOf(
            "il", "lo", "la", "i", "gli", "le", "un", "uno", "una", "l", "un'",
            "di", "a", "da", "in", "con", "su", "per", "tra", "fra",
            "del", "dello", "della", "dei", "degli", "delle", "dell", "al", "allo", "alla", "ai", "agli",
            "alle", "dal", "dalla", "nel", "nella", "nei", "negli", "nelle", "sul", "sulla", "col",
            "e", "ed", "o", "od", "ma", "se", "che", "come", "quando", "mentre", "anche", "però",
            "perché", "poiché", "quindi", "cioè", "ovvero", "oppure",
            "io", "tu", "lui", "lei", "noi", "voi", "loro", "mi", "ti", "ci", "vi", "si", "me", "te",
            "ne", "questo", "questa", "questi", "queste", "quello", "quella", "quelli", "quelle",
            "mio", "mia", "tuo", "tua", "suo", "sua", "nostro", "vostro",
            "è", "sono", "sei", "siamo", "siete", "ho", "hai", "ha", "abbiamo", "avete", "hanno",
            "era", "erano", "essere", "avere", "sarà", "stato", "stata",
            "cosa", "chi", "dove", "quale", "quali", "quanto", "quanta", "quanti", "quante",
            "per favore", "grazie", "ciao", "salve", "prego", "scusa", "ok",
            "dire", "dimmi", "sapere", "volere", "potere", "dovere", "modo", "cosa",
        ),
        sentenceOpeners = setOf(
            "spiega", "spiegami", "descrivi", "elenca", "scrivi", "crea", "fai", "trova", "confronta",
            "riassumi", "considera", "immagina", "supponi", "mostra", "mostrami", "dammi", "dimmi",
            "genera", "inoltre", "poi", "infine", "prima", "quindi", "tuttavia", "però",
        ),
        // Italian capitalizes no pronoun mid-sentence, so grammar produces no false entities here.
        nonEntityCapitals = emptySet(),
        negationMarkers = setOf(
            "non", "no", "mai", "senza", "né", "niente", "nulla", "nessuno", "nessuna",
            "neanche", "nemmeno", "neppure",
        ),
        antonyms = setOf(
            "aumentare" to "diminuire", "abilitare" to "disabilitare", "attivare" to "disattivare",
            "aprire" to "chiudere", "avviare" to "fermare", "connettere" to "disconnettere",
            "installare" to "disinstallare", "aggiungere" to "rimuovere", "includere" to "escludere",
            "caricare" to "scaricare", "mostrare" to "nascondere", "accettare" to "rifiutare",
            "comprare" to "vendere", "salire" to "scendere", "cifrare" to "decifrare",
            "min" to "max", "minimo" to "massimo", "prima" to "dopo", "primo" to "ultimo",
            "migliore" to "peggiore", "vantaggi" to "svantaggi", "pro" to "contro",
            "vero" to "falso", "valido" to "invalido", "sicuro" to "insicuro", "pubblico" to "privato",
            "caldo" to "freddo", "grande" to "piccolo", "veloce" to "lento", "alto" to "basso",
            "vecchio" to "nuovo", "pieno" to "vuoto", "più" to "meno", "crescente" to "decrescente",
        ),
        temporalMarkers = setOf(
            "oggi", "domani", "ieri", "stasera", "stanotte", "adesso", "attualmente", "attuale",
        ),
        scopeMarkers = setOf(
            "esempio", "esempi", "riassunto", "sintesi", "dettagliato", "dettaglio", "dettagli",
            "breve", "conciso", "approfondito", "panoramica", "completo", "esaustivo", "passo",
        ),
        directionalCues = setOf(
            "rispetto", "contro", "meglio", "peggio", "migliore", "peggiore", "superiore", "inferiore",
            "converti", "convertire", "conversione", "cambia", "cambiare", "trasforma", "trasformare",
            "traduci", "tradurre", "sposta", "spostare", "migra", "migrare",
        ),
        units = unitsWith(
            "chilometro" to "km", "chilometri" to "km", "metro" to "meter", "metri" to "meter",
            "chilogrammo" to "kg", "chilogrammi" to "kg", "grammo" to "gram", "grammi" to "gram",
            "libbra" to "pound", "libbre" to "pound", "litro" to "liter", "litri" to "liter",
            "dollaro" to "usd", "dollari" to "usd", "sterlina" to "gbp", "sterline" to "gbp",
            "secondo" to "second", "secondi" to "second", "minuto" to "minute", "minuti" to "minute",
            "ora" to "hour", "ore" to "hour", "giorno" to "day", "giorni" to "day",
        ),
    )

    /** Spanish. */
    public val SPANISH: GuardVocabulary = GuardVocabulary(
        stopwords = setOf(
            "el", "la", "los", "las", "un", "una", "unos", "unas", "lo",
            "de", "a", "en", "con", "por", "para", "sin", "sobre", "entre", "desde", "hasta",
            "del", "al",
            "y", "e", "o", "u", "pero", "si", "que", "como", "cuando", "mientras", "también",
            "porque", "pues", "así",
            "yo", "tú", "él", "ella", "nosotros", "vosotros", "ellos", "ellas", "me", "te", "se",
            "nos", "os", "mi", "tu", "su", "este", "esta", "estos", "estas", "ese", "esa", "esto",
            "es", "son", "soy", "eres", "somos", "está", "están", "he", "has", "ha", "hemos", "han",
            "era", "ser", "estar", "haber", "tener",
            "qué", "quién", "dónde", "cuál", "cuáles", "cuánto", "cuánta", "cuántos", "cómo",
            "por favor", "gracias", "hola", "ok",
            "decir", "dime", "saber", "querer", "poder", "manera", "cosa",
        ),
        sentenceOpeners = setOf(
            "explica", "explícame", "describe", "enumera", "escribe", "crea", "haz", "encuentra",
            "compara", "resume", "considera", "imagina", "supón", "muestra", "muéstrame", "dame",
            "dime", "genera", "además", "luego", "finalmente", "primero", "entonces", "sin embargo",
        ),
        nonEntityCapitals = emptySet(),
        negationMarkers = setOf(
            "no", "nunca", "jamás", "sin", "ni", "nada", "nadie", "ninguno", "ninguna", "tampoco",
        ),
        antonyms = setOf(
            "aumentar" to "disminuir", "habilitar" to "deshabilitar", "activar" to "desactivar",
            "abrir" to "cerrar", "iniciar" to "detener", "conectar" to "desconectar",
            "instalar" to "desinstalar", "agregar" to "quitar", "añadir" to "quitar",
            "incluir" to "excluir", "subir" to "bajar", "mostrar" to "ocultar",
            "aceptar" to "rechazar", "comprar" to "vender", "cifrar" to "descifrar",
            "min" to "max", "mínimo" to "máximo", "antes" to "después", "primero" to "último",
            "mejor" to "peor", "ventajas" to "desventajas", "pros" to "contras",
            "verdadero" to "falso", "válido" to "inválido", "seguro" to "inseguro",
            "público" to "privado", "caliente" to "frío", "grande" to "pequeño", "rápido" to "lento",
            "alto" to "bajo", "viejo" to "nuevo", "lleno" to "vacío", "más" to "menos",
            "ascendente" to "descendente",
        ),
        temporalMarkers = setOf(
            "hoy", "mañana", "ayer", "ahora", "actualmente", "actual", "anoche",
        ),
        scopeMarkers = setOf(
            "ejemplo", "ejemplos", "resumen", "detallado", "detalle", "detalles", "breve", "conciso",
            "exhaustivo", "completo", "panorámica", "paso",
        ),
        directionalCues = setOf(
            "frente", "contra", "mejor", "peor", "superior", "inferior",
            "convierte", "convertir", "conversión", "cambia", "cambiar", "transforma", "transformar",
            "traduce", "traducir", "mueve", "mover", "migra", "migrar",
        ),
        units = unitsWith(
            "kilómetro" to "km", "kilómetros" to "km", "metro" to "meter", "metros" to "meter",
            "kilogramo" to "kg", "kilogramos" to "kg", "gramo" to "gram", "gramos" to "gram",
            "libra" to "pound", "libras" to "pound", "litro" to "liter", "litros" to "liter",
            "dólar" to "usd", "dólares" to "usd",
            "segundo" to "second", "segundos" to "second", "minuto" to "minute", "minutos" to "minute",
            "hora" to "hour", "horas" to "hour", "día" to "day", "días" to "day",
        ),
    )

    /** German. */
    public val GERMAN: GuardVocabulary = GuardVocabulary(
        stopwords = setOf(
            "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
            "und", "oder", "aber", "wenn", "als", "wie", "dass", "weil", "während", "auch", "denn",
            "von", "zu", "mit", "auf", "in", "im", "an", "am", "für", "über", "unter", "bei", "aus",
            "nach", "vor", "durch", "um", "zum", "zur", "ins", "vom",
            "ich", "du", "er", "es", "wir", "ihr", "mich", "dich", "sich", "mir", "dir", "uns",
            "mein", "dein", "sein", "dieser", "diese", "dieses", "man",
            "ist", "sind", "bin", "bist", "war", "waren", "sein", "haben", "hat", "habe", "hast",
            "werden", "wird", "kann", "muss", "soll",
            "was", "wer", "wo", "welche", "welcher", "welches", "wie", "warum", "wann", "wieviel",
            "bitte", "danke", "hallo", "ok",
            "sagen", "wissen", "wollen", "können", "müssen",
        ),
        sentenceOpeners = setOf(
            "erkläre", "erklär", "beschreibe", "liste", "schreibe", "erstelle", "mache", "finde",
            "vergleiche", "fasse", "betrachte", "stell", "zeige", "gib", "generiere", "außerdem",
            "dann", "schließlich", "zuerst", "also", "jedoch",
        ),
        // German capitalizes every noun, so capitalization is a weak entity signal here; the entity
        // guard leans on substitution instead. Capitalized formal pronouns are grammar, not entities.
        nonEntityCapitals = setOf("sie", "ihnen", "ihr", "ihre", "ihrem", "ihren", "ihrer"),
        negationMarkers = setOf(
            "nicht", "kein", "keine", "keinen", "keinem", "keiner", "nie", "niemals", "ohne",
            "nichts", "niemand", "weder",
        ),
        antonyms = setOf(
            "erhöhen" to "verringern", "aktivieren" to "deaktivieren", "öffnen" to "schließen",
            "starten" to "stoppen", "verbinden" to "trennen", "installieren" to "deinstallieren",
            "hinzufügen" to "entfernen", "einschließen" to "ausschließen", "hochladen" to "herunterladen",
            "anzeigen" to "verbergen", "akzeptieren" to "ablehnen", "kaufen" to "verkaufen",
            "verschlüsseln" to "entschlüsseln", "min" to "max", "minimum" to "maximum",
            "vor" to "nach", "erste" to "letzte", "beste" to "schlechteste",
            "vorteile" to "nachteile", "wahr" to "falsch", "gültig" to "ungültig",
            "sicher" to "unsicher", "öffentlich" to "privat", "heiß" to "kalt", "groß" to "klein",
            "schnell" to "langsam", "hoch" to "niedrig", "alt" to "neu", "voll" to "leer",
            "mehr" to "weniger", "aufsteigend" to "absteigend",
        ),
        temporalMarkers = setOf(
            "heute", "morgen", "gestern", "jetzt", "aktuell", "derzeit", "momentan",
        ),
        scopeMarkers = setOf(
            "beispiel", "beispiele", "zusammenfassung", "detailliert", "details", "kurz", "knapp",
            "ausführlich", "überblick", "vollständig", "schritt",
        ),
        directionalCues = setOf(
            "gegen", "besser", "schlechter", "überlegen",
            "konvertiere", "konvertieren", "umwandeln", "wechseln", "ändern", "übersetze", "übersetzen",
            "verschieben", "migrieren", "wandeln",
        ),
        units = unitsWith(
            "kilometer" to "km", "meter" to "meter", "kilogramm" to "kg", "gramm" to "gram",
            "pfund" to "pound", "liter" to "liter", "dollar" to "usd",
            "sekunde" to "second", "sekunden" to "second", "minute" to "minute", "minuten" to "minute",
            "stunde" to "hour", "stunden" to "hour", "tag" to "day", "tage" to "day",
        ),
    )

    /** French. */
    public val FRENCH: GuardVocabulary = GuardVocabulary(
        stopwords = setOf(
            "le", "la", "les", "un", "une", "des", "l", "du", "de", "d", "au", "aux",
            "à", "en", "dans", "avec", "par", "pour", "sans", "sur", "sous", "entre", "vers", "chez",
            "et", "ou", "mais", "si", "que", "qu", "comme", "quand", "pendant", "aussi", "car",
            "parce", "donc",
            "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "me", "te", "se", "moi", "toi",
            "mon", "ton", "son", "ce", "cette", "ces", "cet", "on",
            "est", "sont", "suis", "es", "sommes", "êtes", "ai", "as", "a", "avons", "avez", "ont",
            "était", "être", "avoir", "sera",
            "quoi", "qui", "où", "quel", "quelle", "quels", "quelles", "combien", "comment", "pourquoi",
            "s'il", "vous plaît", "merci", "bonjour", "salut", "ok",
            "dire", "savoir", "vouloir", "pouvoir", "façon", "chose",
        ),
        sentenceOpeners = setOf(
            "explique", "expliquez", "décris", "décrivez", "liste", "écris", "crée", "fais", "trouve",
            "compare", "résume", "considère", "imagine", "suppose", "montre", "donne", "génère",
            "aussi", "puis", "enfin", "d'abord", "donc", "cependant",
        ),
        nonEntityCapitals = emptySet(),
        negationMarkers = setOf(
            "ne", "n", "pas", "non", "jamais", "sans", "ni", "rien", "personne", "aucun", "aucune",
            "nul",
        ),
        antonyms = setOf(
            "augmenter" to "diminuer", "activer" to "désactiver", "ouvrir" to "fermer",
            "démarrer" to "arrêter", "connecter" to "déconnecter", "installer" to "désinstaller",
            "ajouter" to "supprimer", "inclure" to "exclure", "monter" to "descendre",
            "afficher" to "masquer", "accepter" to "refuser", "acheter" to "vendre",
            "chiffrer" to "déchiffrer", "min" to "max", "minimum" to "maximum",
            "avant" to "après", "premier" to "dernier", "meilleur" to "pire",
            "avantages" to "inconvénients", "vrai" to "faux", "valide" to "invalide",
            "sûr" to "risqué", "public" to "privé", "chaud" to "froid", "grand" to "petit",
            "rapide" to "lent", "haut" to "bas", "vieux" to "nouveau", "plein" to "vide",
            "plus" to "moins", "croissant" to "décroissant",
        ),
        // "aujourd'hui" is intentionally absent: the tokenizer splits on the apostrophe, so it could
        // never match as one token. "hier" / "demain" carry the day-level temporal near-misses.
        temporalMarkers = setOf(
            "demain", "hier", "maintenant", "actuellement", "actuel",
        ),
        scopeMarkers = setOf(
            "exemple", "exemples", "résumé", "détaillé", "détail", "détails", "bref", "concis",
            "exhaustif", "complet", "aperçu", "étape",
        ),
        directionalCues = setOf(
            "contre", "mieux", "meilleur", "pire", "supérieur", "inférieur",
            "convertis", "convertir", "conversion", "change", "changer", "transforme", "transformer",
            "traduis", "traduire", "déplace", "déplacer", "migre", "migrer",
        ),
        units = unitsWith(
            "kilomètre" to "km", "kilomètres" to "km", "mètre" to "meter", "mètres" to "meter",
            "kilogramme" to "kg", "kilogrammes" to "kg", "gramme" to "gram", "grammes" to "gram",
            "livre" to "pound", "livres" to "pound", "litre" to "liter", "litres" to "liter",
            "seconde" to "second", "secondes" to "second", "heure" to "hour", "heures" to "hour",
            "jour" to "day", "jours" to "day", "semaine" to "week", "semaines" to "week",
        ),
    )

    private val byLanguage: Map<String, GuardVocabulary> = mapOf(
        "en" to ENGLISH,
        "it" to ITALIAN,
        "es" to SPANISH,
        "de" to GERMAN,
        "fr" to FRENCH,
    )

    /**
     * The pack for an ISO 639 language code.
     *
     * Matched on the language alone, lowercased, so `it`, `IT` and the language part of `it-CH` all
     * resolve to [ITALIAN]. On the JVM there is a `forLocale` taking a `java.util.Locale`.
     *
     * @throws IllegalArgumentException if no pack ships for the language. The message lists the
     *   supported codes; pass a [GuardVocabulary] to [MatchGuards.standard] directly to use your own.
     */
    public fun forLanguage(code: String): GuardVocabulary {
        val language = code.substringBefore('-').substringBefore('_').lowercase()
        return byLanguage[language]
            ?: throw IllegalArgumentException(
                "no guard vocabulary ships for language '$language'. Supported: " +
                    "${byLanguage.keys.sorted()}. Build a GuardVocabulary and pass it to " +
                    "MatchGuards.standard(vocabulary) to add your own.",
            )
    }

    /**
     * Extends the shared unit base ([Vocabulary.UNITS], which already covers language-independent
     * symbols like `km`, `kg`, `usd`) with local spellings, each aliased to an existing canonical unit
     * so a local spelling and its symbol are treated as one unit — never as a swap.
     */
    private fun unitsWith(vararg spellings: Pair<String, String>): Map<String, MeasurementUnit> {
        val base = Vocabulary.UNITS
        val result = LinkedHashMap(base)
        for ((spelling, canonicalKey) in spellings) {
            val unit = requireNotNull(base[canonicalKey]) {
                "unknown canonical unit key '$canonicalKey' for spelling '$spelling'"
            }
            result[spelling] = unit
        }
        return result
    }
}

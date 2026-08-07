package dev.kmemo.store.file

import dev.kmemo.CacheEntry
import kotlin.time.Instant

/**
 * One thing that happened to the store, as text.
 *
 * The journal replays operations rather than storing a snapshot, so what is written is the same set of
 * calls the store received. That is what lets `invalidateByTag` be durable without the journal holding a
 * tag index of its own: the operation is recorded and re-applied to a memory-resident index that does
 * hold one.
 */
internal sealed interface Record {
    data class Put(val entry: CacheEntry) : Record
    data class Remove(val id: String) : Record
    data class Clear(val scope: String?) : Record
    data class InvalidateTag(val tag: String, val scope: String?) : Record
}

/**
 * The journal's on-disk format: a kind character, then length-prefixed fields, one after another.
 *
 * Length prefixes rather than a separator, which means no escaping and no encoding. A prompt containing
 * a newline, a comma, a quote or the separator itself is written exactly as it is and read back exactly
 * as it was, because the reader is told how many characters to take before it takes them. The
 * alternative shapes all cost something: a separator needs escaping, and escaping is where a parser
 * silently corrupts one entry in ten thousand; base64 avoids that and adds a third to the file for text
 * that is already text; JSON adds a dependency to a module whose whole point is that the core has one.
 *
 * There are no record separators either. Parsing is sequential and self-delimiting: a record says how
 * long each of its fields is, so the next record starts where the last one ended. A truncated tail, from
 * a process that died mid-append, therefore fails to parse and is dropped, which is what a cache wants:
 * the entries before it are still good.
 */
internal object Journal {

    private const val PUT = 'P'
    private const val REMOVE = 'R'
    private const val CLEAR = 'C'
    private const val TAG = 'T'

    /**
     * Base 36 for the float bits: shorter than decimal, exact either way.
     *
     * Exactness is the requirement, and it is why the bits are written rather than the value. A float
     * printed as a decimal and parsed back is not guaranteed to be the same float on every platform, and
     * a cache whose vectors shift by one bit between restarts is a cache whose similarities move for a
     * reason nobody could find.
     */
    private const val RADIX = 36

    fun encode(record: Record): String = when (record) {
        is Record.Put -> buildString {
            append(PUT)
            val entry = record.entry
            field(entry.id)
            field(entry.scope)
            field(entry.prompt)
            field(entry.response)
            field(floats(entry.embedding))
            field(entry.createdAt.epochSeconds.toString())
            field(entry.createdAt.nanosecondsOfSecond.toString())
            field(pairs(entry.metadata))
            field(collection(entry.tags))
            field(entry.embedder)
            field(entry.chunkLengths.joinToString(","))
        }
        is Record.Remove -> buildString {
            append(REMOVE)
            field(record.id)
        }
        is Record.Clear -> buildString {
            append(CLEAR)
            field(optional(record.scope))
        }
        is Record.InvalidateTag -> buildString {
            append(TAG)
            field(record.tag)
            field(optional(record.scope))
        }
    }

    /**
     * Every record the text holds, stopping at the first one that does not parse.
     *
     * Stopping rather than throwing. A journal whose tail was truncated by a process that died
     * mid-append is the ordinary case rather than a corruption: the records before the tear are intact
     * and describe a cache that was real. Refusing to open the store would turn one lost write into a
     * lost cache.
     */
    fun decode(text: String): List<Record> {
        val records = ArrayList<Record>()
        val reader = Reader(text)
        while (reader.hasMore()) {
            val record = runCatching { reader.record() }.getOrNull() ?: break
            records += record
        }
        return records
    }

    private fun StringBuilder.field(value: String) {
        append(value.length)
        append(':')
        append(value)
    }

    /**
     * A nullable string, with the null tagged rather than implied.
     *
     * `clear(scope = null)` empties the whole store and `clear(scope = "")` empties one scope that
     * happens to be named with the empty string. Writing both as nothing would replay the second as the
     * first, which is a cache emptied on restart for a reason nobody could reconstruct.
     */
    private fun optional(value: String?): String = if (value == null) "-" else "+$value"

    private fun floats(embedding: FloatArray): String =
        embedding.joinToString(",") { it.toRawBits().toUInt().toString(RADIX) }

    private fun collection(values: Collection<String>): String =
        values.joinToString(",") { "${it.length}:$it" }

    private fun pairs(map: Map<String, String>): String =
        map.entries.joinToString(",") { (key, value) -> "${key.length}:$key${value.length}:$value" }

    private class Reader(private val text: String) {
        private var at = 0

        fun hasMore(): Boolean = at < text.length

        /** Skips the comma that separates two elements inside a compound field. */
        fun skipSeparator() {
            at++
        }

        fun record(): Record {
            return when (val kind = text[at++]) {
                PUT -> put()
                REMOVE -> Record.Remove(field())
                CLEAR -> Record.Clear(optional(field()))
                TAG -> {
                    val tag = field()
                    Record.InvalidateTag(tag, optional(field()))
                }
                else -> error("unknown journal record '$kind'")
            }
        }

        /**
         * Read into locals rather than straight into the constructor call. The fields are positional in
         * the file and a reader that relied on argument evaluation order to keep them in step would be
         * one refactor away from writing a prompt into the response column.
         */
        private fun put(): Record.Put {
            val id = field()
            val scope = field()
            val prompt = field()
            val response = field()
            val embedding = floats(field())
            val seconds = field().toLong()
            val nanos = field().toLong()
            val metadata = pairs(field())
            val tags = collection(field()).toSet()
            val embedder = field()
            val chunks = field()
            return Record.Put(
                CacheEntry(
                    id = id,
                    scope = scope,
                    prompt = prompt,
                    response = response,
                    embedding = embedding,
                    createdAt = Instant.fromEpochSeconds(seconds, nanos),
                    metadata = metadata,
                    tags = tags,
                    embedder = embedder,
                    chunkLengths = if (chunks.isEmpty()) emptyList() else chunks.split(",").map(String::toInt),
                ),
            )
        }

        fun field(): String {
            val colon = text.indexOf(':', at)
            require(colon >= 0) { "a journal field has no length prefix" }
            val length = text.substring(at, colon).toInt()
            val start = colon + 1
            val end = start + length
            require(end <= text.length) { "a journal field claims $length characters and the file ends" }
            at = end
            return text.substring(start, end)
        }

        private fun optional(value: String): String? =
            if (value == "-") null else value.substring(1)

        private fun floats(value: String): FloatArray {
            if (value.isEmpty()) return FloatArray(0)
            val parts = value.split(",")
            return FloatArray(parts.size) { Float.fromBits(parts[it].toUInt(RADIX).toInt()) }
        }

        private fun pairs(value: String): Map<String, String> {
            if (value.isEmpty()) return emptyMap()
            val map = LinkedHashMap<String, String>()
            val reader = Reader(value)
            while (reader.hasMore()) {
                val key = reader.field()
                map[key] = reader.field()
                if (reader.hasMore()) reader.skipSeparator()
            }
            return map
        }

        private fun collection(value: String): List<String> {
            if (value.isEmpty()) return emptyList()
            val values = ArrayList<String>()
            val reader = Reader(value)
            while (reader.hasMore()) {
                values += reader.field()
                if (reader.hasMore()) reader.skipSeparator()
            }
            return values
        }
    }
}

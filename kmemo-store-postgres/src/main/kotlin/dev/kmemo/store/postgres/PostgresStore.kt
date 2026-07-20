package dev.kmemo.store.postgres

import dev.kmemo.CacheEntry
import dev.kmemo.CacheStore
import dev.kmemo.ScoredEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * A durable [CacheStore] backed by Postgres with the **pgvector** extension.
 *
 * This is the "durable semantic cache as a one-dependency choice": point it at a Postgres you already
 * run and cached answers survive a restart and are shared across processes. As with every kmemo store,
 * the adapter reimplements no match logic — the nearest-neighbour search is pgvector's cosine-distance
 * operator (`<=>`), and [dev.kmemo.SemanticCache] still owns threshold, guards and verification.
 *
 * **Schema.** One table (created on first use, or provision it yourself from the shipped `schema.sql`):
 * `id` primary key, `scope`, `prompt`, `response`, `embedding vector`, `created_at`, `expires_at`
 * (nullable — `null` never expires), `metadata jsonb`. The `embedding` column is left
 * dimension-unconstrained so mixed embedding sizes are a storage error at query time, not a schema
 * wall. The search is an exact scan by default (correct, and matching the conformance suite); add an
 * HNSW/IVFFlat index on `embedding` once your dimension is fixed to scale it.
 *
 * **Expiry.** Every read filters on `expires_at IS NULL OR expires_at > now`, where `now` comes from
 * the injected [clock] — so expiry is deterministic under a fixed clock in tests, and honest under the
 * system clock in production. [purgeExpired] deletes the dead rows when you want the space back.
 *
 * @param dataSource a pooled Postgres `DataSource`; the Postgres JDBC driver is the caller's dependency.
 * @param table table name (validated as a plain identifier, since it is interpolated into SQL).
 * @param ttl how long an entry stays valid, or `null` to keep it until removed.
 * @param clock time source; substitute a fixed clock in tests instead of sleeping.
 */
public class PostgresStore(
    private val dataSource: DataSource,
    private val table: String = "kmemo_cache",
    private val ttl: Duration? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CacheStore {

    init {
        require(ttl == null || ttl.isPositive()) { "ttl must be positive, was $ttl" }
        require(table.matches(IDENTIFIER)) {
            "table must be a plain SQL identifier (letters, digits, underscore), was '$table'"
        }
    }

    private val json = Json
    private val schemaMutex = Mutex()
    @Volatile private var schemaReady = false

    override suspend fun put(entry: CacheEntry): Unit = withConnection { connection ->
        val expiresAt = ttl?.let { now().plus(it.inWholeMilliseconds, java.time.temporal.ChronoUnit.MILLIS) }
        connection.prepareStatement(
            """
            INSERT INTO $table (id, scope, prompt, response, embedding, created_at, expires_at, metadata)
            VALUES (?, ?, ?, ?, CAST(? AS vector), ?, ?, CAST(? AS jsonb))
            ON CONFLICT (id) DO UPDATE SET
                scope = EXCLUDED.scope, prompt = EXCLUDED.prompt, response = EXCLUDED.response,
                embedding = EXCLUDED.embedding, created_at = EXCLUDED.created_at,
                expires_at = EXCLUDED.expires_at, metadata = EXCLUDED.metadata
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, entry.id)
            statement.setString(2, entry.scope)
            statement.setString(3, entry.prompt)
            statement.setString(4, entry.response)
            statement.setString(5, encodeVector(entry.embedding))
            statement.setObject(6, entry.createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(7, expiresAt)
            statement.setString(8, encodeMetadata(entry.metadata))
            statement.executeUpdate()
        }
    }

    override suspend fun search(scope: String, embedding: FloatArray, limit: Int): List<ScoredEntry> {
        require(limit > 0) { "limit must be positive, was $limit" }
        return withConnection { connection ->
            connection.prepareStatement(
                """
                SELECT id, scope, prompt, response, embedding, created_at, metadata,
                       embedding <=> CAST(? AS vector) AS distance
                FROM $table
                WHERE scope = ? AND (expires_at IS NULL OR expires_at > ?)
                ORDER BY distance ASC
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, encodeVector(embedding))
                statement.setString(2, scope)
                statement.setObject(3, now())
                statement.setInt(4, limit)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val entry = CacheEntry(
                                id = rows.getString("id"),
                                scope = rows.getString("scope"),
                                prompt = rows.getString("prompt"),
                                response = rows.getString("response"),
                                embedding = decodeVector(rows.getString("embedding")),
                                createdAt = rows.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                                metadata = decodeMetadata(rows.getString("metadata")),
                            )
                            // pgvector `<=>` is cosine distance; similarity is 1 - distance.
                            add(ScoredEntry(entry, 1.0 - rows.getDouble("distance")))
                        }
                    }
                }
            }
        }
    }

    override suspend fun remove(id: String): Boolean = withConnection { connection ->
        connection.prepareStatement("DELETE FROM $table WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeUpdate() > 0
        }
    }

    override suspend fun clear(scope: String?): Unit = withConnection { connection ->
        if (scope == null) {
            connection.prepareStatement("DELETE FROM $table").use { it.executeUpdate() }
        } else {
            connection.prepareStatement("DELETE FROM $table WHERE scope = ?").use { statement ->
                statement.setString(1, scope)
                statement.executeUpdate()
            }
        }
    }

    override suspend fun size(scope: String?): Int = withConnection { connection ->
        val sql = buildString {
            append("SELECT count(*) FROM $table WHERE (expires_at IS NULL OR expires_at > ?)")
            if (scope != null) append(" AND scope = ?")
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setObject(1, now())
            if (scope != null) statement.setString(2, scope)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    /** Deletes every row already past its TTL and returns how many went. */
    public suspend fun purgeExpired(): Int = withConnection { connection ->
        connection.prepareStatement("DELETE FROM $table WHERE expires_at IS NOT NULL AND expires_at <= ?")
            .use { statement ->
                statement.setObject(1, now())
                statement.executeUpdate()
            }
    }

    // ---- plumbing --------------------------------------------------------------------------------

    private suspend fun <T> withConnection(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        ensureSchema()
        dataSource.connection.use(block)
    }

    private suspend fun ensureSchema() {
        if (schemaReady) return
        schemaMutex.withLock {
            if (schemaReady) return
            withContext(Dispatchers.IO) {
                dataSource.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE EXTENSION IF NOT EXISTS vector")
                        statement.execute(
                            """
                            CREATE TABLE IF NOT EXISTS $table (
                                id text PRIMARY KEY,
                                scope text NOT NULL,
                                prompt text NOT NULL,
                                response text NOT NULL,
                                embedding vector NOT NULL,
                                created_at timestamptz NOT NULL,
                                expires_at timestamptz,
                                metadata jsonb NOT NULL DEFAULT '{}'::jsonb
                            )
                            """.trimIndent(),
                        )
                        statement.execute("CREATE INDEX IF NOT EXISTS ${table}_scope_idx ON $table (scope)")
                        statement.execute(
                            "CREATE INDEX IF NOT EXISTS ${table}_expires_at_idx ON $table (expires_at)",
                        )
                    }
                }
            }
            schemaReady = true
        }
    }

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    private fun encodeVector(vector: FloatArray): String =
        vector.joinToString(separator = ",", prefix = "[", postfix = "]")

    private fun decodeVector(text: String): FloatArray {
        val body = text.trim().removePrefix("[").removeSuffix("]")
        if (body.isEmpty()) return FloatArray(0)
        return body.split(',').map { it.trim().toFloat() }.toFloatArray()
    }

    private fun encodeMetadata(metadata: Map<String, String>): String =
        json.encodeToString(METADATA_SERIALIZER, metadata)

    private fun decodeMetadata(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return json.decodeFromString(METADATA_SERIALIZER, text)
    }

    private companion object {
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val METADATA_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}

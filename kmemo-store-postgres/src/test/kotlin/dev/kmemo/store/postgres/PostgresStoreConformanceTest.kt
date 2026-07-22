package dev.kmemo.store.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.kmemo.CacheStore
import dev.kmemo.tck.CacheStoreContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Runs the shared [CacheStoreContract] against a real Postgres + pgvector in Docker. Skipped — not
 * failed — when Docker is unavailable, so `./gradlew build` stays green off CI; runs in CI where Docker
 * is present. Each store gets its own table, so the container is shared but tests do not collide.
 */
class PostgresStoreConformanceTest : CacheStoreContract() {

    override fun createStore(ttl: Duration?): CacheStore {
        val n = counter.incrementAndGet()
        return PostgresStore(dataSource, table = "kmemo_cache_$n", ttl = ttl, clock = clock)
    }

    companion object {
        // Overridable so CI can pin / matrix the image.
        private val image = System.getenv("POSTGRES_IMAGE") ?: "pgvector/pgvector:pg16"
        // testcontainers 2.0's PostgreSQLContainer is no longer generic, so the pgvector image is passed
        // straight to it — the old self-typed PgVectorContainer subclass added nothing and is gone.
        private val container =
            PostgreSQLContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
        private val counter = AtomicInteger(0)
        private lateinit var dataSource: HikariDataSource

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            assumeTrue(
                DockerClientFactory.instance().isDockerAvailable,
                "Docker not available; skipping Postgres conformance test",
            )
            container.start()
            dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = container.jdbcUrl
                    username = container.username
                    password = container.password
                    maximumPoolSize = 4
                },
            )
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            if (::dataSource.isInitialized) dataSource.close()
            if (container.isRunning) container.stop()
        }
    }
}

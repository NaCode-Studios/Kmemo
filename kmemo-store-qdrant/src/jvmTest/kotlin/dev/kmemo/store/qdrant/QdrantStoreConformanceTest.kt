package dev.kmemo.store.qdrant

import dev.kdrant.transport.rest.Kdrant
import dev.kdrant.QdrantClient
import dev.kmemo.CacheStore
import dev.kmemo.tck.CacheStoreContract
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * The shared [CacheStoreContract], run against a real Qdrant in Docker.
 *
 * The exit criterion for this store was that it passes the suite **unmodified**, and that is the whole
 * argument for a fourth store existing at all: it is written by the authors of the suite, so the only
 * thing that makes it credible is that it is held to exactly what Postgres and Redis are held to, with
 * nothing relaxed for it.
 *
 * Skipped rather than failed when Docker is absent, so `./gradlew build` stays green on a laptop, and
 * run in CI where Docker is present. Each store gets its own collection, so the container is shared and
 * the tests do not collide.
 */
class QdrantStoreConformanceTest : CacheStoreContract() {

    override fun createStore(ttl: Duration?): CacheStore = QdrantStore(
        client = client,
        dimensions = DIMENSIONS,
        collection = "kmemo_cache_${counter.incrementAndGet()}",
        ttl = ttl,
        clock = clock,
    )

    companion object {
        /**
         * The contract writes short vectors, and a Qdrant collection has one width. Four is the widest
         * the suite uses and a collection wider than its vectors would refuse them.
         */
        private const val DIMENSIONS = 4

        private const val REST_PORT = 6333

        // Overridable so CI can pin or matrix the image, exactly as the Postgres suite does.
        private val image = System.getenv("QDRANT_IMAGE") ?: "qdrant/qdrant:v1.19.0"

        private val container: GenericContainer<*> =
            GenericContainer(DockerImageName.parse(image)).withExposedPorts(REST_PORT)

        private val counter = AtomicInteger(0)
        private lateinit var client: QdrantClient

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            assumeTrue(
                DockerClientFactory.instance().isDockerAvailable,
                "Docker not available; skipping Qdrant conformance test",
            )
            container.start()
            client = Kdrant(host = container.host, port = container.getMappedPort(REST_PORT))
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            if (::client.isInitialized) client.close()
            if (container.isRunning) container.stop()
        }
    }
}

package dev.kmemo.store.redis

import dev.kmemo.CacheStore
import dev.kmemo.tck.CacheStoreContract
import io.lettuce.core.RedisClient
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Runs the shared [CacheStoreContract] against a real Redis (RediSearch) in Docker. Skipped — not
 * failed — when Docker is unavailable, so `./gradlew build` stays green off CI; runs in CI where Docker
 * is present. Each store gets its own index + key prefix, so the container is shared but tests do not
 * see each other's data.
 */
class RedisStoreConformanceTest : CacheStoreContract() {

    override fun createStore(ttl: Duration?): CacheStore {
        val n = counter.incrementAndGet()
        return RedisStore(
            client = client,
            indexName = "kmemo-it-$n",
            keyPrefix = "kmemo:it:$n:",
            ttl = ttl,
            clock = clock,
        )
    }

    companion object {
        // Overridable so CI can pin / matrix the image.
        private val image = System.getenv("REDIS_IMAGE") ?: "redis/redis-stack:latest"
        private val container = RedisStackContainer(image).withExposedPorts(6379)
        private val counter = AtomicInteger(0)
        private lateinit var client: RedisClient

        @BeforeAll
        @JvmStatic
        fun startContainer() {
            assumeTrue(
                DockerClientFactory.instance().isDockerAvailable,
                "Docker not available; skipping Redis conformance test",
            )
            container.start()
            client = RedisClient.create("redis://${container.host}:${container.getMappedPort(6379)}")
        }

        @AfterAll
        @JvmStatic
        fun stopContainer() {
            if (::client.isInitialized) client.shutdown()
            if (container.isRunning) container.stop()
        }
    }

    private class RedisStackContainer(image: String) :
        GenericContainer<RedisStackContainer>(DockerImageName.parse(image))
}

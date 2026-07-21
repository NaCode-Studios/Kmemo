package dev.kmemo.ktor

import dev.kmemo.Embedder
import dev.kmemo.SemanticCache
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class KmemoPluginTest {

    private val embedder = Embedder { text ->
        val vector = FloatArray(64)
        val tokens = text.lowercase().split(Regex("\\W+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) vector[0] = 1.0f
        for (token in tokens) vector[Math.floorMod(token.hashCode(), 64)] += 1.0f
        vector
    }

    @Test
    fun `the plugin exposes the cache to a route, which then serves from it`() = testApplication {
        var computes = 0
        application {
            install(Kmemo) { cache = SemanticCache(embedder) }
            routing {
                get("/ask") {
                    val answer = call.getOrPut("What is the capital of France?") {
                        computes++
                        "Paris"
                    }
                    call.respondText(answer)
                }
            }
        }

        val first = client.get("/ask").bodyAsText()
        val second = client.get("/ask").bodyAsText()

        assertEquals("Paris", first)
        assertEquals("Paris", second)
        assertEquals(1, computes, "the second request must be served from the cache")
    }
}

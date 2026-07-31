package dev.kmemo.fixtures

/**
 * The failure a test injects when it needs "the network went away".
 *
 * `java.io.IOException` used to play this part, which quietly tied the tests to the JVM for the sake
 * of a name. What the tests actually assert is that *a specific exception type* travels out of the
 * cache unchanged, and any type does that job.
 */
class UpstreamFailure(message: String = "upstream died") : RuntimeException(message)

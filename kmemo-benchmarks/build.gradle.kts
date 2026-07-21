import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jmh)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// A JMH harness, not a library: never published and not part of the binary-compatibility contract
// (see the root build's apiValidation.ignoredProjects). Benchmarks live in src/jmh/kotlin.
dependencies {
    jmh(project(":kmemo-core"))
    jmh(project(":kmemo-store-hnsw"))
    jmh(libs.kotlinx.coroutines.core)
}

jmh {
    warmupIterations.set(3)
    iterations.set(5)
    fork.set(1)
    // Time per operation, in microseconds — the natural unit for a sub-millisecond cache lookup.
    benchmarkMode.set(listOf("avgt"))
    timeUnit.set("us")
}

// Compile the benchmarks on every `check` so they cannot silently rot against the core API, without
// paying to actually run them in CI — that is an explicit `./gradlew :kmemo-benchmarks:jmh`.
tasks.named("check") {
    dependsOn("jmhCompileGeneratedClasses")
}

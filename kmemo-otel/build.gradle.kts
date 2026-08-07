import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// **JVM-only, and the milestone that asked for this module asked it not to be quietly so.**
//
// The question was whether an OpenTelemetry adapter could follow `kmemo-core` to its ten targets,
// because `kmemo-micrometer` is a JVM answer and a team on iOS, on a Linux native binary or in a Wasm
// worker has `CacheListener` and nothing above it. The answer, checked rather than assumed: there is
// no OpenTelemetry API on Maven Central that a Kotlin Multiplatform module can depend on. The Kotlin
// SIG's multiplatform surface is not published there, and `io.opentelemetry:opentelemetry-api` is a
// JVM artifact.
//
// So this is JVM-only for the same reason the store adapters are: it wraps a library that exists
// nowhere else. What a multiplatform team gets instead is `CacheListener`, which is `commonMain` and
// carries every attribute this module reads, and `docs/OTEL-CONVENTIONS.md`, which names them so a
// per-platform exporter emits the same telemetry as this one.
dependencies {
    api(project(":kmemo-core"))
    api(libs.opentelemetry.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.opentelemetry.sdk)
    testImplementation(libs.opentelemetry.sdk.testing)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    // `automaticRelease = true`, and it is not a preference. Without it the plugin uploads the bundle
    // to the Central Portal as USER_MANAGED and stops, so the release job goes green while the version
    // sits in a queue waiting for somebody to press a button, and nothing anywhere reports the
    // difference. That is what happened to 2.1.0. It also turns on deployment validation, which the
    // plugin only performs when the release is automatic: the build then waits for the deployment to
    // reach PUBLISHED or FAILED instead of finishing at "uploaded".
    publishToMavenCentral(automaticRelease = true)

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.nacode-studios", "kmemo-otel", version.toString())
    pom {
        name.set("Kmemo OpenTelemetry")
        description.set(
            "A JVM-only OpenTelemetry adapter for Kmemo, the Kotlin Multiplatform semantic cache for " +
                "LLM calls. Emits metrics and a span per lookup with a child per stage, under a " +
                "proposed set of semantic-convention attributes for semantic caches.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/NaCode-Studios/Kmemo")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("NaCode-Studios")
                name.set("NaCode Studios")
                url.set("https://github.com/NaCode-Studios")
            }
        }
        scm {
            url.set("https://github.com/NaCode-Studios/Kmemo")
            connection.set("scm:git:https://github.com/NaCode-Studios/Kmemo.git")
            developerConnection.set("scm:git:ssh://git@github.com/NaCode-Studios/Kmemo.git")
        }
    }
}

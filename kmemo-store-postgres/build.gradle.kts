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

dependencies {
    api(project(":kmemo-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // The store talks JDBC (java.sql) to a caller-supplied DataSource; the Postgres driver is the
    // caller's single runtime dependency, kept off this module's compile classpath on purpose.

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":kmemo-store-tck"))
    testImplementation(libs.postgresql)
    testImplementation(libs.hikaricp)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
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

    coordinates("io.github.nacode-studios", "kmemo-store-postgres", version.toString())
    pom {
        name.set("Kmemo Postgres store")
        description.set(
            "A JVM-only Postgres (pgvector) CacheStore for Kmemo, the Kotlin Multiplatform semantic " +
                "cache for LLM calls. A durable, one-dependency semantic cache over JDBC with pgvector " +
                "nearest-neighbour search.",
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

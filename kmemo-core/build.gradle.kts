import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc)
    alias(libs.plugins.maven.publish)
    jacoco
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
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    // Property-based tests for the Vectors maths and the text tokenizer invariants.
    testImplementation(libs.kotest.property)
    // The shared store conformance suite; InMemoryStore is held to the same contract as every adapter.
    testImplementation(project(":kmemo-store-tck"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
    finalizedBy(tasks.jacocoTestReport)
}

// Coverage on kmemo-core — the library's heart, where every guard, the match logic and the vector
// maths live. (Kover is the natural choice here, but its 0.9.x line does not yet support the
// Kotlin 2.4 `KotlinWithJavaCompilation` model; JaCoCo works on bytecode and is unaffected.)
val minLineCoverage = "0.90"

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        // A floor, not an aspiration: set just under the current line coverage so the number cannot
        // silently slide down. Raise it deliberately, never lower it to make CI pass.
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = minLineCoverage.toBigDecimal()
            }
        }
    }
}

// Make `check` (and therefore `build`, and CI) enforce the coverage floor.
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

mavenPublishing {
    publishToMavenCentral()

    // Maven Central requires signatures, but a developer running publishToMavenLocal has no key and
    // should not be stopped by that — an unconditional signAllPublications() fails the local build
    // with "no configured signatory". The release workflow sets
    // ORG_GRADLE_PROJECT_signingInMemoryKey, which Gradle surfaces as this project property, so CI
    // still signs everything it publishes.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.nacode-studios", "kmemo-core", version.toString())
    pom {
        name.set("Kmemo Core")
        description.set(
            "Semantic cache for LLM calls on Kotlin/JVM, with guards against false cache hits.",
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

// Secondary distribution: GitHub Packages (Maven Central remains the primary registry).
publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/NaCode-Studios/Kmemo")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

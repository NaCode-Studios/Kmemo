import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// The guard conformance suite, and the reason it is **published** rather than kept in the test tree
// like kmemo-store-tck. `MatchGuard` has been a public interface since 1.0, so a third party could
// always write a guard; what they could not do is find out whether it was any good. A harness that
// only exists inside this repository answers that for nobody. The question the milestone poses is
// "what does a consumer's build have to add in order to use it", and the answer here is one test
// dependency:
//
//     testImplementation("io.github.nacode-studios:kmemo-guard-tck:<version>")
//
// Everything a subclass needs on its compile classpath is therefore `api`, exactly as in the store
// TCK. It stays JVM-only: it is JUnit-based test support, and a guard is measured wherever the author
// runs their tests, not on the phone the cache eventually ships to.
dependencies {
    api(project(":kmemo-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlin.test.junit5)
    implementation(libs.kotlinx.serialization.json)
}

// The corpora are not copied into this module, they are taken from the one place they live. Two files
// with the same pairs in two directories is a corpus that drifts, and drift in the corpus is drift in
// every number this project publishes. `docs/CORPUS.md` owns the originals; this task ships them.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("kmemo-core/src/jvmTest/resources")) {
        include("near-miss-corpus.json", "held-out-corpus.json", "validation-corpus.json")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.nacode-studios", "kmemo-guard-tck", version.toString())
    pom {
        name.set("Kmemo Guard TCK")
        description.set(
            "A JVM-only conformance suite for custom MatchGuards in Kmemo, the Kotlin Multiplatform " +
                "semantic cache for LLM calls — the properties every guard must satisfy, plus the " +
                "labelled corpora and the confusion matrix the built-in guards are measured with.",
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

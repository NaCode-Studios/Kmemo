import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
    applyDefaultHierarchyTemplate()

    // A cache that only runs on a server is a cache for half the problem. A phone pays for every call
    // over a mobile network, a browser or Wasm tool has no server to cache on, and an edge deployment
    // may have no reliable uplink. The JVM adapters — Redis, Postgres, Spring — stay JVM-only by
    // design and not by omission: they wrap drivers that exist nowhere else.
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
                // The external split (M24) is fetched, never committed: the licence stays with the
                // dataset. The test finds it here, skips with a sentence when it is absent, and fails
                // when `-PexternalCorpusRequired=true` says absence is not acceptable, which is what
                // CI passes, because a floor nobody notices has stopped running is not a floor.
                systemProperty(
                    "kmemo.externalCorpus",
                    rootProject.layout.buildDirectory
                        .file("external-corpus/paws-wiki-test.json").get().asFile.path,
                )
                systemProperty(
                    "kmemo.externalCorpus.required",
                    providers.gradleProperty("externalCorpusRequired").getOrElse("false"),
                )
                // The question split (M54), fetched under the same policy and for the same reason.
                systemProperty(
                    "kmemo.qqpCorpus",
                    rootProject.layout.buildDirectory
                        .file("qqp-corpus/qqp-questions.json").get().asFile.path,
                )
                systemProperty(
                    "kmemo.qqpCorpus.required",
                    providers.gradleProperty("qqpCorpusRequired").getOrElse("false"),
                )
                // The published specification is generated from these guards and committed, the way
                // the API dumps are. -PupdateGuardSpec=true rewrites it; without it a drift fails.
                systemProperty(
                    "kmemo.updateGuardSpec",
                    providers.gradleProperty("updateGuardSpec").getOrElse("false"),
                )
                testLogging {
                    events("passed", "skipped", "failed")
                    exceptionFormat = TestExceptionFormat.FULL
                    showStandardStreams = true
                }
            }
        }
    }
    // Node only, no browser environment. A browser test run pulls in the whole webpack and karma
    // toolchain, which is a few hundred npm packages this project never ships and would have to keep
    // patched. The published artifacts are identical either way — the environment decides where tests
    // run, not what is compiled — and the cache has no DOM in it to test against.
    js(IR) {
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
    }
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    mingwX64()

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            // Property-based tests for the Vectors maths and the text tokenizer invariants.
            implementation(libs.kotest.property)
            // The shared store conformance suite; InMemoryStore is held to the same contract as
            // every adapter. It is a JUnit fixture, so it is a JVM test dependency by nature.
            implementation(project(":kmemo-store-tck"))
            // The guard conformance suite, for the same reason: kmemo's own eleven guards are held to
            // the artifact a third party downloads, not to a private copy of it. See
            // StandardGuardComplianceTest.
            implementation(project(":kmemo-guard-tck"))
        }
    }
}

// (Coverage is deferred: Kover's 0.9.x line is not yet compatible with the Kotlin 2.4 Gradle plugin's
// KotlinWithJavaCompilation model. ktlint, detekt, the corpus regression gate and the property-based
// tests carry the quality bar until Kover catches up.)

mavenPublishing {
    // `automaticRelease = true`, and it is not a preference. Without it the plugin uploads the bundle
    // to the Central Portal as USER_MANAGED and stops, so the release job goes green while the version
    // sits in a queue waiting for somebody to press a button, and nothing anywhere reports the
    // difference. That is what happened to 2.1.0. It also turns on deployment validation, which the
    // plugin only performs when the release is automatic: the build then waits for the deployment to
    // reach PUBLISHED or FAILED instead of finishing at "uploaded".
    publishToMavenCentral(automaticRelease = true)

    // Maven Central requires a javadoc jar, and the Dokka Javadoc plugin refuses to run on a
    // multiplatform project — it says so outright rather than producing something wrong, which is the
    // right call and also stops the release dead. The HTML output is what the API site already
    // publishes, so it goes in the jar instead: the requirement is that the artifact exists and
    // documents the code, not that it is in the 2007 Javadoc frameset.
    configure(
        com.vanniktech.maven.publish.KotlinMultiplatform(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )

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
            "Semantic cache for LLM calls on Kotlin Multiplatform, with guards against false cache " +
                "hits. Targets JVM, iOS, macOS, Linux, Windows, JS and Wasm.",
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

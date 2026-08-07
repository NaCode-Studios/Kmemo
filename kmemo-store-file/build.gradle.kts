import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// The one store that follows kmemo-core everywhere it goes. The target list is copied from
// kmemo-core deliberately rather than narrowed: the whole argument for this module is that the
// deployments where a cache pays for itself fastest are the ones that lost everything on restart.
kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
        // The platform seam is an `expect class` with four functions, which the compiler still calls
        // Beta and warns about on every build. The warning is about a language feature rather than
        // about this code, and `allWarningsAsErrors` is worth more than the one it removes: the
        // alternative shape is a function-per-operation `expect fun`, which spreads a four-method
        // object over four top-level declarations to satisfy a flag.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
                testLogging {
                    events("passed", "skipped", "failed")
                    exceptionFormat = TestExceptionFormat.FULL
                }
            }
        }
    }
    js {
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
            api(project(":kmemo-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            // The shared store conformance suite, run on every target this module claims. A store
            // that is conformant on the JVM and untested on iOS is a store that will serve a wrong
            // answer on a phone first.
            implementation(project(":kmemo-store-tck"))
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)

    configure(
        com.vanniktech.maven.publish.KotlinMultiplatform(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.nacode-studios", "kmemo-store-file", version.toString())
    pom {
        name.set("Kmemo File Store")
        description.set(
            "A persistent CacheStore for Kmemo that builds on every target kmemo-core does: an " +
                "append-only journal over a memory-resident index, for phones, desktops and edge " +
                "deployments that start cold on every launch.",
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

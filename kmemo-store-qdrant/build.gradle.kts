import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

// The JVM and the seven native targets `kmemo-core` and Kdrant share. **There is no `js` or `wasmJs`
// here**, and it is Kdrant's decision rather than an omission on either side: a Qdrant reachable from a
// browser is reachable from anyone who opens the developer tools, and an API key shipped to a browser is
// a published key. That argument holds at least as well for a cache, so this module states the gap
// rather than working around it. Use `kmemo-store-file` on those two targets.
kotlin {
    explicitApi()
    compilerOptions {
        allWarningsAsErrors.set(true)
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
            // The client interface and its models, and no transport. The caller picks REST or gRPC and
            // hands the store a QdrantClient, which is also what lets this module stay out of the
            // question of how you connect.
            api(libs.kdrant.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":kmemo-store-tck"))
            // A real Qdrant in Docker, the way the Postgres store is tested. JVM-only, which is why the
            // conformance run lives here rather than in commonTest.
            implementation(libs.kdrant.transport.rest)
            implementation(project.dependencies.platform(libs.testcontainers.bom))
            implementation(libs.testcontainers)
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

    coordinates("io.github.nacode-studios", "kmemo-store-qdrant", version.toString())
    pom {
        name.set("Kmemo Qdrant Store")
        description.set(
            "A CacheStore for Kmemo on Qdrant, through the Kdrant client, so a team already running " +
                "Qdrant for retrieval does not have to operate a second database for its cache.",
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

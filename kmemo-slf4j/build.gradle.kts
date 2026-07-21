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
    api(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // A real SLF4J backend so tests can attach a ListAppender and assert on captured events.
    testImplementation(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.nacode-studios", "kmemo-slf4j", version.toString())
    pom {
        name.set("Kmemo SLF4J logging")
        description.set(
            "SLF4J CacheListener for Kmemo, the semantic cache for LLM calls on Kotlin/JVM — a structured " +
                "log line per hit, miss, write and eviction, with prompt redaction on by default.",
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

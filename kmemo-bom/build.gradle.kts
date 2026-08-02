plugins {
    `java-platform`
    alias(libs.plugins.maven.publish)
}

// A Bill of Materials: multi-module users import this once and then depend on any kmemo artifact
// without a version, so every module stays in lockstep. Constraints only — a BOM ships no code.
dependencies {
    constraints {
        api(project(":kmemo-core"))
        api(project(":kmemo-store-redis"))
        api(project(":kmemo-store-postgres"))
        api(project(":kmemo-store-hnsw"))
        api(project(":kmemo-micrometer"))
        api(project(":kmemo-slf4j"))
        api(project(":kmemo-spring-boot-starter"))
        api(project(":kmemo-spring-ai"))
        api(project(":kmemo-langchain4j"))
        api(project(":kmemo-ktor"))
        api(project(":kmemo-guard-tck"))
    }
}

mavenPublishing {
    publishToMavenCentral()

    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("io.github.nacode-studios", "kmemo-bom", version.toString())
    pom {
        name.set("Kmemo BOM")
        description.set(
            "Bill of Materials for Kmemo, the Kotlin Multiplatform semantic cache for LLM calls — pin " +
                "one version and depend on every kmemo module without repeating it.",
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

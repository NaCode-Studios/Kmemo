plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

subprojects {
    group = "io.github.nacode-studios"
    version = "0.6.0"
}

apiValidation {
    // kmemo-store-tck ships test-support code (an abstract JUnit test class), not a public runtime
    // API, so it is not part of the binary-compatibility contract.
    ignoredProjects.add("kmemo-store-tck")
    // kmemo-benchmarks is a JMH harness, not a published library — it has no public API to guard.
    ignoredProjects.add("kmemo-benchmarks")
    // kmemo-bom is a java-platform (dependency constraints only) — no code, no API to guard.
    ignoredProjects.add("kmemo-bom")
    // examples is a runnable demo, not a published library.
    ignoredProjects.add("examples")
}

// Aggregate the documented modules into one HTML API site, published to GitHub Pages by docs.yml.
dependencies {
    dokka(project(":kmemo-core"))
    dokka(project(":kmemo-store-redis"))
    dokka(project(":kmemo-store-postgres"))
    dokka(project(":kmemo-store-hnsw"))
    dokka(project(":kmemo-micrometer"))
    dokka(project(":kmemo-slf4j"))
    dokka(project(":kmemo-spring-boot-starter"))
    dokka(project(":kmemo-spring-ai"))
    dokka(project(":kmemo-langchain4j"))
    dokka(project(":kmemo-ktor"))
}

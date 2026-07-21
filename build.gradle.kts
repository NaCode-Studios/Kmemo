plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

subprojects {
    group = "io.github.nacode-studios"
    version = "0.3.0"
}

apiValidation {
    // kmemo-store-tck ships test-support code (an abstract JUnit test class), not a public runtime
    // API, so it is not part of the binary-compatibility contract.
    ignoredProjects.add("kmemo-store-tck")
}

// Aggregate the documented modules into one HTML API site, published to GitHub Pages by docs.yml.
dependencies {
    dokka(project(":kmemo-core"))
    dokka(project(":kmemo-store-redis"))
    dokka(project(":kmemo-store-postgres"))
    dokka(project(":kmemo-store-hnsw"))
}

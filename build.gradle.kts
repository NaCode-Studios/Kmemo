plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

subprojects {
    group = "io.github.nacode-studios"
    version = "0.2.0"
}

// Aggregate the documented modules into one HTML API site, published to GitHub Pages by docs.yml.
dependencies {
    dokka(project(":kmemo-core"))
}

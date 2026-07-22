plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    group = "io.github.nacode-studios"
    version = "1.0.0"

    // Lint every Kotlin module — this skips the java-platform BOM, which has no sources. ktlint and
    // detekt each wire their check task into `check`, so `./gradlew build` (and CI) gates on both.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        apply(plugin = "io.gitlab.arturbosch.detekt")

        extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("config/detekt/detekt.yml"))
            // A baseline captures the existing codebase's deliberate style (long, thorough methods;
            // its own naming) so detekt gates *new* smells without a mass rewrite. Per module.
            baseline = file("detekt-baseline.xml")
        }
        tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
            jvmTarget = "17"
            reports {
                xml.required.set(false)
                html.required.set(true)
                txt.required.set(false)
                sarif.required.set(false)
                md.required.set(false)
            }
        }
        // detekt's own check task is not wired into `check` by default; wire the main-source analysis in.
        tasks.named("check") { dependsOn(tasks.named("detekt")) }
    }
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

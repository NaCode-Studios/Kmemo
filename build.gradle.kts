import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.dokka.javadoc) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

// One version for the whole build, at the root, because the corpus bundle is a root artifact and a
// second copy of the number is a second thing to forget on a release.
group = "io.github.nacode-studios"
version = "2.2.0"

subprojects {
    group = rootProject.group
    version = rootProject.version

    // Lint every Kotlin module — this skips the java-platform BOM, which has no sources. ktlint and
    // detekt each wire their check task into `check`, so `./gradlew build` (and CI) gates on both.
    // Both Kotlin plugins get the same lint gate: kmemo-core is multiplatform, the rest are JVM.
    for (kotlinPlugin in listOf("org.jetbrains.kotlin.jvm", "org.jetbrains.kotlin.multiplatform")) {
    plugins.withId(kotlinPlugin) {
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

    // The POM description is the sentence Maven Central and klibs.io show a stranger before they see
    // anything else, and nothing in the build read it until 2.1.0. That is how `kmemo-core:2.0.0`
    // shipped "on Kotlin/JVM" on the release that made the core multiplatform, beside a
    // `kotlin-tooling-metadata.json` from the same build listing iOS, Linux and Wasm. A claim nothing
    // verifies goes stale exactly that way, so the claim is verified here.
    plugins.withId("com.vanniktech.maven.publish") {
        val check = tasks.register<PomPlatformCheck>("checkPomPlatforms")
        // The targets are declared by the module's own `kotlin { }` block, which has not run when the
        // publish plugin is applied, so the values are read once evaluation is finished.
        afterEvaluate {
            check.configure {
                module.set(project.name)
                descriptions.set(
                    project.extensions.getByType<PublishingExtension>().publications
                        .withType(MavenPublication::class.java)
                        .mapNotNull { it.pom.description.orNull }
                        .distinct(),
                )
                nativeTargets.set(
                    (project.extensions.findByName("kotlin") as? KotlinMultiplatformExtension)
                        ?.targets.orEmpty()
                        .filterIsInstance<KotlinNativeTarget>()
                        .map { it.name }
                        .sorted(),
                )
                jvmModule.set(project.plugins.hasPlugin("org.jetbrains.kotlin.jvm"))
            }
        }
        tasks.named("check") { dependsOn(check) }
    }
}

/**
 * Holds every published module's POM description to what that module actually builds for.
 *
 * Two rules, in opposite directions, because the mistake can be made either way. A module that
 * publishes native targets may not describe itself as JVM-only: that is the `2.0.0` failure, and it
 * understated the library on the one surface a stranger meets first. A module that publishes for the
 * JVM alone must say so: an adapter that wraps a JDBC driver or a Spring context runs nowhere else,
 * and letting it inherit the library's "Multiplatform" sentence would overstate it just as quietly.
 *
 * The BOM is subject to neither: it is a `java-platform` with no Kotlin targets at all, so it has no
 * platform of its own to name.
 */
abstract class PomPlatformCheck : DefaultTask() {

    @get:Input
    abstract val module: Property<String>

    /** Every distinct description the module's publications carry. Normally one. */
    @get:Input
    abstract val descriptions: ListProperty<String>

    /** The module's Kotlin/Native target names, empty for a JVM-only or non-Kotlin module. */
    @get:Input
    abstract val nativeTargets: ListProperty<String>

    /** True when the module applies the Kotlin JVM plugin, which is what makes rule two apply. */
    @get:Input
    abstract val jvmModule: Property<Boolean>

    init {
        group = "verification"
        description = "Fails when a module's POM description misstates the platforms it publishes for."
    }

    @TaskAction
    fun check() {
        val name = module.get()
        val texts = descriptions.get()
        require(texts.isNotEmpty()) { "$name publishes with no POM description; Maven Central requires one" }

        val natives = nativeTargets.get()
        for (text in texts) {
            if (natives.isNotEmpty()) {
                val claim = JVM_ONLY_CLAIMS.firstOrNull { text.contains(it, ignoreCase = true) }
                check(claim == null) {
                    "$name publishes ${natives.size} native targets (${natives.joinToString()}) and its " +
                        "POM description calls it \"$claim\". Name the platforms it really supports.\n  $text"
                }
            } else if (jvmModule.get()) {
                check(text.contains("JVM")) {
                    "$name publishes for the JVM alone and its POM description never says so, so it " +
                        "reads as if it ran everywhere the core does.\n  $text"
                }
            }
        }
    }

    private companion object {
        /**
         * Phrases that assert the JVM is the whole story.
         *
         * Deliberately three and not four. "on the JVM" was here until it refused
         * "Targets JVM, iOS, macOS, ...": a platform list opens the same way a JVM-only claim does, and
         * a check that fails a correct description teaches people to reword around it rather than to
         * trust it. These three cannot appear in an honest multiplatform sentence.
         */
        private val JVM_ONLY_CLAIMS = listOf("Kotlin/JVM", "JVM-only", "JVM only")
    }
}

apiValidation {
    // The JVM jar is not the only published artifact any more, so the JVM signature dump is not the
    // whole contract. klib validation guards the ABI of every other target against the same golden
    // file, which is what stops a change that is invisible on the JVM from breaking an iOS consumer.
    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }

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
    dokka(project(":kmemo-guard-tck"))
}

// The Kotlin/JS toolchain resolves its own npm tooling, and three transitive packages carry advisories
// with no fix on the line their consumers ask for. Each is forced to its patched version here rather
// than left in the lock file with an explanation attached.
//
// Forcing across a major is only safe because of what these are: a string-expansion function, a value
// serializer and a text-diff library, none of whose signatures have moved. The JS and Wasm test suites
// running on them is the evidence rather than the assumption.
//
// **This is where those versions are decided, not in the lock file.** `kotlin-js-store/yarn.lock` is
// generated from here by `kotlinUpgradeYarnLock`, so a patch applied to the lock alone is undone by the
// next build. `.github/dependabot.yml` says the same thing to Dependabot.
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("brace-expansion", "5.0.8")
        resolution("serialize-javascript", "7.0.5")
        resolution("diff", "8.0.3")
    }
}


// --- the published corpus and guard specification (M43, M44) --------------------------------------
//
// The corpora and the rules that grade them are this project's real asset, and until 2.3.0 they were
// reachable only by cloning a Kotlin repository: the data lived in a test resource directory, in a
// shape invented here, read by a class that exists nowhere else. A library is judged by what it does;
// a standard is judged by what other people can do with it, and the thing worth standardising here is
// the metric and the data rather than the Kotlin.
//
// So they ship as one versioned archive: the schema, the metric definition, the rules written
// independently of this implementation, the conformance vectors, the marker packs, the three
// committed splits, and a runner that scores a cache without a JVM anywhere near it. The manifest
// carries a SHA-256 per file, so a figure quoted against "kmemo-corpus 2.3.0" names bytes.
//
// The two fetched splits are not in it, and cannot be: they are somebody else's data under somebody
// else's licence, and the fetch scripts that reproduce them are.
val corpusManifest by tasks.registering {
    val sources = listOf(
        rootProject.file("spec"),
        rootProject.file("kmemo-core/src/jvmTest/resources"),
        rootProject.file("tools/corpus-runner"),
    )
    val out = layout.buildDirectory.file("corpus-bundle/MANIFEST.json")
    val bundleVersion = version.toString()
    inputs.files(sources.map { fileTree(it) })
    outputs.file(out)
    doLast {
        val entries = sources.flatMap { root ->
            fileTree(root).files
                .filter { it.extension in setOf("json", "md", "py") }
                .sortedBy { it.path }
                .map { file ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(file.readBytes())
                        .joinToString("") { byte -> "%02x".format(byte) }
                    val name = "${root.name}/${file.relativeTo(root).invariantSeparatorsPath}"
                    """    { "path": "$name", "bytes": ${file.length()}, "sha256": "$digest" }"""
                }
        }
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "name": "kmemo-corpus",
              "version": "$bundleVersion",
              "about": "Labelled near-miss corpora, the metric that scores them, the guard rules that produce this project's published figures, and a runner that reproduces them outside the JVM. Schema in spec/corpus/SCHEMA.json, metric in spec/corpus/METRIC.md, rules in spec/guards/SPEC.md.",
              "files": [
            ${entries.joinToString(",\n")}
              ]
            }

            """.trimIndent(),
        )
    }
}

val corpusBundle by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the corpora, the metric, the guard rules, the vectors and the runner."
    archiveBaseName.set("kmemo-corpus")
    archiveVersion.set(version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(corpusManifest)
    from(rootProject.file("spec")) { into("spec") }
    from(rootProject.file("kmemo-core/src/jvmTest/resources")) {
        into("corpora")
        include("near-miss-corpus.json", "held-out-corpus.json", "validation-corpus.json")
    }
    from(rootProject.file("tools/corpus-runner")) {
        into("runner")
        exclude("__pycache__/**", "*.pyc")
    }
    from(rootProject.file("tools/external-corpus")) {
        into("fetch/external-corpus")
        include("fetch.py", "requirements.txt", "README.md")
    }
    from(rootProject.file("tools/qqp-corpus")) {
        into("fetch/qqp-corpus")
        include("fetch.py", "requirements.txt", "README.md")
    }
    from(rootProject.file("LICENSE"))
}

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// A runnable demo, not a published library.
dependencies {
    implementation(project(":kmemo-core"))
    // The optional persistent-store path (KMEMO_REDIS_URL); the demo runs in-memory without it.
    implementation(project(":kmemo-store-redis"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // The guard conformance suite. One dependency is the whole cost of measuring a custom guard, which
    // is the question M27 set out to answer, and this module is here to prove the answer from outside
    // kmemo-core, the way a third party would.
    testImplementation(project(":kmemo-guard-tck"))
    // M40: an evaluation suite is the workload a semantic cache is best at, and the claim is measured
    // here rather than asserted. Test-only, in the examples module, because nothing in the library
    // depends on Dokimos and the finding is that nothing should.
    testImplementation(libs.dokimos.kotlin)
    // M42 reads the retrieval corpus that tools/rag-corpus/fetch.py writes.
    testImplementation(libs.kotlinx.serialization.json)
}

application {
    mainClass.set("dev.kmemo.examples.DemoKt")
}

tasks.test {
    useJUnitPlatform()
    // The retrieval corpus is fetched, never committed: the licence stays with the dataset. The test
    // finds it here, skips with a sentence when it is absent, and fails when `-PragCorpusRequired=true`
    // says absence is not acceptable, which is what CI passes.
    systemProperty(
        "kmemo.ragCorpus",
        rootProject.layout.buildDirectory.file("rag-corpus/squad-dev.json").get().asFile.path,
    )
    systemProperty(
        "kmemo.ragCorpus.required",
        providers.gradleProperty("ragCorpusRequired").getOrElse("false"),
    )
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

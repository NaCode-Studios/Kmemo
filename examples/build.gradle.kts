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
    // is the question M27 set out to answer — and this module is here to prove the answer from outside
    // kmemo-core, the way a third party would.
    testImplementation(project(":kmemo-guard-tck"))
}

application {
    mainClass.set("dev.kmemo.examples.DemoKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}

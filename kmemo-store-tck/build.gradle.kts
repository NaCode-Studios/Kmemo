import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Multiplatform since M30, and for the reason M30 exists. `kmemo-core` builds for ten targets and
// `InMemoryStore` was the only store that followed it, so the contract only ever had JVM adapters to
// hold and a JVM module was enough. A store that claims iOS and Wasm has to be held to the same
// contract *on* iOS and Wasm, or it is a store that will serve a wrong answer on a phone first.
//
// The JVM variant is unchanged for every existing consumer: `testImplementation(project(":kmemo-store-tck"))`
// from a JVM module resolves it exactly as before.
kotlin {
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
    applyDefaultHierarchyTemplate()

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    js {
        nodejs()
    }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        nodejs()
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
        // The suite lives in the *main* source set rather than in test, on purpose, so an adapter
        // module can subclass CacheStoreContract from its own test source set. The testing libraries
        // are therefore `api` dependencies: a subclass needs them on its compile classpath.
        commonMain.dependencies {
            api(project(":kmemo-core"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.coroutines.test)
            api(libs.kotlin.test)
        }
        // kotlin.test's annotations map to JUnit 5 on the JVM, which is what lets a Gradle JVM test
        // task discover a subclass of the contract at all.
        jvmMain.dependencies {
            api(libs.kotlin.test.junit5)
        }
    }
}

// Not published and not tracked by the binary-compatibility-validator (see the root build): this is
// test-support code, exercised in-repo by kmemo-core and by every store adapter.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

// The store conformance suite (TCK): the shared contract every CacheStore must pass. It lives in the
// *main* source set (not test) on purpose, so an adapter module can subclass CacheStoreContract from
// its own test source set — `testImplementation(project(":kmemo-store-tck"))`. The testing libraries
// are therefore `api` dependencies: a subclass needs them on its compile classpath.
//
// Not published and not tracked by the binary-compatibility-validator (see the root build): this is
// test-support code, exercised in-repo by kmemo-core and by every store adapter.
dependencies {
    api(project(":kmemo-core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.coroutines.test)
    api(libs.kotlin.test.junit5)
}

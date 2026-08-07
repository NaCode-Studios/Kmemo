pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    // The Kotlin/JS and Wasm toolchains add their own repositories for the Node and Yarn
    // distributions they download. FAIL_ON_PROJECT_REPOS forbids that outright, so the mode is
    // PREFER_SETTINGS: everything a module declares still resolves from here, and the two toolchain
    // downloads are allowed to reach their own hosts.
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()

        // The Kotlin/JS and Wasm toolchains download a Node and a Yarn distribution, and neither is
        // on Maven Central. They are declared here rather than left to the plugin so that
        // FAIL_ON_PROJECT_REPOS stays the rule for everything else: a module cannot quietly add a
        // repository, and these two are named, pinned to their exact modules, and visible.
        ivy("https://nodejs.org/dist") {
            name = "Node distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
}

rootProject.name = "Kmemo"

include(":kmemo-core")
include(":kmemo-store-tck")
include(":kmemo-guard-tck")
include(":kmemo-store-redis")
include(":kmemo-store-postgres")
include(":kmemo-store-hnsw")
include(":kmemo-store-file")
include(":kmemo-store-qdrant")
include(":kmemo-micrometer")
include(":kmemo-slf4j")
include(":kmemo-otel")
include(":kmemo-benchmarks")
include(":kmemo-bom")
include(":kmemo-spring-boot-starter")
include(":kmemo-spring-ai")
include(":kmemo-langchain4j")
include(":kmemo-ktor")
include(":examples")

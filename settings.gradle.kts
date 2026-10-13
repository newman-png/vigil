pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vigil"

// The plan/log logic is a plain Kotlin build so it can be tested on any JVM without the Android SDK:
//   ./gradlew -p core test
includeBuild("core")
include(":app")

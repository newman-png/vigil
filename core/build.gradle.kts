plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.vigil"
version = "1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}

tasks.test {
    // Tests may read files from the repo (spec/PROTOCOLS.md documents protocols.json), so edits there rerun them.
    systemProperty("vigil.repoRoot", rootDir.parentFile.absolutePath)
    inputs.dir(rootDir.resolve("../spec")).withPropertyName("spec").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

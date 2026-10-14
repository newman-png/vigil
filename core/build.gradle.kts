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
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    // Spec files (schema.json, sample plans) live outside the module; tests read them from the repo.
    systemProperty("vigil.repoRoot", rootDir.parentFile.absolutePath)
    // Tests read spec/ (schema, samples, HANDOFF.md), so edits there must rerun them.
    inputs.dir(rootDir.resolve("../spec")).withPropertyName("spec").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

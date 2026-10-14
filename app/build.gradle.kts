plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The Nothing Glyph SDK is not redistributed in this repo. tools/fetch-glyph-sdk.sh downloads it (CI does this);
// without it the app builds with a no-op Glyph controller.
val glyphAar = file("libs/glyph-matrix-sdk-2.0.aar")
val hasGlyphSdk = glyphAar.exists()

android {
    namespace = "dev.vigil"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.vigil"
        // 33: the Glyph SDK requires it, and it keeps permission handling simple.
        minSdk = 33
        // 36 (Android 16) also lets the Glyph SDK run without a Nothing API key.
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        buildConfigField("boolean", "HAS_GLYPH_SDK", hasGlyphSdk.toString())
    }

    signingConfigs {
        // A committed debug key, so every CI build installs over the previous one without uninstalling.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            java.srcDir(if (hasGlyphSdk) "src/glyph/java" else "src/noglyph/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("dev.vigil:core")
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)
    if (hasGlyphSdk) implementation(files(glyphAar))
}

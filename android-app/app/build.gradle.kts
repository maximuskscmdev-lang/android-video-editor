plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videoeditor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.videoeditor"
        minSdk = 29 // Android 10+
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        ndk { abiFilters.add("arm64-v8a") } // arm64-v8a only per request
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.13" }

    ndkVersion = "26.1.10909125"

    packaging { jniLibs { useLegacyPackaging = true } }

    // Ensure jniLibs pick up from both src/main/jniLibs and cargo output
    sourceSets { getByName("main") { jniLibs.srcDirs("src/main/jniLibs") } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    // ExoPlayer for preview (MediaCodec decode path preview alternative)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kotlin { jvmToolchain(17) }

// --- Rust / cargo-ndk integration: build arm64-v8a only ---
val cargoNdkBuild by tasks.registering(Exec::class) {
    description = "Build Rust core for arm64-v8a via cargo-ndk"
    group = "rust"
    // Allow missing NDK/SDK on CI: don't fail if cargo ndk not found, just warn
    commandLine("bash", "-c", """
        set -e
        if ! command -v cargo >/dev/null 2>&1; then
          echo "[rust] cargo not found, skipping native build"
          exit 0
        fi
        if [ -z "${'$'}ANDROID_NDK_HOME" ] && [ -z "${'$'}ANDROID_NDK_ROOT" ] && [ ! -d "${'$'}ANDROID_HOME/ndk/26.1.10909125" ]; then
          echo "[rust] ANDROID_NDK_HOME not set, skipping native build (install NDK r26 to build .so)"
          exit 0
        fi
        echo "[rust] building video_core for arm64-v8a..."
        cargo ndk -t arm64-v8a -o src/main/jniLibs build --release --manifest-path ../../Cargo.toml
        ls -lh src/main/jniLibs/arm64-v8a/libvideo_core.so || true
    """.trimIndent())
    workingDir(projectDir)
}

tasks.named("preBuild") { dependsOn(cargoNdkBuild) }

// For sideload APK: assembleRelease produces app-release.apk

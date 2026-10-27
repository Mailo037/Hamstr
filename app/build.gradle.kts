import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Releases are cut from tags: CI passes v1.2.3 in as -Phamstr.version=1.2.3.
val appVersion = providers.gradleProperty("hamstr.version").getOrElse("0.1.0")
val (major, minor, patch) = appVersion.substringBefore('-').split('.').map(String::toInt)

// The release key lives outside git: signing/keystore.properties next to the .jks, written
// by hand locally and from repository secrets in CI. Without it, release builds use the debug key.
val signingProps = Properties().apply {
    rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// cheek, the Rust download engine (github.com/Mailo037/cheek), goes into the APK as
// libcheek.so per ABI. Builds take a pinned release; `cheek.dir` in local.properties points
// at a local checkout built with cargo-ndk instead.
val cheekVersion = "0.1.0"
val cheekTargets = mapOf("arm64-v8a" to "aarch64-linux-android", "armeabi-v7a" to "armv7-linux-androideabi", "x86_64" to "x86_64-linux-android")
val cheekLocal = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}.getProperty("cheek.dir")
val fetchCheek by tasks.registering {
    description = "Puts the cheek binaries into src/main/jniLibs as libcheek.so"
    val jniLibs = layout.projectDirectory.dir("src/main/jniLibs")
    val version = cheekVersion
    val targets = cheekTargets
    val local = cheekLocal
    inputs.property("version", version)
    inputs.property("local", local ?: "")
    outputs.files(targets.keys.map { jniLibs.file("$it/libcheek.so") })
    doLast {
        targets.forEach { (abi, triple) ->
            val dest = jniLibs.file("$abi/libcheek.so").asFile.apply { parentFile.mkdirs() }
            val built = local?.let { File(it, "target/$triple/release/cheek") }
            if (built != null && built.exists()) {
                built.copyTo(dest, overwrite = true)
            } else {
                val url = "https://github.com/Mailo037/cheek/releases/download/v$version/cheek-android-$abi"
                URI(url).toURL().openStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchCheek) }

android {
    namespace = "app.hamstr"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.hamstr"
        minSdk = 26
        targetSdk = 36
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = appVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // yt-dlp ships Python + ffmpeg per ABI; phones plus the x86_64 emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (signingProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file("signing/" + signingProps.getProperty("storeFile"))
            storePassword = signingProps.getProperty("storePassword")
            keyAlias = signingProps.getProperty("keyAlias")
            keyPassword = signingProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    // youtubedl-android runs bundled executables (python, ffmpeg) straight from the
    // native library dir, so they must be extracted at install time.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")

    // In-app player: ExoPlayer behind a MediaSession (background audio, media notification).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

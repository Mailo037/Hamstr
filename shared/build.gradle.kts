// Everything the phone and the desktop app share: theme, components, screens, data and the
// engine's decisions. Code goes into src/jvmCommonMain (both targets are JVMs, so java.io,
// processes and org.json are there); src/androidMain and src/desktopMain hold the few
// platform pieces. Strings, fonts and icons live in src/commonMain/composeResources.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val composeVersion = "1.12.2"

kotlin {
    android {
        namespace = "app.hamstr.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    jvm("desktop")

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
            api("org.jetbrains.compose.foundation:foundation:$composeVersion")
            api("org.jetbrains.compose.ui:ui:$composeVersion")
            api("org.jetbrains.compose.components:components-resources:$composeVersion")
            implementation("org.jetbrains.compose.ui:ui-backhandler:$composeVersion")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            api("io.coil-kt.coil3:coil-compose:3.6.3")
            implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
        }
        // Shared by both targets, with the JDK: where the code goes.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        androidMain.get().dependsOn(jvmCommonMain)
        val desktopMain by getting {
            dependsOn(jvmCommonMain)
            dependencies {
                // Android has org.json built in.
                api("org.json:json:20260814")
                api("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                // Compose UI tests (mouse and touch input) on this computer's Skia.
                implementation(compose.desktop.currentOs)
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.hamstr.res"
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.4.1" apply false
    // AGP 9 compiles Kotlin itself; declaring KGP here pins the Kotlin version it uses.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // Compose Multiplatform: the same Compose code on Android and on the desktop (see shared/).
    id("org.jetbrains.compose") version "1.12.2" apply false
}

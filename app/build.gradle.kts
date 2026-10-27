import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile

buildscript {
    repositories { mavenCentral() }
    // Rewrites libffmpeg.zip.so while keeping its symlinks and file modes (see alignFfmpeg).
    dependencies { classpath("org.apache.commons:commons-compress:1.28.0") }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Releases are cut from tags: CI passes v1.2.3 in as -Phamstr.version=1.2.3.
val appVersion = providers.gradleProperty("hamstr.version").getOrElse("0.5.0")
val (major, minor, patch) = appVersion.substringBefore('-').split('.').map(String::toInt)

// The release key lives outside git: signing/keystore.properties next to the .jks, written
// by hand locally and from repository secrets in CI. Without it, release builds use the debug key.
val signingProps = Properties().apply {
    rootProject.file("signing/keystore.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

// cheek, the Rust download engine (github.com/Mailo037/cheek), goes into the APK as
// libcheek.so per ABI. Builds take a pinned release; `cheek.dir` in local.properties points
// at a local checkout built with cargo-ndk instead.
val cheekVersion = "0.2.1"
val cheekTargets = mapOf("arm64-v8a" to "aarch64-linux-android", "armeabi-v7a" to "armv7-linux-androideabi", "x86_64" to "x86_64-linux-android")
val cheekLocal = if (providers.gradleProperty("cheek.useRelease").getOrElse("false").toBoolean()) null else Properties().apply {
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
    if (local != null) inputs.files(targets.values.map { File(local, "target/$it/release/cheek") })
    outputs.files(targets.keys.map { jniLibs.file("$it/libcheek.so") })
    doLast {
        val releaseBase = "https://github.com/Mailo037/cheek/releases/download/v$version"
        val checksums by lazy {
            URI("$releaseBase/SHA256SUMS.txt").toURL().readText().lineSequence()
                .filter { it.isNotBlank() }.associate { line ->
                    val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
                    name.removePrefix("*") to hash.lowercase()
                }
        }
        targets.forEach { (abi, triple) ->
            val dest = jniLibs.file("$abi/libcheek.so").asFile.apply { parentFile.mkdirs() }
            val built = local?.let { File(it, "target/$triple/release/cheek") }
            if (built != null && built.exists()) {
                built.copyTo(dest, overwrite = true)
            } else {
                val name = "cheek-android-$abi"
                val bytes = URI("$releaseBase/$name").toURL().readBytes()
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(hash == checksums[name]) { "Cheek $version checksum mismatch for $abi" }
                dest.writeBytes(bytes)
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchCheek) }

// youtubedl-android unpacks ffmpeg's shared libraries from libffmpeg.zip.so at runtime. In
// 0.18.1 (the newest release) five of them, libwebp and its helpers, are still linked for
// 4 KB pages, so on 16 KB-page phones the linker refuses to start ffmpeg at all
// (github.com/yausername/youtubedl-android/issues/365). Moving p_align is not enough, they
// need relinking: this rebuilds the same libwebp release with the NDK at 16 KB alignment
// and swaps it into the zip; the other entries are copied over unchanged.
abstract class AlignFfmpeg @Inject constructor(
    private val exec: ExecOperations,
    private val archives: ArchiveOperations,
    private val fs: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles abstract val ffmpegAar: ConfigurableFileCollection
    @get:Input abstract val webpVersion: Property<String>
    @get:Input abstract val webpSha256: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val ndkVersion: Property<String>
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:Internal abstract val cmakeDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private val webpLibs = listOf("libsharpyuv.so", "libwebp.so", "libwebpdecoder.so", "libwebpdemux.so", "libwebpmux.so")

    @TaskAction
    fun align() {
        val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
        val version = webpVersion.get()
        val bytes = URI("https://github.com/webmproject/libwebp/archive/refs/tags/v$version.tar.gz").toURL().readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(hash == webpSha256.get()) { "libwebp $version checksum mismatch" }
        val tarball = File(work, "libwebp.tar.gz").apply { writeBytes(bytes) }
        fs.copy { from(archives.tarTree(archives.gzip(tarball))); into(work) }

        val exe = if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""
        val ndk = ndkDir.get().asFile
        val cmake = cmakeDir.file("bin/cmake$exe").get().asFile
        val ninja = cmakeDir.file("bin/ninja$exe").get().asFile
        val strip = File(File(ndk, "toolchains/llvm/prebuilt").listFiles()!!.single(), "bin/llvm-strip$exe")
        fs.delete { delete(outputDir) }
        abis.get().forEach { abi ->
            val build = File(work, "build-$abi")
            // Same switches as the Termux package youtubedl-android takes ffmpeg from, minus the tools.
            exec.exec {
                commandLine(
                    cmake, "-S", File(work, "libwebp-$version"), "-B", build, "-G", "Ninja",
                    "-DCMAKE_MAKE_PROGRAM=$ninja", "-DCMAKE_TOOLCHAIN_FILE=$ndk/build/cmake/android.toolchain.cmake",
                    "-DANDROID_ABI=$abi", "-DANDROID_PLATFORM=android-24", "-DCMAKE_BUILD_TYPE=Release",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384", "-DBUILD_SHARED_LIBS=ON",
                    "-DWEBP_ENABLE_SWAP_16BIT_CSP=ON", "-DWEBP_BUILD_ANIM_UTILS=OFF", "-DWEBP_BUILD_CWEBP=OFF",
                    "-DWEBP_BUILD_DWEBP=OFF", "-DWEBP_BUILD_GIF2WEBP=OFF", "-DWEBP_BUILD_IMG2WEBP=OFF",
                    "-DWEBP_BUILD_VWEBP=OFF", "-DWEBP_BUILD_WEBPINFO=OFF", "-DWEBP_BUILD_WEBPMUX=OFF",
                    "-DWEBP_BUILD_EXTRAS=OFF",
                )
            }
            exec.exec { commandLine(cmake, "--build", build) }
            val rebuilt = webpLibs.associate { name ->
                val lib = File(build, name)
                exec.exec { commandLine(strip, "--strip-unneeded", lib) }
                "usr/lib/$name" to lib
            }

            val original = archives.zipTree(ffmpegAar.singleFile).matching { include("jni/$abi/libffmpeg.zip.so") }.singleFile
            val aligned = outputDir.file("$abi/libffmpeg.zip.so").get().asFile.apply { parentFile.mkdirs() }
            ZipFile.builder().setFile(original).get().use { zip ->
                ZipArchiveOutputStream(aligned).use { out ->
                    for (entry in zip.entries) {
                        val lib = rebuilt[entry.name]
                        if (lib == null) {
                            out.addRawArchiveEntry(entry, zip.getRawInputStream(entry))
                        } else {
                            out.putArchiveEntry(ZipArchiveEntry(entry.name).apply { unixMode = entry.unixMode; time = entry.time })
                            lib.inputStream().use { it.copyTo(out) }
                            out.closeArchiveEntry()
                        }
                    }
                }
            }
            // youtubedl-android re-extracts the zip only when its size changes.
            check(aligned.length() != original.length()) { "Aligned libffmpeg.zip.so for $abi has the original's size" }
            ZipFile.builder().setFile(aligned).get().use { zip ->
                val names = zip.entries.toList().map { it.name }
                check(names.containsAll(rebuilt.keys)) { "libffmpeg.zip.so for $abi is missing libwebp" }
                for (entry in zip.entries) {
                    if (entry.isUnixSymlink) continue
                    val elf = zip.getInputStream(entry).use { it.readBytes() }
                    val align = minLoadAlign(elf) ?: continue
                    check(align >= 16384) { "${entry.name} in libffmpeg.zip.so for $abi is aligned to $align bytes" }
                }
            }
        }
    }

    /** The smallest p_align over an ELF file's PT_LOAD segments, or null if it is not one. */
    private fun minLoadAlign(elf: ByteArray): Long? {
        if (elf.size < 64 || elf[0] != 0x7f.toByte() || String(elf, 1, 3) != "ELF") return null
        val buf = ByteBuffer.wrap(elf).order(if (elf[5] == 1.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
        val is64 = elf[4] == 2.toByte()
        val phoff = if (is64) buf.getLong(0x20) else buf.getInt(0x1c).toLong()
        val phentsize = buf.getShort(if (is64) 0x36 else 0x2a).toInt()
        val phnum = buf.getShort(if (is64) 0x38 else 0x2c).toInt()
        return (0 until phnum).map { phoff.toInt() + it * phentsize }
            .filter { buf.getInt(it) == 1 }
            .minOfOrNull { if (is64) buf.getLong(it + 48) else buf.getInt(it + 28).toLong() }
    }
}

val youtubedlVersion = "0.18.1"
val ffmpegPackage by configurations.creating { isTransitive = false }
val alignFfmpeg by tasks.registering(AlignFfmpeg::class) {
    description = "Rebuilds the 4 KB-aligned libwebp inside youtubedl-android's libffmpeg.zip.so for 16 KB pages"
    ffmpegAar.from(ffmpegPackage)
    webpVersion = "1.6.0"
    webpSha256 = "93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136"
    // 32-bit ARM devices always use 4 KB pages, so armeabi-v7a keeps the published zip.
    abis = listOf("arm64-v8a", "x86_64")
    ndkVersion = android.ndkVersion
    ndkDir = androidComponents.sdkComponents.ndkDirectory
    cmakeDir = androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.22.1") }
    outputDir = layout.buildDirectory.dir("generated/ffmpeg16k")
}
// The app's own libffmpeg.zip.so wins over the one from the ffmpeg AAR when packaging.
androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(alignFfmpeg, AlignFfmpeg::outputDir)
}

android {
    namespace = "app.hamstr"
    compileSdk = 37
    // Builds libwebp for alignFfmpeg; installed with sdkmanager "ndk;28.2.13676358" "cmake;3.22.1".
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "app.hamstr"
        minSdk = 26
        targetSdk = 36
        versionCode = major * 10_000 + minor * 100 + patch
        versionName = appVersion
        buildConfigField("String", "CHEEK_VERSION", "\"$cheekVersion\"")
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Cheek's release executables are already stripped. Keep their published
            // bytes intact so the embedded binaries match the verified release hashes.
            keepDebugSymbols += "**/libcheek.so"
        }
    }
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

    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlVersion")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlVersion")
    ffmpegPackage("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlVersion@aar")

    // In-app player: ExoPlayer behind a MediaSession (background audio, media notification).
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-ui-compose:1.11.1")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, since the one in android.jar only throws in unit tests.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

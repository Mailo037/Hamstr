import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.inject.Inject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

buildscript {
    repositories { mavenCentral() }
    // Unpacks the Linux FFmpeg build, a .tar.xz (see FetchTools). XZ goes through xz itself:
    // Gradle ships its own commons-compress, whose XZ support can't see this classpath.
    dependencies {
        classpath("org.apache.commons:commons-compress:1.28.0")
        classpath("org.tukaani:xz:1.10")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The same version and Cheek release as the phone app (gradle.properties).
val appVersion = providers.gradleProperty("hamstr.version").get()
val cheekRelease = providers.gradleProperty("hamstr.cheekVersion").get()
// yt-dlp's standalone builds; the app updates its own copy later (yt-dlp -U).
val ytDlpRelease = "2026.10.26"

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Windows 11's rounded corners for the frameless window (WindowFrame).
    implementation("net.java.dev.jna:jna:5.19.1")
}

// The window's own icon (title bar, taskbar), from tools/render_icon.py like the installers' icons.
tasks.processResources { from("icons/hamstr_window.png") }

/** The host this build packages for, named like Compose's resource folders (windows-x64, macos-arm64 …). */
val hostOs = System.getProperty("os.name").lowercase().let {
    when {
        it.startsWith("windows") -> "windows"
        it.startsWith("mac") -> "macos"
        else -> "linux"
    }
}
val hostArch = if (System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }) "arm64" else "x64"

/**
 * Puts yt-dlp, Cheek, FFmpeg and ffprobe for this host into build/tools/<os>-<arch>, where
 * Compose ships them next to the app (appResourcesRootDir). Every download is checked against
 * the checksums its release publishes. A desktop installer is built on the system it is for,
 * so CI runs this once per OS.
 */
abstract class FetchTools @Inject constructor() : DefaultTask() {
    @get:Input abstract val os: Property<String>
    @get:Input abstract val arch: Property<String>
    @get:Input abstract val cheekVersion: Property<String>
    @get:Input abstract val ytDlpVersion: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fetch(url: String) = URI(url).toURL().readBytes()

    /** "<hash>  <name>" lines, as sha256sum writes them. */
    private fun sums(url: String) = String(fetch(url)).lineSequence().filter { it.isNotBlank() }.associate { line ->
        val (hash, name) = line.trim().split(Regex("\\s+"), limit = 2)
        name.removePrefix("*").substringAfterLast('/') to hash.lowercase()
    }

    private fun verified(url: String, expected: String?): ByteArray {
        val bytes = fetch(url)
        check(expected != null && sha256(bytes) == expected) { "Checksum mismatch for $url" }
        return bytes
    }

    @TaskAction
    fun fetchAll() {
        val dir = outputDir.get().dir("${os.get()}-${arch.get()}").asFile.apply { deleteRecursively(); mkdirs() }
        val exe = if (os.get() == "windows") ".exe" else ""
        fun put(name: String, bytes: ByteArray) = File(dir, name + exe).apply { writeBytes(bytes); setExecutable(true) }

        val cheekBase = "https://github.com/Mailo037/cheek/releases/download/v${cheekVersion.get()}"
        val cheekName = when (os.get()) {
            "windows" -> "cheek-x86_64-windows.exe"
            "macos" -> if (arch.get() == "arm64") "cheek-aarch64-macos" else "cheek-x86_64-macos"
            else -> if (arch.get() == "arm64") "cheek-aarch64-linux" else "cheek-x86_64-linux"
        }
        put("cheek", verified("$cheekBase/$cheekName", sums("$cheekBase/SHA256SUMS.txt")[cheekName]))

        val ytBase = "https://github.com/yt-dlp/yt-dlp/releases/download/${ytDlpVersion.get()}"
        val ytName = when (os.get()) {
            "windows" -> "yt-dlp.exe"
            "macos" -> "yt-dlp_macos"
            else -> if (arch.get() == "arm64") "yt-dlp_linux_aarch64" else "yt-dlp_linux"
        }
        put("yt-dlp", verified("$ytBase/$ytName", sums("$ytBase/SHA2-256SUMS")[ytName]))

        if (os.get() == "macos") {
            // Martin Riedl's signed static builds, pinned to FFmpeg 9.0.2.
            val build = if (arch.get() == "arm64") "arm64/1789931890_9.0.2" else "amd64/1789931006_9.0.2"
            for (tool in listOf("ffmpeg", "ffprobe")) {
                val url = "https://ffmpeg.martin-riedl.de/download/macos/$build/$tool.zip"
                val zip = verified(url, String(fetch("$url.sha256")).trim().split(Regex("\\s+")).first().lowercase())
                ZipInputStream(zip.inputStream()).use { z ->
                    generateSequence { z.nextEntry }.first { it.name.substringAfterLast('/') == tool }
                    put(tool, z.readBytes())
                }
            }
        } else {
            // yt-dlp's own FFmpeg builds, made for it; "latest" moves, the checksum file moves with it.
            val base = "https://github.com/yt-dlp/FFmpeg-Builds/releases/download/latest"
            val name = when {
                os.get() == "windows" -> "ffmpeg-master-latest-win64-gpl.zip"
                arch.get() == "arm64" -> "ffmpeg-master-latest-linuxarm64-gpl.tar.xz"
                else -> "ffmpeg-master-latest-linux64-gpl.tar.xz"
            }
            val archive = verified("$base/$name", sums("$base/checksums.sha256")[name])
            val wanted = listOf("ffmpeg", "ffprobe").associateBy { "bin/$it$exe" }
            if (name.endsWith(".zip")) {
                ZipInputStream(archive.inputStream()).use { z ->
                    generateSequence { z.nextEntry }.forEach { e ->
                        wanted.entries.firstOrNull { e.name.endsWith(it.key) }?.let { put(it.value, z.readBytes()) }
                    }
                }
            } else {
                TarArchiveInputStream(XZInputStream(archive.inputStream())).use { t ->
                    generateSequence { t.nextEntry }.forEach { e ->
                        wanted.entries.firstOrNull { e.name.endsWith(it.key) }?.let { put(it.value, t.readBytes()) }
                    }
                }
            }
            check(File(dir, "ffmpeg$exe").exists() && File(dir, "ffprobe$exe").exists()) { "$name has no ffmpeg or ffprobe" }
        }
    }
}

val fetchTools by tasks.registering(FetchTools::class) {
    description = "Downloads yt-dlp, Cheek and FFmpeg for this host into build/tools"
    os = hostOs
    arch = hostArch
    cheekVersion = cheekRelease
    ytDlpVersion = ytDlpRelease
    outputDir = layout.buildDirectory.dir("tools")
}

compose.desktop {
    application {
        mainClass = "app.hamstr.desktop.MainKt"
        jvmArgs += listOf("-Dhamstr.version=$appVersion", "-Dhamstr.cheekVersion=$cheekRelease")

        nativeDistributions {
            // Windows' setup is Inno Setup's (packageWindowsSetup below), not jpackage's MSI; the
            // portable zip is the same app folder, zipped by release.yml.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Hamstr"
            packageVersion = appVersion
            // Windows shows this as the app's name (Task Manager, notifications, "Open with"), so it is the name.
            description = "Hamstr"
            vendor = "Hamstr"
            licenseFile = rootProject.file("LICENSE")
            appResourcesRootDir = fetchTools.flatMap { it.outputDir }
            modules("java.net.http", "java.prefs", "jdk.crypto.ec", "jdk.unsupported")

            windows {
                iconFile = project.file("icons/hamstr.ico")
            }
            macOS {
                bundleID = "app.hamstr.desktop"
                dockName = "Hamstr"
                appCategory = "public.app-category.utilities"
                // DMGs want a major version above 0; 0.6.1 ships as 1.6.1 there.
                packageVersion = appVersion.split('.').let { (major, minor, patch) -> "${major.toInt().coerceAtLeast(1)}.$minor.${patch.substringBefore('-')}" }
                iconFile = project.file("icons/hamstr.icns")
            }
            linux {
                packageName = "hamstr"
                iconFile = project.file("icons/hamstr.png")
                // A launcher entry in the app menu, like any installed app.
                shortcut = true
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo"
            }
        }
    }
}

/**
 * Windows' setup: Inno Setup over the app folder (windows/hamstr.iss). It installs for the
 * signed-in user without admin rights or Windows Installer, which jpackage's MSI needed and
 * which made installing slow and removing it ask for a restart. Needs Inno Setup 6: ISCC on
 * the PATH, in its usual folders, or named by the ISCC variable.
 */
val packageWindowsSetup by tasks.registering(Exec::class) {
    description = "Builds Hamstr's Windows setup with Inno Setup"
    group = "compose desktop"
    dependsOn("createDistributable")
    val app = layout.buildDirectory.dir("compose/binaries/main/app/Hamstr")
    val out = layout.buildDirectory.dir("compose/binaries/main/exe")
    val script = file("windows/hamstr.iss")
    inputs.dir(app)
    inputs.dir(script.parentFile)
    outputs.dir(out)
    val local = System.getenv("LOCALAPPDATA")
    executable = listOfNotNull(
        System.getenv("ISCC"),
        local?.let { "$it/Programs/Inno Setup 6/ISCC.exe" },
        "C:/Program Files (x86)/Inno Setup 6/ISCC.exe",
        "C:/Program Files/Inno Setup 6/ISCC.exe",
    ).firstOrNull { File(it).exists() } ?: "ISCC.exe"
    args(
        "/Qp",
        "/DVersion=$appVersion",
        "/DNumericVersion=${appVersion.substringBefore('-')}",
        "/DSource=${app.get().asFile}",
        "/DOutput=${out.get().asFile}",
        script,
    )
    doFirst { out.get().asFile.deleteRecursively() }
}

if (hostOs == "windows") {
    tasks.matching { it.name == "packageDistributionForCurrentOS" }.configureEach { dependsOn(packageWindowsSetup) }
}

package app.hamstr.desktop

import app.hamstr.Log
import java.io.File
import java.util.Base64

/**
 * "Start with this computer": Hamstr starts in the tray when the user signs in, the way each
 * system expects it (the Run key on Windows, a LaunchAgent on macOS, an autostart entry on
 * Linux). Only an installed Hamstr can do it; `gradle run` has no launcher to start.
 */
object Autostart {
    private const val TAG = "HamstrAutostart"

    /** The installed launcher, which jpackage names; null when running from Gradle. */
    private val launcher: String? = System.getProperty("jpackage.app-path")?.takeIf { File(it).exists() }

    val available get() = launcher != null

    private val home = File(System.getProperty("user.home"))
    private val agent = File(home, "Library/LaunchAgents/app.hamstr.desktop.plist")
    private val entry = File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", "autostart/hamstr.desktop")
    private const val RUN_KEY = "HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** Writes or removes the entry; written again on every start, so it follows the app after an update moves it. */
    fun set(on: Boolean) {
        val app = launcher ?: return
        runCatching {
            when {
                Paths.windows -> {
                    // The value needs the path in quotes (Program Files has a space), and quotes don't
                    // survive Windows' argument parsing; an encoded PowerShell command carries them intact.
                    val value = "\"$app\" $TRAY_FLAG".replace("'", "''")
                    val script = if (on) "Set-ItemProperty -Path '$RUN_KEY' -Name Hamstr -Value '$value'"
                        else "Remove-ItemProperty -Path '$RUN_KEY' -Name Hamstr -ErrorAction SilentlyContinue"
                    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
                    ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).redirectErrorStream(true).start().waitFor()
                }
                Paths.mac -> if (on) {
                    agent.parentFile.mkdirs()
                    agent.writeText(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                        <plist version="1.0">
                        <dict>
                            <key>Label</key><string>app.hamstr.desktop</string>
                            <key>ProgramArguments</key><array><string>$app</string><string>$TRAY_FLAG</string></array>
                            <key>RunAtLoad</key><true/>
                        </dict>
                        </plist>
                        """.trimIndent() + "\n",
                    )
                } else agent.delete()
                else -> if (on) {
                    entry.parentFile.mkdirs()
                    entry.writeText("[Desktop Entry]\nType=Application\nName=Hamstr\nExec=\"$app\" $TRAY_FLAG\nIcon=hamstr\nX-GNOME-Autostart-enabled=true\n")
                } else entry.delete()
            }
        }.onFailure { Log.w(TAG, "Couldn't change autostart", it) }
    }

    /** Started by the system at sign-in: stay in the tray instead of opening the window. */
    const val TRAY_FLAG = "--tray"
}

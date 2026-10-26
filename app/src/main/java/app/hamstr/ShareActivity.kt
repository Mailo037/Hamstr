package app.hamstr

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.hamstr.data.Prefs
import app.hamstr.engine.DownloadService
import app.hamstr.ui.Overlay
import app.hamstr.ui.ShareSheet
import app.hamstr.ui.theme.HamstrTheme
import kotlinx.coroutines.delay

/**
 * Target of "Share -> Hamstr". Transparent window: only the sheet shows, over whatever
 * app the link came from. With "Direkt bunkern" on it queues and closes at once.
 */
class ShareActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url == null) {
            Toast.makeText(this, "No link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        askPermissions(this, notifPermission::launch, storagePermission::launch)
        if (Prefs.settings.value.quickStash) {
            DownloadService.stash(this, url, null, Prefs.settings.value.defaultChoice())
            Toast.makeText(this, "Nom! Stashing it", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            HamstrTheme {
                var visible by remember { mutableStateOf(false) }
                var closing by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                LaunchedEffect(closing) {
                    if (closing) { delay(200); finish(); overridePendingTransition(0, 0) }
                }
                Overlay(visible = visible && !closing, onDismiss = { closing = true }) {
                    ShareSheet(
                        url,
                        onStash = { info, choice ->
                            DownloadService.stash(this, url, info, choice)
                            closing = true
                        },
                        onDismiss = { closing = true },
                    )
                }
            }
        }
    }

    companion object {
        private val URL = Regex("""https?://[^\s"'<>]+""")

        fun extractUrl(intent: Intent?): String? {
            intent ?: return null
            val candidates = listOfNotNull(
                intent.dataString,
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
            )
            return candidates.firstNotNullOfOrNull { URL.find(it)?.value?.trimEnd('.', ',', ')', ']') }
        }
    }
}

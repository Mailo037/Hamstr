package app.hamstr

import android.Manifest
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
import app.hamstr.ui.NotifyAsk
import app.hamstr.ui.NotifyPrompt
import app.hamstr.ui.Overlay
import app.hamstr.ui.ShareSheet
import app.hamstr.ui.theme.HamstrTheme
import kotlinx.coroutines.delay

/**
 * Target of "Share -> Hamstr". Transparent window: only the sheet shows, over whatever
 * app the link came from. With "Instant stash" on it queues and closes at once. After the
 * very first stash it asks, once, whether Hamstr may send notifications.
 */
class ShareActivity : ComponentActivity() {
    private var close: () -> Unit = { finish() }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { close() }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val url = extractUrl(intent)
        if (url == null) {
            Toast.makeText(this, getString(R.string.share_no_link), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        askStorage(this, storagePermission::launch)
        if (Prefs.settings.value.quickStash) {
            DownloadService.stash(this, url, null, Prefs.settings.value.defaultChoice())
            Toast.makeText(this, getString(R.string.share_stashing), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            HamstrTheme {
                var visible by remember { mutableStateOf(false) }
                var closing by remember { mutableStateOf(false) }
                var asking by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { visible = true }
                LaunchedEffect(closing) {
                    if (closing) { delay(200); finish(); overridePendingTransition(0, 0) }
                }
                close = { closing = true }
                // After the stash, the sheet either closes or turns into the one-time question.
                val stashed = { if (NotifyAsk.due(this)) asking = true else closing = true }
                Overlay(visible = visible && !closing && !asking, onDismiss = { closing = true }) {
                    ShareSheet(
                        url,
                        onStash = { info, choice ->
                            DownloadService.stash(this, url, info, choice)
                            stashed()
                        },
                        onDismiss = { closing = true },
                        onOpen = { task ->
                            startActivity(
                                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN)
                                    .putExtra(DownloadService.EXTRA_ID, task.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                            finish()
                        },
                        onStashVersion = { of, choice ->
                            DownloadService.stashVersion(this, of, choice, replace = false)
                            stashed()
                        },
                    )
                }
                Overlay(visible = asking && !closing, onDismiss = { NotifyAsk.asked(); closing = true }) {
                    NotifyPrompt(
                        onAllow = { NotifyAsk.asked(); notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        onLater = { NotifyAsk.asked(); closing = true },
                    )
                }
            }
        }
    }

    companion object {
        fun extractUrl(intent: Intent?): String? {
            intent ?: return null
            val candidates = listOfNotNull(
                intent.dataString,
                intent.getStringExtra(Intent.EXTRA_TEXT),
                intent.getStringExtra(Intent.EXTRA_SUBJECT),
            )
            return candidates.firstNotNullOfOrNull(app.hamstr.engine.MediaLinks::find)
        }
    }
}

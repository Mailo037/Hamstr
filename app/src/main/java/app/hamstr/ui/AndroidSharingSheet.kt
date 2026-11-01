package app.hamstr.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.hamstr.R
import app.hamstr.player.Playback

/** Android's clipboard and share intents, kept outside the shared UI. */
@Composable
fun AndroidSharingSheet(content: ShareContent, onDismiss: () -> Unit, onCopied: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    SharingSheet(
        content,
        onCopyLink = { url ->
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Hamstr", url))
            val message = context.getString(R.string.info_copied)
            if (onCopied != null) onCopied(message) else Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            onDismiss()
        },
        onShareLink = { url ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                .putExtra(Intent.EXTRA_TITLE, content.title)
            context.startActivity(Intent.createChooser(send, content.title))
            onDismiss()
        },
        onShareFiles = { Playback.shareFiles(context, content.files); onDismiss() },
    )
}

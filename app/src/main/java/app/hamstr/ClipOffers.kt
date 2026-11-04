package app.hamstr

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.textclassifier.TextClassifier
import app.hamstr.data.DownloadStore
import app.hamstr.data.Prefs
import app.hamstr.engine.MediaLinks
import app.hamstr.ui.ClipOffer

/**
 * What the stash offers from the clipboard. Android 12 and later tell the user each time an app
 * reads it, so there Hamstr only looks at what Android says about the clip (when it was copied,
 * whether it is a link) and reads it once the user takes it; before Android 12 the link itself
 * is checked. Each copy is offered once ([app.hamstr.data.Settings.clipSeen]).
 */
internal object ClipOffers {
    fun next(context: Context): ClipOffer? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val d = cm.primaryClipDescription ?: return null
        if (!d.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !d.hasMimeType("text/*")) return null
        val stamp = d.timestamp
        if (stamp <= 0 || stamp == Prefs.settings.value.clipSeen) return null
        val offer = if (Build.VERSION.SDK_INT >= 31) {
            if (d.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE || d.getConfidenceScore(TextClassifier.TYPE_URL) < 0.5f) return null
            ClipOffer(null)
        } else {
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            val link = MediaLinks.find(text) ?: return null
            if (DownloadStore.tasks.value.any { it.url == link }) return null
            ClipOffer(link)
        }
        Prefs.edit { it.copy(clipSeen = stamp) }
        return offer
    }

    /** Hamstr copied something itself: that one isn't offered back. */
    fun copiedHere(context: Context) {
        val stamp = context.getSystemService(ClipboardManager::class.java)?.primaryClipDescription?.timestamp ?: return
        Prefs.edit { it.copy(clipSeen = stamp) }
    }
}

package app.hamstr.nearby

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.ShareActivity
import app.hamstr.data.Importer
import app.hamstr.data.Prefs
import app.hamstr.data.keyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import app.hamstr.cast.Cast
import app.hamstr.cast.CastProtocol
import app.hamstr.cast.HamstrCast
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import app.hamstr.engine.DownloadService
import app.hamstr.engine.MediaLinks
import app.hamstr.ui.AppIcon

/**
 * Nearby on the phone: Wi-Fi (holding the multicast lock while it listens) and Bluetooth.
 * A link from a trusted device goes straight into the queue with a short notice; one that
 * needs a choice first (a series' episodes) or comes from a new device waits behind a
 * notification. While Hamstr is open, the question shows in the app itself.
 */
object PhoneNearby {
    private const val CHANNEL = "nearby"
    /** Remembered devices are share targets with this shortcut id prefix and category (res/xml/shortcuts.xml). */
    private const val DEVICE = "device:"
    private const val SEND_TO_DEVICE = "app.hamstr.category.SEND_TO_DEVICE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.nearby_channel), NotificationManager.IMPORTANCE_HIGH))
        val lock = app.getSystemService(WifiManager::class.java)?.createMulticastLock("hamstr-nearby")?.apply { setReferenceCounted(false) }
        // On the same storage as Download, so a finished file only moves.
        Nearby.inbox = File(app.getExternalFilesDir(null) ?: app.filesDir, "nearby-inbox")
        Nearby.init(
            keyValues(app, "nearby"), app.filesDir, DeviceKind.Phone, deviceName(app),
            initiallyAvailable = false,
            transports = listOf(
                LanTransport(onStart = { runCatching { lock?.acquire() } }, onStop = { runCatching { lock?.release() } }),
                BleTransport(app),
            ),
        )
        // Listening while the app is open, and while it plays on another Hamstr or another one
        // plays here ("Play on"), so the screen going off doesn't cut that off.
        val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { foreground.value = true }
            override fun onStop(owner: LifecycleOwner) { foreground.value = false }
        })
        scope.launch {
            combine(foreground, HamstrCast.incoming, Cast.session) { open, incoming, cast ->
                open || incoming != null || cast?.device?.protocol == CastProtocol.Hamstr
            }.distinctUntilChanged().collect(Nearby::setAvailable)
        }
        Nearby.onLink = { url, title, from ->
            if (MediaLinks.needsEpisodeSelection(url)) {
                notify(app, url.hashCode(), app.getString(R.string.nearby_received, from.name), title ?: url, share(app, url))
            } else {
                DownloadService.stash(app, url, null, Prefs.settings.value.defaultChoice())
                notify(app, url.hashCode(), app.getString(R.string.nearby_received, from.name), title ?: url, open(app))
            }
        }
        Nearby.onFiles = { files, from ->
            scope.launch {
                val kept = Importer.adopt(app, files, from.name)
                if (kept.isNotEmpty()) notify(app, from.id.hashCode(), app.getString(R.string.nearby_received, from.name), kept.joinToString(", ") { it.name }, open(app))
            }
        }
        Nearby.onRequest = { r ->
            val title = app.getString(if (r.play) R.string.nearby_play_title else R.string.nearby_ask_title, r.from.name)
            notify(app, r.id.hashCode(), title, app.getString(R.string.nearby_ask_notify), open(app))
        }
        // The remembered devices show in Android's share sheet, drawn in the accent like the other shortcuts.
        scope.launch {
            combine(Nearby.known, Prefs.settings.map { it.accent }) { known, accent -> known.map { Triple(it.id, it.name, it.kind) } to accent }
                .distinctUntilChanged()
                .collect { (devices, accent) -> shareTargets(app, devices, accent) }
        }
    }

    /** The device a link was shared to in Android's share sheet; null for another shortcut or a device forgotten since. */
    fun deviceOf(shortcut: String?): String? =
        shortcut?.takeIf { it.startsWith(DEVICE) }?.removePrefix(DEVICE)?.takeIf { id -> Nearby.known.value.any { it.id == id } }

    /** Offers up to eight [devices] as share targets (not in the launcher) and takes back the forgotten ones. */
    private fun shareTargets(context: Context, devices: List<Triple<String, String, DeviceKind>>, accent: Int) = runCatching {
        val keep = devices.take(8)
        val ids = keep.map { DEVICE + it.first }.toSet()
        val gone = ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id }.filter { it.startsWith(DEVICE) && it !in ids }
        if (gone.isNotEmpty()) ShortcutManagerCompat.removeLongLivedShortcuts(context, gone)
        keep.forEachIndexed { i, (id, name, kind) ->
            val icon = if (kind == DeviceKind.Desktop) R.drawable.ic_computer else R.drawable.ic_smartphone
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, DEVICE + id)
                    .setShortLabel(name)
                    .setIcon(IconCompat.createWithAdaptiveBitmap(AppIcon.glyph(context, icon, accent)))
                    .setIntent(Intent(Intent.ACTION_VIEW).setClassName(context, MainActivity::class.java.name))
                    .setCategories(setOf(SEND_TO_DEVICE))
                    .setLongLived(true)
                    .setExcludedFromSurfaces(ShortcutInfoCompat.SURFACE_LAUNCHER)
                    .setRank(i)
                    .build(),
            )
        }
    }

    /** The name the phone goes by in its own settings, else its model. */
    private fun deviceName(context: Context) =
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun notify(context: Context, id: Int, title: String, text: String, tap: PendingIntent) {
        val allowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed || !Prefs.settings.value.notifications) return
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hamstr)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java).notify("nearby", id, n)
    }

    private fun open(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 7, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun share(context: Context, url: String): PendingIntent = PendingIntent.getActivity(
        context, url.hashCode(),
        Intent(context, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

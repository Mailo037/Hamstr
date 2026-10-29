package app.hamstr.nearby

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
import app.hamstr.MainActivity
import app.hamstr.R
import app.hamstr.ShareActivity
import app.hamstr.data.Prefs
import app.hamstr.data.keyValues
import app.hamstr.engine.DownloadService
import app.hamstr.engine.MediaLinks

/**
 * Nearby on the phone: Wi-Fi (holding the multicast lock while it listens) and Bluetooth.
 * A link from a trusted device goes straight into the queue with a short notice; one that
 * needs a choice first (a series' episodes) or comes from a new device waits behind a
 * notification. While Hamstr is open, the question shows in the app itself.
 */
object PhoneNearby {
    private const val CHANNEL = "nearby"

    fun start(context: Context) {
        val app = context.applicationContext
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.nearby_channel), NotificationManager.IMPORTANCE_HIGH))
        val lock = app.getSystemService(WifiManager::class.java)?.createMulticastLock("hamstr-nearby")?.apply { setReferenceCounted(false) }
        Nearby.init(
            keyValues(app, "nearby"), app.filesDir, DeviceKind.Phone, deviceName(app),
            transports = listOf(
                LanTransport(onStart = { runCatching { lock?.acquire() } }, onStop = { runCatching { lock?.release() } }),
                BleTransport(app),
            ),
        )
        Nearby.onLink = { url, title, from ->
            if (MediaLinks.needsEpisodeSelection(url)) {
                notify(app, url.hashCode(), app.getString(R.string.nearby_received, from.name), title ?: url, share(app, url))
            } else {
                DownloadService.stash(app, url, null, Prefs.settings.value.defaultChoice())
                notify(app, url.hashCode(), app.getString(R.string.nearby_received, from.name), title ?: url, open(app))
            }
        }
        Nearby.onRequest = { r ->
            notify(app, r.id.hashCode(), app.getString(R.string.nearby_ask_title, r.from.name), app.getString(R.string.nearby_ask_notify), open(app))
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

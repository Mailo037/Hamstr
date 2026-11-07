package app.hamstr.player

import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.provider.Settings
import app.hamstr.cast.Cast

/** Android's own output switcher: Bluetooth headphones and speakers, cars, wired ones. */
object Outputs {
    fun show(context: Context) {
        // Playing there and here at once would only confuse; the switcher is for this phone.
        Cast.disconnect()
        if (Build.VERSION.SDK_INT >= 34 && runCatching { MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
        val dialog = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
            .setPackage("com.android.systemui")
            .putExtra("package_name", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 30 && runCatching { context.sendBroadcast(dialog) }.isSuccess) return
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

package app.hamstr.ui

import app.hamstr.res.*
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hamstr.R
import app.hamstr.data.Prefs
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.SheetHeader

/** Asking for Android's notification permission happens once, right after the first stash. */
object NotifyAsk {
    fun permitted(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun due(context: Context): Boolean {
        val s = Prefs.settings.value
        return s.notifications && !s.askedNotifications && !permitted(context)
    }

    fun asked() = Prefs.edit { it.copy(askedNotifications = true) }
}

/** "Want a ping when it's in?", with the reason, shown as a sheet. */
@Composable
fun NotifyPrompt(onAllow: () -> Unit, onLater: () -> Unit) {
    Sheet {
        SheetHeader(
            stringResource(R.string.notify_ask_title),
            subtitle = stringResource(R.string.notify_ask_text),
            subtitleLines = Int.MAX_VALUE,
            leading = { HamstrMark(40.dp) },
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.notify_ask_later), onLater, Modifier.weight(1f))
            HButton(stringResource(R.string.notify_ask_allow), onAllow, Modifier.weight(2f), kind = ButtonKind.Primary, icon = Res.drawable.ic_check)
        }
    }
}

package app.hamstr.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.hamstr.R
import app.hamstr.data.Prefs
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.sheetScroll
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Txt
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.Radii

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
    val c = Hamstr.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet))
            .background(c.overlay)
            .clickable(enabled = false) {}
            .sheetScroll()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(36.dp).height(4.dp).clip(RoundedCornerShape(4.dp)).background(c.line))
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            HamstrMark(40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(R.string.notify_ask_title), style = Hamstr.type.section)
                Spacer(Modifier.height(4.dp))
                Txt(stringResource(R.string.notify_ask_text), style = Hamstr.type.small, color = c.muted)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.notify_ask_later), onLater, Modifier.weight(1f))
            HButton(stringResource(R.string.notify_ask_allow), onAllow, Modifier.weight(2f), kind = ButtonKind.Primary, icon = R.drawable.ic_check)
        }
        Spacer(Modifier.height(4.dp))
    }
}

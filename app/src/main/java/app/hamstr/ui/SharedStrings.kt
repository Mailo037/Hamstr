package app.hamstr.ui

import android.annotation.SuppressLint
import android.content.Context
import org.jetbrains.compose.resources.StringResource

/**
 * A shared string, read the Android way for code outside Compose (notifications, widgets).
 * The shared strings.xml is also this app's own resources, so the name finds it.
 */
@SuppressLint("DiscouragedApi")
fun Context.getString(res: StringResource, vararg args: Any): String {
    val id = resources.getIdentifier(res.key, "string", packageName)
    return if (args.isEmpty()) getString(id) else getString(id, *args)
}

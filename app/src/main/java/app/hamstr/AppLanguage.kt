package app.hamstr

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import app.hamstr.data.AppLanguages
import app.hamstr.data.Prefs
import java.util.Locale

/**
 * The language picked in Settings › Appearance. Android 13 and later keep it in the system (the
 * same per-app language as the system settings show, and they redraw the app). Before that, Hamstr
 * keeps it in its prefs and [wrap]s each activity, service and the app in it.
 */
object AppLanguage {
    /** A tag of [AppLanguages], or "" when the device's language is used. */
    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= 33) {
            AppLanguages.of(context.getSystemService(LocaleManager::class.java).applicationLocales.get(0))
        } else {
            Prefs.settings.value.language
        }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            val list = if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            activity.getSystemService(LocaleManager::class.java).applicationLocales = list
            return
        }
        Prefs.edit { it.copy(language = tag) }
        val locale = if (tag.isEmpty()) system else AppLanguages.locale(tag)
        Locale.setDefault(locale)
        // The app's own resources (notifications, toasts) until the next start.
        val app = activity.applicationContext
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(Configuration(app.resources.configuration).apply { setLocale(locale) }, app.resources.displayMetrics)
        activity.recreate()
    }

    /**
     * Before Android 13, a rotation or resize puts the device's language back as the default;
     * this puts the picked one back, before the screen is drawn again.
     */
    fun keep() {
        if (Build.VERSION.SDK_INT >= 33) return
        Prefs.settings.value.language.takeIf { it.isNotEmpty() }?.let { Locale.setDefault(AppLanguages.locale(it)) }
    }

    /** The device's own language, from before Hamstr changed the default. */
    private val system: Locale = Locale.getDefault()

    /** Before Android 13: [base] speaking the picked language. Read from the prefs file, as this runs before [Prefs.init]. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = base.getSharedPreferences("hamstr", Context.MODE_PRIVATE).getString("language", "").orEmpty()
        if (tag.isEmpty()) return base
        val locale = AppLanguages.locale(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }
}

package app.hamstr.desktop

import app.hamstr.data.AppLanguages
import app.hamstr.data.Prefs
import java.util.Locale

/**
 * The language picked in Settings › Appearance: the default locale, which the strings, numbers and
 * dates follow. A change redraws the window from scratch (keyed on it in Main) and comes back to
 * [reopen], so the picker stays in view.
 */
internal object Language {
    /** The computer's own language, from before Hamstr changed the default. */
    private val system: Locale = Locale.getDefault()

    /** The settings page to open when the window is drawn again in the new language. */
    var reopen: Page? = null

    /** At start, before anything is drawn. */
    fun start() = apply(Prefs.settings.value.language)

    fun pick(tag: String) {
        reopen = Page.Appearance
        apply(tag)
        Prefs.edit { it.copy(language = tag) }
    }

    private fun apply(tag: String) = Locale.setDefault(if (tag.isEmpty()) system else AppLanguages.locale(tag))
}

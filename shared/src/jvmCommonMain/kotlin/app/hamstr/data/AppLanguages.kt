package app.hamstr.data

import java.util.Locale

/**
 * The languages Hamstr is translated into, by tag, each named in its own words so anyone finds
 * theirs. An empty tag follows the device. The same list is in app/src/main/res/xml/locales_config.xml.
 */
object AppLanguages {
    val names: Map<String, String> = linkedMapOf(
        "en" to "English",
        "de" to "Deutsch",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "pt-BR" to "Português (Brasil)",
        "nl" to "Nederlands",
        "pl" to "Polski",
        "tr" to "Türkçe",
        "ru" to "Русский",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh-CN" to "中文（简体）",
    )

    /** The tag of ours a locale falls under (de-AT is de, pt-PT is pt-BR), or "" for none. */
    fun of(locale: Locale?): String {
        val lang = locale?.language?.takeIf { it.isNotEmpty() } ?: return ""
        return names.keys.firstOrNull { Locale.forLanguageTag(it).language == lang } ?: ""
    }

    fun locale(tag: String): Locale = Locale.forLanguageTag(tag)
}

package app.hamstr.engine

import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource

/** What the user can do about a failure. */
enum class Fix { None, Retry, UpdateYtDlp }

/** yt-dlp's or Cheek's error in plain words: what happened, and what helps. */
data class Explained(val title: StringResource, val hint: StringResource, val fix: Fix)

object Failures {
    private fun rule(pattern: String, title: StringResource, hint: StringResource, fix: Fix) = Regex(pattern, RegexOption.IGNORE_CASE) to Explained(title, hint, fix)

    private val OFFLINE = Explained(Res.string.err_offline, Res.string.err_offline_hint, Fix.Retry)
    private val UNKNOWN = Explained(Res.string.err_unknown, Res.string.err_unknown_hint, Fix.Retry)

    // First match wins, so the specific ones come before "the connection broke".
    private val rules = listOf(
        rule("No space left|ENOSPC|disk full", Res.string.err_full, Res.string.err_full_hint, Fix.Retry),
        rule("Unsupported URL|no suitable extractor|isn't a media link|nothing to download", Res.string.err_unsupported, Res.string.err_unsupported_hint, Fix.None),
        rule("DRM|protected by|encrypted", Res.string.err_drm, Res.string.err_drm_hint, Fix.None),
        rule("Private video|video is private|private account|account is private|members[- ]only|join this channel", Res.string.err_private, Res.string.err_private_hint, Fix.None),
        rule("confirm your age|age[- ]restricted|inappropriate for some users", Res.string.err_age, Res.string.err_age_hint, Fix.None),
        rule("not a bot|login required|log ?in to|sign in|requires authentication|use --cookies|rate-limit reached|empty media response", Res.string.err_login, Res.string.err_login_hint, Fix.Retry),
        rule("not available in your country|geo.?restrict|not available from your location|in your country", Res.string.err_geo, Res.string.err_geo_hint, Fix.None),
        rule("live event will begin|premieres in|is_upcoming|live stream|is live|currently live", Res.string.err_live, Res.string.err_live_hint, Fix.Retry),
        rule("Video unavailable|has been removed|no longer available|HTTP Error 404|HTTP Error 410|404 Not Found|410 Gone|does not exist|been deleted|been terminated", Res.string.err_gone, Res.string.err_gone_hint, Fix.None),
        rule("Requested format is not available|format is not available", Res.string.err_format, Res.string.err_format_hint, Fix.None),
        rule("Unable to extract|ExtractorError|nsig|n challenge|signature|player response|Precondition check failed|HTTP Error 403|403 Forbidden|Forbidden", Res.string.err_changed, Res.string.err_changed_hint, Fix.UpdateYtDlp),
        rule(
            "Unable to download (webpage|API page|JSON)|Failed to resolve|name resolution|No address associated|Network is unreachable|" +
                "Connection (reset|refused|aborted)|timed out|IncompleteRead|getaddrinfo|ConnectionError|error sending request|" +
                "connection closed|dns error|tcp connect|connection abort|unexpected EOF|EOF occurred|broken pipe|" +
                "HTTP Error 5\\d\\d|server said 5\\d\\d|Remote end closed|SSL|handshake",
            Res.string.err_offline, Res.string.err_offline_hint, Fix.Retry,
        ),
        rule("ffmpeg|Postprocessing|Conversion failed|CANNOT LINK|Merging formats", Res.string.err_ffmpeg, Res.string.err_ffmpeg_hint, Fix.Retry),
    )

    fun explain(raw: String?): Explained {
        val text = raw.orEmpty()
        if (text.isBlank()) return UNKNOWN
        return rules.firstOrNull { it.first.containsMatchIn(text) }?.second ?: UNKNOWN
    }

    /** A dropped connection or a server hiccup: worth trying again on its own. */
    fun isNetwork(raw: String?) = explain(raw) == OFFLINE
}

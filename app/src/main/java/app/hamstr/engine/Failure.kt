package app.hamstr.engine

import androidx.annotation.StringRes
import app.hamstr.R

/** What the user can do about a failure. */
enum class Fix { None, Retry, UpdateYtDlp }

/** yt-dlp's or Cheek's error in plain words: what happened, and what helps. */
data class Explained(@StringRes val title: Int, @StringRes val hint: Int, val fix: Fix)

object Failures {
    private fun rule(pattern: String, title: Int, hint: Int, fix: Fix) = Regex(pattern, RegexOption.IGNORE_CASE) to Explained(title, hint, fix)

    private val OFFLINE = Explained(R.string.err_offline, R.string.err_offline_hint, Fix.Retry)
    private val UNKNOWN = Explained(R.string.err_unknown, R.string.err_unknown_hint, Fix.Retry)

    // First match wins, so the specific ones come before "the connection broke".
    private val rules = listOf(
        rule("No space left|ENOSPC|disk full", R.string.err_full, R.string.err_full_hint, Fix.Retry),
        rule("Unsupported URL|no suitable extractor|isn't a media link|nothing to download", R.string.err_unsupported, R.string.err_unsupported_hint, Fix.None),
        rule("DRM|protected by|encrypted", R.string.err_drm, R.string.err_drm_hint, Fix.None),
        rule("Private video|video is private|private account|account is private|members[- ]only|join this channel", R.string.err_private, R.string.err_private_hint, Fix.None),
        rule("confirm your age|age[- ]restricted|inappropriate for some users", R.string.err_age, R.string.err_age_hint, Fix.None),
        rule("not a bot|login required|log ?in to|sign in|requires authentication|use --cookies|rate-limit reached|empty media response", R.string.err_login, R.string.err_login_hint, Fix.Retry),
        rule("not available in your country|geo.?restrict|not available from your location|in your country", R.string.err_geo, R.string.err_geo_hint, Fix.None),
        rule("live event will begin|premieres in|is_upcoming|live stream|is live|currently live", R.string.err_live, R.string.err_live_hint, Fix.Retry),
        rule("Video unavailable|has been removed|no longer available|HTTP Error 404|HTTP Error 410|404 Not Found|410 Gone|does not exist|been deleted|been terminated", R.string.err_gone, R.string.err_gone_hint, Fix.None),
        rule("Requested format is not available|format is not available", R.string.err_format, R.string.err_format_hint, Fix.None),
        rule("Unable to extract|ExtractorError|nsig|n challenge|signature|player response|Precondition check failed|HTTP Error 403|403 Forbidden|Forbidden", R.string.err_changed, R.string.err_changed_hint, Fix.UpdateYtDlp),
        rule(
            "Unable to download (webpage|API page|JSON)|Failed to resolve|name resolution|No address associated|Network is unreachable|" +
                "Connection (reset|refused|aborted)|timed out|IncompleteRead|getaddrinfo|ConnectionError|error sending request|" +
                "connection closed|dns error|tcp connect|connection abort|unexpected EOF|EOF occurred|broken pipe|" +
                "HTTP Error 5\\d\\d|server said 5\\d\\d|Remote end closed|SSL|handshake",
            R.string.err_offline, R.string.err_offline_hint, Fix.Retry,
        ),
        rule("ffmpeg|Postprocessing|Conversion failed|CANNOT LINK|Merging formats", R.string.err_ffmpeg, R.string.err_ffmpeg_hint, Fix.Retry),
    )

    fun explain(raw: String?): Explained {
        val text = raw.orEmpty()
        if (text.isBlank()) return UNKNOWN
        return rules.firstOrNull { it.first.containsMatchIn(text) }?.second ?: UNKNOWN
    }

    /** A dropped connection or a server hiccup: worth trying again on its own. */
    fun isNetwork(raw: String?) = explain(raw) == OFFLINE
}

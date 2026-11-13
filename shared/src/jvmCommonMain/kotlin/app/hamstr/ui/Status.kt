package app.hamstr.ui

import androidx.compose.runtime.Composable
import app.hamstr.data.DownloadTask
import app.hamstr.data.Prefs
import app.hamstr.data.Schedule
import app.hamstr.data.Stage
import app.hamstr.data.Status
import app.hamstr.engine.Failures
import app.hamstr.res.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Time left as "48 s", "3 min" or "1 h 5 min"; empty when unknown. Not 0:48, which reads like a
 * length. The units are the international symbols, like the retry countdown's "12 s".
 */
fun eta(sec: Long): String {
    if (sec <= 0) return ""
    if (sec < 60) return "$sec s"
    val minutes = (sec + 30) / 60
    if (minutes < 60) return "$minutes min"
    val h = minutes / 60
    val m = minutes % 60
    return if (m == 0L) "$h h" else "$h h $m min"
}

/** "48 s left"; empty when unknown. */
@Composable
fun etaLeft(sec: Long): String = eta(sec).let { if (it.isEmpty()) it else stringResource(Res.string.eta_left, it) }

/**
 * What a download is doing right now, when it's more than fetching bytes: the string and
 * its argument, so the screens and Android's notification say the same.
 */
fun stageString(t: DownloadTask, now: Long = System.currentTimeMillis()): Pair<StringResource, Any?>? = when (t.stage) {
    null -> null
    Stage.Resolving -> Res.string.stage_resolving to null
    Stage.Merging -> Res.string.stage_merging to null
    Stage.ExtractingAudio -> Res.string.stage_extracting to null
    Stage.Remuxing -> Res.string.stage_remuxing to null
    Stage.Tagging -> Res.string.stage_tagging to null
    Stage.WaitingForNetwork -> Res.string.stage_waiting_network to null
    Stage.WaitingForWifi -> Res.string.stage_waiting_wifi to null
    Stage.PausedBySystem -> Res.string.stage_paused_by_system to null
    Stage.WaitingForNight -> Res.string.stage_waiting_night to Schedule.clock(Prefs.settings.value.nightFrom)
    Stage.WaitingForCharger -> Res.string.stage_waiting_charger to null
    Stage.Retrying -> {
        val left = (t.retryAt - now) / 1000
        if (left > 0) Res.string.stage_retrying to "$left s" else Res.string.stage_retrying_now to null
    }
}

@Composable
fun stageText(t: DownloadTask, now: Long = System.currentTimeMillis()): String? =
    stageString(t, now)?.let { (res, arg) -> if (arg == null) stringResource(res) else stringResource(res, arg) }
        // How far ffmpeg is, when the engine says: "Merging video and audio · 42 %".
        ?.let { text -> if (t.stageProgress >= 0f && t.stage?.waiting == false) text.removeSuffix(" …") + " · ${(t.stageProgress * 100).toInt()} %" else text }

/** The meta line under a download: where it is, how fast, or what it weighs and when it came. */
@Composable
fun statusLine(t: DownloadTask, versions: List<DownloadTask> = listOf(t)): String = when (t.status) {
    Status.Queued -> if (t.progress > 0f) stringResource(Res.string.status_queued_at, (t.progress * 100).toInt()) else stringResource(Res.string.status_queued)
    Status.Running -> listOf(t.speed, etaLeft(t.etaSec)).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { t.source ?: stringResource(Res.string.status_loading) }
    Status.Paused -> stringResource(Res.string.status_paused, (t.progress * 100).toInt())
    Status.Failed -> stringResource(Failures.explain(t.error).title)
    Status.Done -> listOfNotNull(
        t.uploader ?: t.source,
        versions.sumOf { it.sizeBytes }.takeIf { it > 0 }?.let(::formatBytes),
        ago(versions.maxOf { it.finishedAt }),
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    Status.Canceled -> stringResource(Res.string.status_canceled)
}

@Composable
fun ago(ts: Long): String {
    if (ts <= 0) return ""
    val m = (System.currentTimeMillis() - ts) / 60_000
    return when {
        m < 1 -> stringResource(Res.string.ago_now)
        m < 60 -> stringResource(Res.string.ago_minutes, m)
        m < 60 * 24 -> stringResource(Res.string.ago_hours, m / 60)
        else -> stringResource(Res.string.ago_days, m / 60 / 24)
    }
}

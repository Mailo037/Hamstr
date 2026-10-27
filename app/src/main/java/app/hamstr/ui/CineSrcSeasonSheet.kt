package app.hamstr.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.hamstr.R
import app.hamstr.data.Prefs
import app.hamstr.engine.CineSrcCatalog
import app.hamstr.engine.CineSrcSeason
import app.hamstr.engine.CineSrcSeries
import app.hamstr.engine.CineSrcTarget
import app.hamstr.ui.components.*
import app.hamstr.ui.theme.Hamstr
import kotlinx.coroutines.CancellationException
import app.hamstr.ui.components.Sheet
import app.hamstr.ui.components.Chips

/** Browse first; no media is resolved or downloaded until an episode is chosen. Looks like the playlist sheet. */
@Composable
internal fun CineSrcSeasonSheet(target: CineSrcTarget, onEpisode: (String) -> Unit, onDismiss: () -> Unit, onQueued: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val c = Hamstr.colors
    var series by remember(target.tmdbId) { mutableStateOf<CineSrcSeries?>(null) }
    var season by remember(target) { mutableStateOf(target.season) }
    var loaded by remember { mutableStateOf<CineSrcSeason?>(null) }
    var selected by remember(season) { mutableStateOf(emptySet<Int>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var height by remember { mutableStateOf(Prefs.settings.value.height) }
    var queued by remember { mutableStateOf(false) }
    LaunchedEffect(target.tmdbId, retry) {
        error = null
        try {
            val show = CineSrcCatalog.series(target.tmdbId)
            series = show
            check(show.seasons.isNotEmpty()) { resources.getString(R.string.cinesrc_empty) }
            if (season == null) season = show.seasons.firstOrNull { it.number > 0 }?.number ?: show.seasons.firstOrNull()?.number
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(series, season, retry) {
        val show = series ?: return@LaunchedEffect
        val number = season ?: return@LaunchedEffect
        loaded = null
        error = null
        try {
            check(show.seasons.any { it.number == number }) { resources.getString(R.string.cinesrc_season_missing) }
            loaded = CineSrcCatalog.season(show.id, number)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message }
    }
    val current = loaded?.takeIf { it.number == season }
    val seasonLabel = { n: Int? -> if (n == 0) resources.getString(R.string.cinesrc_specials) else resources.getString(R.string.cinesrc_season, n) }
    val episodes = if (error == null) current?.episodes.orEmpty() else emptyList()
    // The header and the state of the season ride in the list; the buttons stay put.
    Sheet(scroll = false) {
        PickList(
            episodes, key = { _, e -> e.number },
            header = {
                item(key = "head") {
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge("CineSrc")
                            series?.seasons?.firstOrNull { it.number == season }?.let {
                                Badge(pluralStringResource(R.plurals.cinesrc_episode_count, it.count, it.count), accent = true)
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        DecodeText(series?.title ?: stringResource(R.string.cinesrc_episodes), style = Hamstr.type.section, maxLines = 2)
                        Txt(stringResource(R.string.cinesrc_choose_hint), style = Hamstr.type.small, color = c.muted)
                        Spacer(Modifier.height(6.dp))
                        series?.let { show ->
                            Chips(show.seasons.map { it.number }, season, seasonLabel) { season = it }
                            Spacer(Modifier.height(6.dp))
                        }
                        when {
                            error != null -> Column(Modifier.padding(vertical = 8.dp)) {
                                Txt(stringResource(R.string.cinesrc_load_failed), style = Hamstr.type.bodyMedium, color = c.red)
                                Spacer(Modifier.height(2.dp))
                                Txt(error.orEmpty(), style = Hamstr.type.mono, color = c.muted, maxLines = 3)
                                Spacer(Modifier.height(8.dp))
                                HButton(stringResource(R.string.cinesrc_retry), { retry++ }, icon = R.drawable.ic_refresh)
                            }
                            current == null -> Txt(stringResource(R.string.cinesrc_loading), color = c.muted, modifier = Modifier.padding(vertical = 24.dp))
                            current.episodes.isEmpty() -> Txt(stringResource(R.string.cinesrc_empty), color = c.muted, modifier = Modifier.padding(vertical = 24.dp))
                            else -> {
                                val all = current.episodes.map { it.number }.toSet()
                                val allOn = selected.size == all.size
                                val totalSec = current.episodes.filter { it.number in selected }.sumOf { it.durationSec }
                                PickBar(selected.size, all.size, totalSec.takeIf { it > 0 }?.let(::formatDuration).orEmpty(), allOn) {
                                    selected = if (allOn) emptySet() else all
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            },
        ) { _, episode ->
            PickRow(
                checked = episode.number in selected, thumbnail = episode.thumbnail,
                title = episode.title.ifBlank { resources.getString(R.string.cinesrc_episode, episode.number, "") },
                meta = listOfNotNull("E" + episode.number.toString().padStart(2, '0'), episode.durationSec.takeIf { it > 0 }?.let(::formatDuration)),
                onToggle = { selected = if (it) selected + episode.number else selected - episode.number },
            )
        }
        // One episode goes on to its own quality sheet; several share one quality here.
        if (selected.size > 1 && episodes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Txt(stringResource(R.string.cinesrc_max_quality), style = Hamstr.type.small, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
            Chips(listOf(null, 1080, 720, 480), height, { it?.let { h -> "${h}p" } ?: resources.getString(R.string.cinesrc_best) }) { height = it }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HButton(stringResource(R.string.share_cancel), onDismiss, Modifier.weight(1f))
            HButton(
                when (selected.size) {
                    0 -> stringResource(R.string.cinesrc_pick)
                    1 -> stringResource(R.string.cinesrc_continue)
                    else -> stringResource(R.string.cinesrc_download_selected, selected.size)
                },
                {
                    val show = series ?: return@HButton
                    val data = current ?: return@HButton
                    if (selected.size == 1) data.episodes.firstOrNull { it.number in selected }?.let { onEpisode(data.target(it).embed) }
                    else if (!queued) {
                        queued = true
                        CineSrcCatalog.enqueue(context, show, data, selected, Prefs.settings.value.defaultChoice().copy(height = height))
                        onQueued()
                    }
                },
                Modifier.weight(2f), kind = ButtonKind.Primary,
                icon = if (selected.size == 1) R.drawable.ic_chevron_right else R.drawable.ic_download,
                enabled = current != null && selected.isNotEmpty() && error == null && !queued,
            )
        }
    }
}

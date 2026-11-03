package app.hamstr.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.hamstr.res.*
import app.hamstr.ui.components.ButtonKind
import app.hamstr.ui.components.Divider
import app.hamstr.ui.components.Group
import app.hamstr.ui.components.HButton
import app.hamstr.ui.components.Icon
import app.hamstr.ui.components.LocalReduceMotion
import app.hamstr.ui.components.SettingRow
import app.hamstr.ui.theme.Hamstr
import app.hamstr.ui.theme.TravelEasing
import app.hamstr.ui.components.Txt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** What Hamstr takes up on this device, part by part, and what is left on its storage. */
class StorageUse(val parts: List<Part>, val free: Long, val total: Long) {
    class Part(val kind: Kind, val bytes: Long)

    /** The parts in the order (and colour) the chart always draws them. */
    enum class Kind(val title: StringResource) {
        Downloads(Res.string.storage_downloads), App(Res.string.storage_app), Engine(Res.string.storage_engine),
        Data(Res.string.storage_data), Cache(Res.string.storage_cache),
    }

    val used get() = parts.sumOf { it.bytes }
    fun of(kind: Kind) = parts.firstOrNull { it.kind == kind }?.bytes ?: 0
}

/** One file of a backup on its way: what came of it, for the line under the buttons; null when cancelled. */
sealed interface BackupResult {
    data object Saved : BackupResult
    class Restored(val downloads: Int) : BackupResult
    class Failed(val message: String, val reading: Boolean) : BackupResult
}

/**
 * Settings › Data & backup on both apps: the storage chart with its parts, clearing the cache,
 * and the backup as a file out and in. How each is done is the platform's: [measure] walks its
 * folders, [clearCache] empties them and says how much went, [export] and [import] ask for a file.
 */
@Composable
fun DataSettings(
    measure: suspend () -> StorageUse,
    clearCache: suspend () -> Long,
    export: suspend () -> BackupResult?,
    import: suspend () -> BackupResult?,
) {
    val scope = rememberCoroutineScope()
    var use by remember { mutableStateOf<StorageUse?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var cleared by remember { mutableStateOf<Long?>(null) }
    var result by remember { mutableStateOf<BackupResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(round) { use = withContext(Dispatchers.IO) { runCatching { measure() }.getOrNull() } }

    SettingLabel(stringResource(Res.string.set_storage), Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        StorageChart(use)
        val cache = use?.of(StorageUse.Kind.Cache) ?: 0
        Column(Modifier.padding(8.dp)) {
            HButton(
                stringResource(Res.string.storage_clear_cache) + if (cache > 0) " · " + formatBytes(cache) else "",
                {
                    scope.launch {
                        cleared = withContext(Dispatchers.IO) { runCatching { clearCache() }.getOrDefault(0) }
                        round++
                    }
                },
                Modifier.fillMaxWidth(), kind = ButtonKind.Neutral, icon = Res.drawable.ic_delete, enabled = use != null && cache > 0,
            )
            cleared?.let {
                Spacer(Modifier.height(8.dp))
                Txt(stringResource(Res.string.storage_cleared, formatBytes(it)), style = Hamstr.type.small, color = Hamstr.colors.muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Txt(stringResource(Res.string.storage_hint), style = Hamstr.type.small, color = Hamstr.colors.ghost, modifier = Modifier.padding(horizontal = 6.dp))

    Spacer(Modifier.height(18.dp))
    SettingLabel(stringResource(Res.string.set_backup), Modifier.padding(horizontal = 6.dp))
    Spacer(Modifier.height(8.dp))
    Group {
        fun run(block: suspend () -> BackupResult?) {
            if (busy) return
            busy = true
            scope.launch {
                block()?.let { result = it }
                busy = false
                if (result is BackupResult.Restored) round++
            }
        }
        SettingRow(stringResource(Res.string.backup_export), subtitle = stringResource(Res.string.backup_export_hint), onClick = { run(export) }) {
            Icon(Res.drawable.ic_upload, tint = Hamstr.colors.muted, size = 20.dp)
        }
        Divider()
        SettingRow(stringResource(Res.string.backup_import), subtitle = stringResource(Res.string.backup_import_hint), onClick = { run(import) }) {
            Icon(Res.drawable.ic_restore, tint = Hamstr.colors.muted, size = 20.dp)
        }
    }
    result?.let { r ->
        Spacer(Modifier.height(8.dp))
        Txt(
            when (r) {
                BackupResult.Saved -> stringResource(Res.string.backup_saved)
                is BackupResult.Restored -> pluralStringResource(Res.plurals.backup_restored, r.downloads, r.downloads)
                is BackupResult.Failed -> stringResource(if (r.reading) Res.string.backup_failed else Res.string.backup_save_failed, r.message)
            },
            style = Hamstr.type.small, color = if (r is BackupResult.Failed) Hamstr.colors.red else Hamstr.colors.muted,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

/**
 * A ring of what takes how much, with the total in its middle and the parts beside it (under
 * it where there is no room beside). The parts are shades of the accent, from full for the
 * downloads to faint for the cache; the ring's track sinks into the panel. It draws itself
 * round once when the numbers arrive.
 */
@Composable
fun StorageChart(use: StorageUse?) {
    val reduce = LocalReduceMotion.current
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(use != null) {
        if (use == null) return@LaunchedEffect
        if (reduce) sweep.snapTo(1f) else sweep.animateTo(1f, tween(700, easing = TravelEasing))
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
        if (maxWidth >= 420.dp) Row(verticalAlignment = Alignment.CenterVertically) {
            StorageRing(use, sweep.value)
            Spacer(Modifier.width(20.dp))
            StorageLegend(use, Modifier.weight(1f))
        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            StorageRing(use, sweep.value)
            Spacer(Modifier.height(16.dp))
            StorageLegend(use, Modifier.fillMaxWidth().padding(horizontal = 4.dp))
        }
    }
}

private val SHADES = listOf(1f, 0.62f, 0.4f, 0.24f)

/** Shades of the accent for what Hamstr keeps; grey for the cache, which can go. */
@Composable
private fun shade(kind: StorageUse.Kind) =
    if (kind == StorageUse.Kind.Cache) Hamstr.colors.ghost else Hamstr.colors.accent.copy(alpha = SHADES[kind.ordinal])

@Composable
private fun StorageRing(use: StorageUse?, sweep: Float) {
    val c = Hamstr.colors
    val parts = use?.parts.orEmpty().filter { it.bytes > 0 }
    val colors = parts.map { shade(it.kind) }
    val used = use?.used ?: 0
    // Read out as one line; the legend beside it has the parts.
    val summary = use?.let { stringResource(Res.string.storage_used, formatBytes(it.used)) } ?: stringResource(Res.string.storage_measuring)
    Box(Modifier.size(128.dp).clearAndSetSemantics { contentDescription = summary }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(128.dp)) {
            val stroke = 16.dp.toPx()
            val inset = Offset(stroke / 2, stroke / 2)
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(c.canvas, 0f, 360f, false, inset, arc, style = Stroke(stroke))
            if (used <= 0) return@Canvas
            // A small gap between neighbours, none for a ring of one; the smallest part still shows.
            val gap = if (parts.size > 1) 2f else 0f
            val angles = ringAngles(parts.map { it.bytes }, min = 5f)
            var start = -90f
            parts.forEachIndexed { i, _ ->
                val full = angles[i] * sweep
                if (full - gap > 0f) drawArc(colors[i], start + gap / 2, full - gap, false, inset, arc, style = Stroke(stroke, cap = StrokeCap.Butt))
                start += full
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(if (use == null) "…" else formatBytes(used), style = Hamstr.type.section, maxLines = 1)
            if (use != null) Txt(stringResource(Res.string.storage_total), style = Hamstr.type.small, color = c.ghost, maxLines = 1)
        }
    }
}

/** Each part's share of the ring in degrees, none below [min]: the biggest gives up what that takes. */
internal fun ringAngles(bytes: List<Long>, min: Float): List<Float> {
    val total = bytes.sum().toFloat()
    if (total <= 0f) return bytes.map { 0f }
    val raw = bytes.map { 360f * it / total }
    val lifted = raw.map { if (it > 0f) maxOf(it, min) else 0f }
    val extra = lifted.sum() - 360f
    val biggest = raw.indices.maxByOrNull { raw[it] } ?: return lifted
    return lifted.mapIndexed { i, a -> if (i == biggest) a - extra else a }
}

@Composable
private fun StorageLegend(use: StorageUse?, modifier: Modifier) {
    val c = Hamstr.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (use == null) {
            Txt(stringResource(Res.string.storage_measuring), style = Hamstr.type.small, color = c.ghost)
            return@Column
        }
        StorageUse.Kind.entries.forEach { kind ->
            val bytes = use.of(kind)
            if (bytes <= 0) return@forEach
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(shade(kind)))
                Spacer(Modifier.width(8.dp))
                Txt(stringResource(kind.title), style = Hamstr.type.small, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Txt(formatBytes(bytes), style = Hamstr.type.mono, color = c.muted, maxLines = 1)
                Txt("%d %%".format((bytes * 100 / use.used.coerceAtLeast(1)).toInt()), style = Hamstr.type.mono.copy(textAlign = TextAlign.End), color = c.ghost, maxLines = 1,
                    modifier = Modifier.width(48.dp))
            }
        }
        if (use.total > 0) Txt(stringResource(Res.string.storage_free, formatBytes(use.free), formatBytes(use.total)), style = Hamstr.type.small, color = c.ghost, maxLines = 2)
    }
}

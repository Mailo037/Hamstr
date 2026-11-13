package app.hamstr.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.hamstr.engine.MediaLinks
import app.hamstr.res.*
import app.hamstr.ui.components.HamstrMark
import app.hamstr.ui.components.Specs
import app.hamstr.ui.components.Txt
import app.hamstr.ui.components.rememberMarkState
import app.hamstr.ui.theme.Hamstr
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.dnd.DropTargetEvent
import java.io.File

/** What is being dragged over the window, while it is. */
internal sealed interface Drag {
    data object Link : Drag
    /** [count] 0: the system doesn't say how many before the drop. */
    data class Files(val count: Int) : Drag
    data object NoLink : Drag
}

/**
 * Links and files dragged onto the window: while they hover, the window says what a drop does
 * (the hamster opens its cheeks); a drop stashes the link or adds the files, and the snack says so.
 */
internal object Drop {
    var drag by mutableStateOf<Drag?>(null)
        private set

    /** Something landed: the stash shows it. */
    var onLanded: () -> Unit = {}

    fun target(): DropTarget = object : DropTarget() {
        override fun dragEnter(e: DropTargetDragEvent) {
            drag = look(e.currentDataFlavorsAsList, runCatching { e.transferable }.getOrNull())
            answer(e)
        }

        override fun dragOver(e: DropTargetDragEvent) = answer(e)

        override fun dropActionChanged(e: DropTargetDragEvent) = answer(e)

        override fun dragExit(e: DropTargetEvent) { drag = null }

        override fun drop(e: DropTargetDropEvent) {
            drag = null
            e.acceptDrop(DnDConstants.ACTION_COPY)
            val t = e.transferable
            runCatching {
                when {
                    t.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                        val files = (t.getTransferData(DataFlavor.javaFileListFlavor) as List<*>).filterIsInstance<File>().filter { it.isFile }
                        if (files.isNotEmpty()) {
                            Files.import(files)
                            Snacks.show(Res.plurals.drop_added, files.size, files.size)
                            onLanded()
                        }
                    }
                    t.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                        val link = (t.getTransferData(DataFlavor.stringFlavor) as? String)?.let(MediaLinks::find)
                        if (link != null) {
                            Queue.stash(link)
                            Snacks.show(Res.string.share_stashing)
                            onLanded()
                        } else {
                            Snacks.show(Res.string.drop_no_link)
                        }
                    }
                }
            }
            e.dropComplete(true)
        }

        private fun answer(e: DropTargetDragEvent) {
            if (drag == Drag.NoLink) e.rejectDrag() else e.acceptDrag(DnDConstants.ACTION_COPY)
        }
    }

    // Windows lets a drag show its data before the drop; elsewhere it may not, and then a text is taken on trust.
    private fun look(flavors: List<DataFlavor>, t: Transferable?): Drag = when {
        DataFlavor.javaFileListFlavor in flavors ->
            Drag.Files(runCatching { (t?.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.size }.getOrNull() ?: 0)
        DataFlavor.stringFlavor in flavors -> {
            val text = runCatching { t?.getTransferData(DataFlavor.stringFlavor) as? String }.getOrNull()
            if (text == null || MediaLinks.find(text) != null) Drag.Link else Drag.NoLink
        }
        else -> Drag.NoLink
    }
}

/** Over everything while something is dragged over the window: what letting go would do. */
@Composable
internal fun DropOverlay() {
    val c = Hamstr.colors
    val now = Drop.drag
    // The last one stays while the overlay fades out.
    var shown by remember { mutableStateOf<Drag>(Drag.Link) }
    if (now != null) shown = now
    AnimatedVisibility(now != null, enter = fadeIn(Specs.appear()), exit = fadeOut(Specs.disappear())) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val mark = rememberMarkState()
                // Cheeks open for something it can take.
                LaunchedEffect(shown) { mark.pull = if (shown == Drag.NoLink) 0f else 1f }
                HamstrMark(96.dp, state = mark)
                val s = shown
                val text = when (s) {
                    Drag.Link -> stringResource(Res.string.drop_link)
                    Drag.NoLink -> stringResource(Res.string.drop_no_link)
                    is Drag.Files -> if (s.count > 0) pluralStringResource(Res.plurals.drop_files, s.count, s.count) else stringResource(Res.string.drop_files_some)
                }
                Txt(text, style = Hamstr.type.section, color = if (s == Drag.NoLink) c.ghost else c.accent)
            }
        }
    }
}

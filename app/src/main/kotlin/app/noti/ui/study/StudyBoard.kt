package app.noti.ui.study

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.noti.R
import app.noti.data.LibraryStore
import app.noti.data.Notebook
import app.noti.data.SettingsStore
import app.noti.ink.InkLayer
import app.noti.pdf.PagePlacement
import app.noti.study.BoardLayer
import app.noti.study.CardFace
import app.noti.study.StudyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A Study board's parts for the editor: its study state, board layer and the sheet's geometry. */
@Stable
internal class StudyBoard(val controller: StudyController, val layer: BoardLayer, val fonts: CardFace.Fonts) {
    val geometry = SheetGeometry()

    /** A finger tap on the canvas at ([x], [y]) px: a menu for whatever is printed there. */
    fun tap(x: Float, y: Float, placement: PagePlacement?) {
        val sheet = placement?.visiblePages?.firstOrNull() ?: return
        if (controller.loop != null) return
        val hit = layer.hit(x, y, sheet) ?: return
        controller.menu = hit to IntOffset(x.toInt(), y.toInt())
    }
}

/** Loads a board's study material (null while it loads, and for other notebooks). */
@Composable
internal fun rememberStudyBoard(notebook: Notebook, library: LibraryStore, settings: SettingsStore): StudyBoard? {
    if (!notebook.board) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store by produceState<StudyStore?>(null, notebook.id) {
        value = withContext(Dispatchers.IO) { StudyStore.load(library.studyFile(notebook)) }
    }
    val loaded = store ?: return null
    val board = remember(loaded) {
        val res = context.resources
        val fonts = CardFace.Fonts(res.getFont(R.font.nunito_regular), res.getFont(R.font.nunito_semibold), res.getFont(R.font.nunito_bold))
        val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        val controller = StudyController(notebook, library, settings, loaded, scope, context.cacheDir, toast)
        val layer = BoardLayer(loaded, library, fonts)
        controller.board = layer
        StudyBoard(controller, layer, fonts)
    }
    DisposableEffect(board) {
        loaded.onSaved = { ok -> if (!ok) Toast.makeText(context, "Couldn't save the study board. Your ink is safe.", Toast.LENGTH_LONG).show() }
        if (loaded.loadError) Toast.makeText(context, "The study material couldn't be read; a copy was kept aside.", Toast.LENGTH_LONG).show()
        onDispose {
            loaded.onSaved = null
            board.controller.cancelLoop()
            board.controller.stopGenerating()
            board.layer.close()
        }
    }
    return board
}

/**
 * Wires a board into the editor: the floating sheet, its dialogs, and loops on the board
 * that the sheet asked for. [serial] is the engine's loop count (document state [9]).
 */
@Composable
internal fun StudyOverlay(board: StudyBoard, layer: InkLayer?, placement: () -> PagePlacement?, serial: Long,
    maxWidth: androidx.compose.ui.unit.Dp, maxHeight: androidx.compose.ui.unit.Dp, hidden: Boolean, onSettings: () -> Unit) {
    val study = board.controller
    LaunchedEffect(layer, serial, study.loop) {
        val current = layer ?: return@LaunchedEffect
        if (study.loop != null && serial > study.loopFrom) {
            current.selectionInk { data -> study.onLoop(data) { current.documentAction(CLEAR_SELECTION) } }
        }
    }
    if (!hidden) StudySheet(study, board.geometry, board.fonts, placement, serial, maxWidth, maxHeight, onSettings)
    StudyDialogs(study, board.geometry, placement, serial)
}

/** InkLayer.documentAction: drop the selection (jni_bridge.cpp nativeDocumentAction). */
private const val CLEAR_SELECTION = 6

/** The top bar's Study button: brings the sheet back (or hides it), with what's due. */
@Composable
internal fun StudyButton(study: StudyController) {
    val colors = MaterialTheme.colorScheme
    val due = study.dueCount
    val active = study.open
    Row(Modifier.heightIn(min = 40.dp).clip(CircleShape)
        .background(if (active) colors.primaryContainer else colors.primary)
        .clickable { if (study.open) study.hide() else study.show() }
        .semantics { contentDescription = "Study" + if (due > 0) ", $due due" else ""; selected = active }
        .padding(start = 12.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        val fg = if (active) colors.onPrimaryContainer else colors.onPrimary
        if (study.generating != null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
        else Icon(Icons.Outlined.School, null, Modifier.size(18.dp), tint = fg)
        Text("Study", style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(start = 6.dp))
        if (due > 0) Text("$due", style = MaterialTheme.typography.labelSmall, color = if (active) colors.onPrimary else colors.primary,
            modifier = Modifier.padding(start = 6.dp).background(if (active) colors.primary else colors.onPrimary, CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

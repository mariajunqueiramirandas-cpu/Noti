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
import app.noti.pdf.PagePlacement
import app.noti.study.BoardLayer
import app.noti.study.CardFace
import app.noti.study.StudyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A notebook's study tools for the editor: floating sheet, optional canvas layer, and fonts. */
@Stable
internal class StudyBoard(val controller: StudyController, val layer: BoardLayer?, val fonts: CardFace.Fonts) {
    val geometry = SheetGeometry()

    /** A finger tap on an Endless canvas at ([x], [y]) px: a menu for whatever is printed there. */
    fun tap(x: Float, y: Float, placement: PagePlacement?) {
        val board = layer ?: return
        val sheet = placement?.visiblePages?.firstOrNull() ?: return
        val hit = board.hit(x, y, sheet) ?: return
        controller.menu = hit to IntOffset(x.toInt(), y.toInt())
    }
}

/** Loads study support for every non-PDF notebook (null while loading, and for PDFs). */
@Composable
internal fun rememberStudyBoard(notebook: Notebook, library: LibraryStore): StudyBoard? {
    if (notebook.isPdf) return null
    val context = LocalContext.current
    val store by produceState<StudyStore?>(null, notebook.id) {
        value = withContext(Dispatchers.IO) { StudyStore.load(library.studyFile(notebook)) }
    }
    val loaded = store ?: return null
    val board = remember(loaded, notebook.isCanvas) {
        val res = context.resources
        val fonts = CardFace.Fonts(res.getFont(R.font.nunito_regular), res.getFont(R.font.nunito_semibold), res.getFont(R.font.nunito_bold))
        val toast: (String) -> Unit = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
        val controller = StudyController(library, loaded, toast)
        val layer = if (notebook.isCanvas) BoardLayer(loaded, library, fonts) else null
        controller.board = layer
        StudyBoard(controller, layer, fonts)
    }
    DisposableEffect(board) {
        loaded.onSaved = { ok -> if (!ok) Toast.makeText(context, "Couldn't save study progress. Your ink is safe.", Toast.LENGTH_LONG).show() }
        if (loaded.loadError) Toast.makeText(context, "The study material couldn't be read; a copy was kept aside.", Toast.LENGTH_LONG).show()
        onDispose {
            loaded.onSaved = null
            board.layer?.close()
        }
    }
    return board
}

/** Shows the study sheet and its dialogs over the ink window. */
@Composable
internal fun BoxScope.StudyOverlay(board: StudyBoard, placement: () -> PagePlacement?,
    maxWidth: androidx.compose.ui.unit.Dp, maxHeight: androidx.compose.ui.unit.Dp, hidden: Boolean) {
    if (hidden) return
    StudySheet(board.controller, board.geometry, board.fonts, placement, maxWidth, maxHeight)
    StudyDialogs(board.controller, board.geometry, placement)
}

/** The top bar's Study button: brings the sheet back (or hides it), with what's due. */
@Composable
internal fun StudyButton(study: StudyController, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val due = study.dueCount
    val active = study.open
    Row(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(CircleShape)
        .background(if (active) colors.primaryContainer else colors.surfaceContainerHigh)
        .clickable { if (study.open) study.hide() else study.show() }
        .semantics { contentDescription = "Study" + if (due > 0) ", $due due" else ""; selected = active }
        .padding(horizontal = if (compact) 6.dp else 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        val fg = if (active) colors.onPrimaryContainer else colors.onSurface
        Icon(Icons.Outlined.School, null, Modifier.size(18.dp), tint = fg)
        if (!compact) Text("Study", style = MaterialTheme.typography.labelLarge, color = fg, modifier = Modifier.padding(start = 6.dp))
        if (due > 0 && !compact) Text("$due", style = MaterialTheme.typography.labelSmall, color = colors.onPrimary,
            modifier = Modifier.padding(start = 6.dp).background(colors.primary, CircleShape)
                .padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

package app.noti.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.noti.ui.common.NotiIconButton
import app.noti.ui.common.NotiTextButton

@Composable
internal fun PdfPagesDialog(count: Int, currentPage: Int, onPage: (Int) -> Unit, onClose: () -> Unit,
    thumbnail: (Int) -> Bitmap?) {
    val colors = MaterialTheme.colorScheme
    val current = currentPage.coerceIn(0, count - 1)
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = current)
    var enteredPage by remember { mutableStateOf("") }
    val target = enteredPage.toIntOrNull()?.takeIf { it in 1..count }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(0.86f),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surface,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pages", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    NotiIconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close pages") }
                }
                Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = enteredPage, onValueChange = { if (it.all(Char::isDigit)) enteredPage = it },
                        modifier = Modifier.weight(1f), label = { Text("Page number") },
                        singleLine = true, isError = enteredPage.isNotEmpty() && target == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { target?.let { onPage(it - 1) } }),
                    )
                    NotiTextButton(onClick = { target?.let { onPage(it - 1) } }, enabled = target != null) { Text("Go") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp),
                    state = grid,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(count) { index ->
                        val selected = index == current
                        val shape = MaterialTheme.shapes.medium
                        Column(
                            modifier = Modifier.clip(shape)
                                .background(if (selected) colors.primaryContainer else colors.surfaceContainerLow)
                                .selectable(selected = selected, role = Role.Button, onClick = { onPage(index) })
                                .semantics { contentDescription = "Page ${index + 1}" }
                                .padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(Modifier.fillMaxWidth().height(104.dp).clip(MaterialTheme.shapes.small)
                                .background(Color.White).border(1.dp, colors.outlineVariant, MaterialTheme.shapes.small)) {
                                thumbnail(index)?.let { bitmap ->
                                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                                    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                                }
                            }
                            Text("${index + 1}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

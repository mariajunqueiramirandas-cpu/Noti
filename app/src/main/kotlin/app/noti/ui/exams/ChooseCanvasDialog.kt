package app.noti.ui.exams

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.noti.data.LibraryStore
import app.noti.data.Paper
import app.noti.data.PaperTemplate
import app.noti.data.SettingsStore
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiDialogFrame
import app.noti.ui.common.NotiGroup
import app.noti.ui.common.NotiGroupDivider
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton

/**
 * Asks which notebook to open study material in (existing notebooks sorted by recent, or
 * create a brand new canvas on the spot).
 */
@Composable
internal fun ChooseCanvasDialog(
    library: LibraryStore,
    settings: SettingsStore,
    suggestedName: String = "",
    onDismiss: () -> Unit,
    onChosen: (notebookId: String) -> Unit,
) {
    val canvases = remember(library.notebooks) {
        library.notebooks.filter { !it.isPdf && it.trashedAt == null }.sortedByDescending { it.openedAt }
    }
    var creating by rememberSaveable { mutableStateOf(canvases.isEmpty()) }
    var newName by rememberSaveable(suggestedName) { mutableStateOf(suggestedName) }
    val folderNames = remember(library.folders) { library.folders.associate { it.id to it.name } }

    NotiDialogFrame(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Novo caderno" else "Escolher caderno") },
        maxWidth = 520.dp,
        actions = {
            NotiTextButton(onClick = onDismiss) { Text("Cancelar") }
            if (creating) {
                if (canvases.isNotEmpty()) {
                    NotiOutlinedButton(onClick = { creating = false }) { Text("Usar existente") }
                }
                NotiButton(onClick = {
                    val created = library.create(
                        name = newName.trim(),
                        paper = Paper(settings.defaultTemplate.takeIf { it != PaperTemplate.Blank } ?: PaperTemplate.Grid, settings.defaultColor),
                        folderId = null,
                    )
                    onChosen(created.id)
                }) { Text("Criar e abrir") }
            } else {
                NotiOutlinedButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Novo caderno")
                }
            }
        },
    ) {
        if (creating) {
            Text(
                "Crie um caderno novo para resolver e fixar as questões.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("Nome do caderno") },
                placeholder = { Text("Ex.: Física, Simulado, Rascunhos…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                "Escolha em qual caderno abrir esta questão.",
                style = MaterialTheme.typography.bodyMedium,
            )
            NotiGroup {
                canvases.forEachIndexed { index, nb ->
                    if (index > 0) NotiGroupDivider()
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onChosen(nb.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Description, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                nb.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val detail = listOfNotNull(
                                folderNames[nb.folderId],
                                if (nb.isCanvas) "Canvas" else "${nb.pageCount} ${if (nb.pageCount == 1) "página" else "páginas"}",
                            ).joinToString(" · ")
                            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

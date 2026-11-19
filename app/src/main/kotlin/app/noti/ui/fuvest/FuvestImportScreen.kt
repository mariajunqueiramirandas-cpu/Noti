package app.noti.ui.fuvest

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.noti.data.LibraryStore
import app.noti.fuvest.*
import app.noti.openrouter.openRouterKeys
import app.noti.pdf.PdfImport
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiDialogFrame
import app.noti.ui.common.NotiFilterChip
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import app.noti.ui.common.StudyText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun FuvestImportScreen(library: LibraryStore, store: FuvestStore, onBack: () -> Unit,
    onSettings: () -> Unit, onSource: (String, Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val keys = remember(library) { openRouterKeys(library.appContext) }
    val importer = remember { FuvestImport() }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var year by rememberSaveable { mutableStateOf("2023") }
    var range by rememberSaveable { mutableStateOf("") }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var analyzedRange by rememberSaveable { mutableStateOf("") }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val candidate = remember(draftJson) { draftJson?.let { FuvestExam.from(JSONObject(it)) } }
    val draft = candidate.takeIf { reviewing }
    var edit by remember { mutableStateOf<FuvestQuestion?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runTask(label: String, action: suspend () -> Unit) {
        if (task?.isActive == true) return
        progress = label
        error = null
        task = scope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = fuvestError(failure)
            } finally {
                progress = null
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runTask("Copiando PDF para a biblioteca…") {
            withContext(NonCancellable) {
                val result = withContext(Dispatchers.IO) { PdfImport.import(library.appContext, uri, library.pdfDir) }
                library.addPdf(result.id, result.name, result.pageCount, folderId = null)
                sourceId = result.id
            }
        }
    }

    fun back() {
        when {
            task?.isActive == true -> task?.cancel()
            reviewing -> { reviewing = false; error = null }
            else -> onBack()
        }
    }

    BackHandler { back() }
    val files = library.notebooks.filter { it.isPdf && it.trashedAt == null }.sortedByDescending { it.openedAt }
    val source = files.firstOrNull { it.id == sourceId }
    val yearValue = year.toIntOrNull()?.takeIf { year.length == 4 && it in 1977..2099 }
    val enabled = task?.isActive != true

    FuvestPage(if (draft == null) "Importar prova" else "Revisar questões",
        "FUVEST · Segunda fase · 2º dia · Engenharia", ::back, progress, error, onCancel = { task?.cancel() }) {
        item("import-overview") {
            val colors = MaterialTheme.colorScheme
            Surface(shape = RoundedCornerShape(24.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val icon = if (draft == null) Icons.Outlined.PictureAsPdf else Icons.AutoMirrored.Outlined.FactCheck
                    FuvestEmblem(icon, colors.surface, colors.primary)
                    Column(Modifier.weight(1f)) {
                        FuvestHeading(draft?.title ?: "Caderno de questões")
                        Text(if (draft == null) "Adicione um PDF para organizar as questões em tópicos de estudo."
                        else "Confira o enunciado e os tópicos das ${draft.questions.size} questões antes de salvar.",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (draft == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (source != null) Text("${source.name} · ${source.pageCount} páginas", style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        NotiOutlinedButton(enabled = enabled, onClick = { pick.launch("application/pdf") }) { Text("Selecionar PDF") }
                        if (files.isNotEmpty()) Box {
                            NotiTextButton(enabled = enabled, onClick = { choosing = true }) { Text("Usar PDF da biblioteca") }
                            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false },
                                modifier = Modifier.heightIn(max = 320.dp).widthIn(max = 340.dp)) {
                                files.forEach { notebook ->
                                    DropdownMenuItem(text = { Text(notebook.name) }, onClick = {
                                        sourceId = notebook.id
                                        choosing = false
                                        error = null
                                    })
                                }
                            }
                        }
                    }
                    OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Ano") },
                        singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.widthIn(max = 180.dp))
                    OutlinedTextField(range, { range = it }, label = { Text("Páginas (opcional)") },
                        placeholder = { Text("Ex.: 2–5, 8–11") },
                        supportingText = { Text("Deixe vazio para que o Noti escolha as páginas do acervo oficial de 2023 ou leia o PDF completo.") },
                        singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
                }
            }
            item {
                Text("A leitura envia imagens das páginas para o OpenRouter usando a sua chave. Você pode conferir os enunciados antes de salvar.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            item {
                if (candidate != null && candidate.sourceId == source?.id && candidate.year == yearValue && analyzedRange == range) {
                    NotiButton(enabled = enabled, onClick = { reviewing = true }) { Text("Continuar revisão") }
                } else if (!keys.hasKey) NotiOutlinedButton(onClick = onSettings) { Text("Configurar OpenRouter") }
                else NotiButton(enabled = enabled && source != null && yearValue != null, onClick = {
                    val selected = source ?: return@NotiButton
                    val examYear = yearValue ?: return@NotiButton
                    runTask("Preparando o PDF…") {
                        val file = library.pdfFile(selected)?.takeIf { it.isFile }
                            ?: error("O PDF não está disponível na biblioteca.")
                        val catalog = checkNotNull(store.catalog)
                        require(catalog.exams.none { it.sourceId == selected.id }) { "Este PDF já está nas provas salvas." }
                        val official = Fuvest2023.matches(file)
                        require(!official || examYear == 2023) { "O caderno oficial selecionado é da FUVEST 2023." }
                        require(!official || catalog.exams.none { it.official2023 }) { "O caderno oficial de 2023 já foi importado." }
                        val groups = if (official && range.isBlank()) Fuvest2023.pageGroups else {
                            val pages = parseFuvestPages(range, selected.pageCount)
                            // One overlap page gives multi-page questions context at batch boundaries.
                            pages.windowed(size = 4, step = 3, partialWindows = true)
                                .filterIndexed { index, batch -> index == 0 || batch.size > 1 }
                        }
                        val key = withContext(Dispatchers.IO) { keys.load() }
                            ?: error("Salve uma chave em Configurações → OpenRouter.")
                        val analyzed = importer.analyze(key, selected.id, examYear, groups, official,
                            { page -> renderFuvestPage(file, page) }) { batch, total ->
                            progress = "Analisando páginas · lote $batch de $total"
                        }
                        draftJson = analyzed.json().toString()
                        analyzedRange = range
                        reviewing = true
                    }
                }) { Text("Analisar prova") }
            }
        } else {
            draft.questions.forEach { question ->
                item(key = question.code) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${question.code} · ${question.subject.label}", style = MaterialTheme.typography.titleMedium)
                            Text(question.topics.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                            if (question.answerSheet) Text("A questão faz referência à folha de respostas.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                NotiOutlinedButton(enabled = enabled, onClick = { edit = question }) { Text("Revisar leitura") }
                                NotiTextButton(enabled = enabled && source != null, onClick = {
                                    onSource(draft.sourceId, question.pages.first())
                                }) { Text("Abrir PDF · p. ${question.pages.joinToString(", ")}") }
                            }
                        }
                    }
                }
            }
            item {
                NotiButton(enabled = enabled && source != null, onClick = {
                    runTask("Salvando questões e tópicos…") {
                        store.update { catalog ->
                            require(catalog.exams.none { it.sourceId == draft.sourceId || it.official2023 && draft.official2023 }) {
                                "Este caderno já está nas provas salvas."
                            }
                            catalog.copy(exams = catalog.exams + draft)
                        }
                        draftJson = null
                        reviewing = false
                        onBack()
                    }
                }) { Text("Salvar 12 questões") }
            }
        }
    }
    edit?.let { question ->
        EditReadQuestion(question, onClose = { edit = null }, onSave = { corrected ->
            if (draft != null) draftJson = draft.copy(
                questions = draft.questions.map { if (it.code == corrected.code) corrected else it },
            ).json().toString()
            edit = null
        })
    }
}

@Composable
internal fun EditReadQuestion(question: FuvestQuestion, onClose: () -> Unit, onSave: (FuvestQuestion) -> Unit) {
    var stem by rememberSaveable(question.code) { mutableStateOf(question.stem) }
    var parts by rememberSaveable(question.code) { mutableStateOf(ArrayList(question.parts.map { it.text })) }
    var tags by rememberSaveable(question.code) { mutableStateOf(question.topics.joinToString(", ")) }
    var editingText by rememberSaveable(question.code) { mutableStateOf(false) }
    val topics = tags.split(',').map(String::trim).filter { normalizeTopic(it).isNotBlank() }.distinctBy(::normalizeTopic)
    NotiDialogFrame(onClose, title = { Text("Revisar ${question.code}") }, actions = {
        NotiTextButton(onClick = onClose) { Text("Cancelar") }
        NotiButton(enabled = topics.isNotEmpty() && parts.all(String::isNotBlank), onClick = {
            onSave(question.copy(stem = stem.trim(),
                parts = question.parts.mapIndexed { index, part -> part.copy(text = parts[index].trim()) },
                topics = topics,
            ))
        }) { Text("Salvar revisão") }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotiFilterChip(selected = !editingText, onClick = { editingText = false }, label = { Text("Prévia") })
            NotiFilterChip(selected = editingText, onClick = { editingText = true }, label = { Text("Editar texto") })
        }
        if (editingText) {
            OutlinedTextField(tags, { tags = it }, label = { Text("Tópicos separados por vírgulas") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(stem, { stem = it }, label = { Text("Enunciado") }, modifier = Modifier.fillMaxWidth())
            question.parts.forEachIndexed { index, part ->
                OutlinedTextField(parts[index], { value -> parts = ArrayList(parts).apply { set(index, value) } },
                    label = { Text("Item ${part.label}") }, modifier = Modifier.fillMaxWidth())
            }
        } else {
            val preview = remember(stem, parts) {
                (listOf(stem) + question.parts.mapIndexed { index, part -> "${part.label}) ${parts[index]}" })
                    .filter(String::isNotBlank).joinToString("\n\n")
            }
            StudyText(preview, style = MaterialTheme.typography.bodyLarge)
            Text(topics.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
    }
}

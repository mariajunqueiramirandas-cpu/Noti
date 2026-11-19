package app.noti.ui.exams

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.noti.data.LibraryStore
import app.noti.exams.*
import app.noti.openrouter.openRouterKeys
import app.noti.pdf.PdfImport
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiDialogFrame
import app.noti.ui.common.NotiFilterChip
import app.noti.ui.common.NotiIconButton
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
internal fun ExamImportScreen(
    library: LibraryStore,
    store: ExamStore,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSource: (String, Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keys = remember(library) { openRouterKeys(library.appContext) }
    val importer = remember { ExamImport() }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var range by rememberSaveable { mutableStateOf("") }
    var draftJson by rememberSaveable { mutableStateOf<String?>(null) }
    var analyzedRange by rememberSaveable { mutableStateOf("") }
    var reviewing by rememberSaveable { mutableStateOf(false) }
    val candidate = remember(draftJson) { draftJson?.let { PastExam.from(JSONObject(it)) } }
    val draft = candidate.takeIf { reviewing }
    var edit by remember { mutableStateOf<ExamQuestion?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var task by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun updateDraft(change: (PastExam) -> PastExam) {
        val current = draft ?: return
        draftJson = change(current).json().toString()
    }

    fun runTask(label: String, action: suspend () -> Unit) {
        if (task?.isActive == true) return
        progress = label
        error = null
        task = scope.launch {
            try { action() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = examError(failure)
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
    val enabled = task?.isActive != true

    ExamPage(
        title = if (draft == null) "Importar prova" else "Revisar prova",
        subtitle = if (draft == null) "Qualquer vestibular, simulado ou prova em PDF" else draft.subtitle,
        back = ::back,
        progress = progress,
        error = error,
        onCancel = { task?.cancel() },
    ) {
        item("import-overview") {
            val colors = MaterialTheme.colorScheme
            Surface(shape = RoundedCornerShape(24.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val icon = if (draft == null) Icons.Outlined.PictureAsPdf else Icons.AutoMirrored.Outlined.FactCheck
                    ExamEmblem(icon, colors.surface, colors.primary)
                    Column(Modifier.weight(1f)) {
                        ExamHeading(draft?.title ?: "Análise automática por IA")
                        Text(
                            if (draft == null) "A IA identifica a banca, o ano, as disciplinas, as questões (objetivas ou discursivas) e os tópicos automaticamente."
                            else "Confira os dados identificados pela IA e as ${draft.questions.size} questões antes de salvar.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                            DropdownMenu(
                                expanded = choosing,
                                onDismissRequest = { choosing = false },
                                modifier = Modifier.heightIn(max = 320.dp).widthIn(max = 340.dp),
                            ) {
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
                    OutlinedTextField(
                        value = range,
                        onValueChange = { range = it },
                        label = { Text("Páginas (opcional)") },
                        placeholder = { Text("Ex.: 1–12 ou 2–5, 8–11") },
                        supportingText = { Text("Deixe vazio para analisar o PDF completo.") },
                        singleLine = true,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text(
                    "A análise envia imagens das páginas ao OpenRouter usando sua chave. Você pode revisar tudo antes de salvar.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                if (candidate != null && candidate.sourceId == source?.id && analyzedRange == range) {
                    NotiButton(enabled = enabled, onClick = { reviewing = true }) { Text("Continuar revisão") }
                } else if (!keys.hasKey) {
                    NotiOutlinedButton(onClick = onSettings) { Text("Configurar OpenRouter") }
                } else {
                    NotiButton(enabled = enabled && source != null, onClick = {
                        val selected = source ?: return@NotiButton
                        runTask("Preparando o PDF…") {
                            val file = library.pdfFile(selected)?.takeIf { it.isFile }
                                ?: error("O PDF não está disponível na biblioteca.")
                            val catalog = checkNotNull(store.catalog)
                            require(catalog.exams.none { it.sourceId == selected.id }) { "Este PDF já está nas provas salvas." }
                            val isFuvest2023 = LegacyFuvest2023.matches(file)
                            val pages = parseExamPages(range, selected.pageCount)
                            // Overlap one page between consecutive batches so multi-page questions keep context.
                            val groups = pages.windowed(size = 4, step = 3, partialWindows = true)
                                .filterIndexed { index, batch -> index == 0 || batch.size > 1 }
                            val key = withContext(Dispatchers.IO) { keys.load() }
                                ?: error("Salve uma chave em Configurações → OpenRouter.")
                            val analyzed = importer.analyze(
                                key = key,
                                sourceId = selected.id,
                                fileName = selected.name,
                                groups = groups,
                                render = { page -> renderExamPage(file, page) },
                                officialSolutionUrl = { code -> if (isFuvest2023) LegacyFuvest2023.solutionUrl(code) else null },
                            ) { batch, total ->
                                progress = "Analisando prova · lote $batch de $total"
                            }
                            draftJson = analyzed.json().toString()
                            analyzedRange = range
                            reviewing = true
                        }
                    }) { Text("Analisar prova") }
                }
            }
        } else {
            item("exam-metadata") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Dados da prova (detectados pela IA)", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = draft.title,
                            onValueChange = { value -> updateDraft { it.copy(title = value) } },
                            label = { Text("Nome da prova") },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = draft.institution,
                                onValueChange = { value -> updateDraft { it.copy(institution = value) } },
                                label = { Text("Banca / Instituição") },
                                placeholder = { Text("ENEM, FUVEST, UNICAMP…") },
                                singleLine = true,
                                enabled = enabled,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = draft.year?.toString().orEmpty(),
                                onValueChange = { raw ->
                                    val digits = raw.filter(Char::isDigit).take(4)
                                    updateDraft { it.copy(year = digits.toIntOrNull()) }
                                },
                                label = { Text("Ano") },
                                singleLine = true,
                                enabled = enabled,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.width(112.dp),
                            )
                        }
                        OutlinedTextField(
                            value = draft.details,
                            onValueChange = { value -> updateDraft { it.copy(details = value) } },
                            label = { Text("Detalhes (fase, caderno, área)") },
                            singleLine = true,
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            draft.questions.forEach { question ->
                item(key = "${question.code}-${question.pages.firstOrNull()}") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${question.code} · ${question.subject} · ${question.format.label}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(question.topics.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                                }
                                if (draft.questions.size > 1) {
                                    NotiIconButton(enabled = enabled, onClick = {
                                        updateDraft { exam -> exam.copy(questions = exam.questions.filterNot { it === question }) }
                                    }) {
                                        Icon(Icons.Outlined.DeleteOutline, "Remover questão ${question.code}")
                                    }
                                }
                            }
                            if (question.format == QuestionFormat.Choice && question.correctChoice in question.choices.indices) {
                                Text(
                                    "Gabarito detectado: ${'A' + question.correctChoice}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            if (question.answerSheet) Text(
                                "A questão faz referência à folha de respostas.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                NotiOutlinedButton(enabled = enabled, onClick = { edit = question }) { Text("Revisar leitura") }
                                NotiTextButton(enabled = enabled && source != null && question.pages.isNotEmpty(), onClick = {
                                    onSource(draft.sourceId, question.pages.first())
                                }) { Text("Abrir PDF · p. ${question.pages.joinToString(", ")}") }
                            }
                        }
                    }
                }
            }
            item {
                val validTitle = draft.title.trim().isNotEmpty()
                NotiButton(enabled = enabled && source != null && validTitle && draft.questions.isNotEmpty(), onClick = {
                    val cleaned = draft.copy(
                        title = draft.title.trim(),
                        institution = draft.institution.trim(),
                        details = draft.details.trim(),
                    )
                    runTask("Salvando questões e tópicos…") {
                        store.update { catalog ->
                            require(catalog.exams.none { it.sourceId == cleaned.sourceId }) {
                                "Este caderno já está nas provas salvas."
                            }
                            catalog.copy(exams = catalog.exams + cleaned)
                        }
                        draftJson = null
                        reviewing = false
                        onBack()
                    }
                }) { Text("Salvar ${draft.questions.size} ${if (draft.questions.size == 1) "questão" else "questões"}") }
            }
        }
    }
    edit?.let { question ->
        EditReadQuestion(question, onClose = { edit = null }, onSave = { corrected ->
            updateDraft { exam ->
                exam.copy(questions = exam.questions.map { if (it.code == question.code) corrected else it })
            }
            edit = null
        })
    }
}

@Composable
internal fun EditReadQuestion(question: ExamQuestion, onClose: () -> Unit, onSave: (ExamQuestion) -> Unit) {
    var subject by rememberSaveable(question.code) { mutableStateOf(question.subject) }
    var stem by rememberSaveable(question.code) { mutableStateOf(question.stem) }
    var parts by rememberSaveable(question.code) { mutableStateOf(ArrayList(question.parts.map { it.text })) }
    var choices by rememberSaveable(question.code) { mutableStateOf(ArrayList(question.choices)) }
    var correctChoice by rememberSaveable(question.code) { mutableIntStateOf(question.correctChoice) }
    var tags by rememberSaveable(question.code) { mutableStateOf(question.topics.joinToString(", ")) }
    var editingText by rememberSaveable(question.code) { mutableStateOf(false) }
    val topics = tags.split(',').map(String::trim).filter { normalizeTopic(it).isNotBlank() }.distinctBy(::normalizeTopic)
    val isChoice = question.format == QuestionFormat.Choice
    val canSave = topics.isNotEmpty() && subject.trim().isNotBlank() &&
        if (isChoice) stem.isNotBlank() && choices.size >= 2 && choices.all(String::isNotBlank)
        else (stem.isNotBlank() || parts.isNotEmpty()) && parts.all(String::isNotBlank)

    NotiDialogFrame(onClose, title = { Text("Revisar ${question.code}") }, actions = {
        NotiTextButton(onClick = onClose) { Text("Cancelar") }
        NotiButton(enabled = canSave, onClick = {
            onSave(question.copy(
                subject = normalizeSubjectLabel(subject),
                stem = stem.trim(),
                parts = if (isChoice) emptyList() else question.parts.mapIndexed { index, part -> part.copy(text = parts[index].trim()) },
                choices = if (isChoice) choices.map(String::trim) else emptyList(),
                correctChoice = if (isChoice && correctChoice in choices.indices) correctChoice else -1,
                topics = topics,
            ))
        }) { Text("Salvar revisão") }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotiFilterChip(selected = !editingText, onClick = { editingText = false }, label = { Text("Prévia") })
            NotiFilterChip(selected = editingText, onClick = { editingText = true }, label = { Text("Editar texto") })
        }
        if (editingText) {
            OutlinedTextField(subject, { subject = it }, label = { Text("Disciplina") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(tags, { tags = it }, label = { Text("Tópicos separados por vírgulas") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(stem, { stem = it }, label = { Text("Enunciado") }, modifier = Modifier.fillMaxWidth())
            if (isChoice) {
                Text("Alternativas (opcional: marque o gabarito)", style = MaterialTheme.typography.labelMedium)
                choices.forEachIndexed { index, text ->
                    val letter = 'A' + index
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = correctChoice == index,
                            onClick = { correctChoice = if (correctChoice == index) -1 else index },
                            modifier = Modifier.semantics { contentDescription = "Marcar alternativa $letter como correta" },
                        )
                        OutlinedTextField(
                            value = text,
                            onValueChange = { value -> choices = ArrayList(choices).apply { set(index, value) } },
                            label = { Text("Alternativa $letter") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                question.parts.forEachIndexed { index, part ->
                    OutlinedTextField(
                        value = parts[index],
                        onValueChange = { value -> parts = ArrayList(parts).apply { set(index, value) } },
                        label = { Text("Item ${part.label}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            val preview = remember(stem, parts, choices, isChoice) {
                buildString {
                    if (stem.isNotBlank()) append(stem)
                    if (isChoice) {
                        choices.forEachIndexed { i, text ->
                            if (isNotEmpty()) append("\n\n")
                            append("${'A' + i}) $text")
                        }
                    } else {
                        question.parts.forEachIndexed { index, part ->
                            if (isNotEmpty()) append("\n\n")
                            append("${part.label}) ${parts[index]}")
                        }
                    }
                }
            }
            StudyText(preview, style = MaterialTheme.typography.bodyLarge)
            Text("$subject · ${topics.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

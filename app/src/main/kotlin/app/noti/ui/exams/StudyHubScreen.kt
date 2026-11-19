package app.noti.ui.exams

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.noti.data.LibraryStore
import app.noti.data.SettingsStore
import app.noti.exams.*
import app.noti.openrouter.OpenRouterFailure
import app.noti.openrouter.openRouterKeys
import app.noti.study.Progress
import app.noti.ui.assaad.AssaadHubPanel
import app.noti.ui.assaad.LocalAssaadSession
import app.noti.ui.common.NotiAlertDialog
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiIconButton
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import app.noti.ui.study.StudyLaunchTarget
import app.noti.ui.theme.Noti
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.time.LocalDate

private enum class StudyHubTab(val label: String) {
    Exams("Provas"),
    Assaad("Assaad"),
}

@Composable
internal fun StudyHubScreen(
    library: LibraryStore,
    settings: SettingsStore,
    store: ExamStore,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSource: (String, Int) -> Unit,
    onOpenOnCanvas: (notebookId: String, target: StudyLaunchTarget) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keys = remember(library) { openRouterKeys(library.appContext) }
    val lessons = remember { ExamLessons() }
    val assaad = LocalAssaadSession.current
    var hubTab by rememberSaveable { mutableStateOf(StudyHubTab.Exams) }
    var selectedSubjectKey by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedExamId by rememberSaveable { mutableStateOf<String?>(null) }
    var topicKey by rememberSaveable { mutableStateOf<String?>(null) }
    var inLesson by rememberSaveable { mutableStateOf(false) }
    var activeStep by rememberSaveable { mutableStateOf<StudyMilestone?>(null) }
    var marking by remember { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    val destinations = rememberSaveableStateHolder()
    var editing by remember { mutableStateOf<ExamReference?>(null) }
    var deletingExam by remember { mutableStateOf<PastExam?>(null) }
    var pendingCanvas by remember { mutableStateOf<Pair<String, StudyLaunchTarget>?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var task by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loadRetry by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }

    LaunchedEffect(store, loadRetry) {
        try {
            store.load()
            loadFailed = false
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            loadFailed = true
        }
    }
    val catalog = store.catalog
    if (showImport && catalog != null) {
        destinations.SaveableStateProvider("import") {
            ExamImportScreen(library, store, onBack = { showImport = false }, onSettings, onSource)
        }
        return
    }

    val subjects = remember(catalog?.exams) { catalog?.subjects().orEmpty() }
    val currentSubject = subjects.firstOrNull { it.key == selectedSubjectKey }
    val selectedExam = catalog?.exams?.firstOrNull { it.sourceId == selectedExamId }
    val subjectTopics = remember(catalog?.exams, currentSubject?.key) {
        currentSubject?.let { catalog?.topics(it.key) }.orEmpty()
    }
    val topic = subjectTopics.firstOrNull { it.key == topicKey }
    val lesson = topic?.key?.let { catalog?.lessons?.get(it) }
    val questionProgress by produceState(emptyMap<String, Progress>(), library, catalog?.exams, catalog?.progress) {
        value = if (catalog == null) emptyMap() else examQuestionProgress(library, catalog)
    }
    var studyDay by remember { mutableStateOf(LocalDate.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            studyDay = LocalDate.now()
        }
    }
    val overview = catalog?.journey?.overview(questionProgress, studyDay)

    fun runTask(label: String, action: suspend () -> Unit) {
        if (task?.isActive == true) return
        error = null
        progress = label
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

    fun goBack() {
        error = null
        when {
            task?.isActive == true -> task?.cancel()
            activeStep != null -> activeStep = null
            inLesson -> inLesson = false
            topicKey != null -> topicKey = null
            selectedSubjectKey != null -> selectedSubjectKey = null
            selectedExamId != null -> selectedExamId = null
            else -> onBack()
        }
    }

    fun askCanvasForExam(reference: ExamReference) {
        pendingCanvas = reference.exam.title to StudyLaunchTarget.Exam(reference.key)
    }

    fun openOriginal(reference: ExamReference) {
        val source = library.notebook(reference.exam.sourceId)
        if (source == null || source.trashedAt != null || library.pdfFile(source)?.isFile != true || reference.question.pages.isEmpty()) {
            error = "O PDF original não está disponível na biblioteca."
        } else onSource(source.id, reference.question.pages.first())
    }

    fun saveResume(change: (StudyJourney) -> StudyJourney) {
        scope.launch {
            try {
                store.update { it.copy(journey = change(it.journey)) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Não foi possível salvar o ponto de retomada da aula."
            }
        }
    }

    fun openLesson(t: ExamTopic) {
        error = null
        selectedExamId = null
        selectedSubjectKey = t.subjectKey
        topicKey = t.key
        activeStep = null
        inLesson = true
        if (catalog?.journey?.lastTopic != t.key) saveResume { it.copy(lastTopic = t.key) }
    }

    fun openStep(t: ExamTopic, step: StudyMilestone?) {
        activeStep = step
        val journey = store.catalog?.journey ?: return
        if (step != null && step !in journey.milestones(t.key) && journey.inProgress[t.key] != step)
            saveResume { it.open(t.key, step) }
    }

    fun completeStep(t: ExamTopic, step: StudyMilestone) {
        if (marking || step in store.catalog?.journey?.milestones(t.key).orEmpty()) return
        marking = true
        error = null
        scope.launch {
            try {
                store.update { state ->
                    state.copy(journey = state.journey.complete(t.key, step, System.currentTimeMillis()))
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Não foi possível salvar a conclusão da etapa."
            } finally {
                marking = false
            }
        }
    }

    fun generate(t: ExamTopic) {
        runTask("Preparando a questão de referência…") {
            val key = withContext(Dispatchers.IO) { keys.load() }
                ?: error("Salve uma chave em Configurações → OpenRouter.")
            val reference = t.questions.firstOrNull { ref ->
                library.notebook(ref.exam.sourceId)?.takeIf { it.trashedAt == null }
                    ?.let(library::pdfFile)?.isFile == true
            } ?: error("Adicione o PDF original à biblioteca para gerar esta aula com a questão de referência.")
            val file = requireNotNull(library.notebook(reference.exam.sourceId)?.let(library::pdfFile))
            val generated = lessons.generate(
                key, t, reference, render = { page -> renderExamPage(file, page) },
                onWriting = { progress = "Preparando explicações e exercícios…" },
                onReviewing = { progress = "Revisando cálculos, exemplos e exercícios…" },
            )
            store.update {
                it.copy(lessons = it.lessons + (t.key to generated), journey = it.journey.copy(lastTopic = t.key))
            }
            activeStep = null
            inLesson = true
        }
    }

    BackHandler { goBack() }
    val atRoot = selectedSubjectKey == null && selectedExamId == null && topicKey == null && !inLesson
    val title = when {
        !atRoot -> topic?.name ?: currentSubject?.label ?: selectedExam?.title ?: "Estudos"
        else -> "Estudos"
    }
    val subtitle = when {
        inLesson && lesson != null -> "${currentSubject?.label} · Aula completa"
        topic != null -> "${currentSubject?.label} · ${questionCount(topic.questions.size)}"
        currentSubject != null -> "Tópicos"
        selectedExam != null -> selectedExam.subtitle
        hubTab == StudyHubTab.Assaad -> "Plataforma Assaad"
        else -> "Provas anteriores e trilhas"
    }
    key(if (inLesson) "lesson/${topic?.key}/${activeStep?.wire}" else topicKey ?: selectedSubjectKey ?: selectedExamId ?: "home-${hubTab.name}") {
        ExamPage(
            title = title,
            subtitle = subtitle,
            back = ::goBack,
            progress = progress,
            error = error,
            onCancel = { task?.cancel() },
            maxWidth = if (activeStep != null) 720.dp else 960.dp,
            headerExtra = if (atRoot && assaad != null) {
                { HubTabSwitcher(hubTab) { hubTab = it } }
            } else null,
        ) {
            if (atRoot && hubTab == StudyHubTab.Assaad && assaad != null) {
                item("assaad-hub") {
                    AssaadHubPanel(
                        session = assaad,
                        onOpenCanvas = { suggested, qid ->
                            pendingCanvas = suggested to StudyLaunchTarget.Assaad(qid)
                        },
                    )
                }
                return@ExamPage
            }
            when {
                catalog == null -> item {
                    if (loadFailed) {
                        Text("Não foi possível ler o conteúdo salvo.", color = MaterialTheme.colorScheme.error)
                        NotiOutlinedButton(onClick = { loadRetry++ }) { Text("Tentar novamente") }
                    } else CircularProgressIndicator(Modifier.size(24.dp))
                }
                topic != null && inLesson && lesson != null -> {
                    val steps = lesson.milestones()
                    val step = activeStep?.takeIf { it in steps }
                    val completed = catalog.journey.milestones(topic.key)
                    val reference = topic.questions.firstOrNull { it.key == lesson.questionKey }
                    if (step == null) {
                        lessonPathContent(
                            lesson, topic.subjectLabel, completed, catalog.journey.inProgress[topic.key], reference,
                            (questionProgress[reference?.key]?.attempts ?: 0) > 0, progress == null,
                            open = { openStep(topic, it) }, practice = ::askCanvasForExam,
                        )
                    } else {
                        lessonMilestoneContent(
                            lesson, topic.subjectLabel, step, step in completed,
                            !marking && progress == null, checkNotNull(overview),
                            onComplete = { completeStep(topic, step) },
                            onNext = { openStep(topic, steps.getOrNull(steps.indexOf(step) + 1)) },
                            onPath = { activeStep = null },
                        )
                    }
                }
                topic != null -> {
                    item("topic-lesson") {
                        val accent = subjectAccent(topic.subjectLabel)
                        val steps = lesson?.milestones().orEmpty()
                        val completed = steps.count { it in catalog.journey.milestones(topic.key) }
                        Surface(shape = RoundedCornerShape(24.dp), color = accent.container, contentColor = accent.ink) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ExamEmblem(accent.icon, accent.ink.copy(alpha = .12f), accent.ink)
                                    Column(Modifier.weight(1f)) {
                                        ExamHeading("Aula do zero à prova")
                                        Text(
                                            if (steps.isEmpty()) "Conceitos, exemplos e exercícios guiados"
                                            else "$completed de ${steps.size} etapas concluídas",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                if (steps.isNotEmpty()) ExamProgressRail(
                                    completed.toFloat() / steps.size, accent.ink,
                                    accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth(),
                                )
                                if (lesson == null) Text(
                                    "Para gerar a aula, o Noti envia a questão de referência ao OpenRouter.",
                                    style = MaterialTheme.typography.bodySmall,
                                ) else if (!lesson.isReviewed) Text(
                                    "Esta versão foi salva antes da revisão do conteúdo.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (lesson != null) {
                                        NotiButton(enabled = progress == null, onClick = { openLesson(topic) }) { Text("Abrir aula") }
                                        if (!lesson.isReviewed) NotiOutlinedButton(
                                            enabled = progress == null,
                                            onClick = { if (keys.hasKey) generate(topic) else onSettings() },
                                        ) {
                                            Text(if (keys.hasKey) "Atualizar e revisar aula" else "Configurar OpenRouter")
                                        }
                                    } else {
                                        NotiButton(enabled = progress == null && keys.hasKey, onClick = { generate(topic) }) {
                                            Text("Gerar aula do zero")
                                        }
                                        if (!keys.hasKey) NotiOutlinedButton(onClick = onSettings) { Text("Configurar OpenRouter") }
                                    }
                                }
                            }
                        }
                    }
                    item("real-questions") { ExamHeading("Questões de provas") }
                    topic.questions.forEach { reference ->
                        item(key = reference.key) {
                            ExamQuestionCard(
                                reference, questionProgress[reference.key]?.attempts ?: 0, progress == null,
                                { openOriginal(reference) }, { askCanvasForExam(reference) }, { editing = reference },
                            )
                        }
                    }
                }
                selectedExam != null -> {
                    item("exam-questions-title") { ExamHeading("Questões da prova") }
                    selectedExam.questions.forEach { q ->
                        val reference = ExamReference(selectedExam, q)
                        item(key = reference.key) {
                            ExamQuestionCard(
                                reference, questionProgress[reference.key]?.attempts ?: 0, progress == null,
                                { openOriginal(reference) }, { askCanvasForExam(reference) }, { editing = reference },
                            )
                        }
                    }
                }
                currentSubject != null -> {
                    item("subject-overview") {
                        val questions = currentSubject.questions.map { it.key }
                        val completed = questions.count { (questionProgress[it]?.attempts ?: 0) > 0 }
                        val accent = subjectAccent(currentSubject.label)
                        Surface(shape = RoundedCornerShape(24.dp), color = accent.container, contentColor = accent.ink) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ExamEmblem(accent.icon, accent.ink.copy(alpha = .12f), accent.ink)
                                    Column(Modifier.weight(1f)) {
                                        ExamHeading("Sua trilha")
                                        Text(
                                            if (questions.isEmpty()) "Importe uma prova para descobrir os tópicos."
                                            else "$completed de ${questions.size} questões praticadas",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                }
                                if (questions.isNotEmpty()) ExamProgressRail(
                                    completed.toFloat() / questions.size, accent.ink,
                                    accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth(),
                                ) else NotiButton(onClick = { showImport = true }) { Text("Importar prova") }
                            }
                        }
                    }
                    if (subjectTopics.isNotEmpty()) item("available-topics") { ExamHeading("Tópicos") }
                    subjectTopics.forEach { row ->
                        item(key = row.key) {
                            val steps = catalog.lessons[row.key]?.milestones().orEmpty()
                            val completed = steps.count { it in catalog.journey.milestones(row.key) }
                            val detail = if (steps.isEmpty()) questionCount(row.questions.size)
                            else "${questionCount(row.questions.size)} · $completed/${steps.size} etapas"
                            ExamTopicRow(row.name, detail, row.subjectLabel, steps.size, completed) { topicKey = row.key }
                        }
                    }
                }
                else -> {
                    val allTopics = remember(catalog.exams) { catalog.allTopics() }
                    val unfinished = allTopics.filter { candidate ->
                        catalog.lessons[candidate.key]?.milestones()?.any { it !in catalog.journey.milestones(candidate.key) } == true
                    }
                    val resume = unfinished.firstOrNull { it.key == catalog.journey.lastTopic } ?: unfinished.firstOrNull()
                    val references = catalog.allReferences()
                    val nextQuestion = references.firstOrNull { (questionProgress[it.key]?.attempts ?: 0) == 0 }
                    val nextTopic = nextQuestion?.let { ref ->
                        val firstTopic = ref.question.topics.firstOrNull() ?: return@let null
                        allTopics.firstOrNull { it.key == topicId(ref.question.subjectKey, firstTopic) }
                    }
                    val dashboard = checkNotNull(overview)
                    item("study-hero") {
                        when {
                            resume != null -> ExamHomeHero(
                                dashboard, "Continue sua trilha",
                                "${resume.subjectLabel} · ${resume.name}", "Continuar aula",
                            ) { openLesson(resume) }
                            nextTopic != null -> ExamHomeHero(
                                dashboard, "Comece por aqui",
                                "${nextTopic.subjectLabel} · ${nextTopic.name}", "Estudar tópico",
                            ) {
                                selectedSubjectKey = nextTopic.subjectKey
                                topicKey = nextTopic.key
                            }
                            references.isNotEmpty() -> ExamHomeHero(
                                dashboard, "Continue praticando",
                                "Explore as questões e reveja os tópicos das suas provas.", "Explorar tópicos",
                            ) {
                                selectedSubjectKey = references.first().question.subjectKey
                            }
                            else -> ExamHomeHero(
                                dashboard, "Monte sua trilha",
                                "Importe qualquer prova em PDF — ENEM, FUVEST, UNICAMP, ITA ou sua faculdade. A IA organiza tudo.",
                                "Importar prova",
                            ) { showImport = true }
                        }
                    }
                    item("daily-goal") { ExamDailyGoal(dashboard.today) }
                    if (subjects.isNotEmpty()) {
                        item("subjects-title") { ExamHeading("Disciplinas") }
                        item("subjects") {
                            BoxWithConstraints(Modifier.fillMaxWidth()) {
                                val columns = when {
                                    maxWidth >= 760.dp && LocalDensity.current.fontScale < 1.35f -> 3
                                    maxWidth >= 520.dp && LocalDensity.current.fontScale < 1.35f -> 2
                                    else -> 1
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    subjects.chunked(columns).forEach { row ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                                            row.forEach { group ->
                                                val tried = group.questions.count { (questionProgress[it.key]?.attempts ?: 0) > 0 }
                                                ExamSubjectCard(group.label, tried, group.questions.size, Modifier.weight(1f)) {
                                                    selectedSubjectKey = group.key
                                                }
                                            }
                                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item("exams-title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ExamHeading("Provas salvas") }
                            NotiTextButton(onClick = { showImport = true }) { Text("Importar prova") }
                        }
                    }
                    if (catalog.exams.isEmpty()) {
                        item("no-exams") {
                            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Nenhuma prova importada ainda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        "Escolha qualquer PDF de prova anterior. A IA lê a banca, o ano, as disciplinas e todas as questões.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else {
                        catalog.exams
                            .sortedWith(compareByDescending<PastExam> { it.year ?: 0 }.thenBy { it.title })
                            .forEach { exam ->
                                item(key = exam.sourceId) {
                                    val colors = MaterialTheme.colorScheme
                                    Surface(
                                        onClick = { selectedExamId = exam.sourceId },
                                        shape = RoundedCornerShape(22.dp),
                                        color = colors.surfaceContainerLowest,
                                    ) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            ExamEmblem(Icons.Outlined.PictureAsPdf, colors.secondaryContainer, colors.onSecondaryContainer)
                                            Column(Modifier.weight(1f)) {
                                                Text(exam.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                                Text(exam.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                                            }
                                            NotiIconButton(onClick = { deletingExam = exam }) {
                                                Icon(Icons.Outlined.DeleteOutline, "Remover prova ${exam.title}")
                                            }
                                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    pendingCanvas?.let { (suggestedName, target) ->
        ChooseCanvasDialog(
            library = library,
            settings = settings,
            suggestedName = suggestedName,
            onDismiss = { pendingCanvas = null },
            onChosen = { notebookId ->
                pendingCanvas = null
                onOpenOnCanvas(notebookId, target)
            },
        )
    }

    deletingExam?.let { exam ->
        NotiAlertDialog(
            onDismissRequest = { deletingExam = null },
            title = { Text("Remover ${exam.title}?") },
            text = { Text("As questões saem da lista de provas. O PDF original e seus cadernos permanecem na biblioteca.") },
            confirmButton = {
                NotiButton(destructive = true, onClick = {
                    val target = exam
                    deletingExam = null
                    runTask("Removendo prova…") {
                        store.update { current -> current.copy(exams = current.exams.filterNot { it.sourceId == target.sourceId }) }
                        if (selectedExamId == target.sourceId) selectedExamId = null
                    }
                }) { Text("Remover") }
            },
            dismissButton = { NotiTextButton(onClick = { deletingExam = null }) { Text("Cancelar") } },
        )
    }

    editing?.let { reference ->
        EditReadQuestion(reference.question, onClose = { editing = null }, onSave = { corrected ->
            editing = null
            runTask("Salvando revisão…") {
                store.update { current ->
                    val exams = current.exams.map { exam ->
                        if (exam.sourceId != reference.exam.sourceId) exam else exam.copy(
                            questions = exam.questions.map { if (it.code == corrected.code) corrected else it },
                        )
                    }
                    val lessonsMap = if (corrected.prompt == reference.question.prompt) current.lessons else
                        current.lessons.mapValues { (_, savedLesson) ->
                            if (savedLesson.questionKey == reference.key) savedLesson.copy(reviewVersion = 0) else savedLesson
                        }
                    current.copy(exams = exams, lessons = lessonsMap)
                }
                if (topic != null && store.catalog?.topics(topic.subjectKey)?.none { it.key == topic.key } == true) topicKey = null
            }
        })
    }
}

@Composable
private fun HubTabSwitcher(active: StudyHubTab, onSelect: (StudyHubTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(colors.surfaceContainerHigh).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StudyHubTab.entries.forEach { tab ->
            val selected = tab == active
            Box(
                Modifier.clip(CircleShape)
                    .background(if (selected) colors.primaryContainer else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun ExamPage(
    title: String,
    subtitle: String,
    back: () -> Unit,
    progress: String?,
    error: String?,
    onCancel: () -> Unit,
    maxWidth: Dp = 960.dp,
    headerExtra: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NotiIconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                headerExtra?.invoke()
            }
            Surface(
                Modifier.widthIn(max = maxWidth).fillMaxWidth().weight(1f),
                color = Noti.extras.sheet,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column {
                    progress?.let {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                it,
                                Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            NotiTextButton(onClick = onCancel) { Text("Cancelar") }
                        }
                    }
                    error?.let {
                        Text(
                            it,
                            Modifier.padding(horizontal = 18.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ExamHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun ExamTopicRow(title: String, subtitle: String, subjectLabel: String, steps: Int, completed: Int, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = subjectAccent(subjectLabel)
    Surface(onClick = open, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExamEmblem(
                if (steps > 0 && steps == completed) Icons.Outlined.Check else accent.icon,
                accent.container,
                accent.ink,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (steps > 0) ExamProgressRail(
                    completed.toFloat() / steps, accent.ink,
                    accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth(),
                )
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = accent.ink)
        }
    }
}

@Composable
private fun ExamQuestionCard(
    reference: ExamReference,
    attempts: Int,
    enabled: Boolean,
    source: () -> Unit,
    practice: () -> Unit,
    edit: () -> Unit,
) {
    val question = reference.question
    val accent = subjectAccent(question.subject)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExamEmblem(if (attempts == 0) Icons.Outlined.EditNote else Icons.Outlined.Check, accent.container, accent.ink)
                Column(Modifier.weight(1f)) {
                    Text(reference.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "${question.subject} · ${question.format.label} · " + if (attempts == 0) "+${StudyOverview.QuestionXp} XP"
                        else "$attempts ${if (attempts == 1) "tentativa" else "tentativas"} · ${StudyOverview.QuestionXp} XP ganhos",
                        color = accent.ink,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Text(
                question.topics.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotiButton(enabled = enabled, onClick = practice) { Text("Resolver no quadro") }
                if (question.pages.isNotEmpty()) {
                    NotiTextButton(enabled = enabled, onClick = source) { Text("Abrir PDF · p. ${question.pages.joinToString(", ")}") }
                }
                NotiTextButton(enabled = enabled, onClick = edit) { Text("Editar questão") }
            }
        }
    }
}

private fun questionCount(size: Int) = "$size ${if (size == 1) "questão" else "questões"}"

internal fun examError(error: Exception): String = when (error) {
    is ExamLessonReviewFailure -> "A revisão encontrou problemas na aula. Tente gerar novamente."
    is OpenRouterFailure.Api -> when (error.status) {
        401 -> "A chave do OpenRouter foi recusada. Confira Configurações → OpenRouter."
        402 -> "Confira os créditos e o limite de gastos do OpenRouter."
        429, 503 -> error.retryAfterSeconds?.takeIf { it > 0 }?.let { "O OpenRouter pediu uma pausa de $it segundos." }
            ?: "O OpenRouter está ocupado. Tente novamente em instantes."
        else -> "O OpenRouter não concluiu a solicitação (erro ${error.status})."
    }
    is OpenRouterFailure.Incomplete, is OpenRouterFailure.Protocol, is JSONException ->
        "A resposta do modelo veio incompleta. Tente novamente."
    is OpenRouterFailure.Network -> if (error.timedOut) "O OpenRouter demorou demais. Tente novamente."
        else "A conexão com o OpenRouter foi interrompida."
    is IllegalArgumentException, is IllegalStateException -> error.message ?: "Não foi possível concluir esta ação."
    else -> "Não foi possível concluir esta ação."
}

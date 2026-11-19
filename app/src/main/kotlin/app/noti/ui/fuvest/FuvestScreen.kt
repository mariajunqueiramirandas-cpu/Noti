package app.noti.ui.fuvest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.noti.data.LibraryStore
import app.noti.fuvest.*
import app.noti.openrouter.OpenRouterFailure
import app.noti.openrouter.openRouterKeys
import app.noti.study.Progress
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiIconButton
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import app.noti.ui.theme.Noti
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.time.LocalDate

@Composable
internal fun FuvestScreen(
    library: LibraryStore,
    store: FuvestStore,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSource: (String, Int) -> Unit,
    onPractice: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val keys = remember(library) { openRouterKeys(library.appContext) }
    val lessons = remember { FuvestLessons() }
    val uri = LocalUriHandler.current
    var subject by rememberSaveable { mutableStateOf<FuvestSubject?>(null) }
    var topicKey by rememberSaveable { mutableStateOf<String?>(null) }
    var inLesson by rememberSaveable { mutableStateOf(false) }
    var activeStep by rememberSaveable { mutableStateOf<FuvestMilestone?>(null) }
    var marking by remember { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    val destinations = rememberSaveableStateHolder()
    var editing by remember { mutableStateOf<FuvestReference?>(null) }
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
            FuvestImportScreen(library, store, onBack = { showImport = false }, onSettings, onSource)
        }
        return
    }

    val currentSubject = subject
    val topicsBySubject = remember(catalog?.exams) {
        FuvestSubject.entries.associateWith { catalog?.topics(it).orEmpty() }
    }
    val subjectsTopics = topicsBySubject[currentSubject].orEmpty()
    val topic = subjectsTopics.firstOrNull { it.key == topicKey }
    val lesson = topic?.key?.let { catalog?.lessons?.get(it) }
    val questionProgress by produceState(emptyMap<String, Progress>(), library, catalog?.exams) {
        value = fuvestQuestionProgress(library, catalog?.exams.orEmpty())
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
                error = fuvestError(failure)
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
            subject != null -> subject = null
            else -> onBack()
        }
    }

    fun practice(reference: FuvestReference) {
        runTask("Abrindo quadro de estudo…") {
            prepareFuvestBoard(library, checkNotNull(store.catalog), reference.exam, reference.question)
            onPractice(reference.exam.boardId, reference.key)
        }
    }

    fun openOriginal(reference: FuvestReference) {
        val source = library.notebook(reference.exam.sourceId)
        if (source == null || source.trashedAt != null || library.pdfFile(source)?.isFile != true) {
            error = "O PDF original não está disponível na biblioteca."
        } else onSource(source.id, reference.question.pages.first())
    }

    fun saveResume(change: (FuvestJourney) -> FuvestJourney) {
        scope.launch {
            try {
                store.update { it.copy(journey = change(it.journey)) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Não foi possível salvar o ponto de retomada da aula."
            }
        }
    }

    fun openLesson(topic: FuvestTopic) {
        error = null
        subject = topic.subject
        topicKey = topic.key
        activeStep = null
        inLesson = true
        if (catalog?.journey?.lastTopic != topic.key) saveResume { it.copy(lastTopic = topic.key) }
    }

    fun openStep(topic: FuvestTopic, step: FuvestMilestone?) {
        activeStep = step
        val journey = store.catalog?.journey ?: return
        if (step != null && step !in journey.milestones(topic.key) && journey.inProgress[topic.key] != step)
            saveResume { it.open(topic.key, step) }
    }

    fun completeStep(topic: FuvestTopic, step: FuvestMilestone) {
        if (marking || step in store.catalog?.journey?.milestones(topic.key).orEmpty()) return
        marking = true
        error = null
        scope.launch {
            try {
                store.update { state ->
                    state.copy(journey = state.journey.complete(topic.key, step, System.currentTimeMillis()))
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = "Não foi possível salvar a conclusão da etapa."
            } finally {
                marking = false
            }
        }
    }

    fun generate(topic: FuvestTopic) {
        runTask("Preparando a questão de referência…") {
            val key = withContext(Dispatchers.IO) { keys.load() }
                ?: error("Salve uma chave em Configurações → OpenRouter.")
            val reference = topic.questions.firstOrNull { ref ->
                library.notebook(ref.exam.sourceId)?.takeIf { it.trashedAt == null }
                    ?.let(library::pdfFile)?.isFile == true
            } ?: error("Adicione o PDF original à biblioteca para gerar esta aula com a questão de referência.")
            val file = requireNotNull(library.notebook(reference.exam.sourceId)?.let(library::pdfFile))
            val generated = lessons.generate(key, topic, reference, render = { page -> renderFuvestPage(file, page) },
                onWriting = { progress = "Preparando explicações e exercícios…" },
                onReviewing = { progress = "Revisando cálculos, exemplos e exercícios…" },
            )
            store.update {
                it.copy(lessons = it.lessons + (topic.key to generated), journey = it.journey.copy(lastTopic = topic.key))
            }
            activeStep = null
            inLesson = true
        }
    }

    BackHandler { goBack() }
    val title = topic?.name ?: currentSubject?.label ?: "FUVEST"
    val subtitle = when {
        inLesson && lesson != null -> "${currentSubject?.label} · Aula completa"
        topic != null -> "${currentSubject?.label} · ${questionCount(topic.questions.size)}"
        currentSubject != null -> "Tópicos"
        else -> "Segunda fase · Engenharia"
    }
    key(if (inLesson) "lesson/${topic?.key}/${activeStep?.wire}" else topicKey ?: subject?.name ?: "home") {
        FuvestPage(title, subtitle, ::goBack, progress, error, onCancel = { task?.cancel() },
            maxWidth = if (activeStep != null) 720.dp else 960.dp) {
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
                        lessonPathContent(lesson, topic.subject, completed, catalog.journey.inProgress[topic.key], reference,
                            (questionProgress[reference?.key]?.attempts ?: 0) > 0, progress == null,
                            open = { openStep(topic, it) }, practice = ::practice)
                    } else {
                        lessonMilestoneContent(lesson, topic.subject, step, step in completed,
                            !marking && progress == null, checkNotNull(overview),
                            onComplete = { completeStep(topic, step) },
                            onNext = { openStep(topic, steps.getOrNull(steps.indexOf(step) + 1)) },
                            onPath = { activeStep = null })
                    }
                }
                topic != null -> {
                    item("topic-lesson") {
                        val accent = topic.subject.accent()
                        val steps = lesson?.milestones().orEmpty()
                        val completed = steps.count { it in catalog.journey.milestones(topic.key) }
                        Surface(shape = RoundedCornerShape(24.dp), color = accent.container, contentColor = accent.ink) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FuvestEmblem(accent.icon, accent.ink.copy(alpha = .12f), accent.ink)
                                    Column(Modifier.weight(1f)) {
                                        FuvestHeading("Aula do zero à prova")
                                        Text(if (steps.isEmpty()) "Conceitos, exemplos e exercícios guiados" else
                                            "$completed de ${steps.size} etapas concluídas", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                if (steps.isNotEmpty()) FuvestProgressRail(completed.toFloat() / steps.size, accent.ink,
                                    accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth())
                                if (lesson == null) Text("Para gerar a aula, o Noti envia a questão de referência ao OpenRouter.",
                                    style = MaterialTheme.typography.bodySmall)
                                else if (!lesson.isReviewed) Text("Esta versão foi salva antes da revisão do conteúdo.",
                                    style = MaterialTheme.typography.bodySmall)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (lesson != null) {
                                        NotiButton(enabled = progress == null, onClick = { openLesson(topic) }) { Text("Abrir aula") }
                                        if (!lesson.isReviewed) NotiOutlinedButton(enabled = progress == null,
                                            onClick = { if (keys.hasKey) generate(topic) else onSettings() }) {
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
                    item("real-questions") { FuvestHeading("Questões da FUVEST") }
                    topic.questions.forEach { reference ->
                        item(key = reference.key) {
                            FuvestQuestionCard(reference, questionProgress[reference.key]?.attempts ?: 0, progress == null,
                                { openOriginal(reference) }, { practice(reference) }, { editing = reference })
                        }
                    }
                }
                currentSubject != null -> {
                    item("subject-overview") {
                        val questions = catalog.exams.flatMap { exam ->
                            exam.questions.filter { it.subject == currentSubject }.map { exam.key(it) }
                        }
                        val completed = questions.count { (questionProgress[it]?.attempts ?: 0) > 0 }
                        val accent = currentSubject.accent()
                        Surface(shape = RoundedCornerShape(24.dp), color = accent.container, contentColor = accent.ink) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    FuvestEmblem(accent.icon, accent.ink.copy(alpha = .12f), accent.ink)
                                    Column(Modifier.weight(1f)) {
                                        FuvestHeading("Sua trilha")
                                        Text(if (questions.isEmpty()) "Importe uma prova para descobrir os tópicos." else
                                            "$completed de ${questions.size} questões praticadas",
                                            style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                if (questions.isNotEmpty()) FuvestProgressRail(completed.toFloat() / questions.size, accent.ink,
                                    accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth())
                                else NotiButton(onClick = { showImport = true }) { Text("Importar prova") }
                            }
                        }
                    }
                    if (subjectsTopics.isNotEmpty()) item("available-topics") { FuvestHeading("Tópicos da prova") }
                    subjectsTopics.forEach { row ->
                        item(key = row.key) {
                            val steps = catalog.lessons[row.key]?.milestones().orEmpty()
                            val completed = steps.count { it in catalog.journey.milestones(row.key) }
                            val detail = if (steps.isEmpty()) questionCount(row.questions.size) else
                                "${questionCount(row.questions.size)} · $completed/${steps.size} etapas"
                            FuvestRow(row.name, detail, row.subject, steps.size, completed) { topicKey = row.key }
                        }
                    }
                }
                else -> {
                    val allTopics = topicsBySubject.values.flatten()
                    val unfinished = allTopics.filter { candidate ->
                        catalog.lessons[candidate.key]?.milestones()?.any { it !in catalog.journey.milestones(candidate.key) } == true
                    }
                    val resume = unfinished.firstOrNull { it.key == catalog.journey.lastTopic } ?: unfinished.firstOrNull()
                    val references = catalog.exams.sortedByDescending { it.year }.flatMap { exam ->
                        exam.questions.map { FuvestReference(exam, it) }
                    }
                    val nextQuestion = references.firstOrNull { (questionProgress[it.key]?.attempts ?: 0) == 0 }
                    val nextTopic = nextQuestion?.let { ref ->
                        topicsBySubject[ref.question.subject]?.firstOrNull {
                            it.key == topicId(ref.question.subject, ref.question.topics.first())
                        }
                    }
                    val dashboard = checkNotNull(overview)
                    item("study-hero") {
                        when {
                            resume != null -> FuvestHomeHero(dashboard, "Continue sua trilha",
                                "${resume.subject.label} · ${resume.name}", "Continuar aula") { openLesson(resume) }
                            nextTopic != null -> FuvestHomeHero(dashboard, "Comece por aqui",
                                "${nextTopic.subject.label} · ${nextTopic.name}", "Estudar tópico") {
                                subject = nextTopic.subject
                                topicKey = nextTopic.key
                            }
                            references.isNotEmpty() -> FuvestHomeHero(dashboard, "Continue praticando",
                                "Explore as questões e reveja os tópicos das suas provas.", "Explorar tópicos") {
                                subject = references.first().question.subject
                            }
                            else -> FuvestHomeHero(dashboard, "Monte sua trilha",
                                "Importe uma prova da FUVEST para descobrir seus tópicos de estudo.", "Importar prova") { showImport = true }
                        }
                    }
                    item("daily-goal") { FuvestDailyGoal(dashboard.today) }
                    item("subjects-title") { FuvestHeading("Disciplinas") }
                    item("subjects") {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val wide = maxWidth >= 690.dp && LocalDensity.current.fontScale < 1.35f
                            val subjects = FuvestSubject.entries.map { discipline ->
                                val questions = references.filter { it.question.subject == discipline }
                                Triple(discipline, questions.count { (questionProgress[it.key]?.attempts ?: 0) > 0 }, questions.size)
                            }
                            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                subjects.forEach { (discipline, count, total) ->
                                    FuvestSubjectCard(discipline, count, total, Modifier.weight(1f)) { subject = discipline }
                                }
                            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                subjects.forEach { (discipline, count, total) ->
                                    FuvestSubjectCard(discipline, count, total, Modifier.fillMaxWidth()) { subject = discipline }
                                }
                            }
                        }
                    }
                    if (catalog.exams.isNotEmpty()) {
                        item("exams-title") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { FuvestHeading("Provas salvas") }
                                NotiTextButton(onClick = { showImport = true }) { Text("Importar prova") }
                            }
                        }
                        catalog.exams.sortedByDescending { it.year }.forEach { exam ->
                            item(key = exam.sourceId) {
                                val colors = MaterialTheme.colorScheme
                                Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLowest) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        FuvestEmblem(Icons.Outlined.PictureAsPdf, colors.secondaryContainer, colors.onSecondaryContainer)
                                        Column(Modifier.weight(1f)) {
                                            Text(exam.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text("2º dia · ${questionCount(exam.questions.size)}", style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item("exam-actions") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NotiTextButton(onClick = {
                                try { uri.openUri(Fuvest2023.EXAM) } catch (_: IllegalArgumentException) {
                                    error = "Não foi possível abrir o PDF oficial."
                                }
                            }) { Text("PDF oficial de 2023") }
                        }
                    }
                }
            }
        }
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
                    val lessons = if (corrected.prompt == reference.question.prompt) current.lessons else
                        current.lessons.mapValues { (_, lesson) ->
                            if (lesson.questionKey == reference.key) lesson.copy(reviewVersion = 0) else lesson
                        }
                    current.copy(exams = exams, lessons = lessons)
                }
                if (topic != null && store.catalog?.topics(topic.subject)?.none { it.key == topic.key } == true) topicKey = null
            }
        })
    }
}

@Composable
internal fun FuvestPage(
    title: String,
    subtitle: String,
    back: () -> Unit,
    progress: String?,
    error: String?,
    onCancel: () -> Unit,
    maxWidth: Dp = 960.dp,
    content: LazyListScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = maxWidth).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                NotiIconButton(onClick = back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Voltar") }
                Column(Modifier.padding(start = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, modifier = Modifier.semantics { heading() })
                    Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Surface(Modifier.widthIn(max = maxWidth).fillMaxWidth().weight(1f),
                color = Noti.extras.sheet, shape = MaterialTheme.shapes.extraLarge) {
                Column {
                    progress?.let {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(it, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium)
                            NotiTextButton(onClick = onCancel) { Text("Cancelar") }
                        }
                    }
                    error?.let {
                        Text(it, Modifier.padding(horizontal = 18.dp, vertical = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
                }
            }
        }
    }
}

@Composable
internal fun FuvestHeading(text: String) {
    Text(text, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun FuvestRow(title: String, subtitle: String, subject: FuvestSubject, steps: Int, completed: Int, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = subject.accent()
    Surface(onClick = open, shape = RoundedCornerShape(20.dp), color = colors.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FuvestEmblem(if (steps > 0 && steps == completed) Icons.Outlined.Check else accent.icon,
                accent.container, accent.ink)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (steps > 0) FuvestProgressRail(completed.toFloat() / steps, accent.ink,
                    accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth())
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = accent.ink)
        }
    }
}

@Composable
private fun FuvestQuestionCard(reference: FuvestReference, attempts: Int, enabled: Boolean,
    source: () -> Unit, practice: () -> Unit, edit: () -> Unit) {
    val question = reference.question
    val accent = question.subject.accent()
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FuvestEmblem(if (attempts == 0) Icons.Outlined.EditNote else Icons.Outlined.Check, accent.container, accent.ink)
                Column(Modifier.weight(1f)) {
                    Text(reference.label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (attempts == 0) "Desafio da prova · +${FuvestOverview.QuestionXp} XP" else
                        "$attempts ${if (attempts == 1) "tentativa" else "tentativas"} · ${FuvestOverview.QuestionXp} XP ganhos",
                        color = accent.ink, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(question.topics.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NotiButton(enabled = enabled, onClick = practice) { Text("Resolver no quadro") }
                NotiTextButton(enabled = enabled, onClick = source) { Text("Abrir PDF · p. ${question.pages.joinToString(", ")}") }
                NotiTextButton(enabled = enabled, onClick = edit) { Text("Editar questão") }
            }
        }
    }
}

private fun questionCount(size: Int) = "$size ${if (size == 1) "questão" else "questões"}"

internal fun fuvestError(error: Exception): String = when (error) {
    is FuvestLessonReviewFailure -> "A revisão encontrou problemas na aula. Tente gerar novamente."
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

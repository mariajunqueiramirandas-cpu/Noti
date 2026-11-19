package app.noti.ui.exams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noti.exams.*
import app.noti.ui.common.NotiButton
import app.noti.ui.common.NotiOutlinedButton
import app.noti.ui.common.NotiTextButton
import app.noti.ui.common.StudyText

internal fun LazyListScope.lessonPathContent(
    lesson: ExamLesson,
    subjectLabel: String,
    completed: Set<StudyMilestone>,
    inProgress: StudyMilestone?,
    reference: ExamReference?,
    examTried: Boolean,
    enabled: Boolean,
    open: (StudyMilestone) -> Unit,
    practice: (ExamReference) -> Unit,
) {
    val milestones = lesson.milestones()
    val next = inProgress?.takeIf { it in milestones && it !in completed }
        ?: milestones.firstOrNull { it !in completed }
    val done = milestones.count { it in completed }
    item("goal") {
        val accent = subjectAccent(subjectLabel)
        val longGoal = lesson.goal.length > 220
        var showGoal by rememberSaveable { mutableStateOf(false) }
        LessonCard(accent.container, accent.ink) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExamEmblem(if (next == null) Icons.Outlined.Check else accent.icon,
                    accent.ink.copy(alpha = .12f), accent.ink)
                Column(Modifier.weight(1f)) {
                    ExamHeading(if (next == null) "Aula concluída" else "Trilha da aula")
                    Text("$done de ${milestones.size} etapas concluídas", style = MaterialTheme.typography.labelMedium)
                }
            }
            ExamProgressRail(done.toFloat() / milestones.size.coerceAtLeast(1), accent.ink,
                accent.ink.copy(alpha = .16f), Modifier.fillMaxWidth())
            if (!longGoal) LessonText(lesson.goal)
            if (longGoal && next != null) {
                Text("A seguir · ${next.title(lesson)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (next != null) {
                    NotiButton(enabled = enabled, onClick = { open(next) }) {
                        Text(if (done == 0 && inProgress == null) "Começar trilha" else "Continuar trilha")
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                } else if (reference != null && !examTried) {
                    NotiButton(enabled = enabled, onClick = { practice(reference) }) { Text("Ir para o desafio da prova") }
                }
                if (longGoal) NotiTextButton(onClick = { showGoal = !showGoal }) {
                    Text(if (showGoal) "Ocultar objetivo" else "Ver objetivo")
                }
            }
            AnimatedVisibility(longGoal && showGoal) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ExamHeading("Objetivo da aula")
                    LessonText(lesson.goal)
                }
            }
            if (!lesson.isReviewed) Text("Esta versão foi salva antes da revisão do conteúdo.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    milestones.forEachIndexed { index, step ->
        when {
            index == 0 && step.chapterIndex != null -> item("learn") { ExamHeading("Aprender") }
            step.exerciseIndex == 0 -> item("exercises") { ExamHeading("Praticar") }
        }
        item(step.wire) {
            MilestoneRow(step.title(lesson), index + 1, step.xp, step in completed, next == step, subjectLabel,
                enabled, open = { open(step) })
        }
    }
    if (reference != null) {
        item("exam-title") { ExamHeading("Na prova") }
        item("exam") {
            val accent = subjectAccent(subjectLabel)
            LessonCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ExamEmblem(if (examTried) Icons.Outlined.Check else Icons.Outlined.EditNote, accent.container, accent.ink)
                    Column(Modifier.weight(1f)) {
                        Text("Questão da prova", style = MaterialTheme.typography.titleLarge)
                        Text(reference.label, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(if (examTried) "${StudyOverview.QuestionXp} XP ganhos" else "+${StudyOverview.QuestionXp} XP",
                        color = accent.ink, style = MaterialTheme.typography.labelMedium)
                }
                Text("Escreva e justifique sua solução no quadro. Confira e avalie sua resposta para registrar a tentativa.",
                    style = MaterialTheme.typography.bodyMedium)
                NotiButton(enabled = enabled, onClick = { practice(reference) }) { Text("Resolver no quadro") }
            }
        }
    }
    item("mastery") {
        LessonCard {
            ExamHeading("Confira seu domínio")
            lesson.checklist.forEach { criterion ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = subjectAccent(subjectLabel).ink)
                    Column(Modifier.weight(1f)) { LessonText(criterion) }
                }
            }
            Text(if (lesson.isReviewed) "Aula gerada e revisada por IA" else "Aula gerada por IA",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MilestoneRow(title: String, position: Int, xp: Int, done: Boolean, next: Boolean,
    subjectLabel: String, enabled: Boolean, open: () -> Unit) {
    val accent = subjectAccent(subjectLabel)
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, enabled = enabled, shape = RoundedCornerShape(20.dp),
        color = if (next) accent.container else colors.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp),
                color = if (next || done) accent.ink else accent.container,
                contentColor = if (next || done) accent.container else accent.ink) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (done) Icon(Icons.Outlined.Check, null, Modifier.size(21.dp))
                    else Text(position.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(when {
                    done -> "Concluída · $xp XP"
                    next -> "Próxima etapa · +$xp XP"
                    else -> "+$xp XP"
                }, color = accent.ink, style = MaterialTheme.typography.labelMedium)
            }
            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp), tint = accent.ink)
        }
    }
}

internal fun LazyListScope.lessonMilestoneContent(
    lesson: ExamLesson,
    subjectLabel: String,
    step: StudyMilestone,
    done: Boolean,
    enabled: Boolean,
    overview: StudyOverview,
    onComplete: () -> Unit,
    onNext: () -> Unit,
    onPath: () -> Unit,
) {
    item("milestone") {
        val steps = lesson.milestones()
        val accent = subjectAccent(subjectLabel)
        val colors = MaterialTheme.colorScheme
        val chapter = step.chapterIndex?.let(lesson.sections::get)
        val exercise = step.exerciseIndex?.let(lesson.exercises::get)
        val hasNext = step != steps.last()
        var showHint by rememberSaveable { mutableStateOf(false) }
        var showAnswer by rememberSaveable { mutableStateOf(false) }
        var priorLevel by rememberSaveable { mutableIntStateOf(0) }
        var priorToday by rememberSaveable { mutableIntStateOf(0) }
        val rewardPosition = remember { BringIntoViewRequester() }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LessonCard(accent.container, accent.ink) {
                Text("Etapa ${steps.indexOf(step) + 1} de ${steps.size} · ${if (chapter != null) "Aprender" else "Praticar"}",
                    style = MaterialTheme.typography.labelLarge)
                Text(step.title(lesson), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(if (done) "Concluída · ${step.xp} XP" else "+${step.xp} XP ao concluir",
                    style = MaterialTheme.typography.labelMedium)
            }
            if (chapter != null) LessonCard { LessonText(chapter.body) }
            LessonCard {
                ExamHeading(if (chapter != null) "Teste seu entendimento" else "Sua vez de resolver")
                LessonText(chapter?.check ?: requireNotNull(exercise).prompt)
                if (!done && !showAnswer) Text(
                    if (chapter != null) "Tente responder com suas palavras antes de conferir."
                    else "Resolva primeiro e depois compare seu raciocínio com a resolução.",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (exercise != null) NotiTextButton(onClick = { showHint = !showHint }) {
                        Text(if (showHint) "Ocultar dica" else "Ver dica")
                    }
                    NotiOutlinedButton(onClick = { showAnswer = !showAnswer }) {
                        Text(if (chapter != null) {
                            if (showAnswer) "Ocultar resposta" else "Conferir resposta"
                        } else {
                            if (showAnswer) "Ocultar resolução" else "Conferir resolução"
                        })
                    }
                }
                if (showHint && exercise != null) LessonCard(colors.surfaceContainerHighest) {
                    Text("Dica", style = MaterialTheme.typography.titleSmall)
                    LessonText(exercise.hint)
                }
                if (showAnswer) LessonCard(accent.container, accent.ink) {
                    Text(if (chapter != null) "Resposta esperada" else "Resolução", style = MaterialTheme.typography.titleSmall)
                    LessonText(chapter?.answer ?: requireNotNull(exercise).solution)
                }
            }
            AnimatedVisibility(visible = done && priorLevel > 0, modifier = Modifier.bringIntoViewRequester(rewardPosition)) {
                Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                    shape = RoundedCornerShape(20.dp), color = colors.tertiaryContainer,
                    contentColor = colors.onTertiaryContainer) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExamEmblem(Icons.Outlined.Star, colors.surface.copy(alpha = .7f), colors.onTertiaryContainer)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Etapa concluída · +${step.xp} XP", style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold)
                            if (overview.level > priorLevel) Text("Nível ${overview.level} alcançado!", style = MaterialTheme.typography.bodyMedium)
                            if (priorToday < StudyOverview.DailyGoal && overview.today >= StudyOverview.DailyGoal)
                                Text("Meta de hoje concluída!", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            LaunchedEffect(done) {
                if (done && priorLevel > 0) {
                    withFrameNanos { }
                    rewardPosition.bringIntoView()
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (done) {
                    NotiButton(onClick = if (hasNext) onNext else onPath) {
                        Text(if (hasNext) "Próxima etapa" else "Voltar à trilha")
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(start = 8.dp).size(18.dp))
                    }
                    if (hasNext) NotiTextButton(onClick = onPath) { Text("Voltar à trilha") }
                } else NotiButton(enabled = enabled && showAnswer, onClick = {
                    priorLevel = overview.level
                    priorToday = overview.today
                    onComplete()
                }) {
                    Text(if (chapter != null) "Concluir etapa" else "Concluir exercício")
                }
            }
        }
    }
}

@Composable
private fun LessonCard(
    color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(24.dp), color = color, contentColor = contentColor) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun LessonText(body: String) = StudyText(body, style = MaterialTheme.typography.bodyLarge)

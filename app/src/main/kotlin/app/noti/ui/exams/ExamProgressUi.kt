package app.noti.ui.exams

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.noti.exams.StudyOverview
import app.noti.exams.normalizeTopic
import app.noti.ui.brand.DotiMascot

internal data class ExamAccent(val container: Color, val ink: Color, val icon: ImageVector)

@Composable
internal fun subjectAccent(subject: String): ExamAccent {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3
    val key = normalizeTopic(subject)
    return when {
        key.contains("matemat") || key.contains("calculo") || key.contains("algebra") || key.contains("geometr") -> ExamAccent(
            if (dark) Color(0xFF463466) else Color(0xFFECE1FF),
            if (dark) Color(0xFFE3CBFF) else Color(0xFF5C378B),
            Icons.Outlined.Functions,
        )
        key.contains("fisic") || key.contains("mecanic") || key.contains("eletric") -> ExamAccent(
            if (dark) Color(0xFF233F65) else Color(0xFFDFEDFF),
            if (dark) Color(0xFFBEDBFF) else Color(0xFF234E87),
            Icons.Outlined.Bolt,
        )
        key.contains("quimic") -> ExamAccent(
            if (dark) Color(0xFF224B46) else Color(0xFFD8F4EB),
            if (dark) Color(0xFFABEDDA) else Color(0xFF155C50),
            Icons.Outlined.Science,
        )
        key.contains("biolog") || key.contains("ecolog") || key.contains("genet") -> ExamAccent(
            if (dark) Color(0xFF244930) else Color(0xFFDCF2E0),
            if (dark) Color(0xFFB3E8BD) else Color(0xFF1E5D33),
            Icons.Outlined.Biotech,
        )
        key.contains("histor") || key.contains("filosof") || key.contains("sociolog") -> ExamAccent(
            if (dark) Color(0xFF543C26) else Color(0xFFFBE9D5),
            if (dark) Color(0xFFF5CEA7) else Color(0xFF7D4816),
            Icons.Outlined.AccountBalance,
        )
        key.contains("geograf") -> ExamAccent(
            if (dark) Color(0xFF1E4852) else Color(0xFFD9F0F6),
            if (dark) Color(0xFFAEE2EE) else Color(0xFF155564),
            Icons.Outlined.Public,
        )
        key.contains("portug") || key.contains("literat") || key.contains("lingu") || key.contains("ingles") || key.contains("espanhol") || key.contains("redac") -> ExamAccent(
            if (dark) Color(0xFF593142) else Color(0xFFFCE2EB),
            if (dark) Color(0xFFF7BDD2) else Color(0xFF832D4D),
            Icons.Outlined.MenuBook,
        )
        else -> {
            val palette = listOf(
                Triple(Color(0xFFECE1FF), Color(0xFF463466), Color(0xFF5C378B) to Color(0xFFE3CBFF)),
                Triple(Color(0xFFDFEDFF), Color(0xFF233F65), Color(0xFF234E87) to Color(0xFFBEDBFF)),
                Triple(Color(0xFFD8F4EB), Color(0xFF224B46), Color(0xFF155C50) to Color(0xFFABEDDA)),
                Triple(Color(0xFFFBE9D5), Color(0xFF543C26), Color(0xFF7D4816) to Color(0xFFF5CEA7)),
                Triple(Color(0xFFFCE2EB), Color(0xFF593142), Color(0xFF832D4D) to Color(0xFFF7BDD2)),
            )
            val pick = palette[Math.floorMod(key.hashCode(), palette.size)]
            ExamAccent(
                if (dark) pick.second else pick.first,
                if (dark) pick.third.second else pick.third.first,
                Icons.Outlined.School,
            )
        }
    }
}

@Composable
internal fun ExamEmblem(icon: ImageVector, container: Color, ink: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp).background(container, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = ink)
    }
}

@Composable
internal fun ExamProgressRail(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(clamped, label = "study progress")
    Box(modifier.height(6.dp).clip(CircleShape).background(track).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
    }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(CircleShape).background(color))
    }
}

@Composable
internal fun ExamHomeHero(overview: StudyOverview, title: String, detail: String, action: String, onAction: () -> Unit) {
    val white = Color(0xFFFCF8FF)
    val mint = Color(0xFFB8F2DB)
    val muted = Color(0xFFE2D4F6)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(29.dp))
        .background(Brush.linearGradient(listOf(Color(0xFF2B194D), Color(0xFF65458E))))) {
        val showMascot = maxWidth >= 310.dp && LocalDensity.current.fontScale < 1.4f
        val mascotSize = if (maxWidth >= 600.dp) 136.dp else 98.dp
        val titleStyle = if (maxWidth >= 600.dp) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium
        if (showMascot) Canvas(Modifier.matchParentSize()) {
            val center = Offset(size.width * .87f, size.height * .37f)
            listOf(.18f, .25f, .33f).forEach { scale ->
                drawCircle(white.copy(alpha = .12f), radius = size.width * scale, center = center, style = Stroke(1.dp.toPx()))
            }
            drawCircle(mint.copy(alpha = .8f), radius = 3.dp.toPx(),
                center = Offset(size.width * .75f, size.height * .12f))
            drawCircle(muted.copy(alpha = .7f), radius = 5.dp.toPx(),
                center = Offset(size.width * .95f, size.height * .55f))
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.clip(CircleShape).background(white.copy(alpha = .14f)).padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(Icons.Outlined.Star, null, Modifier.size(15.dp), tint = mint)
                        Text("Nível ${overview.level} · ${overview.xp} XP", color = white, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(title, color = white, style = titleStyle, fontWeight = FontWeight.ExtraBold)
                    Text(detail, color = muted, style = MaterialTheme.typography.bodyMedium)
                }
                if (showMascot) DotiMascot(Modifier.size(mascotSize))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ExamProgressRail(overview.levelProgress, mint, white.copy(alpha = .18f), Modifier.fillMaxWidth())
                Text("${StudyOverview.LevelXp - (overview.xp % StudyOverview.LevelXp)} XP para o nível ${overview.level + 1}",
                    color = muted, style = MaterialTheme.typography.labelMedium)
            }
            Button(onClick = onAction, shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = white, contentColor = Color(0xFF40285F))) {
                Text(action, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun ExamDailyGoal(today: Int) {
    val target = StudyOverview.DailyGoal
    val achieved = today >= target
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ExamEmblem(if (achieved) Icons.Outlined.Check else Icons.Outlined.TrackChanges,
                    colors.surface.copy(alpha = .65f), colors.onTertiaryContainer)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (achieved) "Meta de hoje concluída" else "Meta de hoje", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold)
                    Text(if (achieved) "Você concluiu $target atividades de estudo."
                    else "Conclua etapas ou avalie suas respostas no quadro.", style = MaterialTheme.typography.bodySmall)
                }
                Text("${today.coerceAtMost(target)}/$target", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            }
            Row(Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(today.coerceIn(0, target).toFloat(), 0f..target.toFloat())
            }, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(target) { index ->
                    Box(Modifier.weight(1f).height(6.dp).clip(CircleShape)
                        .background(if (index < today) colors.tertiary else colors.onTertiaryContainer.copy(alpha = .15f)))
                }
            }
        }
    }
}

@Composable
internal fun ExamSubjectCard(subjectLabel: String, tried: Int, count: Int, modifier: Modifier = Modifier, open: () -> Unit) {
    val accent = subjectAccent(subjectLabel)
    Surface(onClick = open, modifier = modifier, shape = RoundedCornerShape(23.dp), color = accent.container,
        contentColor = accent.ink) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExamEmblem(accent.icon, accent.ink.copy(alpha = .1f), accent.ink)
                Text(subjectLabel, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(21.dp))
            }
            Text(if (count == 0) "Nenhuma questão importada" else "$tried de $count ${if (count == 1) "questão praticada" else "questões praticadas"}",
                style = MaterialTheme.typography.bodySmall)
            if (count > 0) ExamProgressRail(tried.toFloat() / count, accent.ink, accent.ink.copy(alpha = .14f), Modifier.fillMaxWidth())
        }
    }
}

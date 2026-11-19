package app.noti.ui.fuvest

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.TrackChanges
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
import app.noti.fuvest.FuvestOverview
import app.noti.fuvest.FuvestSubject
import app.noti.ui.brand.DotiMascot

internal data class FuvestAccent(val container: Color, val ink: Color, val icon: ImageVector)

@Composable
internal fun FuvestSubject.accent(): FuvestAccent {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.3
    return when (this) {
        FuvestSubject.Math -> FuvestAccent(
            if (dark) Color(0xFF463466) else Color(0xFFECE1FF),
            if (dark) Color(0xFFE3CBFF) else Color(0xFF5C378B), Icons.Outlined.Functions,
        )
        FuvestSubject.Physics -> FuvestAccent(
            if (dark) Color(0xFF233F65) else Color(0xFFDFEDFF),
            if (dark) Color(0xFFBEDBFF) else Color(0xFF234E87), Icons.Outlined.Bolt,
        )
        FuvestSubject.Chemistry -> FuvestAccent(
            if (dark) Color(0xFF224B46) else Color(0xFFD8F4EB),
            if (dark) Color(0xFFABEDDA) else Color(0xFF155C50), Icons.Outlined.Science,
        )
    }
}

@Composable
internal fun FuvestEmblem(icon: ImageVector, container: Color, ink: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp).background(container, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(24.dp), tint = ink)
    }
}

@Composable
internal fun FuvestProgressRail(fraction: Float, color: Color, track: Color, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(clamped, label = "study progress")
    Box(modifier.height(6.dp).clip(CircleShape).background(track).semantics {
        progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
    }) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(animated).clip(CircleShape).background(color))
    }
}

@Composable
internal fun FuvestHomeHero(overview: FuvestOverview, title: String, detail: String, action: String, onAction: () -> Unit) {
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
                FuvestProgressRail(overview.levelProgress, mint, white.copy(alpha = .18f), Modifier.fillMaxWidth())
                Text("${FuvestOverview.LevelXp - (overview.xp % FuvestOverview.LevelXp)} XP para o nível ${overview.level + 1}",
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
internal fun FuvestDailyGoal(today: Int) {
    val target = FuvestOverview.DailyGoal
    val achieved = today >= target
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = colors.tertiaryContainer, contentColor = colors.onTertiaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FuvestEmblem(if (achieved) Icons.Outlined.Check else Icons.Outlined.TrackChanges,
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
internal fun FuvestSubjectCard(subject: FuvestSubject, tried: Int, count: Int, modifier: Modifier = Modifier, open: () -> Unit) {
    val accent = subject.accent()
    Surface(onClick = open, modifier = modifier, shape = RoundedCornerShape(23.dp), color = accent.container,
        contentColor = accent.ink) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FuvestEmblem(accent.icon, accent.ink.copy(alpha = .1f), accent.ink)
                Text(subject.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.size(21.dp))
            }
            Text(if (count == 0) "Nenhuma questão importada" else "$tried de $count ${if (count == 1) "questão praticada" else "questões praticadas"}",
                style = MaterialTheme.typography.bodySmall)
            if (count > 0) FuvestProgressRail(tried.toFloat() / count, accent.ink, accent.ink.copy(alpha = .14f), Modifier.fillMaxWidth())
        }
    }
}

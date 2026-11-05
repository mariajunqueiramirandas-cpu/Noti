package app.noti.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noti.R
import app.noti.ui.theme.NotiBrand
import app.noti.ui.theme.NotiFont

/** Doti is decorative beside readable copy, announced only when it is the subject. */
@Composable
fun DotiMascot(modifier: Modifier = Modifier, description: String? = null) {
    Image(painterResource(R.drawable.doti), description, modifier)
}

@Composable
fun NotiWordmark(modifier: Modifier = Modifier, showMascot: Boolean = true) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        if (showMascot) DotiMascot(Modifier.size(38.dp))
        Text(buildAnnotatedString {
            append("noti")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
        }, fontFamily = NotiFont, fontSize = 32.sp, lineHeight = 38.sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A quiet welcome strip. It lives in the scrollable library rather than above its controls. */
@Composable
fun LibraryWelcome(compact: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
        .background(colors.primaryContainer.copy(alpha = 0.5f))
        .padding(horizontal = if (compact) 18.dp else 28.dp, vertical = if (compact) 12.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(NotiBrand.Tagline, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                color = colors.onSurface)
            Text("A page for whatever pops into your head.", style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        DotiMascot(Modifier.size(if (compact) 72.dp else 108.dp))
    }
}

@Composable
fun FriendlyNote(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
        .background(colors.secondaryContainer.copy(alpha = 0.45f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        DotiMascot(Modifier.size(48.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

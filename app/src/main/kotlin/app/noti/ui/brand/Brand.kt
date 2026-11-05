package app.noti.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noti.R
import app.noti.ui.common.NotiGroup
import app.noti.ui.theme.NotiFont

/** Doti is decorative beside readable copy, announced only when it is the subject. */
@Composable
fun DotiMascot(modifier: Modifier = Modifier, description: String? = null) {
    Image(painterResource(R.drawable.doti), description, modifier)
}

/** The lowercase wordmark; its plum dot is the only colored letterform in the app. */
@Composable
fun NotiWordmark(modifier: Modifier = Modifier, showMascot: Boolean = true, size: Int = 28) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showMascot) DotiMascot(Modifier.size((size + 4).dp))
        Text(buildAnnotatedString {
            append("noti")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(".") }
        }, fontFamily = NotiFont, fontSize = size.sp, lineHeight = (size + 6).sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.04f * size).sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun FriendlyNote(title: String, body: String, modifier: Modifier = Modifier) {
    NotiGroup(modifier) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DotiMascot(Modifier.size(44.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

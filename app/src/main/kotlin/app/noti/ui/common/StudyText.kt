package app.noti.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.noti.R
import app.noti.study.MathText
import app.noti.study.mathSpans
import app.noti.study.readableStudyText

/** Use the same typesetting as the study board for prose with inline or display formulas. */
@Composable
internal fun StudyText(body: String, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val source = body.trim()
    if (source.isEmpty()) return
    val rich = remember(source) { "**" in source || mathSpans(source).isNotEmpty() }
    val accessibleText = remember(source) { readableStudyText(source) }
    if (!rich) {
        Text(accessibleText, modifier, style = style)
        return
    }
    val density = LocalDensity.current
    val resources = LocalContext.current.resources
    val color = style.color.takeOrElse { LocalContentColor.current }
    val size = if (style.fontSize.isSp) style.fontSize else 16.sp
    val fontPx = with(density) { size.toPx() }
    val lineSpacing = if (style.lineHeight.isSp) with(density) { style.lineHeight.toPx() } / fontPx else 1.35f
    val proseFont = when (style.fontWeight) {
        FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black -> R.font.nunito_bold
        FontWeight.SemiBold -> R.font.nunito_semibold
        else -> R.font.nunito_regular
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.toPx() }
        val block = remember(source, width, fontPx, lineSpacing, color, resources, proseFont) {
            MathText.layout(source, width, MathText.Style(fontPx, color.toArgb(),
                resources.getFont(proseFont), resources.getFont(R.font.nunito_bold), lineSpacing))
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { block.height.toDp() })
            .semantics { text = AnnotatedString(accessibleText) }) {
            drawIntoCanvas { block.draw(it.nativeCanvas, 0f, 0f) }
        }
    }
}

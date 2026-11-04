package app.noti.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.noti.data.Paper
import app.noti.data.PaperTemplate

/**
 * Draws the paper: background color plus its lines, grid or dots.
 *
 * The pattern is anchored at the top-left corner with a fixed spacing, exactly like the
 * engine's ink (surface coordinates from the top-left). So when the writing area is
 * resized (page panel, full screen, rotation) paper and ink stay lined up.
 */
@Composable
fun PaperBackground(paper: Paper, modifier: Modifier = Modifier, spacing: Dp = 32.dp) {
    Canvas(modifier) { drawPaper(paper, spacing.toPx(), 1.dp.toPx()) }
}

/** A small preview of the paper, for thumbnails and pickers. */
@Composable
fun PaperPreview(paper: Paper, modifier: Modifier = Modifier) {
    PaperBackground(paper, modifier, spacing = 9.dp)
}

private fun DrawScope.drawPaper(paper: Paper, step: Float, stroke: Float) {
    drawRect(Color(paper.color.argb))
    val line = if (paper.color.isDark) Color(0x33FFFFFF) else Color(0x2E3A5A8C)
    val w = size.width
    val h = size.height
    when (paper.template) {
        PaperTemplate.Blank -> Unit
        PaperTemplate.Lined -> {
            var y = step * 2
            while (y < h) {
                drawLine(line, Offset(0f, y), Offset(w, y), stroke)
                y += step
            }
        }
        PaperTemplate.Grid -> {
            var y = step
            while (y < h) {
                drawLine(line, Offset(0f, y), Offset(w, y), stroke)
                y += step
            }
            var x = step
            while (x < w) {
                drawLine(line, Offset(x, 0f), Offset(x, h), stroke)
                x += step
            }
        }
        PaperTemplate.Dots -> {
            val dot = if (paper.color.isDark) Color(0x59FFFFFF) else Color(0x593A4A6A)
            val r = stroke * 1.4f
            var y = step
            while (y < h) {
                var x = step
                while (x < w) {
                    drawCircle(dot, r, Offset(x, y))
                    x += step
                }
                y += step
            }
        }
    }
}

/** Page-space pattern: 1000 units across, shared with stored ink and PDF export. */
internal fun DrawScope.drawPaperPage(paper: Paper, width: Float, height: Float, scale: Float) {
    drawRect(Color(paper.color.argb), size = androidx.compose.ui.geometry.Size(width, height))
    val line = if (paper.color.isDark) Color(0x33FFFFFF) else Color(0x2E3A5A8C)
    val step = 40f * scale
    val stroke = scale.coerceAtLeast(0.5f)
    if (step <= 0) return
    when (paper.template) {
        PaperTemplate.Blank -> Unit
        PaperTemplate.Lined, PaperTemplate.Grid -> {
            var y = if (paper.template == PaperTemplate.Lined) step * 2 else step
            while (y < height) { drawLine(line, Offset(0f, y), Offset(width, y), stroke); y += step }
            if (paper.template == PaperTemplate.Grid) {
                var x = step
                while (x < width) { drawLine(line, Offset(x, 0f), Offset(x, height), stroke); x += step }
            }
        }
        PaperTemplate.Dots -> {
            var y = step
            while (y < height) {
                var x = step
                while (x < width) { drawCircle(line, stroke * 1.4f, Offset(x, y)); x += step }
                y += step
            }
        }
    }
}

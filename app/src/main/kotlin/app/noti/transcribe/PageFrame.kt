package app.noti.transcribe

/**
 * Where a page's worth of writing sits on its engine page: page-frame units are
 * (engine units - origin) × [scale]. Text is laid out in the page frame ([TextInk] sizes
 * and wraps for a page 1000 wide), then placed back. Identity on ordinary pages.
 */
class PageFrame(val originX: Float, val originY: Float, val scale: Float) {
    fun toPage(s: SelectedStroke): SelectedStroke {
        if (this === Identity) return s
        val p = FloatArray(s.points.size) { i -> ((s.points[i] - if (i % 2 == 0) originX else originY) * scale) }
        return SelectedStroke(s.id, s.page, s.color, p)
    }

    fun toEngine(strokes: TextInk.Strokes): TextInk.Strokes {
        if (this === Identity) return strokes
        val k = 1f / scale
        val c = strokes.capsules
        val out = FloatArray(c.size) { i ->
            when (i % 3) {
                0 -> originX + c[i] * k
                1 -> originY + c[i] * k
                else -> c[i] * k
            }
        }
        return TextInk.Strokes(out, strokes.sizes)
    }

    companion object {
        val Identity = PageFrame(0f, 0f, 1f)

        /**
         * On the Endless canvas, a page is one screen wide ([span] of them across the sheet),
         * starting just left of the writing: text keeps the size and wrap it would have on a page.
         */
        fun canvas(strokes: List<SelectedStroke>, span: Float): PageFrame {
            if (strokes.isEmpty()) return Identity
            return PageFrame(strokes.minOf { it.x0 } - TextInk.Margin / span, strokes.minOf { it.y0 }, span)
        }
    }
}

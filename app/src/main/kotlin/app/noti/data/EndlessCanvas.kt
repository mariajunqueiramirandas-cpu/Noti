package app.noti.data

import kotlin.math.max

/**
 * The Endless paper: one free canvas instead of a stack of pages.
 *
 * The canvas is a single engine page (page 0), a sheet [SPAN] "screens" wide whose 1000
 * page units span the whole width, so ink can go anywhere across it. One screen, a
 * [BASE_DP] wide column, is 1000 / [SPAN] units; paper lines keep the spacing they have on
 * a page that width. The sheet's width is fixed (ink coordinates scale with it), but it
 * grows downwards for as long as you keep going, so it never runs out.
 *
 * You start at home: the top of the middle column, [HOME_Y] screens below the top edge,
 * with room in every direction.
 */
object EndlessCanvas {
    /** Sheet width in screens (columns of [BASE_DP]). */
    const val SPAN = 16f
    /** One screen of canvas at 100% zoom, in dp. */
    const val BASE_DP = 1000f
    /** Page units per screen. */
    const val BASE_UNITS = 1000f / SPAN
    /** Home row: this many screens below the sheet's top edge. */
    const val HOME_Y = 4f
    /** The sheet is at least this tall (height / width): as tall as it is wide. */
    const val MIN_ASPECT = 1f
    /** The sheet always reaches this many screens past the lowest ink and the view. */
    const val GROW = 8f

    /** Where home's top-left corner is, in page units. */
    const val HOME_LEFT_UNITS = 500f - BASE_UNITS / 2
    const val HOME_TOP_UNITS = HOME_Y * BASE_UNITS

    /** Sheet height / width with room below [strokes] (canonical canvas ink). */
    fun aspect(strokes: List<InkArchive.Stroke>): Float {
        var bottom = 0f
        for (s in strokes) {
            val c = s.capsules
            for (i in c.indices step 3) bottom = max(bottom, c[i + 1] + c[i + 2])
        }
        return max(MIN_ASPECT, (bottom + GROW * BASE_UNITS) / 1000f)
    }

    /** Proportions of the A4-shaped pages Endless notebooks used before the canvas. */
    private const val LEGACY_ASPECT = 1.4142f
    /** Room left between two former pages, in former page units (one ruled line). */
    private const val LEGACY_GAP = 40f

    /**
     * Moves ink written on the former A4-shaped Endless pages onto the canvas: page 0
     * becomes the home column and later pages continue below it. A former page becomes one
     * screen of canvas, about the width it had on a tablet in portrait. Lossless apart from
     * float rounding (dividing by [SPAN] is exact).
     */
    fun fromPages(bytes: ByteArray?): ByteArray? {
        if (bytes == null) return null
        val k = 1f / SPAN
        val strokes = InkArchive.decode(bytes).map { s ->
            val top = s.page * (LEGACY_ASPECT * 1000f + LEGACY_GAP)
            val c = s.capsules
            val out = FloatArray(c.size)
            for (i in c.indices step 3) {
                out[i] = HOME_LEFT_UNITS + c[i] * k
                out[i + 1] = HOME_TOP_UNITS + (top + c[i + 1]) * k
                out[i + 2] = c[i + 2] * k
            }
            InkArchive.Stroke(0, s.color, out)
        }
        return InkArchive.encode(strokes)
    }
}

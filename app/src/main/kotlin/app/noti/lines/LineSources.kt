package app.noti.lines

import app.noti.data.EndlessCanvas
import app.noti.data.Paper
import app.noti.data.PaperSize
import app.noti.data.PaperTemplate
import app.noti.pdf.FormField
import app.noti.pdf.PdfDocument
import app.noti.pdf.RegionImage
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Where the writing lines of a notebook's pages come from. Main thread. */
interface LineSource {
    /** Millimetres of paper per page unit on [page]. */
    fun mmPerUnit(page: Int): Float

    /** The lines a loop (x y pairs, page units) means; may be empty. */
    suspend fun inLoop(page: Int, loop: FloatArray): List<WriteLine>

    /** The line a tap at ([x], [y]) means, or null. */
    suspend fun atPoint(page: Int, x: Float, y: Float): WriteLine?

    /** Every line of [page] meant for writing, for filling the page in as a form. */
    suspend fun wholePage(page: Int): List<WriteLine>

    /** The printed label of each line (`Name:` gives "Name"), where the source can read it. */
    suspend fun labels(page: Int, lines: List<WriteLine>): List<String?> = lines.map { null }
}

/**
 * A PDF's lines, found in its pixels ([LineFinder]) around the loop, plus its real form
 * fields where it has them (Android 15+), which win over lines found inside them.
 */
class PdfLines(private val pdf: PdfDocument) : LineSource {
    override fun mmPerUnit(page: Int) = pdf.mmPerUnit(page)
    private fun units(page: Int, mm: Float) = mm / mmPerUnit(page)
    private fun height(page: Int) = 1000f * pdf.aspect(page)

    override suspend fun inLoop(page: Int, loop: FloatArray): List<WriteLine> {
        val b = LinePicker.bounds(loop)
        val found = around(page, b[0], b[1], b[2], b[3])
        return LinePicker.inLoop(found, loop, units(page, 12f))
    }

    override suspend fun atPoint(page: Int, x: Float, y: Float): WriteLine? {
        // A band across the page around the tap: lines are usually long, and labels sit left.
        val found = around(page, x - units(page, 90f), y - units(page, 20f), x + units(page, 90f), y + units(page, 8f))
        return LinePicker.atPoint(found, x, y, units(page, 2.5f))
    }

    override suspend fun wholePage(page: Int): List<WriteLine> = around(page, 0f, 0f, 1000f, height(page), margins = false)

    override suspend fun labels(page: Int, lines: List<WriteLine>): List<String?> {
        val boxes = lines.map { it.label }
        val wanted = boxes.filterNotNull()
        if (wanted.isEmpty()) return lines.map { null }
        val texts = suspendCancellableCoroutine { c -> pdf.textIn(page, wanted) { c.resume(it) } }
        var at = 0
        return boxes.map { b -> if (b == null) null else texts.getOrNull(at++)?.trimEnd(':', '.', ' ', '_') }
    }

    /** Lines in a region, with room around it for labels to the left and the line above. */
    private suspend fun around(page: Int, l: Float, t: Float, r: Float, b: Float, margins: Boolean = true): List<WriteLine> {
        val left = (if (margins) l - units(page, 45f) else l).coerceIn(0f, 1000f)
        val top = (if (margins) t - units(page, 30f) else t).coerceIn(0f, height(page))
        val right = (if (margins) r + units(page, 12f) else r).coerceIn(0f, 1000f)
        val bottom = (if (margins) b + units(page, 12f) else b).coerceIn(0f, height(page))
        if (right - left < 1f || bottom - top < 1f) return emptyList()
        val image = suspendCancellableCoroutine<RegionImage?> { c ->
            pdf.renderRegion(page, left, top, right, bottom, LineFinder.PX_PER_MM) { c.resume(it) }
        } ?: return emptyList()
        val found = withContext(Dispatchers.Default) { LineFinder(image.pxPerMm).find(image.raster).map { toUnits(it, image) } }
        val fields = suspendCancellableCoroutine<List<FormField>> { c -> pdf.formFields(page) { c.resume(it) } }
            .filter { it.right > left && it.left < right && it.bottom > top && it.top < bottom }
        if (fields.isEmpty()) return found
        val fieldLines = fields.flatMap { fieldLines(page, it) }
        val outside = found.filter { line -> fields.none { f -> line.x0 < f.right && line.x1 > f.left && line.y >= f.top - 1f && line.y <= f.bottom + 2f } }
        return LinePicker.readingOrder(outside + fieldLines)
    }

    /** A form field's writing lines: one along its bottom, or several down a multi-line field. */
    private fun fieldLines(page: Int, f: FormField): List<WriteLine> {
        val h = f.bottom - f.top
        val inset = units(page, 0.8f)
        if (!f.multiLine) return listOf(WriteLine(f.left + inset, f.right - inset, f.bottom - h * 0.2f, room = h * 0.75f,
            kind = LineKind.Cell))
        val n = max(1, (h / units(page, 8f)).roundToInt())
        val gap = h / n
        return List(n) { k -> WriteLine(f.left + inset, f.right - inset, f.top + gap * (k + 1) - gap * 0.2f, room = gap * 0.8f, kind = LineKind.Box) }
    }

    private fun toUnits(f: FoundLine, image: RegionImage): WriteLine {
        val u = image.unitsPerPx
        fun x(v: Float) = image.left + v * u
        fun y(v: Float) = image.top + v * u
        return WriteLine(x(f.x0), x(f.x1), y(f.y), f.slope, f.room * u, x(f.start), f.kind, f.sure,
            f.cells?.let { c -> FloatArray(c.size) { x(c[it]) } },
            f.label?.let { floatArrayOf(x(it[0]), y(it[1]), x(it[2]), y(it[3])) })
    }
}

/**
 * Noti's own paper: Lined, Grid and Dots pages have their rows at known places, so no
 * finding is needed (and on Blank paper there are none). On the Endless canvas a "page"
 * is one screen of the sheet, so lines are as close as they look.
 */
class PaperLines(private val paper: Paper, private val canvas: Boolean) : LineSource {
    private val step = if (canvas) 40f / EndlessCanvas.SPAN else 40f
    private val skip = if (paper.template == PaperTemplate.Lined) 2 else 1
    private val widthMm = if (paper.size == PaperSize.Letter) 215.9f else 210f
    override fun mmPerUnit(page: Int) = if (canvas) widthMm / EndlessCanvas.BASE_UNITS else widthMm / 1000f
    private val ruled get() = paper.template != PaperTemplate.Blank

    override suspend fun inLoop(page: Int, loop: FloatArray): List<WriteLine> =
        if (!ruled) emptyList() else LinePicker.paperLines(loop, step, skip, minLength = 10f / mmPerUnit(page))

    override suspend fun atPoint(page: Int, x: Float, y: Float): WriteLine? {
        if (!ruled) return null
        // To the page's edge, or about a screen on the canvas.
        val right = if (canvas) min(1000f, x + EndlessCanvas.BASE_UNITS * 0.9f) else 1000f - 24f
        return LinePicker.paperLineAt(x, y, step, skip, right)
    }

    override suspend fun wholePage(page: Int): List<WriteLine> = emptyList()
}

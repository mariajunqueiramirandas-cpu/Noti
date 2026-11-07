package app.noti.ui.editor

import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.noti.R
import app.noti.data.SettingsStore
import app.noti.ink.InkLayer
import app.noti.lines.BlockMatch
import app.noti.lines.BlockMatcher
import app.noti.lines.Laid
import app.noti.lines.LineLayout
import app.noti.lines.LinePicker
import app.noti.lines.LineSource
import app.noti.lines.Overflow
import app.noti.lines.TextBlock
import app.noti.lines.TextBlockStore
import app.noti.lines.TypeStyle
import app.noti.lines.WriteLine
import app.noti.pdf.PagePlacement
import app.noti.transcribe.InkLines
import app.noti.transcribe.TextInk
import kotlin.coroutines.resume
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One place to type: the lines found there (the candidates; tap one to leave it out or
 * bring it back) and the text going on them. [existing] is a block already on the page
 * here, being edited: its ink is hidden while the field is open.
 */
@Stable
internal class TypeField(
    val candidates: List<WriteLine>,
    included: Set<Int>,
    text: String,
    val label: String? = null,
    val existing: BlockMatch? = null,
) {
    var included by mutableStateOf(included)
    var text by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    /** The existing block's ink is hidden (it comes back when the field is placed or cancelled). */
    var hidden = false
    val original = text
    val lines get() = candidates.filterIndexed { i, _ -> i in included }

    fun toggle(index: Int) { included = if (index in included) included - index else included + index }
}

/**
 * Typing on lines on one page: a single place (from a loop or tap) or, in form mode, every
 * blank of the page, Tab moving between them. Main thread.
 */
@Stable
internal class TypeSession(
    val page: Int,
    val fields: List<TypeField>,
    val mmPerUnit: Float,
    val form: Boolean,
    color: FloatArray,
    style: TypeStyle,
    /** For a loop that found no lines: a line along it, offered with "Type here anyway". */
    val fallback: WriteLine? = null,
) {
    var current by mutableIntStateOf(0)
    var style by mutableStateOf(style)
    var color by mutableStateOf(color)
    var busy by mutableStateOf(false)
    var penPad by mutableStateOf(false)
    val field get() = fields[current]
    /** Laid out on the current field, for the preview and the panel (set by [TypeTool]). */
    var laid by mutableStateOf<Laid?>(null)
}

/** Shared between the editor's canvas (which draws the session) and [TypeTool] (which runs it). */
@Stable
internal class TypeState {
    var session by mutableStateOf<TypeSession?>(null)
    /** Bumped to ask for the whole page as a form ("Fill this page"). */
    var fillRequest by mutableIntStateOf(0)
    var fillPage = 0
    internal var regular: Typeface? = null
    internal var bold: Typeface? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG)

    fun requestFill(page: Int) { fillPage = page; fillRequest++ }

    /** The session on the page: lines found, the text so far, the caret. Read in the draw phase. */
    fun draw(scope: DrawScope, placement: PagePlacement) {
        val s = session ?: return
        val p = placement.visiblePages.firstOrNull { it.page == s.page } ?: return
        val k = p.width / 1000f
        fun x(u: Float) = p.left + u * k
        fun y(u: Float) = p.top + u * k
        with(scope) {
            val plum = Color(0xFF5C4A87)
            val dp = density
            // Form mode: every field softly outlined, the current one stronger.
            if (s.form) s.fields.forEachIndexed { i, f ->
                val ls = f.lines.ifEmpty { f.candidates }
                if (ls.isEmpty()) return@forEachIndexed
                val l = ls.minOf { it.x0 } - 1.2f / s.mmPerUnit; val r = ls.maxOf { it.x1 } + 1.2f / s.mmPerUnit
                val t = ls.minOf { it.y - min(it.room, 12f / s.mmPerUnit) }; val b = ls.maxOf { it.y } + 1.5f / s.mmPerUnit
                val current = i == s.current
                drawRoundRect(plum.copy(alpha = if (current) 0.10f else 0.05f), Offset(x(l), y(t)),
                    androidx.compose.ui.geometry.Size((r - l) * k, (b - t) * k), androidx.compose.ui.geometry.CornerRadius(6 * dp))
                if (current) drawRoundRect(plum.copy(alpha = 0.55f), Offset(x(l), y(t)),
                    androidx.compose.ui.geometry.Size((r - l) * k, (b - t) * k), androidx.compose.ui.geometry.CornerRadius(6 * dp),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f * dp))
            }
            val f = s.field
            var number = 0
            f.candidates.forEachIndexed { i, line ->
                val on = i in f.included
                val y0 = y(line.yAt(line.x0)); val y1 = y(line.yAt(line.x1))
                if (on) {
                    drawLine(plum.copy(alpha = 0.32f), Offset(x(line.x0), y0), Offset(x(line.x1), y1), 5f * dp, StrokeCap.Round)
                    number++
                    val c = Offset(x(line.x0) - 9f * dp, y0)
                    drawCircle(plum, 7.5f * dp, c)
                    drawIntoCanvas { canvas ->
                        paint.typeface = bold ?: Typeface.DEFAULT_BOLD
                        paint.textSize = 10f * dp; paint.color = 0xFFFBFAF8.toInt(); paint.textAlign = Paint.Align.CENTER; paint.alpha = 255
                        canvas.nativeCanvas.drawText("$number", c.x, c.y + 3.5f * dp, paint)
                    }
                } else {
                    drawLine(Color(0xFF8E8A96).copy(alpha = 0.55f), Offset(x(line.x0), y0), Offset(x(line.x1), y1), 3f * dp,
                        StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6f * dp, 5f * dp)))
                }
            }
            // The text as it will be placed, in its ink color; what doesn't fit, faded.
            val laid = s.laid ?: return
            val argb = colorArgb(s.color)
            drawIntoCanvas { canvas ->
                val c = canvas.nativeCanvas
                paint.typeface = (if (s.style.bold) bold else regular) ?: Typeface.DEFAULT
                paint.textSize = laid.em * k
                paint.textAlign = Paint.Align.LEFT
                for ((words, alpha) in listOf(laid.words to 255, laid.overflow to 90)) for (w in words) {
                    paint.color = argb; paint.alpha = alpha
                    val px = x(w.x); val py = y(w.y)
                    if (w.slope != 0f) {
                        val save = c.save()
                        c.rotate(Math.toDegrees(atan(w.slope.toDouble())).toFloat(), px, py)
                        c.drawText(w.text, px, py, paint)
                        c.restoreToCount(save)
                    } else c.drawText(w.text, px, py, paint)
                }
            }
            laid.caret?.let { caret ->
                val cx = x(caret[0]); val cy = y(caret[1])
                val h = laid.em * k
                drawLine(plum, Offset(cx, cy + h * 0.12f), Offset(cx + caret[2] * h * 0.9f, cy - h * 0.8f), 1.6f * dp)
            }
        }
    }

    companion object {
        /** Premultiplied RGBA (engine colors) to ARGB. */
        fun colorArgb(c: FloatArray): Int {
            val a = c[3].coerceIn(0f, 1f)
            fun ch(v: Float) = if (a <= 0f) 0 else ((v / a).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            return ((a * 255f + 0.5f).toInt() shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
        }

        /** ARGB to premultiplied RGBA, opaque (typed text is pen ink). */
        fun penColor(argb: Long): FloatArray =
            floatArrayOf(((argb shr 16) and 0xFF) / 255f, ((argb shr 8) and 0xFF) / 255f, (argb and 0xFF) / 255f, 1f)
    }
}

/**
 * The Type tool's half outside the engine (docs/TYPE_ON_LINES.md): each loop or tap the
 * engine reports (document state [10]) becomes a [TypeSession] on the lines there, or
 * reopens text typed there before; "Fill this page" makes one of the whole page. Placing
 * turns the text into ink (one undoable edit) and saves it as a [TextBlock].
 */
@Composable
internal fun TypeTool(
    state: TypeState,
    layer: InkLayer?,
    documentState: DoubleArray,
    source: LineSource?,
    blocks: TextBlockStore?,
    settings: SettingsStore,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val regular = remember { context.resources.getFont(R.font.nunito_regular) }
    val bold = remember { context.resources.getFont(R.font.nunito_bold) }
    SideEffect { state.regular = regular; state.bold = bold }
    val inkRegular = remember { TextInk(regular) }
    val inkBold = remember { TextInk(bold) }
    val serial = documentState.getOrNull(10)?.toLong() ?: 0L
    var handled by remember(layer) { mutableLongStateOf(serial) }
    var handledFill by remember { mutableIntStateOf(state.fillRequest) }
    val lock = remember { Mutex() }

    fun ink(style: TypeStyle) = if (style.bold) inkBold else inkRegular
    fun layoutFor(session: TypeSession, style: TypeStyle) = LineLayout(ink(style).measure(), session.mmPerUnit)
    fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

    // Keep the preview in step with the field, the text and the style.
    val session = state.session
    if (session != null) {
        val field = session.field
        val lines = field.lines
        val text = field.text.text
        val style = session.style
        val laid = remember(session, field, lines, text, style) { if (lines.isEmpty()) null else layoutFor(session, style).layout(lines, text, style) }
        SideEffect { session.laid = laid }
    }

    suspend fun replace(ids: IntArray, page: Int, color: FloatArray, strokes: TextInk.Strokes): Boolean {
        val current = layer ?: return false
        return suspendCancellableCoroutine { c -> current.replaceStrokes(ids, page, color, strokes.capsules, strokes.sizes) { c.resume(it) } }
    }

    /** Hides a reopened block's ink while it is edited. */
    suspend fun hide(s: TypeSession, field: TypeField) {
        val existing = field.existing ?: return
        if (field.hidden) return
        if (replace(existing.ids, s.page, existing.block.color, TextInk.Strokes(FloatArray(0), IntArray(0)))) field.hidden = true
    }

    /** Places [field]'s text as ink and saves its block. With [restore], puts a reopened block back as it was. */
    suspend fun place(s: TypeSession, field: TypeField, restore: Boolean = false): Boolean {
        val existing = field.existing
        if (existing != null && !field.hidden) {
            if (restore || field.text.text == field.original) return true   // never touched
            hide(s, field)
            if (!field.hidden) return false
        }
        val block = existing?.block
        val style = if (restore && block != null) block.style.copy(size = block.em) else s.style
        val text = if (restore) field.original else field.text.text
        val lines = if (restore && existing != null) existing.lines else field.lines
        val color = if (restore && block != null) block.color else s.color
        if (text.isBlank() || lines.isEmpty()) {
            if (block != null && !restore) blocks?.remove(block.id)
            if (restore) field.hidden = false
            return true
        }
        val laid = layoutFor(s, style).layout(lines, text, style)
        val strokes = withContext(Dispatchers.Default) { ink(style).place(laid.words, laid.em) }
        if (strokes.isEmpty) { if (block != null && !restore) blocks?.remove(block.id); return true }
        if (!replace(IntArray(0), s.page, color, strokes)) return false
        field.hidden = false
        blocks?.put(TextBlock(block?.id ?: TextBlock.newId(), s.page, text, style, laid.em, color,
            laid.lines.take(max(lines.size, (laid.words.maxOfOrNull { it.line } ?: 0) + 1)),
            TextBlock.marks(strokes.capsules, strokes.sizes)))
        return true
    }

    fun finish(s: TypeSession, keep: Boolean, then: (() -> Unit)? = null) {
        if (s.busy) return
        s.busy = true
        scope.launch {
            lock.withLock {
                var ok = true
                for ((i, f) in s.fields.withIndex()) {
                    // Only fields that were opened can have changed; reopened ones go back if cancelled.
                    if (!keep) { if (f.hidden) place(s, f, restore = true); continue }
                    if (s.form && i != s.current && f.text.text == f.original && !f.hidden) continue
                    if (!place(s, f)) ok = false
                }
                if (!ok) Toast.makeText(context, "Couldn't place the text: this notebook has reached its ink capacity.", Toast.LENGTH_LONG).show()
                if (keep) settings.typeStyle = s.style.copy(overflow = Overflow.Stop)
            }
            s.busy = false
            if (state.session === s) state.session = null
            then?.invoke()
        }
    }

    fun go(s: TypeSession, to: Int) {
        if (to !in s.fields.indices || to == s.current || s.busy) return
        val from = s.fields[s.current]
        s.busy = true
        scope.launch {
            lock.withLock {
                if (from.text.text != from.original || from.hidden) place(s, from)
                s.current = to
                hide(s, s.fields[to])
            }
            s.busy = false
        }
    }

    fun open(next: TypeSession) {
        state.session = next
        scope.launch { lock.withLock { hide(next, next.field) } }
    }

    fun startStyle() = settings.typeStyle

    // ---- loops and taps ----
    LaunchedEffect(layer, serial) {
        val current = layer ?: return@LaunchedEffect
        if (serial <= handled) return@LaunchedEffect
        handled = serial
        val src = source ?: return@LaunchedEffect
        val raw = suspendCancellableCoroutine<FloatArray> { c -> current.typeLoop { c.resume(it) } }
        if (raw.size < 3) return@LaunchedEffect
        val page = raw[0].toInt()
        val loop = raw.copyOfRange(1, raw.size)
        val mm = src.mmPerUnit(page)
        val tap = LinePicker.isTap(loop, 1.5f / mm)
        val tx = loop[0]; val ty = loop[1]

        // A tap on a line of the open session leaves it out or brings it back; in a form, a
        // tap on another field moves there.
        val open = state.session
        if (open != null && tap && open.page == page && !open.busy) {
            val f = open.field
            val hit = LinePicker.atPoint(f.candidates, tx, ty, 2.5f / mm)
            if (hit != null) { f.toggle(f.candidates.indexOf(hit)); return@LaunchedEffect }
            if (open.form) {
                val other = open.fields.indexOfFirst { LinePicker.atPoint(it.candidates, tx, ty, 2.5f / mm) != null }
                if (other >= 0) { go(open, other); return@LaunchedEffect }
            }
        }
        if (open != null) {
            // Anywhere else: place what's open, then start here.
            suspendCancellableCoroutine<Unit> { c -> finish(open, keep = true) { c.resume(Unit) } }
        }

        // Text typed here before reopens.
        val store = blocks
        if (store != null && store.blocks.any { it.page == page }) {
            val ink = InkLines.parse(suspendCancellableCoroutine { c -> current.pageInk(page) { c.resume(it) } })
            val matches = BlockMatcher.find(store.blocks, page, ink)
            val hit = if (tap) matches.firstOrNull { it.contains(tx, ty, it.block.em * 0.4f) }
                else matches.firstOrNull { m -> m.strokes.count { s -> LinePicker.inside(loop, (s.x0 + s.x1) / 2, (s.y0 + s.y1) / 2) } * 2 > m.strokes.size }
            if (hit != null) {
                val lines = hit.lines
                open(TypeSession(page, listOf(TypeField(lines, lines.indices.toSet(), hit.block.text, existing = hit)), mm,
                    form = false, color = hit.block.color, style = hit.block.style))
                return@LaunchedEffect
            }
        }

        val found = if (tap) listOfNotNull(src.atPoint(page, tx, ty)) else src.inLoop(page, loop)
        val color = TypeState.penColor(settings.penSlots.currentColor)
        if (found.isEmpty()) {
            val fallback = if (tap) WriteLine(tx, min(1000f, tx + 70f / mm), ty + 1f / mm, room = 9f / mm)
                else LinePicker.oneLine(loop, 12f / mm)
            open(TypeSession(page, listOf(TypeField(emptyList(), emptySet(), "")), mm, form = false, color = color,
                style = startStyle(), fallback = fallback))
            return@LaunchedEffect
        }
        // Lines found but none sure (an underlined heading): offer them all greyed, so one tap includes.
        val included = found.indices.filter { found[it].sure }.toSet()
        open(TypeSession(page, listOf(TypeField(found, included, "")), mm, form = false, color = color, style = startStyle()))
    }

    // ---- Fill this page ----
    LaunchedEffect(layer, state.fillRequest) {
        if (state.fillRequest == handledFill) return@LaunchedEffect
        handledFill = state.fillRequest
        val current = layer ?: return@LaunchedEffect
        val src = source ?: return@LaunchedEffect
        val page = state.fillPage
        state.session?.let { open -> suspendCancellableCoroutine<Unit> { c -> finish(open, keep = true) { c.resume(Unit) } } }
        toast("Looking for blanks on page ${page + 1}…")
        val mm = src.mmPerUnit(page)
        val groups = LinePicker.fields(src.wholePage(page), mm)
        if (groups.isEmpty()) { toast("No blanks found on this page. Loop some lines to type on them."); return@LaunchedEffect }
        val labels = src.labels(page, groups.map { it.first() })
        val matches = blocks?.let { store ->
            if (store.blocks.none { it.page == page }) emptyList()
            else BlockMatcher.find(store.blocks, page, InkLines.parse(suspendCancellableCoroutine { c -> current.pageInk(page) { c.resume(it) } }))
        }.orEmpty()
        val used = HashSet<BlockMatch>()
        val fields = groups.mapIndexed { i, lines ->
            val first = lines.first()
            // Text already typed in this field opens with it.
            val m = matches.firstOrNull { m -> m !in used && m.lines.any { b -> kotlin.math.abs(b.yAt(first.x0) - first.y) < 2f / mm && b.x0 < first.x1 && b.x1 > first.x0 } }
            if (m != null) used += m
            TypeField(lines, lines.indices.toSet(), m?.block?.text ?: "", labels.getOrNull(i), m)
        }
        open(TypeSession(page, fields, mm, form = true, color = TypeState.penColor(settings.penSlots.currentColor), style = startStyle()))
    }

    val s = state.session ?: return
    TypePanel(
        session = s,
        settings = settings,
        onDone = { finish(s, keep = true) },
        onCancel = { finish(s, keep = false) },
        onField = { go(s, it) },
        onUseFallback = {
            val fb = s.fallback ?: return@TypePanel
            state.session = TypeSession(s.page, listOf(TypeField(listOf(fb), setOf(0), s.field.text.text)), s.mmPerUnit,
                form = false, color = s.color, style = s.style)
        },
    )
}

package app.noti.study

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Typesetting for study material: prose with LaTeX math, drawn straight onto a Canvas, so
 * the floating sheet and a printed card on the board look exactly alike, at any zoom.
 *
 * Text is split into paragraphs by newlines. `$…$` or `\(…\)` is inline math, `$$…$$` or
 * `\[…\]` is display math (its own centered line), `**…**` is bold prose.
 *
 * The math is a practical subset of LaTeX for math and physics: letters, digits, Greek,
 * operators and relations with TeX spacing; `^` `_`; `\frac` `\dfrac` `\tfrac` `\binom`;
 * `\sqrt[n]{}`; `\vec` `\hat` `\bar` `\overline` `\dot` `\ddot` `\tilde` `\overrightarrow`;
 * `\sum` `\prod` `\int` `\oint` `\iint` `\lim` with limits; `\left…\right` stretchy
 * delimiters; `\text` `\mathrm` `\operatorname` `\mathbf` `\boldsymbol`; function names;
 * spaces; `\boxed`; `pmatrix` `bmatrix` `vmatrix` `matrix` `cases` `aligned`.
 * Unknown commands are shown by name rather than dropped.
 */
object MathText {
    class Style(
        val size: Float,
        val color: Int,
        val prose: Typeface = Typeface.DEFAULT,
        val proseBold: Typeface = Typeface.DEFAULT_BOLD,
        val lineSpacing: Float = 1.35f,
        /** Paragraphs are this many line heights apart (on top of the line). */
        val paragraphGap: Float = 0.35f,
    )

    /** Laid-out text, [width] × [height] px; draw at its top-left corner. */
    class Block internal constructor(val width: Float, val height: Float, private val lines: List<Line>) {
        fun draw(canvas: Canvas, x: Float, y: Float) {
            for (line in lines) for (p in line.items) p.box.draw(canvas, x + p.x, y + line.baseline)
        }
        val lineCount get() = lines.size
    }

    internal class Placed(val box: Box, val x: Float)
    internal class Line(val items: List<Placed>, val baseline: Float)

    fun layout(source: String, width: Float, style: Style): Block {
        val maxW = max(width, style.size * 4)
        val prose = paint(style.prose, style.size, style.color)
        val fm = prose.fontMetrics
        val lineAsc = -fm.ascent
        val lineDesc = fm.descent
        val lines = ArrayList<Line>()
        var y = 0f
        var first = true
        for (paragraph in source.replace("\r", "").split('\n')) {
            if (paragraph.isBlank()) { if (!first) y += style.size * style.paragraphGap; continue }
            if (!first) y += style.size * style.paragraphGap
            first = false
            for (row in wrap(runs(paragraph, style), maxW, style)) {
                val asc = max(lineAsc, row.maxOfOrNull { it.box.asc } ?: 0f)
                val desc = max(lineDesc, row.maxOfOrNull { it.box.desc } ?: 0f)
                // Natural line pitch, plus whatever tall math needs beyond it.
                val pitch = max(style.size * style.lineSpacing, asc + desc + style.size * 0.15f)
                val extraAbove = max(0f, asc - lineAsc)
                val baseline = y + extraAbove + lineAsc + (pitch - (lineAsc + lineDesc) - extraAbove - max(0f, desc - lineDesc)).coerceAtLeast(0f) / 2
                lines += Line(row, baseline)
                y = baseline + max(lineDesc, desc) + (pitch - lineAsc - lineDesc).coerceAtLeast(0f) / 2
            }
        }
        val usedW = lines.maxOfOrNull { l -> l.items.maxOfOrNull { it.x + it.box.w } ?: 0f } ?: 0f
        return Block(min(maxW, usedW), y, lines)
    }

    // ---- prose and math runs ----

    private sealed interface Run
    private class Word(val box: Box) : Run
    private class Gap(val w: Float) : Run
    private class Display(val box: Box) : Run

    private fun runs(paragraph: String, style: Style): List<Run> {
        val out = ArrayList<Run>()
        val space = paint(style.prose, style.size, style.color).measureText(" ")
        var i = 0
        var bold = false
        val text = StringBuilder()
        fun flushText() {
            if (text.isEmpty()) return
            val p = paint(if (bold) style.proseBold else style.prose, style.size, style.color)
            var start = 0
            val s = text.toString()
            while (start < s.length) {
                if (s[start] == ' ') { out += Gap(space); while (start < s.length && s[start] == ' ') start++; continue }
                var end = start
                while (end < s.length && s[end] != ' ') end++
                out += Word(TextBox(s.substring(start, end), p))
                start = end
            }
            text.clear()
        }
        while (i < paragraph.length) {
            val c = paragraph[i]
            when {
                paragraph.startsWith("$$", i) || paragraph.startsWith("\\[", i) -> {
                    val close = if (c == '$') "$$" else "\\]"
                    val end = paragraph.indexOf(close, i + 2).let { if (it < 0) paragraph.length else it }
                    flushText()
                    out += Display(Math.layout(paragraph.substring(i + 2, end), style.size * 1.08f, style.color, display = true))
                    i = min(paragraph.length, end + 2)
                }
                c == '$' || paragraph.startsWith("\\(", i) -> {
                    val close = if (c == '$') "$" else "\\)"
                    val from = i + if (c == '$') 1 else 2
                    val end = paragraph.indexOf(close, from).let { if (it < 0) paragraph.length else it }
                    flushText()
                    Math.layoutChunks(paragraph.substring(from, end), style.size * 1.05f, style.color).forEach { out += Word(it) }
                    i = min(paragraph.length, end + close.length)
                    // Punctuation right after math stays on its line.
                }
                paragraph.startsWith("**", i) -> { flushText(); bold = !bold; i += 2 }
                c == '\\' && i + 1 < paragraph.length && paragraph[i + 1] == '$' -> { text.append('$'); i += 2 }
                else -> { text.append(c); i++ }
            }
        }
        flushText()
        return out
    }

    private fun wrap(runs: List<Run>, width: Float, style: Style): List<List<Placed>> {
        val rows = ArrayList<List<Placed>>()
        var row = ArrayList<Placed>()
        var x = 0f
        var pendingGap = 0f
        fun newRow() { if (row.isNotEmpty()) rows += row; row = ArrayList(); x = 0f; pendingGap = 0f }
        for (r in runs) when (r) {
            is Gap -> if (row.isNotEmpty()) pendingGap = r.w
            is Display -> {
                newRow()
                val box = if (r.box.w > width) ScaledBox(r.box, width / r.box.w) else r.box
                rows += listOf(Placed(box, (width - box.w) / 2))
            }
            is Word -> {
                val box = if (r.box.w > width) ScaledBox(r.box, width / r.box.w) else r.box
                if (row.isNotEmpty() && x + pendingGap + box.w > width) newRow()
                x += if (row.isEmpty()) 0f else pendingGap
                row += Placed(box, x)
                x += box.w
                pendingGap = 0f
            }
        }
        newRow()
        if (rows.isEmpty()) rows += emptyList<Placed>()
        return rows
    }

    // ---- paints ----

    private val paints = HashMap<Triple<Typeface, Float, Int>, Paint>()
    internal fun paint(face: Typeface, size: Float, color: Int): Paint = synchronized(paints) {
        paints.getOrPut(Triple(face, size, color)) {
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = face; textSize = size; this.color = color }
        }
    }

    internal val SERIF: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.NORMAL) }
    internal val SERIF_ITALIC: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.ITALIC) }
    internal val SERIF_BOLD: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD) }
    internal val SERIF_BOLD_ITALIC: Typeface by lazy { Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) }
}

// =====================================================================================
// Boxes: TeX-style, [asc] above the baseline and [desc] below it.
// =====================================================================================

internal abstract class Box {
    var w = 0f
    var asc = 0f
    var desc = 0f
    /** Extra room after an italic letter, so a superscript doesn't collide with it. */
    open val italic: Float get() = 0f
    abstract fun draw(c: Canvas, x: Float, y: Float)
}

internal class TextBox(val text: String, val paint: Paint, slant: Boolean = false) : Box() {
    private val slantRoom = if (slant) paint.textSize * 0.06f else 0f
    override val italic get() = slantRoom
    init {
        w = paint.measureText(text)
        val r = Rect()
        paint.getTextBounds(text, 0, text.length, r)
        val fm = paint.fontMetrics
        // Letters sit on a common x-height at least, so a row of them doesn't bounce.
        asc = max(-r.top.toFloat(), -fm.ascent * 0.52f)
        desc = max(r.bottom.toFloat(), 0f)
    }
    override fun draw(c: Canvas, x: Float, y: Float) { c.drawText(text, x, y, paint) }
}

internal class SpaceBox(width: Float) : Box() {
    init { w = width }
    override fun draw(c: Canvas, x: Float, y: Float) {}
}

internal class HBox(val items: List<Box>, val gaps: FloatArray = FloatArray(items.size)) : Box() {
    init {
        var x = 0f
        items.forEachIndexed { i, b -> x += gaps[i]; x += b.w; asc = max(asc, b.asc); desc = max(desc, b.desc) }
        w = x
    }
    override val italic get() = items.lastOrNull()?.italic ?: 0f
    override fun draw(c: Canvas, x: Float, y: Float) {
        var cx = x
        items.forEachIndexed { i, b -> cx += gaps[i]; b.draw(c, cx, y); cx += b.w }
    }
}

/** [inner] shifted [dy] down (negative raises it). */
internal class ShiftBox(val inner: Box, val dy: Float, val dx: Float = 0f) : Box() {
    init { w = inner.w + dx; asc = inner.asc - dy; desc = inner.desc + dy }
    override fun draw(c: Canvas, x: Float, y: Float) = inner.draw(c, x + dx, y + dy)
}

internal class ScaledBox(val inner: Box, val k: Float) : Box() {
    init { w = inner.w * k; asc = inner.asc * k; desc = inner.desc * k }
    override fun draw(c: Canvas, x: Float, y: Float) {
        val s = c.save(); c.translate(x, y); c.scale(k, k); inner.draw(c, 0f, 0f); c.restoreToCount(s)
    }
}

/** Boxes stacked vertically and centred: each row is a box and the y of its baseline. */
internal class StackBox(val rows: List<Pair<Box, Float>>, val rules: List<FloatArray> = emptyList(), val paint: Paint? = null,
    width: Float = rows.maxOfOrNull { it.first.w } ?: 0f, val align: Int = 0) : Box() {
    init {
        w = width
        asc = rows.maxOfOrNull { -(it.second - it.first.asc) } ?: 0f
        desc = rows.maxOfOrNull { it.second + it.first.desc } ?: 0f
        rules.forEach { asc = max(asc, -it[1] + it[2]); desc = max(desc, it[1] + it[2]) }
    }
    override fun draw(c: Canvas, x: Float, y: Float) {
        for ((b, by) in rows) b.draw(c, x + when (align) { 0 -> (w - b.w) / 2; -1 -> 0f; else -> w - b.w }, y + by)
        paint?.let { p -> rules.forEach { r -> c.drawRect(x + r[0], y + r[1] - r[2] / 2, x + r[3], y + r[1] + r[2] / 2, p) } }
    }
}

/** Something drawn as a path in a box of the given size (delimiters, radicals, arrows, frames). */
internal class PathBox(width: Float, ascent: Float, descent: Float, val paint: Paint, val stroke: Float,
    val build: (Path, Float, Float) -> Unit) : Box() {
    private val path = Path()
    private val p = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    init { w = width; asc = ascent; desc = descent }
    override fun draw(c: Canvas, x: Float, y: Float) {
        path.rewind(); build(path, x, y); c.drawPath(path, p)
    }
}

/** [body] with something drawn over it (sqrt bar, accents, frames): [extra] draws around it. */
internal class DecoratedBox(val body: Box, val bodyX: Float, width: Float, ascent: Float, descent: Float,
    val extra: (Canvas, Float, Float) -> Unit) : Box() {
    init { w = width; asc = ascent; desc = descent }
    override fun draw(c: Canvas, x: Float, y: Float) { body.draw(c, x + bodyX, y); extra(c, x, y) }
}

// =====================================================================================
// Math: parse a LaTeX subset into atoms, then lay atoms out as boxes.
// =====================================================================================

internal enum class Cls { Ord, Op, Bin, Rel, Open, Close, Punct, Inner }
internal enum class Face { Italic, Upright, Bold, BoldItalic }

internal sealed class Node { open val cls: Cls get() = Cls.Ord }
internal class Sym(val text: String, override val cls: Cls, val face: Face) : Node()
internal class Group(val nodes: List<Node>, override val cls: Cls = Cls.Ord) : Node()
internal class Frac(val num: Node, val den: Node, val rule: Boolean, val style: Int /* 0 auto, 1 display, 2 text */) : Node()
internal class Scripts(val base: Node, val sup: Node?, val sub: Node?) : Node() { override val cls get() = base.cls }
internal class Root(val body: Node, val index: Node?) : Node()
internal class Accent(val body: Node, val kind: String) : Node()
internal class BigOp(val glyph: String, val limits: Boolean, val integral: Boolean) : Node() { override val cls get() = Cls.Op }
internal class FuncName(val name: String, val limits: Boolean) : Node() { override val cls get() = Cls.Op }
internal class Delimited(val left: String, val body: Node, val right: String) : Node() { override val cls get() = Cls.Inner }
internal class Space(val em: Float) : Node()
internal class Words(val text: String, val bold: Boolean) : Node()
internal class Matrix(val rows: List<List<Node>>, val env: String) : Node() { override val cls get() = Cls.Inner }
internal class Boxed(val body: Node) : Node()

internal object Math {
    fun layout(src: String, size: Float, color: Int, display: Boolean): Box =
        Layout(size, color).list(Parser(src).parseAll(), if (display) 0 else 1)

    /** Inline math cut after top-level relations and binary operators, so a line may wrap there. */
    fun layoutChunks(src: String, size: Float, color: Int): List<Box> {
        val nodes = Parser(src).parseAll()
        val layout = Layout(size, color)
        val chunks = ArrayList<List<Node>>()
        var current = ArrayList<Node>()
        for ((i, n) in nodes.withIndex()) {
            current += n
            val breakable = (n.cls == Cls.Rel || n.cls == Cls.Bin) && i > 0 && i < nodes.lastIndex && nodes[i + 1].cls != Cls.Rel
            if (breakable) { chunks += current; current = ArrayList() }
        }
        if (current.isNotEmpty() || chunks.isEmpty()) chunks += current
        if (chunks.size == 1) return listOf(layout.list(chunks[0], 1))
        // Lay each chunk out with the spacing it has in the whole formula: its first atom keeps the space before it.
        val out = ArrayList<Box>()
        var prev: Node? = null
        for (chunk in chunks) {
            out += layout.list(chunk, 1, before = prev)
            prev = chunk.lastOrNull()
        }
        return out
    }
}

/** Recursive-descent parser for the LaTeX subset. Never throws: bad input degrades. */
internal class Parser(private val s: String) {
    private var i = 0

    fun parseAll(): List<Node> = parseList(stopAt = null)

    private fun peek() = if (i < s.length) s[i] else '\u0000'
    private fun skipSpaces() { while (i < s.length && s[i].isWhitespace()) i++ }

    private fun parseList(stopAt: Char?, stopWords: Set<String> = emptySet()): List<Node> {
        val out = ArrayList<Node>()
        while (i < s.length) {
            skipSpaces()
            if (i >= s.length) break
            val c = s[i]
            if (stopAt != null && c == stopAt) break
            if (c == '\\' && stopWords.isNotEmpty()) {
                val word = peekCommand()
                if (word in stopWords) break
            }
            if (c == '&' || (c == '\\' && i + 1 < s.length && s[i + 1] == '\\')) {
                if (stopWords.isNotEmpty() || stopAt == '}') break
                // Outside an environment: a row break is a wide space.
                i += if (c == '&') 1 else 2; out += Space(1f); continue
            }
            if (c == '^' || c == '_') {
                i++
                val arg = parseArg()
                val base = out.removeLastOrNull() ?: Group(emptyList())
                out += attachScript(base, c == '^', arg)
                continue
            }
            if (c == '\'') {
                i++
                var primes = "′"
                while (peek() == '\'') { primes += "′"; i++ }
                val base = out.removeLastOrNull() ?: Group(emptyList())
                out += attachScript(base, true, Sym(primes, Cls.Ord, Face.Upright))
                continue
            }
            out += parseAtom() ?: continue
        }
        return out
    }

    private fun attachScript(base: Node, sup: Boolean, arg: Node): Node = when {
        base is Scripts && sup && base.sup == null -> Scripts(base.base, arg, base.sub)
        base is Scripts && !sup && base.sub == null -> Scripts(base.base, base.sup, arg)
        sup -> Scripts(base, arg, null)
        else -> Scripts(base, null, arg)
    }

    private fun peekCommand(): String {
        if (peek() != '\\') return ""
        var j = i + 1
        if (j < s.length && !s[j].isLetter()) return s[j].toString()
        while (j < s.length && s[j].isLetter()) j++
        return s.substring(i + 1, j)
    }

    private fun readCommand(): String {
        i++ // backslash
        if (i >= s.length) return ""
        if (!s[i].isLetter()) return s[i++].toString()
        val start = i
        while (i < s.length && s[i].isLetter()) i++
        return s.substring(start, i)
    }

    /** A braced group, or a single atom/character. */
    private fun parseArg(): Node {
        skipSpaces()
        if (peek() == '{') {
            i++
            val list = parseList('}')
            if (peek() == '}') i++
            return if (list.size == 1) list[0] else Group(list)
        }
        if (i >= s.length) return Group(emptyList())
        // An unbraced argument is one token: \frac12 is ½, x^23 is x²3.
        if (s[i].isDigit()) return Sym(s[i++].toString(), Cls.Ord, Face.Upright)
        return parseAtom() ?: Group(emptyList())
    }

    private fun readBraced(): String {
        skipSpaces()
        if (peek() != '{') return if (i < s.length) s[i++].toString() else ""
        var depth = 0
        val start = i + 1
        while (i < s.length) {
            when (s[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) { i++; return s.substring(start, i - 1) } } }
            i++
        }
        return s.substring(start)
    }

    private fun parseAtom(): Node? {
        val c = s[i]
        when {
            c == '{' -> { i++; val l = parseList('}'); if (peek() == '}') i++; return Group(l) }
            c == '}' -> { i++; return null }
            c == '\\' -> return command(readCommand())
            c == '~' -> { i++; return Space(0.33f) }
        }
        i++
        return when {
            c.isLetter() -> Sym(c.toString(), Cls.Ord, Face.Italic)
            c.isDigit() || c == '.' -> {
                // Digits and a decimal point stay together.
                val start = i - 1
                while (i < s.length && (s[i].isDigit() || (s[i] == '.' && i + 1 < s.length && s[i + 1].isDigit()))) i++
                Sym(s.substring(start, i), Cls.Ord, Face.Upright)
            }
            c == '+' || c == '*' -> Sym(if (c == '*') "∗" else "+", Cls.Bin, Face.Upright)
            c == '-' -> Sym("−", Cls.Bin, Face.Upright)
            c == '=' || c == '<' || c == '>' || c == ':' -> Sym(c.toString(), Cls.Rel, Face.Upright)
            c == '(' || c == '[' -> Sym(c.toString(), Cls.Open, Face.Upright)
            c == ')' || c == ']' -> Sym(c.toString(), Cls.Close, Face.Upright)
            c == ',' || c == ';' -> Sym(c.toString(), Cls.Punct, Face.Upright)
            c == '|' -> Sym("|", Cls.Ord, Face.Upright)
            c == '/' -> Sym("/", Cls.Ord, Face.Upright)
            c == '!' || c == '?' -> Sym(c.toString(), Cls.Close, Face.Upright)
            else -> Sym(c.toString(), Cls.Ord, Face.Upright)
        }
    }

    private fun command(name: String): Node? {
        SYMBOLS[name]?.let { (text, cls) -> return Sym(text, cls, if (name.length > 1 && name[0].isLowerCase() && name in GREEK_LOWER) Face.Italic else Face.Upright) }
        BIG[name]?.let { (glyph, integral) -> return BigOp(glyph, limits = !integral, integral = integral) }
        if (name in FUNCTIONS) return FuncName(name, limits = name in LIMIT_FUNCTIONS)
        return when (name) {
            "frac", "cfrac" -> Frac(parseArg(), parseArg(), true, 0)
            "dfrac" -> Frac(parseArg(), parseArg(), true, 1)
            "tfrac" -> Frac(parseArg(), parseArg(), true, 2)
            "binom" -> Delimited("(", Frac(parseArg(), parseArg(), false, 0), ")")
            "sqrt" -> {
                skipSpaces()
                val index = if (peek() == '[') {
                    i++
                    val l = parseList(']')
                    if (peek() == ']') i++
                    Group(l)
                } else null
                Root(parseArg(), index)
            }
            "vec", "hat", "bar", "overline", "dot", "ddot", "tilde", "widehat", "widetilde", "overrightarrow", "underline" ->
                Accent(parseArg(), name)
            "boxed" -> Boxed(parseArg())
            "text", "textrm", "mbox", "textit" -> Words(readBraced(), false)
            "textbf" -> Words(readBraced(), true)
            "mathrm", "operatorname", "mathup" -> {
                val text = readBraced()
                if (name == "operatorname") FuncName(text, false) else Group(Parser(text).parseAll().map { upright(it, Face.Upright) })
            }
            "mathbf", "bm", "boldsymbol", "mathbfit" -> {
                val face = if (name == "mathbf") Face.Bold else Face.BoldItalic
                Group(Parser(readBraced()).parseAll().map { upright(it, face) })
            }
            "mathit", "mathcal", "mathscr", "mathbb", "mathsf", "mathtt" -> {
                val text = readBraced()
                if (name == "mathbb") Group(text.map { Sym(BLACKBOARD[it] ?: it.toString(), Cls.Ord, Face.Upright) })
                else Group(Parser(text).parseAll())
            }
            "left" -> {
                val left = delimiter()
                val body = parseList(null, stopWords = setOf("right"))
                if (peekCommand() == "right") readCommand()
                val right = delimiter()
                Delimited(left, Group(body), right)
            }
            "right" -> { delimiter(); null }
            "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr" -> {
                val d = delimiter()
                Sym(d, if (name.endsWith("r")) Cls.Close else Cls.Open, Face.Upright)
            }
            "begin" -> environment(readBraced())
            "end" -> { readBraced(); null }
            "," , "thinspace" -> Space(3f / 18)
            ":", ">", "medspace" -> Space(4f / 18)
            ";", "thickspace" -> Space(5f / 18)
            "!", "negthinspace" -> Space(-3f / 18)
            " " -> Space(0.33f)
            "quad" -> Space(1f)
            "qquad" -> Space(2f)
            "displaystyle", "textstyle", "limits", "nolimits", "mathstrut", "notag", "nonumber" -> null
            "{" -> Sym("{", Cls.Open, Face.Upright)
            "}" -> Sym("}", Cls.Close, Face.Upright)
            "|" -> Sym("‖", Cls.Ord, Face.Upright)
            "%", "#", "&", "_", "$" -> Sym(name, Cls.Ord, Face.Upright)
            "\\" -> Space(1f)
            "" -> null
            else -> Words("\\$name", false)
        }
    }

    private fun upright(n: Node, face: Face): Node = when (n) {
        is Sym -> if (n.face == Face.Italic || face == Face.Bold || face == Face.BoldItalic) Sym(n.text, n.cls, face) else n
        is Group -> Group(n.nodes.map { upright(it, face) }, n.cls)
        is Scripts -> Scripts(upright(n.base, face), n.sup, n.sub)
        else -> n
    }

    private fun delimiter(): String {
        skipSpaces()
        if (i >= s.length) return "."
        if (s[i] == '\\') {
            val name = readCommand()
            return when (name) {
                "{", "lbrace" -> "{"; "}", "rbrace" -> "}"; "|", "Vert", "lVert", "rVert" -> "‖"
                "langle" -> "⟨"; "rangle" -> "⟩"; "lvert", "rvert", "vert" -> "|"
                "lfloor" -> "⌊"; "rfloor" -> "⌋"; "lceil" -> "⌈"; "rceil" -> "⌉"
                else -> "."
            }
        }
        return s[i++].toString()
    }

    private fun environment(env: String): Node {
        val rows = ArrayList<List<Node>>()
        var row = ArrayList<Node>()
        while (i < s.length) {
            val cell = parseList(null, stopWords = setOf("end"))
            row += Group(cell)
            skipSpaces()
            when {
                peek() == '&' -> i++
                s.startsWith("\\\\", i) -> { i += 2; rows += row; row = ArrayList() }
                peekCommand() == "end" -> { readCommand(); readBraced(); break }
                i >= s.length -> break
                else -> i++
            }
        }
        if (row.isNotEmpty() && !(row.size == 1 && (row[0] as Group).nodes.isEmpty())) rows += row
        val m = Matrix(rows, env)
        return when (env) {
            "pmatrix" -> Delimited("(", m, ")")
            "bmatrix" -> Delimited("[", m, "]")
            "vmatrix" -> Delimited("|", m, "|")
            "Vmatrix" -> Delimited("‖", m, "‖")
            "Bmatrix" -> Delimited("{", m, "}")
            "cases" -> Delimited("{", m, ".")
            else -> m
        }
    }

    companion object {
        val GREEK_LOWER = setOf("alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta", "theta", "vartheta",
            "iota", "kappa", "lambda", "mu", "nu", "xi", "pi", "varpi", "rho", "varrho", "sigma", "varsigma", "tau", "upsilon",
            "phi", "varphi", "chi", "psi", "omega")
        val SYMBOLS: Map<String, Pair<String, Cls>> = buildMap {
            val o = Cls.Ord
            listOf("alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ϵ", "varepsilon" to "ε", "zeta" to "ζ",
                "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν",
                "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "varrho" to "ϱ", "sigma" to "σ", "varsigma" to "ς", "tau" to "τ",
                "upsilon" to "υ", "phi" to "ϕ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
                "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
                "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
                "infty" to "∞", "partial" to "∂", "nabla" to "∇", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
                "emptyset" to "∅", "varnothing" to "∅", "angle" to "∠", "prime" to "′", "degree" to "°", "circ" to "∘",
                "cdots" to "⋯", "ldots" to "…", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱", "forall" to "∀", "exists" to "∃",
                "neg" to "¬", "triangle" to "△", "square" to "□", "aleph" to "ℵ", "dagger" to "†", "star" to "⋆", "bullet" to "∙",
                "imath" to "ı", "jmath" to "ȷ", "AA" to "Å", "checkmark" to "✓", "hslash" to "ℏ",
            ).forEach { (k, v) -> put(k, v to o) }
            listOf("pm" to "±", "mp" to "∓", "times" to "×", "cdot" to "·", "div" to "÷", "ast" to "∗", "cup" to "∪", "cap" to "∩",
                "setminus" to "∖", "wedge" to "∧", "land" to "∧", "vee" to "∨", "lor" to "∨", "oplus" to "⊕", "otimes" to "⊗",
            ).forEach { (k, v) -> put(k, v to Cls.Bin) }
            listOf("leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "equiv" to "≡",
                "sim" to "∼", "simeq" to "≃", "cong" to "≅", "propto" to "∝", "ll" to "≪", "gg" to "≫", "to" to "→",
                "rightarrow" to "→", "leftarrow" to "←", "gets" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
                "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "iff" to "⟺", "implies" to "⟹", "mapsto" to "↦",
                "longrightarrow" to "⟶", "uparrow" to "↑", "downarrow" to "↓", "rightleftharpoons" to "⇌",
                "in" to "∈", "notin" to "∉", "ni" to "∋", "subset" to "⊂", "supset" to "⊃", "subseteq" to "⊆", "supseteq" to "⊇",
                "perp" to "⊥", "parallel" to "∥", "mid" to "∣", "doteq" to "≐", "coloneqq" to "≔", "triangleq" to "≜",
                "lesssim" to "≲", "gtrsim" to "≳",
            ).forEach { (k, v) -> put(k, v to Cls.Rel) }
            listOf("langle" to "⟨", "lfloor" to "⌊", "lceil" to "⌈", "lvert" to "|").forEach { (k, v) -> put(k, v to Cls.Open) }
            listOf("rangle" to "⟩", "rfloor" to "⌋", "rceil" to "⌉", "rvert" to "|").forEach { (k, v) -> put(k, v to Cls.Close) }
            put("colon", ":" to Cls.Punct)
        }
        val BIG = mapOf("sum" to ("∑" to false), "prod" to ("∏" to false), "coprod" to ("∐" to false),
            "bigcup" to ("⋃" to false), "bigcap" to ("⋂" to false), "int" to ("∫" to true), "iint" to ("∬" to true),
            "iiint" to ("∭" to true), "oint" to ("∮" to true))
        val FUNCTIONS = setOf("sin", "cos", "tan", "cot", "sec", "csc", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh",
            "coth", "ln", "log", "lg", "exp", "lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "dim", "ker",
            "deg", "arg", "gcd", "Pr", "hom", "mod", "bmod", "Tr", "tr", "sgn", "erf")
        val LIMIT_FUNCTIONS = setOf("lim", "limsup", "liminf", "max", "min", "sup", "inf", "det", "Pr", "gcd")
        val BLACKBOARD = mapOf('R' to "ℝ", 'N' to "ℕ", 'Z' to "ℤ", 'Q' to "ℚ", 'C' to "ℂ", 'P' to "ℙ", 'E' to "𝔼")
    }
}

/**
 * Lays atoms out. Styles: 0 display, 1 text, 2 script, 3 scriptscript. Sizes in px of the
 * text style ([size]); script styles shrink to 70% and 50%.
 */
internal class Layout(private val size: Float, private val color: Int) {
    private fun sizeOf(style: Int) = when (style) { 0, 1 -> size; 2 -> size * 0.71f; else -> size * 0.55f }
    private fun face(f: Face) = when (f) {
        Face.Italic -> MathText.SERIF_ITALIC; Face.Upright -> MathText.SERIF
        Face.Bold -> MathText.SERIF_BOLD; Face.BoldItalic -> MathText.SERIF_BOLD_ITALIC
    }
    private fun paint(f: Face, style: Int) = MathText.paint(face(f), sizeOf(style), color)
    private fun axis(style: Int) = sizeOf(style) * 0.27f
    private fun rule(style: Int) = max(1f, sizeOf(style) * 0.055f)
    private fun inkPaint(style: Int) = MathText.paint(MathText.SERIF, sizeOf(style), color)

    fun list(nodes: List<Node>, style: Int, before: Node? = null): Box {
        if (nodes.isEmpty()) return SpaceBox(0f)
        val boxes = ArrayList<Box>(nodes.size)
        val gaps = FloatArray(nodes.size)
        var prev: Cls? = before?.cls
        var prevNode: Node? = before
        for ((idx, n) in nodes.withIndex()) {
            var cls = n.cls
            // A binary operator with nothing to its left (or after an operator) is unary.
            if (cls == Cls.Bin && (prev == null || prev in setOf(Cls.Bin, Cls.Op, Cls.Rel, Cls.Open, Cls.Punct))) cls = Cls.Ord
            if (cls == Cls.Bin && idx == nodes.lastIndex && before == null && nodes.size > 1) cls = Cls.Ord
            gaps[idx] = if (prev == null) 0f else spacing(prev, cls, style, prevNode)
            boxes += node(n, style)
            prev = cls
            prevNode = n
        }
        return HBox(boxes, gaps)
    }

    private fun spacing(a: Cls, b: Cls, style: Int, prevNode: Node?): Float {
        val em = sizeOf(style)
        val thin = em * 3 / 18; val med = em * 4 / 18; val thick = em * 5 / 18
        val tight = style >= 2
        return when {
            a == Cls.Bin || b == Cls.Bin -> if (tight) 0f else med
            a == Cls.Rel || b == Cls.Rel -> if (a == Cls.Rel && b == Cls.Rel) 0f else if (tight) 0f else thick
            a == Cls.Punct -> if (tight) 0f else thin
            a == Cls.Op && (b == Cls.Ord || b == Cls.Inner || b == Cls.Op) -> if (prevNode is BigOp && (prevNode as BigOp).integral) thin * 0.5f else thin
            b == Cls.Op && a != Cls.Open -> thin
            a == Cls.Inner || b == Cls.Inner -> if (tight || a == Cls.Open || b == Cls.Close || a == Cls.Punct) 0f else thin
            else -> 0f
        }
    }

    fun node(n: Node, style: Int): Box = when (n) {
        is Sym -> TextBox(n.text, paint(n.face, style), slant = n.face == Face.Italic || n.face == Face.BoldItalic)
        is Group -> list(n.nodes, style)
        is Space -> SpaceBox(n.em * sizeOf(style))
        is Words -> TextBox(n.text, MathText.paint(if (n.bold) MathText.SERIF_BOLD else MathText.SERIF, sizeOf(style), color))
        is FuncName -> TextBox(n.name, paint(Face.Upright, style))
        is Frac -> frac(n, style)
        is Scripts -> scripts(n, style)
        is Root -> root(n, style)
        is Accent -> accent(n, style)
        is BigOp -> bigOp(n, style)
        is Delimited -> delimited(n, style)
        is Matrix -> matrix(n, style)
        is Boxed -> boxed(n, style)
    }

    private fun frac(n: Frac, style: Int): Box {
        val inner = when (n.style) { 1 -> 0; 2 -> 1; else -> style }
        val childStyle = if (inner == 0) 1 else min(3, inner + 1)
        val num = node(n.num, childStyle)
        val den = node(n.den, childStyle)
        val t = if (n.rule) rule(inner) else 0f
        val a = axis(inner)
        val gap = sizeOf(inner) * if (inner == 0) 0.16f else 0.1f
        val numY = -(a + t / 2 + gap + num.desc)
        val denY = -a + t / 2 + gap + den.asc
        val width = max(num.w, den.w) + sizeOf(inner) * 0.24f
        val rules = if (n.rule) listOf(floatArrayOf(sizeOf(inner) * 0.06f, -a, t, width - sizeOf(inner) * 0.06f)) else emptyList()
        return StackBox(listOf(num to numY, den to denY), rules, inkPaint(inner).let { Paint(it).apply { this.style = Paint.Style.FILL } }, width)
    }

    private fun scripts(n: Scripts, style: Int): Box {
        val sub = if (style == 0) 2 else min(3, style + 1)
        val limits = (n.base is BigOp && (n.base as BigOp).limits && style == 0) || (n.base is FuncName && (n.base as FuncName).limits && style <= 1)
        val base = node(n.base, style)
        val sup = n.sup?.let { node(it, sub) }
        val sb = n.sub?.let { node(it, sub) }
        if (limits) {
            val gap = sizeOf(style) * 0.12f
            val rows = ArrayList<Pair<Box, Float>>()
            rows += base to 0f
            sup?.let { rows += it to -(base.asc + gap + it.desc) }
            sb?.let { rows += it to base.desc + gap + it.asc }
            return StackBox(rows, width = max(base.w, max(sup?.w ?: 0f, sb?.w ?: 0f)))
        }
        val em = sizeOf(style)
        var supShift = max(em * 0.42f, base.asc - em * 0.28f)
        var subShift = max(em * 0.2f, base.desc + em * 0.12f)
        if (sup != null) supShift = max(supShift, sup.desc + em * 0.22f)
        if (sup != null && sb != null) {
            val clearance = (supShift - sup.desc) - (sb.asc - subShift)
            if (clearance < em * 0.18f) subShift += em * 0.18f - clearance
        }
        val items = ArrayList<Box>()
        items += base
        val after = ArrayList<Pair<Box, Float>>()
        val italic = if (n.base is Sym || n.base is Group) base.italic else 0f
        sup?.let { after += ShiftBox(it, -supShift, italic) to 0f }
        sb?.let { after += ShiftBox(it, subShift) to 0f }
        val scriptsBox = StackBox(after, width = after.maxOf { it.first.w }, align = -1)
        return HBox(listOf(base, scriptsBox), floatArrayOf(0f, em * 0.03f))
    }

    private fun root(n: Root, style: Int): Box {
        val body = node(n.body, if (style == 0) 1 else style)
        val em = sizeOf(style)
        val t = rule(style)
        val gap = em * 0.12f
        val top = max(body.asc, em * 0.62f) + gap + t
        val bottom = max(body.desc, em * 0.1f)
        val signW = em * 0.55f + (top + bottom - em) .coerceAtLeast(0f) * 0.12f
        val index = n.index?.let { node(it, 3) }
        val indexW = index?.let { max(0f, it.w - signW * 0.45f) } ?: 0f
        val bodyX = indexW + signW + em * 0.05f
        val paint = inkPaint(style)
        val stroke = Paint(paint).apply { this.style = Paint.Style.STROKE; strokeWidth = t; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val path = Path()
        return DecoratedBox(body, bodyX, bodyX + body.w + em * 0.08f, top + t / 2, bottom) { c, x, y ->
            path.rewind()
            val x0 = x + indexW
            path.moveTo(x0, y - (top + bottom) * 0.38f + bottom)
            path.lineTo(x0 + signW * 0.22f, y - (top + bottom) * 0.48f + bottom)
            path.lineTo(x0 + signW * 0.55f, y + bottom)
            path.lineTo(x0 + signW, y - top + t / 2)
            path.lineTo(x + bodyX + body.w + em * 0.06f, y - top + t / 2)
            c.drawPath(path, stroke)
            index?.draw(c, x, y - (top + bottom) * 0.42f + bottom - index.desc)
        }
    }

    private fun accent(n: Accent, style: Int): Box {
        val body = node(n.body, style)
        val em = sizeOf(style)
        val t = rule(style)
        val wide = n.kind in setOf("overline", "overrightarrow", "underline", "widehat", "widetilde") || body.w > em * 0.9f
        val gap = em * 0.1f
        val markH = when (n.kind) { "vec", "overrightarrow" -> em * 0.2f; "hat", "widehat", "tilde", "widetilde" -> em * 0.16f; else -> em * 0.08f }
        val top = max(body.asc, em * 0.46f) + gap
        val stroke = Paint(inkPaint(style)).apply { this.style = Paint.Style.STROKE; strokeWidth = t; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val fill = Paint(inkPaint(style)).apply { this.style = Paint.Style.FILL }
        val path = Path()
        val under = n.kind == "underline"
        val shift = if (body.italic > 0f && !wide) body.italic * 1.2f else 0f
        return DecoratedBox(body, 0f, body.w, if (under) body.asc else top + markH + t, if (under) body.desc + gap + t else body.desc) { c, x, y ->
            val cx = x + body.w / 2 + shift
            val half = if (wide) body.w / 2 else em * 0.2f
            val base = y - top
            path.rewind()
            when (n.kind) {
                "vec", "overrightarrow" -> {
                    val l = if (wide) x else cx - half; val r = if (wide) x + body.w else cx + half
                    val my = base - markH / 2
                    path.moveTo(l, my); path.lineTo(r, my)
                    path.moveTo(r - markH * 0.75f, my - markH * 0.5f); path.lineTo(r, my); path.lineTo(r - markH * 0.75f, my + markH * 0.5f)
                    c.drawPath(path, stroke)
                }
                "hat", "widehat" -> { path.moveTo(cx - half, base); path.lineTo(cx, base - markH); path.lineTo(cx + half, base); c.drawPath(path, stroke) }
                "tilde", "widetilde" -> {
                    path.moveTo(cx - half, base - markH * 0.3f)
                    path.cubicTo(cx - half / 2, base - markH * 1.2f, cx + half / 2, base + markH * 0.4f, cx + half, base - markH * 0.6f)
                    c.drawPath(path, stroke)
                }
                "bar", "overline" -> { val l = if (wide) x else cx - half; val r = if (wide) x + body.w else cx + half; c.drawLine(l, base - t, r, base - t, stroke) }
                "underline" -> c.drawLine(x, y + body.desc + gap, x + body.w, y + body.desc + gap, stroke)
                "dot" -> c.drawCircle(cx, base - markH / 2, t * 1.1f, fill)
                "ddot" -> { c.drawCircle(cx - em * 0.11f, base - markH / 2, t * 1.1f, fill); c.drawCircle(cx + em * 0.11f, base - markH / 2, t * 1.1f, fill) }
            }
        }
    }

    private fun bigOp(n: BigOp, style: Int): Box {
        val k = if (style == 0) (if (n.integral) 2.0f else 1.55f) else (if (n.integral) 1.4f else 1.15f)
        val p = MathText.paint(if (n.integral) MathText.SERIF_ITALIC else MathText.SERIF, sizeOf(style) * k, color)
        val box = TextBox(n.glyph, p)
        // Centre it on the math axis.
        val mid = (box.asc - box.desc) / 2
        return ShiftBox(box, mid - axis(style))
    }

    private fun delimited(n: Delimited, style: Int): Box {
        val body = node(n.body, style)
        val a = axis(style)
        val half = max(body.asc - a, body.desc + a) + sizeOf(style) * 0.08f
        val left = delim(n.left, half, style, true)
        val right = delim(n.right, half, style, false)
        return HBox(listOf(left, body, right), floatArrayOf(0f, sizeOf(style) * 0.04f, sizeOf(style) * 0.04f))
    }

    /** A delimiter reaching [half] above and below the math axis: a glyph when that's small, a path otherwise. */
    private fun delim(d: String, half: Float, style: Int, left: Boolean): Box {
        val em = sizeOf(style)
        if (d == "." || d.isEmpty()) return SpaceBox(em * 0.06f)
        val a = axis(style)
        if (half <= em * 0.62f) return TextBox(d, paint(Face.Upright, style))
        val t = rule(style) * 1.1f
        val w = when (d) { "|", "‖" -> em * 0.22f; "{", "}" -> em * 0.42f; else -> em * 0.32f + half * 0.06f }
        val p = paint(Face.Upright, style)
        return PathBox(w, half + a, half - a, p, t) { path, x, y ->
            val top = y - a - half; val bot = y - a + half; val mid = y - a
            val l = x + t; val r = x + w - t
            val flip = !left
            fun X(v: Float) = if (flip) x + w - (v - x) else v
            when (d) {
                "(", ")" -> { path.moveTo(X(r), top); path.quadTo(X(l - (r - l) * 0.35f), mid, X(r), bot) }
                "[", "]" -> { path.moveTo(X(r), top); path.lineTo(X(l), top); path.lineTo(X(l), bot); path.lineTo(X(r), bot) }
                "⌊", "⌋" -> { path.moveTo(X(l), top); path.lineTo(X(l), bot); path.lineTo(X(r), bot) }
                "⌈", "⌉" -> { path.moveTo(X(r), top); path.lineTo(X(l), top); path.lineTo(X(l), bot) }
                "{", "}" -> {
                    val c = (l + r) / 2
                    path.moveTo(X(r), top)
                    path.cubicTo(X(c), top, X(c), top + (mid - top) * 0.15f, X(c), mid - (mid - top) * 0.35f)
                    path.cubicTo(X(c), mid - 2, X(l), mid, X(l), mid)
                    path.cubicTo(X(c), mid, X(c), mid + 2, X(c), mid + (bot - mid) * 0.35f)
                    path.cubicTo(X(c), bot - (bot - mid) * 0.15f, X(c), bot, X(r), bot)
                }
                "⟨", "⟩" -> { path.moveTo(X(r), top); path.lineTo(X(l), mid); path.lineTo(X(r), bot) }
                "‖" -> { path.moveTo(x + w * 0.3f, top); path.lineTo(x + w * 0.3f, bot); path.moveTo(x + w * 0.7f, top); path.lineTo(x + w * 0.7f, bot) }
                else -> { path.moveTo(x + w / 2, top); path.lineTo(x + w / 2, bot) }
            }
        }
    }

    private fun matrix(n: Matrix, style: Int): Box {
        val cellStyle = if (style == 0) 1 else style
        val cells = n.rows.map { row -> row.map { node(it, cellStyle) } }
        val cols = cells.maxOfOrNull { it.size } ?: 0
        if (cols == 0) return SpaceBox(0f)
        val em = sizeOf(style)
        val colGap = if (n.env == "cases" || n.env.startsWith("align")) em * 0.9f else em * 0.8f
        val widths = FloatArray(cols) { c -> cells.maxOf { r -> r.getOrNull(c)?.w ?: 0f } }
        val rowGap = em * 0.32f
        val heights = cells.map { r -> (r.maxOfOrNull { it.asc } ?: 0f) to (r.maxOfOrNull { it.desc } ?: 0f) }
        val total = heights.sumOf { (it.first + it.second).toDouble() }.toFloat() + rowGap * (cells.size - 1)
        var y = -total / 2 - axis(style)
        val rows = ArrayList<Pair<Box, Float>>()
        val totalW = widths.sum() + colGap * (cols - 1)
        cells.forEachIndexed { ri, r ->
            val (asc, desc) = heights[ri]
            val baseline = y + asc
            var x = 0f
            r.forEachIndexed { ci, b ->
                // Left aligned for cases and aligned's odd columns, right for aligned's first; centred otherwise.
                val align = when {
                    n.env == "cases" -> 0f
                    n.env.startsWith("align") -> if (ci % 2 == 0) widths[ci] - b.w else 0f
                    else -> (widths[ci] - b.w) / 2
                }
                rows += ShiftBox(b, 0f, x + align) to baseline
                x += widths[ci] + if (n.env.startsWith("align") && ci % 2 == 0) em * 0.15f else colGap
            }
            y = baseline + desc + rowGap
        }
        return StackBox(rows, width = totalW, align = -1)
    }

    private fun boxed(n: Boxed, style: Int): Box {
        val body = node(n.body, style)
        val em = sizeOf(style)
        val pad = em * 0.25f
        val stroke = Paint(inkPaint(style)).apply { this.style = Paint.Style.STROKE; strokeWidth = rule(style) }
        return DecoratedBox(body, pad, body.w + pad * 2, body.asc + pad, body.desc + pad) { c, x, y ->
            c.drawRect(x + stroke.strokeWidth / 2, y - body.asc - pad + stroke.strokeWidth / 2,
                x + body.w + pad * 2 - stroke.strokeWidth / 2, y + body.desc + pad - stroke.strokeWidth / 2, stroke)
        }
    }
}

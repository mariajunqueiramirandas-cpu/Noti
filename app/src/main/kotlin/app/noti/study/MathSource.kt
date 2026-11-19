package app.noti.study

/** A complete LaTeX span, at its offsets in the original text. */
internal data class MathSpan(val start: Int, val end: Int, val latex: String, val display: Boolean)

/** Recognize the delimiters shared by imported questions, generated lessons and study cards. */
internal fun mathSpans(source: String): List<MathSpan> = buildList {
    var i = 0
    while (i < source.length) {
        val delimiter = when {
            source.startsWith("$$", i) -> "$$" to "$$"
            source.startsWith("\\[", i) -> "\\[" to "\\]"
            source.startsWith("\\(", i) -> "\\(" to "\\)"
            source[i] == '$' && !source.isCurrencySign(i) -> "$" to "$"
            else -> null
        }
        if (delimiter != null) {
            val (open, close) = delimiter
            val from = i + open.length
            val display = open == "$$" || open == "\\["
            val end = source.findMathCloser(from, close, display)
            if (end > from && source.substring(from, end).isNotBlank()) {
                add(MathSpan(i, end + close.length, source.substring(from, end), display))
                i = end + close.length
                continue
            }
            i = if (end >= from) end + close.length else from
        } else {
            i += source.stepOverEscape(i)
        }
    }
}

private fun String.findMathCloser(from: Int, close: String, display: Boolean): Int {
    var i = from
    while (i < length) {
        if (!display && (this[i] == '\n' || this[i] == '\r')) return -1
        if (startsWith(close, i)) return if (close == "$" && startsWith("$$", i)) -1 else i
        i += stepOverEscape(i)
    }
    return -1
}

private fun String.stepOverEscape(at: Int): Int =
    if (this[at] == '\\' && (getOrNull(at + 1) == '\\' || getOrNull(at + 1) == '$')) 2 else 1

private fun String.isCurrencySign(dollar: Int): Boolean =
    getOrNull(dollar - 1) == 'R' && getOrNull(dollar - 2)?.isLetter() != true &&
        (dollar + 1 until length).firstOrNull { this[it] != ' ' && this[it] != '\t' }
            ?.let { this[it].isDigit() } == true

/** Linearized math for accessibility and one-line previews. Keep grouping and all values. */
internal fun readableStudyText(source: String): String = buildString {
    var end = 0
    for (span in mathSpans(source)) {
        append(source.substring(end, span.start).readableProse())
        append(Parser(span.latex).parseAll().joinToString("") { it.readable() })
        end = span.end
    }
    append(source.substring(end).readableProse())
}

private fun String.readableProse() = replace("\\$", "$").replace("**", "")

private fun Node.readable(): String = when (this) {
    is Sym -> text
    is Group -> nodes.joinToString("") { it.readable() }
    is Frac -> if (rule) "(${num.readable()})/(${den.readable()})" else "(${num.readable()} sobre ${den.readable()})"
    is Scripts -> base.readable() + (sub?.readable()?.script("₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎", "_").orEmpty()) +
        (sup?.readable()?.script("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾", "^").orEmpty())
    is Root -> "${index?.readable()?.script("⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾", "^").orEmpty()}√(${body.readable()})"
    is Accent -> body.readable() + when (kind) {
        "vec", "overrightarrow" -> "\u20d7"
        "bar", "overline" -> "\u0305"
        "dot" -> "\u0307"
        "ddot" -> "\u0308"
        "hat", "widehat" -> "\u0302"
        "tilde", "widetilde" -> "\u0303"
        else -> ""
    }
    is BigOp -> glyph
    is FuncName -> name
    is Delimited -> left.removeInvisibleDelimiter() + body.readable() + right.removeInvisibleDelimiter()
    is Space -> if (em > 0f) " " else ""
    is Words -> text
    is Matrix -> rows.joinToString("; ") { row -> row.joinToString(", ") { it.readable() } }
    is Boxed -> body.readable()
    is Annotation -> base.readable() + label.readable().script(if (above) "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁼⁽⁾" else "₀₁₂₃₄₅₆₇₈₉₊₋₌₍₎",
        if (above) "^" else "_")
}

private fun String.removeInvisibleDelimiter() = if (this == ".") "" else this

private fun String.script(alphabet: String, marker: String): String {
    val numbers = "0123456789+−=()"
    if (all { it in numbers }) return map { alphabet[numbers.indexOf(it)] }.joinToString("")
    return if (all { it == '′' }) this else "$marker($this)"
}

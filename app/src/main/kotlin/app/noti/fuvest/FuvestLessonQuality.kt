package app.noti.fuvest

import app.noti.study.mathSpans
import java.math.BigDecimal

internal data class FuvestReview(
    val fundamentals: List<String> = emptyList(),
    val practice: List<String> = emptyList(),
    val solution: List<String> = emptyList(),
) {
    val passed get() = fundamentals.isEmpty() && practice.isEmpty() && solution.isEmpty()

    operator fun plus(other: FuvestReview) = FuvestReview(
        fundamentals + other.fundamentals,
        practice + other.practice,
        solution + other.solution,
    )
}

internal fun checkFuvestLesson(lesson: FuvestLesson, question: FuvestQuestion): FuvestReview {
    fun checkSections(sections: List<FuvestLessonSection>): List<String> =
        sections.flatMap { section ->
            listOf(section.body, section.check, section.answer).flatMap { text ->
                contentIssues(text).map { "${section.title}: $it" }
            }
        }

    val practice = checkSections(lesson.sections.drop(3)).toMutableList()
    val sourceWords = contentWords(question.prompt)
    val seen = mutableListOf<Set<String>>()
    lesson.exercises.forEachIndexed { index, exercise ->
        val words = contentWords(exercise.prompt)
        if (sourceDependent.containsMatchIn(normalizeTopic(exercise.prompt)) || overlaps(words, sourceWords)) {
            practice += "O exercício ${index + 1} reaproveita a questão da prova ou depende dos dados dela. Crie um enunciado autônomo em outro contexto."
        }
        if (seen.any { words == it || overlaps(words, it) && overlaps(it, words) }) {
            practice += "O exercício ${index + 1} repete outro exercício. Mude a situação e a decisão matemática."
        }
        seen += words
        listOf(exercise.prompt, exercise.hint, exercise.solution).forEach { text ->
            practice += contentIssues(text).map { "Exercício ${index + 1}: $it" }
        }
    }
    return FuvestReview(
        (contentIssues(lesson.goal) + checkSections(lesson.sections.take(3))).distinct(),
        (practice + lesson.checklist.flatMap(::contentIssues)).distinct(),
        (listOf(lesson.solution.answer) + lesson.solution.hints + lesson.solution.steps).flatMap(::contentIssues).distinct(),
    )
}

private val strayLanguage = Regex("""\b(?:equivalently|first decide)\b""", RegexOption.IGNORE_CASE)
private val backstage = Regex("""(?i)\b(?:payload|body|check|answer):[ \t]*""")
private val equatesDiscontinuityWithJump = Regex("""\bdescontinuidade (?:isto e|ou seja|significa) (?:um )?salto\b""")
private val unrelatedScripts = setOf(
    Character.UnicodeScript.HAN, Character.UnicodeScript.HANGUL,
    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
)

private fun contentIssues(text: String): List<String> = wrongLinearEquivalences(text) + listOfNotNull(
    incompatibleComposition(text),
    "Diferencie a descontinuidade em geral de uma descontinuidade por salto.".takeIf {
        equatesDiscontinuityWithJump.containsMatchIn(normalizeTopic(text))
    },
    "Reescreva o trecho em português fluente.".takeIf {
        strayLanguage.containsMatchIn(text) || text.any { char -> Character.UnicodeScript.of(char.code) in unrelatedScripts }
    },
    "Retire os nomes de campos e notas de bastidores do texto da aluna.".takeIf { backstage.containsMatchIn(text) },
)

private val statedUnits = Regex(
    """\b([a-z])\([a-z]\)[${'$'}]?\s*,\s*em ([\p{L} ]+),\s*(?:conforme|em função de|de acordo com)[^.:;]*?\bem ([\p{L} ]+)""",
    RegexOption.IGNORE_CASE,
)

private val unitDimensions = mapOf(
    "graus celsius" to "temperatura", "graus fahrenheit" to "temperatura", "kelvin" to "temperatura",
    "segundos" to "tempo", "minutos" to "tempo", "horas" to "tempo",
    "litros" to "volume", "litros por minuto" to "vazão", "litros por segundo" to "vazão",
    "metros" to "comprimento", "quilometros" to "comprimento",
    "metros por segundo" to "velocidade", "quilometros por hora" to "velocidade",
    "gramas" to "massa", "quilogramas" to "massa", "reais" to "custo",
)

/** A clear dimensional mismatch in a prompt that explicitly declares both units and asks to compose. */
private fun incompatibleComposition(text: String): String? {
    val match = statedUnits.find(text) ?: return null
    val (function, outputUnit, inputUnit) = match.destructured
    val input = unitDimensions[normalizeTopic(inputUnit)] ?: return null
    val output = unitDimensions[normalizeTopic(outputUnit)] ?: return null
    val asksToCompute = Regex(
        """\b(?:calcule|determine|encontre)\b[^.!?]*\b$function\(\s*$function\(""", RegexOption.IGNORE_CASE,
    ).containsMatchIn(text)
    if (input == output || !asksToCompute) return null
    return "A composição $function($function(…)) usa $outputUnit como entrada, mas a função espera $inputUnit. " +
        "Use uma composição com grandezas compatíveis ou uma situação matemática sem unidades."
}

private val stepFieldLabel = Regex("""(?i)^\s*item ([a-z])\s*[—–-]\s*(?:body|check|answer):[ \t]*""")
private val stepPayload = Regex("""(?im)(?:^|[\r\n]|\\n)+[ \t]*payload:[^\r\n]*""")

internal fun polishSolutionStep(value: String): String = value
    .replace(stepPayload, "").replace(stepFieldLabel) { "Item ${it.groupValues[1]}. " }.trim()

private val sourceDependent = Regex(
    """\b(?:dados|valores|tarifas|empresas|figura|enunciado|questao|item)\s+(?:da|do|desta|deste)\s+(?:questao|prova|enunciado|fuvest)\b""",
)

private val teachingWords = setOf(
    "calcule", "compare", "considere", "determine", "encontre", "escreva", "explique", "justifique",
    "questao", "exercicio", "demonstre", "intervalo", "valores", "resultado", "funcao", "dados",
)

private fun contentWords(text: String): Set<String> = normalizeTopic(text).split(' ')
    .filterTo(mutableSetOf()) { (it.length >= 4 || it.length >= 2 && it.all(Char::isDigit)) && it !in teachingWords }

private fun overlaps(candidate: Set<String>, reference: Set<String>): Boolean =
    candidate.size >= 4 && candidate.count { it in reference } * 4 >= candidate.size * 3

private val equivalence = Regex("""\\iff|\\Leftrightarrow|⇔|⟺""")
private val proseEquivalence = Regex("""^\s*[,;:]?\s*(?:é\s+)?equivalente\s+a\s*$|^\s+equivale\s+a\s*$""",
    RegexOption.IGNORE_CASE)

/** Check equivalences of affine comparisons with one variable. Unrecognized notation goes to review. */
internal fun wrongLinearEquivalences(text: String): List<String> {
    val math = mathSpans(text)
    val pairs = math.flatMap { span -> equivalence.split(span.latex).zipWithNext() } +
        math.zipWithNext().mapNotNull { (left, right) ->
            val between = text.substring(left.end, right.start)
            if (proseEquivalence.matches(between)) left.latex to right.latex else null
        }
    return pairs.mapNotNull { (left, right) ->
        val first = linearComparison(left) ?: return@mapNotNull null
        val second = linearComparison(right) ?: return@mapNotNull null
        if (first.first != second.first || first.second.sameAs(second.second)) null
        else "As comparações $left e $right não são equivalentes. Recalcule a passagem e confira o limite."
    }.distinct()
}

private data class Ratio(val numerator: BigDecimal, val denominator: BigDecimal = BigDecimal.ONE) {
    operator fun plus(other: Ratio) = Ratio(numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator)
    operator fun unaryMinus() = Ratio(-numerator, denominator)
    operator fun div(other: Ratio): Ratio =
        Ratio(numerator * other.denominator * BigDecimal(other.numerator.signum()), denominator * other.numerator.abs())
    fun sign() = numerator.signum()
    fun sameAs(other: Ratio) = (numerator * other.denominator).compareTo(other.numerator * denominator) == 0
}

private enum class Relation { Less, LessOrEqual, Equal, MoreOrEqual, More;
    fun flip() = entries[entries.lastIndex - ordinal]
}

private data class LinearResult(val relation: Relation, val boundary: Ratio) {
    fun sameAs(other: LinearResult) = relation == other.relation && boundary.sameAs(other.boundary)
}

private val relationPattern = Regex("<=|>=|[<>=]")
private val fraction = Regex("""\\(?:d?frac|tfrac)\{(-?\d{1,12})}\{(\d{1,12})}""")
private val coefficient = Regex("""([+-]?)(\d{1,12}(?:[,.]\d{1,8})?)?([a-z])?(?:/(\d{1,12}))?""")
private val signedTerm = Regex("""[+-]?[^+-]+""")

private fun linearComparison(source: String): Pair<Char, LinearResult>? {
    val text = fraction.replace(source) { m -> "${m.groupValues[1]}/${m.groupValues[2]}" }
        .replace(Regex("""\\leq?(?![a-z])|≤"""), "<=")
        .replace(Regex("""\\geq?(?![a-z])|≥"""), ">=")
        .replace("−", "-").replace(Regex("""\s+|\\[,;!]"""), "")
    val relation = relationPattern.findAll(text).singleOrNull() ?: return null
    val left = text.substring(0, relation.range.first)
    val right = text.substring(relation.range.last + 1)
    val variable = text.filter { it in 'a'..'z' }.toSet().singleOrNull() ?: return null
    val (leftSlope, leftIntercept) = affineTerms(left) ?: return null
    val (rightSlope, rightIntercept) = affineTerms(right) ?: return null
    val slope = leftSlope + -rightSlope
    if (slope.sign() == 0) return null
    val comparison = when (relation.value) {
        "<" -> Relation.Less
        "<=" -> Relation.LessOrEqual
        ">" -> Relation.More
        ">=" -> Relation.MoreOrEqual
        else -> Relation.Equal
    }
    return variable to LinearResult(
        if (slope.sign() < 0) comparison.flip() else comparison,
        (rightIntercept + -leftIntercept) / slope,
    )
}

private fun affineTerms(expression: String): Pair<Ratio, Ratio>? {
    val terms = signedTerm.findAll(expression).map { it.value }.toList()
    if (terms.isEmpty() || terms.joinToString("") != expression) return null
    var slope = Ratio(BigDecimal.ZERO)
    var intercept = Ratio(BigDecimal.ZERO)
    for (term in terms) {
        val match = coefficient.matchEntire(term) ?: return null
        val sign = match.groupValues[1]
        val digits = match.groupValues[2]
        val variable = match.groupValues[3]
        if (digits.isEmpty() && variable.isEmpty()) return null
        val denominator = match.groupValues[4].ifEmpty { "1" }.toBigDecimal()
        if (denominator.signum() == 0) return null
        val value = Ratio((sign + digits.ifEmpty { "1" }).replace(',', '.').toBigDecimal(), denominator)
        if (variable.isEmpty()) intercept += value else slope += value
    }
    return slope to intercept
}

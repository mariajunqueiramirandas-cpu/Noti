package app.noti.assaad

import app.noti.study.BoardStudy
import app.noti.study.CardFace
import app.noti.study.ItemKind
import app.noti.study.Srs
import app.noti.study.StudyItem
import app.noti.study.Verdict

/** Board study items made from Assaad questions are named after the question. */
const val ASSAAD_ITEM_PREFIX = "assaad-"
fun assaadItemId(questionId: String) = ASSAAD_ITEM_PREFIX + questionId
fun assaadQuestionId(itemId: String): String? = itemId.takeIf { it.startsWith(ASSAAD_ITEM_PREFIX) }?.removePrefix(ASSAAD_ITEM_PREFIX)
/** Every Assaad question says where it's from, on the card and diagonally across it. */
const val ASSAAD_CREDIT = "Plataforma Assaad · Pedro Assaad"

private val imageMark = Regex("\\s*\\[Imagem]\\s*")

/**
 * An Assaad question as a study board item: the same multiple choice card as any other.
 * Its right option is unknown ([correctChoice] -1) until Assaad checks an answer.
 */
fun AssaadQuestion.studyItem(createdAt: Long, correctChoice: Int = -1): StudyItem = StudyItem(
    id = assaadItemId(id),
    kind = ItemKind.Choice,
    topic = tags.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Plataforma Assaad",
    prompt = prompt.replace(imageMark, " ${CardFace.FIGURE} ").trim(),
    choices = alternatives.map { it.text.replace(imageMark, " ").trim() },
    correctChoice = correctChoice,
    answer = alternatives.getOrNull(correctChoice)?.let { "${it.label}) ${it.text.replace(imageMark, " ").trim()}" } ?: "",
    excerpt = source,
    difficulty = when (difficulty?.lowercase()) { "fácil", "facil", "easy" -> 1; "difícil", "dificil", "hard" -> 3; else -> 2 },
    createdAt = createdAt,
    // An image Noti won't load keeps its place as a placeholder, so the next one doesn't take it.
    figures = images.map { it.url ?: "" },
    choiceFigures = alternatives.map { a -> a.images.map { it.url ?: "" } },
    credit = ASSAAD_CREDIT,
)

/**
 * The board's study material with every saved Assaad question on it, in saved order, and
 * what Assaad said about each answer: the chosen option, the right one, and a review
 * schedule once it's checked. Questions no saved set holds any more leave the board unless
 * they're printed on it. Everything else on the board is left as it is.
 */
fun BoardStudy.withAssaad(questions: List<AssaadQuestion>, answer: (String) -> AssaadAnswer?, now: Long): BoardStudy {
    val existing = items.associateBy { it.id }
    val wanted = LinkedHashMap<String, StudyItem>()
    var nextProgress = progress
    for (q in questions) {
        val id = assaadItemId(q.id)
        val checked = answer(q.id)?.takeIf { it.status == "confirmed" }
        val chosen = checked?.let { a -> q.alternatives.indexOfFirst { it.id == a.alternativeId } } ?: -1
        val right = checked?.correctAlternativeId?.let { rid -> q.alternatives.indexOfFirst { it.id == rid } }?.takeIf { it >= 0 }
            ?: if (checked?.correct == true) chosen else -1
        val item = q.studyItem(existing[id]?.createdAt?.takeIf { it > 0 } ?: now, right)
        wanted[id] = item
        val p = nextProgress[id] ?: app.noti.study.Progress()
        val updated = when {
            chosen >= 0 && p.chosen != chosen -> p.copy(chosen = chosen, attempts = p.attempts + 1,
                srs = p.srs ?: Srs.review(null, Srs.ratingFor(if (chosen == right) Verdict.Correct else Verdict.Incorrect, 0, false, item.difficulty), now))
            chosen < 0 && p.chosen >= 0 -> p.copy(chosen = -1)
            else -> p
        }
        if (updated != p) nextProgress = nextProgress + (id to updated)
    }
    val printed = cards.mapTo(HashSet()) { it.itemId }
    val kept = items.mapNotNull { item ->
        when {
            item.id in wanted -> wanted.remove(item.id)
            assaadQuestionId(item.id) != null && item.id !in printed -> null
            else -> item
        }
    }
    val nextItems = kept + wanted.values
    val gone = items.map { it.id }.toSet() - nextItems.map { it.id }.toSet()
    return copy(items = nextItems, progress = nextProgress - gone, current = current?.takeIf { it !in gone })
}

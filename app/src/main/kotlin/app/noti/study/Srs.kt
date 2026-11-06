package app.noti.study

import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/** How well you knew it. */
enum class Rating(val value: Int, val label: String) {
    Again(1, "Again"), Hard(2, "Hard"), Good(3, "Good"), Easy(4, "Easy");
    companion object { fun of(v: Int) = entries.firstOrNull { it.value == v } ?: Good }
}

/** An item's memory state for spaced repetition (FSRS). [stability] is in days. */
data class SrsState(
    val stability: Double,
    val difficulty: Double,
    val due: Long,
    val lastReview: Long,
    val reps: Int,
    val lapses: Int,
    val lastRating: Rating,
) {
    fun json(): JSONObject = JSONObject().put("s", stability).put("d", difficulty).put("due", due).put("last", lastReview)
        .put("reps", reps).put("lapses", lapses).put("rating", lastRating.value)

    companion object {
        fun from(o: JSONObject) = SrsState(
            stability = o.getDouble("s").also { require(it > 0 && it.isFinite()) },
            difficulty = o.getDouble("d").coerceIn(1.0, 10.0),
            due = o.getLong("due"), lastReview = o.getLong("last"),
            reps = o.optInt("reps"), lapses = o.optInt("lapses"), lastRating = Rating.of(o.optInt("rating", 3)),
        )
    }
}

/**
 * Free Spaced Repetition Scheduler (FSRS-4.5, default parameters) aiming for 90% recall:
 * at that retention the next interval equals the stability. A forgotten item ([Rating.Again])
 * comes back after [RELEARN_MS] in the same session.
 */
object Srs {
    private val w = doubleArrayOf(0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474,
        0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755)
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    private const val RETENTION = 0.9
    const val DAY_MS = 86_400_000L
    const val RELEARN_MS = 10 * 60_000L
    private const val MAX_DAYS = 36500.0

    /** Probability of recalling after [days] at [stability]. */
    fun retrievability(days: Double, stability: Double) = (1 + FACTOR * days / stability).pow(DECAY)

    fun intervalDays(stability: Double): Double =
        (stability / FACTOR * (RETENTION.pow(1 / DECAY) - 1)).coerceIn(1.0, MAX_DAYS)

    private fun initDifficulty(r: Rating) = (w[4] - (r.value - 3) * w[5]).coerceIn(1.0, 10.0)

    fun review(state: SrsState?, rating: Rating, now: Long): SrsState {
        if (state == null) {
            val s = w[rating.value - 1]
            return SrsState(s, initDifficulty(rating), due(now, rating, s), now, 1, if (rating == Rating.Again) 1 else 0, rating)
        }
        val days = max(0.0, (now - state.lastReview).toDouble() / DAY_MS)
        val r = retrievability(days, state.stability)
        val d = state.difficulty
        val nextD = (w[7] * initDifficulty(Rating.Good) + (1 - w[7]) * (d - w[6] * (rating.value - 3))).coerceIn(1.0, 10.0)
        val nextS = if (rating == Rating.Again) {
            min(state.stability, w[11] * d.pow(-w[12]) * ((state.stability + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r)))
        } else {
            val hard = if (rating == Rating.Hard) w[15] else 1.0
            val easy = if (rating == Rating.Easy) w[16] else 1.0
            state.stability * (exp(w[8]) * (11 - d) * state.stability.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hard * easy + 1)
        }.coerceIn(0.01, MAX_DAYS)
        return SrsState(nextS, nextD, due(now, rating, nextS), now, state.reps + 1,
            state.lapses + if (rating == Rating.Again) 1 else 0, rating)
    }

    private fun due(now: Long, rating: Rating, stability: Double): Long =
        if (rating == Rating.Again) now + RELEARN_MS else now + (intervalDays(stability) * DAY_MS).roundToLong()

    /**
     * A checked answer as a rating: right without help is Good (Easy on a first try of
     * something hard), each hint or a revealed answer costs a step.
     */
    fun ratingFor(verdict: Verdict, hintsShown: Int, answerShown: Boolean, difficulty: Int = 2): Rating = when (verdict) {
        Verdict.Incorrect -> Rating.Again
        Verdict.Partial -> if (answerShown) Rating.Again else Rating.Hard
        Verdict.Correct -> when {
            answerShown || hintsShown >= 2 -> Rating.Hard
            hintsShown == 0 && difficulty >= 3 -> Rating.Easy
            else -> Rating.Good
        }
    }
}

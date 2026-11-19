package app.noti.exams

import app.noti.study.Progress
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class StudyMilestone(val wire: String, val xp: Int) {
    Foundations("foundations", 10),
    MentalModel("mental_model", 10),
    Reasoning("reasoning", 10),
    Examples("examples", 10),
    Strategy("strategy", 10),
    Basics("basics", 15),
    Application("application", 15),
    Transfer("transfer", 15);

    val chapterIndex: Int? get() = ordinal.takeIf { it < Basics.ordinal }
    val exerciseIndex: Int? get() = (ordinal - Basics.ordinal).takeIf { it >= 0 }
}

internal fun ExamLesson.milestones() = StudyMilestone.entries.filter {
    it.chapterIndex?.let(sections.indices::contains) == true ||
        it.exerciseIndex?.let(exercises.indices::contains) == true
}

internal fun StudyMilestone.title(lesson: ExamLesson): String =
    chapterIndex?.let { lesson.sections[it].title } ?: when (this) {
        StudyMilestone.Basics -> "Exercício de fundamentos"
        StudyMilestone.Application -> "Exercício de aplicação"
        else -> "Desafio de transferência"
    }

internal data class StudyJourney(
    val completed: Map<String, Map<StudyMilestone, Long>> = emptyMap(),
    val lastTopic: String? = null,
    val inProgress: Map<String, StudyMilestone> = emptyMap(),
) {
    fun milestones(topic: String): Set<StudyMilestone> = completed[topic]?.keys.orEmpty()

    fun open(topic: String, step: StudyMilestone): StudyJourney {
        if (step in milestones(topic)) return this
        return copy(lastTopic = topic, inProgress = inProgress + (topic to step))
    }

    fun complete(topic: String, step: StudyMilestone, at: Long): StudyJourney {
        val marks = completed[topic].orEmpty()
        if (step in marks) return this
        return copy(
            completed = completed + (topic to (marks + (step to at))),
            lastTopic = topic,
            inProgress = if (inProgress[topic] == step) inProgress - topic else inProgress,
        )
    }

    fun migrateKeys(remap: (String) -> String): StudyJourney = StudyJourney(
        completed = completed.entries.associate { (k, v) -> remap(k) to v },
        lastTopic = lastTopic?.let(remap),
        inProgress = inProgress.entries.associate { (k, v) -> remap(k) to v },
    )

    fun overview(
        questions: Map<String, Progress>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): StudyOverview {
        val marks = completed.values.flatMap { it.entries }
        val attempts = questions.values.filter { it.attempts > 0 }
        fun isToday(at: Long) = at > 0 && Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == today
        val todayCount = marks.count { isToday(it.value) } +
            attempts.count { it.srs?.lastReview?.let(::isToday) == true }
        return StudyOverview(
            xp = marks.sumOf { it.key.xp } + attempts.size * StudyOverview.QuestionXp,
            today = todayCount,
        )
    }

    fun json(): JSONObject = JSONObject().put("completed", JSONObject().apply {
        completed.forEach { (topic, marks) ->
            put(topic, JSONObject().apply { marks.forEach { (step, time) -> put(step.wire, time) } })
        }
    }).put("in_progress", JSONObject().apply {
        inProgress.forEach { (topic, step) -> put(topic, step.wire) }
    }).apply { lastTopic?.let { put("last_topic", it) } }

    companion object {
        fun from(json: JSONObject): StudyJourney {
            val stored = json.optJSONObject("completed") ?: JSONObject()
            val completed = stored.keys().asSequence().mapNotNull { topic ->
                val marks = stored.optJSONObject(topic) ?: return@mapNotNull null
                val steps = StudyMilestone.entries.mapNotNull { step ->
                    marks.optLong(step.wire).takeIf { it > 0 }?.let { step to it }
                }.toMap()
                (topic to steps).takeIf { steps.isNotEmpty() }
            }.toMap()
            val open = json.optJSONObject("in_progress") ?: JSONObject()
            val inProgress = open.keys().asSequence().mapNotNull { topic ->
                StudyMilestone.entries.firstOrNull { it.wire == open.optString(topic) }?.let { topic to it }
            }.toMap()
            return StudyJourney(completed, json.optString("last_topic").ifBlank { null }, inProgress)
        }
    }
}

internal data class StudyOverview(val xp: Int, val today: Int) {
    val level: Int get() = xp / LevelXp + 1
    val levelProgress: Float get() = (xp % LevelXp).toFloat() / LevelXp

    companion object {
        const val QuestionXp = 20
        const val LevelXp = 100
        const val DailyGoal = 3
    }
}

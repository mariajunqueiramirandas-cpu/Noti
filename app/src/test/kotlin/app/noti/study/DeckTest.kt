package app.noti.study

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeckTest {
    private fun item(id: String, kind: ItemKind = ItemKind.Short) = StudyItem(
        id = id, kind = kind, topic = "Kinematics", prompt = "A car accelerates at $2\\,\\mathrm{m/s^2}$. Find \$v$ after $3\\,\\mathrm{s}$.",
        choices = if (kind == ItemKind.Choice) listOf("1", "2", "3", "6") else emptyList(), correctChoice = if (kind == ItemKind.Choice) 3 else -1,
        answer = "\$v = 6\\,\\mathrm{m/s}$", solution = listOf("\$v = at$", "\$v = 2 \\cdot 3 = 6$"), hints = listOf("Use \$v = v_0 + at$."),
        sourceId = "pdf-1", pages = listOf(12, 13), excerpt = "Uniform acceleration", difficulty = 2, createdAt = 5L,
    )

    @Test fun everythingSurvivesARoundTrip() {
        val now = 1_000_000L
        val study = BoardStudy(
            sources = listOf(StudySource("pdf-1", "Physics 1", 40)),
            items = listOf(item("a"), item("b", ItemKind.Choice), item("c", ItemKind.Flash)),
            progress = mapOf(
                "a" to Progress(hintsShown = 1, answerShown = true, grade = Grade(Verdict.Partial, 0.5f, "Sign error",
                    listOf(StepNote(true, "ok"), StepNote(false, "sign")), read = "v = -6", at = now), attempts = 2,
                    srs = Srs.review(null, Rating.Hard, now)),
                "b" to Progress(chosen = 3),
            ),
            cards = listOf(PinnedCard("k1", "a", 512.5f, 300.25f, 360f, 0.0625f, now)),
            labels = listOf(PinnedLabel("l1", "Kinematics", 500f, 290f, 0.0625f)),
            pages = listOf(PlacedPage("p1", "pdf-1", 11, 400f, 260f, 40f)),
            exams = listOf(Exam("e1", listOf("a", "b"), 20, now, now + 5, PinnedCard("s1", "", 1f, 2f, 300f, 0.06f))),
            current = "b",
        )
        val back = BoardStudy.decode(study.encode())
        assertEquals(study, back)
    }

    @Test fun brokenEntriesAreSkippedNotFatal() {
        val text = """{"version":1,"items":[{"id":"x","prompt":"ok"},{"nope":1}],"cards":[{"id":"c","itemId":"x","x":1,"y":2,"widthDp":300,"unitsPerDp":-1}],"future":true}"""
        val study = BoardStudy.decode(text)
        assertEquals(listOf("x"), study.items.map { it.id })
        assertTrue("a card with an impossible scale is dropped", study.cards.isEmpty())
    }

    @Test fun studyOrderPutsDueFirstThenNewThenTheRest() {
        val now = 10 * Srs.DAY_MS
        val study = BoardStudy(
            items = listOf(item("seen"), item("new1"), item("due"), item("new2")),
            progress = mapOf(
                "seen" to Progress(srs = Srs.review(null, Rating.Good, now)),
                "due" to Progress(srs = Srs.review(null, Rating.Good, now - 9 * Srs.DAY_MS)),
            ),
        )
        assertEquals(listOf("due", "new1", "new2", "seen"), study.studyOrder(now).map { it.id })
        assertEquals(1, study.dueCount(now))
    }

    @Test fun outcomeComesFromTheChoiceTheCheckOrTheRating() {
        val choice = item("b", ItemKind.Choice)
        assertEquals(Verdict.Correct, Progress(chosen = 3).outcome(choice))
        assertEquals(Verdict.Incorrect, Progress(chosen = 0).outcome(choice))
        assertNull(Progress().outcome(choice))
        assertEquals(Verdict.Partial, Progress(grade = Grade(Verdict.Partial, 0.5f, "")).outcome(item("a")))
        val flash = item("c", ItemKind.Flash)
        assertEquals(Verdict.Incorrect, Progress(srs = Srs.review(null, Rating.Again, 0)).outcome(flash))
        assertEquals(Verdict.Correct, Progress(srs = Srs.review(null, Rating.Easy, 0)).outcome(flash))
    }
}

class SrsTest {
    @Test fun goodReviewsSpaceOutAndAgainComesBackSoon() {
        var s = Srs.review(null, Rating.Good, 0)
        val first = s.due
        assertTrue(first >= Srs.DAY_MS)
        val intervals = ArrayList<Long>()
        repeat(4) {
            val next = Srs.review(s, Rating.Good, s.due)
            intervals += next.due - s.due
            s = next
        }
        assertTrue("intervals grow: \$intervals", intervals.zipWithNext().all { (a, b) -> b > a })
        val lapse = Srs.review(s, Rating.Again, s.due)
        assertEquals(s.due + Srs.RELEARN_MS, lapse.due)
        assertEquals(1, lapse.lapses)
        assertTrue(lapse.stability < s.stability)
    }

    @Test fun easyBeatsGoodBeatsHard() {
        val base = Srs.review(null, Rating.Good, 0)
        val t = base.due
        val hard = Srs.review(base, Rating.Hard, t).due
        val good = Srs.review(base, Rating.Good, t).due
        val easy = Srs.review(base, Rating.Easy, t).due
        assertTrue(hard < good && good < easy)
    }

    @Test fun ninetyPercentRecallAtTheInterval() {
        val s = 12.0
        assertEquals(0.9, Srs.retrievability(Srs.intervalDays(s), s), 1e-6)
    }

    @Test fun checkedAnswersBecomeRatings() {
        assertEquals(Rating.Again, Srs.ratingFor(Verdict.Incorrect, 0, false))
        assertEquals(Rating.Hard, Srs.ratingFor(Verdict.Partial, 0, false))
        assertEquals(Rating.Again, Srs.ratingFor(Verdict.Partial, 0, true))
        assertEquals(Rating.Good, Srs.ratingFor(Verdict.Correct, 1, false))
        assertEquals(Rating.Hard, Srs.ratingFor(Verdict.Correct, 2, false))
        assertEquals(Rating.Easy, Srs.ratingFor(Verdict.Correct, 0, false, difficulty = 3))
    }
}

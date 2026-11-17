package app.noti.assaad

import app.noti.study.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AssaadStudyItemsTest {
    private val now = 1_800_000_000_000L
    private fun question(images: Boolean = false): AssaadQuestion {
        val o = JSONObject(javaClass.getResource("/assaad-batch.json")!!.readText())
        if (images) o.getJSONArray("questions").getJSONObject(0)
            .put("prompt", "Observe o mapa. [Imagem] Qual rio favoreceu a agricultura egípcia?")
            .put("images", org.json.JSONArray().put(JSONObject().put("alt", "mapa").put("url", "https://cdn.plataformaassaad.com.br/mapa.png")))
        return AssaadBatch.parse(o).questions.single()
    }

    @Test fun aSavedQuestionBecomesAnOrdinaryUncheckedChoiceCard() {
        val board = BoardStudy().withAssaad(listOf(question(images = true)), { null }, now)
        val item = board.items.single()
        assertEquals(assaadItemId("q-1"), item.id)
        assertEquals(ItemKind.Choice, item.kind)
        assertEquals(listOf("Rio Nilo", "Rio Amazonas"), item.choices)
        assertEquals("no answer key is invented", -1, item.correctChoice)
        assertEquals("Egito", item.topic)
        assertEquals(listOf("https://cdn.plataformaassaad.com.br/mapa.png"), item.figures)
        assertTrue("the picture keeps its place in the prompt", item.prompt.contains(CardFace.FIGURE))
        assertTrue(board.isNew(item.id))
    }

    @Test fun assaadsCheckSetsTheChoiceAndTheRightOptionOnce() {
        val q = question()
        val answer = AssaadAnswer("c", "a-2", "confirmed", "Resposta incorreta.", correct = false, correctAlternativeId = "a-1")
        val checked = BoardStudy().withAssaad(listOf(q), { answer }, now)
        val item = checked.items.single()
        assertEquals(0, item.correctChoice)
        assertEquals(1, checked.progressOf(item.id).chosen)
        assertEquals(Verdict.Incorrect, checked.progressOf(item.id).outcome(item))
        assertNotNull(checked.progressOf(item.id).srs)
        assertSame("syncing again changes nothing", checked.progressOf(item.id), checked.withAssaad(listOf(q), { answer }, now + 1).progressOf(item.id))
        // Pending answers are not checked yet.
        val pending = BoardStudy().withAssaad(listOf(q), { answer.copy(status = "pending") }, now)
        assertEquals(-1, pending.progressOf(item.id).chosen)
    }

    @Test fun removedSetsLeaveTheBoardUnlessPrinted() {
        val q = question()
        val board = BoardStudy(items = listOf(StudyItem("mine", ItemKind.Short, "Own", "Keep me"))).withAssaad(listOf(q), { null }, now)
        assertEquals(listOf("mine", assaadItemId("q-1")), board.items.map { it.id })
        assertEquals(listOf("mine"), board.withAssaad(emptyList(), { null }, now).items.map { it.id })
        val printed = board.copy(cards = listOf(PinnedCard("c", assaadItemId("q-1"), 0f, 0f, 300f, 1f, now)))
        assertEquals(2, printed.withAssaad(emptyList(), { null }, now).items.size)
    }
}

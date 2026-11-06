package app.noti.study

import app.noti.data.LibraryStore
import app.noti.data.Paper
import app.noti.data.PaperSize
import app.noti.data.PaperTemplate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BoardExportTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private val study = BoardStudy(
        items = listOf(StudyItem("q", ItemKind.Short, "Optics", "Snell's law: find $\\theta_2$.", answer = "$30^\\circ$")),
        cards = listOf(PinnedCard("c", "q", 600f, 500f, 360f, 0.0625f)),
        labels = listOf(PinnedLabel("l", "Optics", 600f, 495f, 0.0625f)),
    )

    @Test fun boundsCoverEveryPrintedCardAndHeading() {
        val export = BoardExport.of(context, study) { null }!!
        val b = export.bounds!!
        assertEquals(600f, b.left, 0.01f)
        assertTrue("the heading sits above the card", b.top < 495f)
        assertEquals(600f + 360f * 0.0625f, b.right, 0.01f)
        assertTrue(b.bottom > 500f + 60f * 0.0625f)
    }

    @Test fun nothingPrintedMeansNothingToDraw() {
        assertNull(BoardExport.of(context, study.copy(cards = emptyList(), labels = emptyList())) { null })
    }

    @Test fun aBoardsSavedStudyIsWhatExportReads() {
        val library = LibraryStore(context)
        val nb = library.create("Optics", Paper(PaperTemplate.Grid), null, board = true)
        assertTrue(nb.board && nb.isCanvas)
        assertEquals(PaperSize.Endless, nb.paper.size)
        StudyStore.load(library.studyFile(nb)).update { study }
        // Saves are written in order on their own thread; wait for this one.
        val deadline = System.currentTimeMillis() + 5000
        while (BoardExport.read(library.studyFile(nb)) != study && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(study, BoardExport.read(library.studyFile(nb)))
        // A board stays a board (and Endless) through a paper change and a reload.
        library.setPaper(nb.id, Paper(PaperTemplate.Dots, size = PaperSize.A4))
        val reloaded = LibraryStore(context).notebook(nb.id)!!
        assertTrue(reloaded.board)
        assertEquals(PaperSize.Endless, reloaded.paper.size)
        assertEquals(PaperTemplate.Dots, reloaded.paper.template)
    }
}

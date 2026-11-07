package app.noti.lines

import app.noti.transcribe.SelectedStroke
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextBlocksTest {
    private val plum = floatArrayOf(0.36f, 0.29f, 0.53f, 1f)

    /** A stroke with [n] capsules starting at ([x], [y]), as InkLines.parse would give it. */
    private fun stroke(id: Int, n: Int, x: Float, y: Float, color: FloatArray = plum, page: Int = 2) =
        SelectedStroke(id, page, color, FloatArray(2 + n * 2) { i -> if (i % 2 == 0) x + i else y })

    private val block = TextBlock(
        id = "b1", page = 2, text = "Ada Lovelace", style = TypeStyle(align = TextAlign.Center, bold = true),
        em = 12.5f, color = plum,
        lines = listOf(WriteLine(100f, 400f, 300f, 0.01f, 20f, 140f, LineKind.Comb, cells = floatArrayOf(100f, 250f, 400f))),
        words = listOf(WordMark(5, 141f, 297f), WordMark(9, 190f, 297f)),
    )

    @Test fun roundTripsThroughJson() {
        val back = TextBlocksCodec.decode(TextBlocksCodec.encode(listOf(block))).single()
        assertEquals(block, back)
        assertEquals(12.5f, back.em)
        assertNull(back.style.size)
        assertArrayEquals(floatArrayOf(100f, 250f, 400f), back.lines.single().cells, 0f)
    }

    @Test fun foundWhereItIsAndWhereItWasMoved() {
        val ink = listOf(stroke(3, 7, 50f, 50f), stroke(8, 5, 141f, 297f), stroke(9, 9, 190f, 297f))
        val m = BlockMatcher.find(listOf(block), 2, ink).single()
        assertArrayEquals(intArrayOf(8, 9), m.ids)
        assertEquals(0f, m.dx); assertEquals(0f, m.dy)

        val moved = listOf(stroke(11, 5, 141f + 30.25f, 297f - 12f), stroke(12, 9, 190f + 30.25f, 297f - 12f))
        val mm = BlockMatcher.find(listOf(block), 2, moved).single()
        assertEquals(30.25f, mm.dx, 1e-4f); assertEquals(-12f, mm.dy, 1e-4f)
        assertEquals(130.25f, mm.lines.single().x0, 1e-4f)
        assertTrue(mm.contains(171.5f, 285f, 2f))
    }

    @Test fun notFoundWhenAWordIsGoneTheColorDiffersOrOnAnotherPage() {
        assertTrue(BlockMatcher.find(listOf(block), 2, listOf(stroke(8, 5, 141f, 297f))).isEmpty())
        val red = floatArrayOf(0.8f, 0.1f, 0.1f, 1f)
        assertTrue(BlockMatcher.find(listOf(block), 2, listOf(stroke(8, 5, 141f, 297f, red), stroke(9, 9, 190f, 297f, red))).isEmpty())
        assertTrue(BlockMatcher.find(listOf(block), 3, listOf(stroke(8, 5, 141f, 297f, page = 3), stroke(9, 9, 190f, 297f, page = 3))).isEmpty())
        // Words moved apart (one dragged away alone) are not the block any more.
        assertTrue(BlockMatcher.find(listOf(block), 2, listOf(stroke(8, 5, 141f, 297f), stroke(9, 9, 260f, 297f))).isEmpty())
    }

    @Test fun marksComeFromTheInkThatWasPlaced() {
        val capsules = floatArrayOf(1f, 2f, 0.5f, 3f, 4f, 0.5f, 9f, 8f, 0.5f, 7f, 6f, 0.5f, 5f, 4f, 0.5f, 3f, 2f, 0.5f)
        assertEquals(listOf(WordMark(1, 1f, 2f), WordMark(2, 9f, 8f)), TextBlock.marks(capsules, intArrayOf(1, 2)))
    }

    @Test fun storeSavesAndReloads() {
        val file = File.createTempFile("blocks", ".json").also { it.delete() }
        val store = TextBlockStore.load(file)
        assertTrue(store.blocks.isEmpty())
        store.put(block)
        store.put(block.copy(text = "Ada King"))
        assertEquals("one block per id", 1, store.blocks.size)
        // Saves run in order on the writer; a copy waits for them.
        val copy = File(file.parentFile, file.name + ".copy")
        TextBlockStore.copy(file, copy)
        assertEquals("Ada King", TextBlockStore.read(copy).single().text)
        assertEquals("Ada King", TextBlockStore.load(file).blocks.single().text)
        file.writeText("not json")
        assertTrue(TextBlockStore.load(file).blocks.isEmpty())
        assertTrue(File(file.path + ".broken").exists())
    }
}

package app.noti.lines

import app.noti.R
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
class TextInkTest {
    private val ink = TextInk(RuntimeEnvironment.getApplication().resources.getFont(R.font.nunito_regular))

    @Test fun placedWordsSitOnTheirBaselinesAndFollowATiltedLine() {
        val em = 20f
        val words = listOf(PlacedWord("xxx", 100f, 300f, 0f, 0), PlacedWord(" ", 200f, 300f, 0f, 0),
            PlacedWord("mmm", 100f, 400f, 0.05f, 1))
        val strokes = ink.place(words, em)
        assertEquals("one stroke per non-blank word", 2, strokes.sizes.size)
        val (flat, tilted) = boxes(strokes)
        // x-height letters: bottom on the baseline, left at the word's point, width as measured.
        assertEquals(300f, flat[3], 1f)
        assertEquals(100f, flat[0], 2f)
        val width = ink.measure().width("xxx") * em
        assertTrue("ends within its measured width", flat[2] <= 100f + width + 0.5f)
        // Turned with the line: the right end sits lower (y grows down) by about slope · width.
        assertTrue("tilted word's bottom-right is lower than a flat one would be", tilted[3] > 400f + 0.03f * (tilted[2] - tilted[0]))
    }

    private fun boxes(strokes: TextInk.Strokes): List<FloatArray> {
        var at = 0
        return strokes.sizes.map { n ->
            val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            repeat(n) {
                val c = at * 6
                for ((x, y) in listOf(strokes.capsules[c] to strokes.capsules[c + 1], strokes.capsules[c + 3] to strokes.capsules[c + 4])) {
                    box[0] = minOf(box[0], x); box[1] = minOf(box[1], y); box[2] = maxOf(box[2], x); box[3] = maxOf(box[3], y)
                }
                at++
            }
            box
        }
    }
}

package app.noti.lines

import org.junit.Assert.*
import org.junit.Test

class LinePickerTest {
    private fun rect(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, t, r, b, l, b)
    private val answers = List(4) { WriteLine(100f, 900f, 300f + it * 40f, room = 40f) }

    @Test fun aLoopAroundLinesTakesThemInOrder() {
        val got = LinePicker.inLoop(answers.reversed(), rect(80f, 280f, 920f, 390f), minLength = 50f)
        assertEquals(listOf(300f, 340f, 380f), got.map { it.y })
        assertTrue(got.all { it.x0 == 100f && it.x1 == 900f })
    }

    @Test fun aLoopThatGrazesMostOfALineTakesAllOfItAndHalfALineTakesHalf() {
        // 70% of the line inside: the whole line.
        assertEquals(900f, LinePicker.inLoop(answers.take(1), rect(80f, 270f, 660f, 310f), 50f).single().x1)
        // A third of it: just that part.
        val part = LinePicker.inLoop(answers.take(1), rect(380f, 270f, 650f, 310f), 50f).single()
        assertTrue(part.x0 in 380f..400f && part.x1 in 630f..650f)
        assertEquals(part.x0, part.start)
        // A sliver: nothing.
        assertTrue(LinePicker.inLoop(answers.take(1), rect(380f, 270f, 400f, 310f), 50f).isEmpty())
    }

    @Test fun tapsPickTheLineTheyAreOnOrJustAbove() {
        assertTrue(LinePicker.isTap(floatArrayOf(5f, 5f, 5.5f, 5f, 5f, 5.2f), 2f))
        assertFalse(LinePicker.isTap(rect(0f, 0f, 50f, 50f), 2f))
        assertEquals(340f, LinePicker.atPoint(answers, 500f, 330f, 5f)!!.y)   // above line 2, below line 1
        assertEquals(340f, LinePicker.atPoint(answers, 500f, 342f, 5f)!!.y)   // on it
        assertNull(LinePicker.atPoint(answers, 950f, 330f, 5f))              // past its end
        assertNull(LinePicker.atPoint(answers, 500f, 200f, 5f))              // far above
    }

    @Test fun cellsInARowReadLeftToRight() {
        val row = listOf(WriteLine(500f, 700f, 301f, room = 30f), WriteLine(100f, 300f, 300f, room = 30f),
            WriteLine(100f, 300f, 340f, room = 30f))
        assertEquals(listOf(100f, 500f, 100f), LinePicker.readingOrder(row).map { it.x0 })
    }

    @Test fun noLinesMeansOneAlongTheLoop() {
        val l = LinePicker.oneLine(rect(100f, 200f, 400f, 260f), maxRoom = 40f)
        assertEquals(100f, l.x0); assertEquals(400f, l.x1)
        assertTrue(l.y in 250f..260f)
        assertEquals(40f, l.room)
    }

    @Test fun paperLinesComeFromTheTemplateAsWideAsTheLoop() {
        val lines = LinePicker.paperLines(rect(100f, 30f, 600f, 205f), step = 40f, skip = 2, minLength = 20f)
        assertEquals(listOf(80f, 120f, 160f, 200f), lines.map { it.y })
        assertTrue(lines.all { it.x0 == 100f && it.x1 == 600f && it.room == 40f })
        val tap = LinePicker.paperLineAt(300f, 101f, 40f, 2, right = 1000f)!!
        assertEquals(120f, tap.y); assertEquals(300f, tap.x0); assertEquals(1000f, tap.x1)
        assertEquals(80f, LinePicker.paperLineAt(300f, 10f, 40f, 2, 1000f)!!.y)
    }

    @Test fun formFieldsGroupAnswerLinesButNotLabelledBlanks() {
        val mm = 0.21f
        val name = WriteLine(200f, 600f, 100f, room = 60f, start = 200f, label = floatArrayOf(100f, 80f, 190f, 100f))
        val date = WriteLine(200f, 600f, 150f, room = 40f, label = floatArrayOf(100f, 130f, 190f, 150f))
        val box = List(3) { WriteLine(100f, 900f, 260f + it * 40f, room = 40f) }
        val unsure = WriteLine(100f, 900f, 500f, room = 40f, sure = false)
        val fields = LinePicker.fields(listOf(name, date) + box + unsure, mm)
        assertEquals(listOf(1, 1, 3), fields.map { it.size })
    }
}

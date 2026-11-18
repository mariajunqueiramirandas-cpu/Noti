package app.noti.ui.common

import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import app.noti.ink.ReadingTouch
import app.noti.pdf.PagePlacement
import app.noti.pdf.PageViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfReadingGestureTest {
    private lateinit var placement: PagePlacement
    private val viewport = PageViewport(10f) { placement = it }.apply {
        setCanvas(300, 600)
        setDocument(FloatArray(8) { 1.4f })
        goTo(3)
    }
    private val configuration = ViewConfiguration.get(RuntimeEnvironment.getApplication())
    private val gestures = CanvasGestures(configuration.scaledTouchSlop.toFloat(), viewport,
        onTwoFingerDoubleTap = {}, readOnly = { true }, doubleTapSlopPx = configuration.scaledDoubleTapSlop.toFloat())
    private val touch = ReadingTouch(gestures)

    @Test fun stylusDragScrollsAndStylusDoubleTapZooms() {
        val initialTop = placement.top
        send(MotionEvent.ACTION_DOWN, 100, 100f, 300f)
        send(MotionEvent.ACTION_MOVE, 130, 100f, 220f)
        send(MotionEvent.ACTION_UP, 300, 100f, 220f)
        assertTrue(placement.top < initialTop)

        tap(600, 120f, 300f)
        tap(770, 120f, 300f)
        assertEquals(2f, placement.zoom, 0f)
    }

    @Test fun cancelledFingerTapDoesNotCountTowardDoubleTap() {
        send(MotionEvent.ACTION_DOWN, 100, 150f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        send(MotionEvent.ACTION_CANCEL, 140, 150f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        tap(240, 150f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(1f, placement.zoom, 0f)
        tap(400, 150f, 300f, MotionEvent.TOOL_TYPE_FINGER)
        assertEquals(2f, placement.zoom, 0f)
    }

    private fun tap(time: Long, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        send(MotionEvent.ACTION_DOWN, time, x, y, tool)
        send(MotionEvent.ACTION_UP, time + 40, x, y, tool)
    }

    private fun send(action: Int, time: Long, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS) {
        val pointer = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val coordinates = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
        val event = MotionEvent.obtain(time, time, action, 1, arrayOf(pointer), arrayOf(coordinates),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            touch.dispatch(event, action, time * 1_000_000)
        } finally {
            event.recycle()
        }
    }
}

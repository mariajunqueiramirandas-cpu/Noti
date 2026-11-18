package app.noti.ui.common

import app.noti.ink.NotiInk
import app.noti.pdf.PagePlacement
import app.noti.pdf.PageViewport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CanvasGesturesTest {
    private class Fixture {
        lateinit var page: PagePlacement
        private val viewport = PageViewport(10f) { page = it }.apply {
            setCanvas(300, 400)
            setDocument(floatArrayOf(1f, 1f, 1f))
        }
        val gestures = CanvasGestures(8f, viewport, onTwoFingerDoubleTap = {})

        fun down(id: Int, x: Float, y: Float, ms: Long) =
            gestures.onGesture(NotiInk.GESTURE_DOWN, id, x, y, ms * 1_000_000)

        fun move(id: Int, x: Float, y: Float, ms: Long) =
            gestures.onGesture(NotiInk.GESTURE_MOVE, id, x, y, ms * 1_000_000)
    }

    @Test fun replayedPanUsesTheLandingPositionInItsFirstBatch() {
        val f = Fixture()
        val top = f.page.top
        f.down(1, 150f, 200f, 0)
        f.move(1, 150f, 160f, 32)
        f.gestures.onGestureBatchEnd()
        assertEquals(top - 40f, f.page.top, 0f)
    }

    @Test fun replayedPinchZoomsInItsFirstBatch() {
        val f = Fixture()
        f.down(1, 100f, 200f, 0)
        f.down(2, 200f, 200f, 8)
        f.move(1, 75f, 200f, 32)
        f.move(2, 225f, 200f, 32)
        f.gestures.onGestureBatchEnd()
        assertEquals(1.5f, f.page.zoom, 0.001f)
    }

    @Test fun penContactStopsTouchScrollingUntilLift() {
        val f = Fixture()
        val top = f.page.top
        f.down(1, 150f, 200f, 0)
        f.gestures.onPenDown()
        f.move(1, 150f, 160f, 32)
        f.down(2, 150f, 200f, 40)
        f.move(2, 150f, 150f, 72)
        f.gestures.onGestureBatchEnd()
        assertEquals(top, f.page.top, 0f)
        f.gestures.onPenUp()
        f.down(3, 150f, 200f, 80)
        f.move(3, 150f, 170f, 112)
        f.gestures.onGestureBatchEnd()
        assertEquals(top - 30f, f.page.top, 0f)
    }
}

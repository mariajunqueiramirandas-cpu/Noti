package app.noti.ink

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StylusKeyDispatcherTest {
    private val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY)

    @Test fun latestCanvasOwnsKeysAndDisposalRestoresPreviousCanvas() {
        val dispatcher = StylusKeyDispatcher()
        val calls = mutableListOf<String>()
        val editor = dispatcher.register({ calls.add("editor"); true }, { calls.add("reset editor") })
        assertFalse(dispatcher.dispatch(event))
        dispatcher.setActive(true)
        assertTrue(dispatcher.dispatch(event))
        val scratch = dispatcher.register({ calls.add("scratch"); true }, { calls.add("reset scratch") })
        assertTrue(dispatcher.dispatch(event))
        assertEquals("scratch", calls.last())
        scratch.close(); scratch.close()
        assertTrue(dispatcher.dispatch(event))
        assertEquals("editor", calls.last())
        editor.close()
        assertFalse(dispatcher.dispatch(event))
    }

    @Test fun focusLossClearsHeldStateAndFocusedDialogsHaveTheirOwnDispatchPath() {
        val dispatcher = StylusKeyDispatcher()
        var resets = 0
        dispatcher.register({ true }, { resets++ })
        dispatcher.setActive(true)
        dispatcher.setActive(false)
        assertFalse(dispatcher.dispatch(event))
        assertTrue(dispatcher.dispatchFromFocusedWindow(event))
        dispatcher.reset()
        assertEquals(3, resets)
    }

    @Test fun unregisteringAnUnderlyingCanvasDoesNotStealTheTopRegistration() {
        val dispatcher = StylusKeyDispatcher()
        val first = dispatcher.register({ fail("underlying canvas received input"); false }, {})
        dispatcher.register({ it.keyCode == 308 }, {})
        first.close()
        dispatcher.setActive(true)
        assertTrue(dispatcher.dispatch(event))
        assertFalse(dispatcher.dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_PAGE_DOWN)))
    }
}

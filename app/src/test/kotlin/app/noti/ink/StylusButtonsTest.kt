package app.noti.ink

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StylusButtonsTest {
    private val clicks = mutableListOf<PenButton>()
    private val reports = mutableListOf<String>()
    private val buttons = StylusButtons(500, clicks::add, reports::add)
    private fun down(button: PenButton = PenButton.Primary, time: Long = 100, device: Int = 7, repeat: Int = 0, downTime: Long = time) =
        buttons.key(button, device, KeyEvent.ACTION_DOWN, time, repeat, downTime = downTime)
    private fun up(button: PenButton = PenButton.Primary, time: Long = 150, device: Int = 7, canceled: Boolean = false) =
        buttons.key(button, device, KeyEvent.ACTION_UP, time, canceled = canceled)

    @Test fun syntheticClickNeverLeavesAHeldButton() {
        down(); up(time = 101)
        assertEquals(listOf(PenButton.Primary), clicks)
        assertEquals(0, buttons.flags(7, 0, true))
        assertTrue(reports.last().contains("click · 1 ms"))
    }

    @Test fun physicalHoldIsAvailableUntilReleaseAndDoesNotClick() {
        down()
        assertEquals(NotiInk.FLAG_BUTTON_PRIMARY, buttons.flags(7, 0, false))
        up(time = 2100)
        assertTrue(clicks.isEmpty())
        assertEquals(0, buttons.flags(7, 0, true))
        assertTrue(reports.last().contains("long press · 2000 ms"))
    }

    @Test fun fastHoldUsedForInkDoesNotAlsoToggleOnRelease() {
        down()
        assertEquals(NotiInk.FLAG_BUTTON_PRIMARY, buttons.flags(7, 0, true))
        up()
        assertTrue(clicks.isEmpty())
    }

    @Test fun hoverAloneDoesNotSuppressAShortClick() {
        down(); buttons.motion(7, 0); buttons.flags(7, 0, false); up()
        assertEquals(listOf(PenButton.Primary), clicks)
    }

    @Test fun repeatAndDuplicateDownEventsNeverRestartTheClockOrFireClicks() {
        down(); down(time = 120, downTime = 100); down(time = 600, repeat = 1); up(time = 650)
        assertTrue(clicks.isEmpty())
        assertTrue(reports.last().contains("550 ms"))
        assertEquals(2, reports.size)
    }

    @Test fun orphanRepeatsAndReleasesAreIgnored() {
        down(repeat = 1); up()
        assertTrue(clicks.isEmpty())
        assertEquals(0, buttons.flags(7, 0, true))
    }

    @Test fun aNewPressRecoversFromAMissingReleaseWithoutFiringTheLostClick() {
        down(); down(time = 1000); up(time = 1020)
        assertEquals(listOf(PenButton.Primary), clicks)
        assertEquals(0, buttons.flags(7, 0, true))
        assertTrue(reports.last().contains("20 ms"))
    }

    @Test fun cancelResetAndDisconnectNeverTriggerClicksOrLeaveKeysHeld() {
        down(); up(canceled = true)
        down(); buttons.reset(); up()
        down(); buttons.removeDevice(7); up()
        assertTrue(clicks.isEmpty())
        assertEquals(0, buttons.flags(7, 0, true))
    }

    @Test fun disablingOrRemappingCancelsAnActivePress() {
        down(); buttons.options = buttons.options.copy(swapLegacyKeys = true); up()
        down(); buttons.options = buttons.options.copy(enabled = false); up()
        assertTrue(clicks.isEmpty())
        assertEquals(0, buttons.flags(7, MotionEvent.BUTTON_STYLUS_PRIMARY, true))
    }

    @Test fun holdsCanBeDisabledWithoutLosingClickActions() {
        buttons.options = buttons.options.copy(holds = false)
        down()
        assertEquals(0, buttons.flags(7, MotionEvent.BUTTON_STYLUS_PRIMARY, true))
        up()
        assertEquals(listOf(PenButton.Primary), clicks)
    }

    @Test fun bothMotionAndKeyPathsProduceOnlyOneClick() {
        down(); buttons.motion(7, MotionEvent.BUTTON_STYLUS_PRIMARY)
        buttons.motion(7, 0); up()
        assertEquals(listOf(PenButton.Primary), clicks)
        buttons.motion(7, MotionEvent.BUTTON_STYLUS_PRIMARY); buttons.motion(7, 0)
        assertEquals(1, clicks.size)
    }

    @Test fun emptyMotionFlagsAndProximityLossNeverCancelARealKeyHold() {
        down(); buttons.motion(7, MotionEvent.BUTTON_STYLUS_PRIMARY); buttons.motion(7, 0)
        assertEquals(NotiInk.FLAG_BUTTON_PRIMARY, buttons.flags(7, 0, true))
        up(time = 1100)
        assertEquals(0, buttons.flags(7, 0, true))
    }

    @Test fun motionOnlyStylusRetainsTemporaryToolsWithoutInventingClicks() {
        assertEquals(NotiInk.FLAG_BUTTON_SECONDARY, buttons.flags(7, MotionEvent.BUTTON_STYLUS_SECONDARY, true))
        assertEquals(0, buttons.flags(7, 0, true))
        assertTrue(clicks.isEmpty())
    }

    @Test fun simultaneousButtonsReleaseIndependently() {
        down(); down(PenButton.Secondary)
        assertEquals(12, buttons.flags(7, 0, true))
        up()
        assertEquals(8, buttons.flags(7, 0, true))
        up(PenButton.Secondary)
        assertEquals(0, buttons.flags(7, 0, true))
        assertTrue(clicks.isEmpty())
    }

    @Test fun splitBluetoothAndDigitizerDevicesCanUseOnePhysicalPenHold() {
        down(device = 40)
        assertEquals(4, buttons.flags(9, 0, true))
        up(device = 40)
        assertTrue(clicks.isEmpty())
        assertEquals(0, buttons.flags(9, 0, true))
    }

    @Test fun multiplePressedDevicesDoNotGuessAnUnrelatedDigitizer() {
        down(device = 7); down(PenButton.Secondary, device = 8)
        assertEquals(4, buttons.flags(7, 0, false))
        assertEquals(8, buttons.flags(8, 0, false))
        assertEquals(0, buttons.flags(9, 0, false))
        buttons.removeDevice(8)
        assertEquals(4, buttons.flags(9, 0, false))
    }

    @Test fun focusClickDoesNotMasqueradeAsAnEraserOrLasso() {
        down(PenButton.Focus)
        assertEquals(0, buttons.flags(7, 0, true))
        up(PenButton.Focus)
        assertEquals(listOf(PenButton.Focus), clicks)
    }

    @Test fun thresholdBoundaryIsALongPressAndRapidSeparateClicksRemainSeparate() {
        down(); up(time = 600)
        assertTrue(clicks.isEmpty())
        down(time = 700); up(time = 710); down(time = 720); up(time = 730)
        assertEquals(listOf(PenButton.Primary, PenButton.Primary), clicks)
    }

    @Test fun mappingProtectsKeyboardsAndRecognizesBothAndroidAndXiaomiPaths() {
        val options = StylusButtonOptions()
        fun map(code: Int, source: Int = InputDevice.SOURCE_KEYBOARD, name: String? = "Bluetooth Keyboard", opts: StylusButtonOptions = options) =
            StylusButtonKeys.button(code, source, name, opts)
        assertEquals(PenButton.Primary, map(308))
        assertEquals(PenButton.Secondary, map(309))
        assertNull(map(310))
        assertEquals(PenButton.Focus, map(310, opts = options.copy(captureFocus = true)))
        assertNull(map(92)); assertNull(map(93)); assertNull(map(311)); assertNull(map(KeyEvent.KEYCODE_A))
        assertNull(map(93, name = "Open remote"))
        assertNull(map(93, name = "Pen Keyboard"))
        assertEquals(PenButton.Primary, map(93, name = "Xiaomi Focus Pen"))
        assertEquals(PenButton.Secondary, map(92, name = "Xiaomi Focus Pen"))
        assertEquals(PenButton.Primary, map(93, source = InputDevice.SOURCE_STYLUS, name = null))
        assertEquals(PenButton.Secondary, map(93, name = "Xiaomi Focus Pen", opts = options.copy(swapLegacyKeys = true)))
        assertNull(map(93, name = "Xiaomi Focus Pen", opts = options.copy(legacyKeys = false)))
        assertNull(map(308, opts = options.copy(enabled = false)))
    }

    @Test fun xiaomiWindowAttributesLatchNotesPackageBeforeAddView() {
        assertTrue(XiaomiStylusWindow.isXiaomi("Xiaomi", "Xiaomi"))
        assertTrue(XiaomiStylusWindow.isXiaomi("Xiaomi", "Redmi"))
        assertTrue(XiaomiStylusWindow.isXiaomi("unknown", "POCO"))
        assertFalse(XiaomiStylusWindow.isXiaomi("Google", "google"))

        val nonXiaomiAttrs = WindowManager.LayoutParams()
        assertFalse(XiaomiStylusWindow.applyBeforeAdd(nonXiaomiAttrs, hijack = true, isXiaomi = false))
        assertNull(nonXiaomiAttrs.packageName)

        val disabledAttrs = WindowManager.LayoutParams()
        assertFalse(XiaomiStylusWindow.applyBeforeAdd(disabledAttrs, hijack = false, isXiaomi = true))
        assertNull(disabledAttrs.packageName)

        val windowAttrs = WindowManager.LayoutParams()
        assertTrue(XiaomiStylusWindow.applyBeforeAdd(windowAttrs, hijack = true, isXiaomi = true))
        assertEquals(XiaomiStylusWindow.NOTES_PACKAGE, windowAttrs.packageName)

        // Simulates ViewRootImpl.mWindowAttributes.copyFrom(wparams) and WindowState.mAttrs.copyFrom(attrs)
        // during WindowManagerGlobal.addView -> WindowManagerService.addWindow:
        val viewRootAttrs = WindowManager.LayoutParams().apply { copyFrom(windowAttrs) }
        val windowStateAttrs = WindowManager.LayoutParams().apply { copyFrom(viewRootAttrs) }
        assertEquals(XiaomiStylusWindow.NOTES_PACKAGE, windowStateAttrs.packageName)

        // Subsequent relayouts with non-null app packageName do not clobber the latched WindowState packageName:
        val relayoutAttrs = WindowManager.LayoutParams().apply { packageName = "app.noti" }
        windowStateAttrs.copyFrom(relayoutAttrs)
        assertEquals(XiaomiStylusWindow.NOTES_PACKAGE, windowStateAttrs.packageName)

        val addViewStack = arrayOf(
            StackTraceElement("app.noti.MainActivity", "getPackageName", "MainActivity.kt", 36),
            StackTraceElement("android.view.WindowManagerGlobal", "addView", "WindowManagerGlobal.java", 390),
        )
        val subWindowStack = arrayOf(
            StackTraceElement("app.noti.MainActivity", "getPackageName", "MainActivity.kt", 36),
            StackTraceElement("android.view.Window", "adjustLayoutParamsForSubWindow", "Window.java", 815),
        )
        val normalStack = arrayOf(
            StackTraceElement("app.noti.MainActivity", "getPackageName", "MainActivity.kt", 36),
            StackTraceElement("android.content.ComponentName", "<init>", "ComponentName.java", 130),
        )
        assertEquals(
            XiaomiStylusWindow.NOTES_PACKAGE,
            XiaomiStylusWindow.resolvePackageName("app.noti", hijack = true, isXiaomi = true, stack = addViewStack),
        )
        assertEquals(
            XiaomiStylusWindow.NOTES_PACKAGE,
            XiaomiStylusWindow.resolvePackageName("app.noti", hijack = true, isXiaomi = true, stack = subWindowStack),
        )
        assertEquals(
            "app.noti",
            XiaomiStylusWindow.resolvePackageName("app.noti", hijack = false, isXiaomi = true, stack = addViewStack),
        )
        assertEquals(
            "app.noti",
            XiaomiStylusWindow.resolvePackageName("app.noti", hijack = true, isXiaomi = false, stack = addViewStack),
        )
        assertEquals(
            "app.noti",
            XiaomiStylusWindow.resolvePackageName("app.noti", hijack = true, isXiaomi = true, stack = normalStack),
        )
    }
}

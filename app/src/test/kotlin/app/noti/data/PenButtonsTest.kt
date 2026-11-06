package app.noti.data

import android.content.Context
import app.noti.ink.PenButton
import app.noti.ink.PenHold
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PenButtonsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var settings: SettingsStore

    @Before fun setup() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsStore(context)
    }

    @Test fun defaultsGiveXiaomiClickOnlyFirmwareUsefulShortcutsAndLeaveFocusToTheSystem() {
        assertEquals(PenButtonAction.ToggleEraser, settings.buttonAction(PenButton.Primary))
        assertEquals(PenButtonAction.ToggleLasso, settings.buttonAction(PenButton.Secondary))
        assertEquals(PenButtonAction.None, settings.buttonAction(PenButton.Focus))
        assertTrue(settings.penButtonOptions().enabled)
        assertFalse(settings.penButtonOptions().captureFocus)
        assertFalse(settings.penButtonOptions(enabled = false).enabled)
    }

    @Test fun toggleToolsReturnToTheUsersLastDrawingToolAcrossReopening() {
        settings.tool = Tool.Highlighter
        settings.performButtonAction(PenButtonAction.ToggleEraser)
        assertEquals(Tool.Eraser, settings.tool)
        settings = SettingsStore(context)
        settings.performButtonAction(PenButtonAction.ToggleEraser)
        assertEquals(Tool.Highlighter, settings.tool)
        settings.performButtonAction(PenButtonAction.ToggleLasso)
        assertEquals(Tool.Lasso, settings.tool)
        settings.performButtonAction(PenButtonAction.ToggleLasso)
        assertEquals(Tool.Highlighter, settings.tool)
        settings.tool = Tool.Pen
        settings.performButtonAction(PenButtonAction.ToggleEraser)
        settings.performButtonAction(PenButtonAction.ToggleLasso)
        settings.performButtonAction(PenButtonAction.ToggleLasso)
        assertEquals(Tool.Pen, settings.tool)
    }

    @Test fun switchingDrawingToolsAndCyclingColorsUseTheRightPresets() {
        settings.performButtonAction(PenButtonAction.SwitchDrawingTool)
        assertEquals(Tool.Highlighter, settings.tool)
        repeat(4) { settings.performButtonAction(PenButtonAction.NextColor) }
        assertEquals(1, settings.highlighterSlots.color)
        assertEquals(0, settings.penSlots.color)
        settings.performButtonAction(PenButtonAction.SwitchDrawingTool)
        settings.performButtonAction(PenButtonAction.ToggleEraser)
        settings.performButtonAction(PenButtonAction.NextColor)
        assertEquals(Tool.Pen, settings.tool)
        assertEquals(1, settings.penSlots.color)
    }

    @Test fun undoRedoAndNoneDoNotChangeTheSelectedTool() {
        val actions = mutableListOf<Int>()
        settings.tool = Tool.Lasso
        settings.performButtonAction(PenButtonAction.Undo, actions::add)
        settings.performButtonAction(PenButtonAction.Redo, actions::add)
        settings.performButtonAction(PenButtonAction.None, actions::add)
        assertEquals(listOf(0, 1), actions)
        assertEquals(Tool.Lasso, settings.tool)
    }

    @Test fun buttonConfigurationPersistsAndUnknownValuesFallBackSafely() {
        settings.primaryButtonAction = PenButtonAction.Undo
        settings.secondaryButtonAction = PenButtonAction.NextColor
        settings.focusButtonAction = PenButtonAction.Redo
        settings.penButtonsEnabled = false
        settings.penButtonHold = PenHold.Off
        settings.toolDialAlways = true
        settings.penLegacyKeys = false
        settings.penSwapLegacyKeys = true
        settings = SettingsStore(context)
        assertEquals(PenButtonAction.Undo, settings.primaryButtonAction)
        assertEquals(PenButtonAction.NextColor, settings.secondaryButtonAction)
        assertEquals(PenButtonAction.Redo, settings.focusButtonAction)
        assertTrue(settings.penButtonOptions().captureFocus)
        assertFalse(settings.penButtonsEnabled); assertEquals(PenHold.Off, settings.penButtonHold); assertTrue(settings.toolDialAlways); assertFalse(settings.penLegacyKeys)
        assertTrue(settings.penSwapLegacyKeys)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("primaryButtonAction", "removed action").putString("lastDrawingTool", "Eraser")
            .putString("penButtonHold", "removed hold").remove("penButtonHolds").commit()
        settings = SettingsStore(context)
        assertEquals(PenButtonAction.ToggleEraser, settings.primaryButtonAction)
        assertEquals(Tool.Pen, settings.lastDrawingTool)
        assertEquals(PenHold.Dial, settings.penButtonHold)
    }

    @Test fun existingHighlighterSettingMigratesWithoutLosingTheReturnTool() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("tool", "Highlighter").commit()
        settings = SettingsStore(context)
        settings.performButtonAction(PenButtonAction.ToggleEraser)
        settings.performButtonAction(PenButtonAction.ToggleEraser)
        assertEquals(Tool.Highlighter, settings.tool)
    }
    @Test fun theToolDialIsTheDefaultHoldAndTheOldEraseSwitchMigrates() {
        assertEquals(PenHold.Dial, settings.penButtonHold)
        assertEquals(PenHold.Dial, settings.penButtonOptions().hold)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("penButtonHolds", false).commit()
        assertEquals(PenHold.Off, SettingsStore(context).penButtonHold)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("penButtonHolds", true).commit()
        assertEquals(PenHold.Dial, SettingsStore(context).penButtonHold)
        settings.penButtonHold = PenHold.Erase
        assertEquals(PenHold.Erase, SettingsStore(context).penButtonHold)
    }

    @Test fun theDialHidesOnlyWhenThePenCanSummonIt() {
        assertTrue(settings.toolDialOnHold)
        settings.toolDialAlways = true
        assertFalse(settings.toolDialOnHold)
        settings.toolDialAlways = false
        settings.penButtonHold = PenHold.Erase
        assertFalse(settings.toolDialOnHold)
        settings.penButtonHold = PenHold.Dial
        settings.penButtonsEnabled = false
        assertFalse(settings.toolDialOnHold)
    }

    @Test fun dialSegmentsPickToolsAndReturnToTheLastDrawingToolForPresets() {
        settings.applyDialSegment(1)
        assertEquals(Tool.Highlighter, settings.tool)
        settings.applyDialSegment(2)
        assertEquals(Tool.Eraser, settings.tool)
        settings.applyDialSegment(DialColors + 2)
        assertEquals(Tool.Highlighter, settings.tool)
        assertEquals(2, settings.highlighterSlots.color)
        settings.applyDialSegment(4)
        assertEquals(Tool.Transcribe, settings.tool)
        settings.applyDialSegment(0)
        settings.applyDialSegment(3)
        settings.applyDialSegment(DialWidths + 2)
        assertEquals(Tool.Pen, settings.tool)
        assertEquals(2, settings.penSlots.width)
        assertEquals(SettingsStore.DEFAULT_HIGHLIGHTER.width, settings.highlighterSlots.width)
    }
}

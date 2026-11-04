package app.noti.data

import app.noti.ink.PenButton
import app.noti.ink.StylusButtonOptions

enum class PenButtonAction(val label: String) {
    None("No click action"), ToggleEraser("Toggle eraser"), ToggleLasso("Toggle lasso"),
    SwitchDrawingTool("Switch pen / highlighter"), NextColor("Next quick color"), Undo("Undo"), Redo("Redo"),
}

fun SettingsStore.penButtonOptions(enabled: Boolean = true) = StylusButtonOptions(
    enabled = enabled && penButtonsEnabled, holds = penButtonHolds, legacyKeys = penLegacyKeys,
    swapLegacyKeys = penSwapLegacyKeys, captureFocus = focusButtonAction != PenButtonAction.None,
)

fun SettingsStore.buttonAction(button: PenButton) = when (button) {
    PenButton.Primary -> primaryButtonAction
    PenButton.Secondary -> secondaryButtonAction
    PenButton.Focus -> focusButtonAction
}

/** Use the same persistent tool state as the floating selector. */
fun SettingsStore.performButtonAction(action: PenButtonAction, documentAction: (Int) -> Unit = {}) {
    when (action) {
        PenButtonAction.None -> Unit
        PenButtonAction.ToggleEraser -> tool = if (tool == Tool.Eraser) lastDrawingTool else Tool.Eraser
        PenButtonAction.ToggleLasso -> tool = if (tool == Tool.Lasso) lastDrawingTool else Tool.Lasso
        PenButtonAction.SwitchDrawingTool -> tool = if (lastDrawingTool == Tool.Pen) Tool.Highlighter else Tool.Pen
        PenButtonAction.NextColor -> {
            tool = lastDrawingTool
            if (tool == Tool.Highlighter) highlighterSlots = highlighterSlots.copy(color = (highlighterSlots.color + 1) % 3)
            else penSlots = penSlots.copy(color = (penSlots.color + 1) % 3)
        }
        PenButtonAction.Undo -> documentAction(0)
        PenButtonAction.Redo -> documentAction(1)
    }
}

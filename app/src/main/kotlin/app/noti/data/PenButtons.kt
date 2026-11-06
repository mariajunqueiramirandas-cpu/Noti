package app.noti.data

import app.noti.ink.PenButton
import app.noti.ink.StylusButtonOptions

enum class PenButtonAction(val label: String) {
    None("No click action"), ToggleEraser("Toggle eraser"), ToggleLasso("Toggle lasso"),
    SwitchDrawingTool("Switch pen / highlighter"), NextColor("Next quick color"), Undo("Undo"), Redo("Redo"),
}

fun SettingsStore.penButtonOptions(enabled: Boolean = true) = StylusButtonOptions(
    enabled = enabled && penButtonsEnabled, hold = penButtonHold, legacyKeys = penLegacyKeys,
    swapLegacyKeys = penSwapLegacyKeys, captureFocus = focusButtonAction != PenButtonAction.None,
)

fun SettingsStore.buttonAction(button: PenButton) = when (button) {
    PenButton.Primary -> primaryButtonAction
    PenButton.Secondary -> secondaryButtonAction
    PenButton.Focus -> focusButtonAction
}

/**
 * One of the dial's ten segments (see RadialToolSelector): 0–3 tools, 4–6 quick colors,
 * 7–9 quick widths. A color or width returns to the last pen or highlighter if the
 * eraser or lasso is in hand, like [PenButtonAction.NextColor].
 */
fun SettingsStore.applyDialSegment(segment: Int) {
    when (segment) {
        in 0..3 -> tool = Tool.entries[segment]
        in 4..9 -> {
            tool = lastDrawingTool
            val slots = if (tool == Tool.Highlighter) highlighterSlots else penSlots
            val next = if (segment < 7) slots.copy(color = segment - 4) else slots.copy(width = segment - 7)
            if (tool == Tool.Highlighter) highlighterSlots = next else penSlots = next
        }
    }
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

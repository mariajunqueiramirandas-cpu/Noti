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
    motionClicks = penMotionClicks,
)

fun SettingsStore.buttonAction(button: PenButton) = when (button) {
    PenButton.Primary -> primaryButtonAction
    PenButton.Secondary -> secondaryButtonAction
    PenButton.Focus -> focusButtonAction
}

/** The dial's first quick-color segment: the tools come first (see RadialToolSelector). */
val DialColors = Tool.entries.size
/** The dial's first quick-width segment. */
val DialWidths = DialColors + 3
/** Segments around the dial: every tool, three quick colors, three quick widths. */
val DialSegments = DialWidths + 3

/**
 * One of the dial's segments (see RadialToolSelector): the tools, then the quick colors
 * ([DialColors]) and the quick widths ([DialWidths]). A color or width returns to the
 * last pen or highlighter if another tool is in hand, like [PenButtonAction.NextColor].
 */
fun SettingsStore.applyDialSegment(segment: Int) {
    when (segment) {
        in 0 until DialColors -> tool = Tool.entries[segment]
        in DialColors until DialSegments -> {
            tool = lastDrawingTool
            val slots = if (tool == Tool.Highlighter) highlighterSlots else penSlots
            val next = if (segment < DialWidths) slots.copy(color = segment - DialColors) else slots.copy(width = segment - DialWidths)
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

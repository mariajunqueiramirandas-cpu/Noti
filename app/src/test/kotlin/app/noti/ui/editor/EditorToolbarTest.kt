package app.noti.ui.editor

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import app.noti.ui.captureRenderedView
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.noti.data.*
import app.noti.pdf.PagePlacement
import app.noti.ui.common.DocumentBackground
import app.noti.ui.common.DocumentViewport
import app.noti.ui.theme.NotiTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorToolbarTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var renderView: View
    private lateinit var settings: SettingsStore
    private var undos = 0
    private var redos = 0
    private var resets = 0
    private var scrolled = 0f
    private var requestedPage = -1
    private var saves = 0
    private var exports = 0
    private var papers = 0
    private var precision = 0
    private val tools = listOf("Pen", "Highlighter", "Eraser", "Lasso", "Transcribe")
    private val controls = listOf("Undo", "Redo", "Back to library", "Pages", "More",
        "Document scroll", "Zoom 175 percent. Reset to fit width")
    private val quickSlots = listOf("Brush options", "Collapse tools") + (1..3).map { "Quick color $it" } +
        listOf("3.0", "5.0", "9.0").map { "Quick width $it" }

    @Test fun portraitTabletKeepsControlsVisibleInDarkMode() { render(800, ThemeMode.Dark, height = 1100); assertControls(); screenshot("portrait-dark") }
    @Test fun landscapeTabletKeepsControlsVisibleInLightMode() { render(1200, ThemeMode.Light); assertControls(); screenshot("landscape-light") }
    @Test fun narrowWindowKeepsControlsApart() { render(360, ThemeMode.Dark); assertControls(); screenshot("narrow-dark") }
    @Test fun smallTabletWithLargeFontsKeepsControlsInsideWindow() { render(600, ThemeMode.Light, 2f); assertControls() }
    @Test fun largeFontsKeepControlsInsideWindow() { render(800, ThemeMode.Light, 2f); assertControls(); screenshot("large-font") }

    @Test
    @Config(qualifiers = "w412dp-h915dp-mdpi")
    fun phoneWithLargeTextKeepsEditorActionsApart() { render(412, ThemeMode.Light, 1.5f, height = 915); assertControls(); screenshot("phone-large-text") }

    @Test
    @Config(qualifiers = "w915dp-h412dp-mdpi")
    fun shortLandscapeDialStaysClearOfHeaderAndNavigation() {
        render(915, ThemeMode.Light, height = 412)
        assertControls()
        assertTargetsApart(controls + tools + quickSlots)
        screenshot("phone-landscape-tools")
    }

    @Test fun dialSelectsToolsWithoutMovingOrClosing() {
        render(800, ThemeMode.Light)
        val positions = tools.map { compose.onNodeWithContentDescription(it).fetchSemanticsNode().positionOnScreen }
        tools.forEach { label ->
            compose.onNodeWithContentDescription(label).performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(label, settings.tool.label) }
            tools.forEachIndexed { index, tool ->
                assertEquals(positions[index], compose.onNodeWithContentDescription(tool).assertIsDisplayed().fetchSemanticsNode().positionOnScreen)
            }
        }
        compose.onNodeWithContentDescription("Brush options").assertIsNotEnabled()
        // A preset brings back the last drawing tool rather than doing nothing.
        compose.onNodeWithContentDescription("Quick width 40.0").performClick()
        compose.runOnIdle { assertEquals(Tool.Highlighter, settings.tool); assertEquals(2, settings.highlighterSlots.width) }
    }

    @Test fun wholeAnnularSectorRespondsOutsideTheIconTarget() {
        render(800, ThemeMode.Light)
        compose.onNodeWithContentDescription("Highlighter").performClick()
        // Near the pen segment's outer corner, outside its icon target.
        compose.onNodeWithTag("toolbar").performTouchInput { click(Offset(25.1f, 151.3f)) }
        compose.onNodeWithContentDescription("Pen").assertIsSelected()
        compose.runOnIdle { assertEquals(Tool.Pen, settings.tool) }
    }

    @Test fun quickPresetsEditOnlyTheCurrentDrawingTool() {
        render(800, ThemeMode.Light)
        compose.onNodeWithContentDescription("Quick color 2").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Quick width 9.0").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(1, settings.penSlots.color); assertEquals(2, settings.penSlots.width) }
        compose.onNodeWithContentDescription("Highlighter").performClick()
        compose.onNodeWithContentDescription("Quick width 40.0").performClick()
        compose.onNodeWithContentDescription("Quick color 3").performClick()
        compose.runOnIdle {
            assertEquals(2, settings.highlighterSlots.width); assertEquals(2, settings.highlighterSlots.color)
            assertEquals(2, settings.penSlots.width); assertEquals(1, settings.penSlots.color)
        }
        compose.onNodeWithContentDescription("Quick color 1").performClick()
        compose.runOnIdle { assertEquals(0, settings.highlighterSlots.color) }
        screenshot("highlighter-light")
    }

    @Test fun everySegmentMapsToItsOwnSlot() {
        assertEquals(11, DialSegments); assertEquals(5, DialColors); assertEquals(8, DialWidths)
        val sweep = 360f / 11
        // Five tools across the top, the middle one (lasso's neighbour, eraser) straight up.
        assertEquals(2, segmentAt(-90f)); assertEquals(0, segmentAt(-90f - 2 * sweep)); assertEquals(4, segmentAt(-90f + 2 * sweep))
        assertEquals(5, segmentAt(-90f + 3 * sweep)); assertEquals(7, segmentAt(-90f + 5 * sweep))
        assertEquals(8, segmentAt(-90f + 6 * sweep)); assertEquals(10, segmentAt(-90f + 8 * sweep))
        // Each segment owns half a sweep either side of its centre, wrapping at ±180°.
        (0 until DialSegments).forEach { i ->
            val centre = -90f + (i - 2) * sweep
            assertEquals(i, segmentAt(centre - sweep / 2 + 0.5f)); assertEquals(i, segmentAt(centre + sweep / 2 - 0.5f))
            assertEquals(i, segmentAt(centre + 360f)); assertEquals(i, segmentAt(centre - 360f))
        }
    }

    @Test fun centerSwatchOpensFullBrushOptions() {
        render(800, ThemeMode.Dark)
        screenshot("radial-dark")
        compose.onNodeWithContentDescription("Brush options").performClick()
        popupScreenshot("brush-options-dark")
        compose.onNodeWithContentDescription("Color 2").performClick()
        compose.onNodeWithContentDescription("Width 9.0").performClick()
        compose.runOnIdle { assertEquals(1, settings.penSlots.color); assertEquals(2, settings.penSlots.width) }
        compose.onNodeWithText("Pressure sensitivity").performScrollTo().assertIsDisplayed()
    }

    @Test fun realPopupKeepsOneStableDialWindowWhenChangingTools() {
        render(800, ThemeMode.Light, floating = true)
        val before = compose.onNodeWithContentDescription("Pen").fetchSemanticsNode()
        val root = generateSequence(before) { it.parent }.last()
        tools.forEach { label ->
            compose.onNodeWithContentDescription(label).performClick()
            val current = compose.onNodeWithContentDescription("Pen").fetchSemanticsNode()
            assertSame(before.root, current.root)
            assertEquals(before.positionOnScreen, current.positionOnScreen)
            assertEquals(root.size, generateSequence(current) { it.parent }.last().size)
        }
    }

    @Test fun narrowDialHasSeparateTouchTargets() {
        render(360, ThemeMode.Light)
        assertTargetsApart(controls + tools + quickSlots)
        screenshot("radial-narrow")
    }

    @Test fun overflowKeepsSaveStatusAndPageActionsAccessible() {
        render(360, ThemeMode.Light)
        compose.onNodeWithText("Autosave on").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Autosave on").assertIsDisplayed()
        compose.onNodeWithText("Next page").performClick()
        compose.runOnIdle { assertEquals(3, requestedPage) }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Previous page").performClick()
        compose.runOnIdle { assertEquals(1, requestedPage) }
    }

    @Test fun toolsCanBeCollapsedAndExpandedFromMore() {
        render(360, ThemeMode.Light)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Collapse tools").performClick()
        compose.onNodeWithContentDescription("Pen").assertDoesNotExist()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Expand tools").performClick()
        compose.onNodeWithContentDescription("Pen").assertIsDisplayed()
    }

    @Test fun directCollapseFreesCanvasAndPreservesToolAndCenter() {
        render(1200, ThemeMode.Light)
        val before = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithContentDescription("Collapse tools").performClick()
        tools.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        val collapsed = compose.onNodeWithContentDescription("Expand tools").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(before, collapsed.center)
        assertEquals(56f, collapsed.width, 0.5f)
        screenshot("collapsed-light")
        compose.runOnIdle { settings.tool = Tool.Eraser }
        compose.onNodeWithContentDescription("Expand tools").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Eraser. Drag to move tools"))
        compose.onNodeWithContentDescription("Expand tools").performClick()
        compose.onNodeWithContentDescription("Eraser").assertIsSelected()
        assertEquals(before, compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().boundsInRoot.center)
    }

    @Test fun realPopupCollapseKeepsCenterAndShrinksItsInputWindow() {
        render(800, ThemeMode.Light, floating = true)
        repeat(3) {
            val expanded = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode()
            val center = expanded.positionOnScreen + Offset(expanded.size.width / 2f, expanded.size.height / 2f)
            compose.onNodeWithContentDescription("Collapse tools").performClick()
            val collapsed = compose.onNodeWithContentDescription("Expand tools").fetchSemanticsNode()
            val rootSize = generateSequence(collapsed) { it.parent }.last().size
            // 56 dp button plus the transparent shadow halo on each side.
            assertEquals(76, rootSize.width); assertEquals(76, rootSize.height)
            assertEquals(center, collapsed.positionOnScreen + Offset(28f, 28f))
            compose.onNodeWithContentDescription("Expand tools").performClick()
        }
    }

    @Test fun collapsedPopupCanBeDraggedAndReopenedWithoutDrift() {
        render(800, ThemeMode.Dark, floating = true)
        compose.onNodeWithContentDescription("Collapse tools").performClick()
        val before = compose.onNodeWithContentDescription("Expand tools").fetchSemanticsNode().positionOnScreen + Offset(28f, 28f)
        compose.onNodeWithContentDescription("Expand tools").performTouchInput { swipe(center, center + Offset(220f, 150f), 600) }
        val after = compose.onNodeWithContentDescription("Expand tools").fetchSemanticsNode().positionOnScreen + Offset(28f, 28f)
        assertEquals(before.x + 220f, after.x, 32f); assertEquals(before.y + 150f, after.y, 32f)
        compose.onNodeWithContentDescription("Expand tools").performClick()
        val brush = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode()
        assertEquals(after, brush.positionOnScreen + Offset(brush.size.width / 2f, brush.size.height / 2f))
    }

    @Test fun saveFailureExposesRetryWithoutOpeningMenu() {
        render(360, ThemeMode.Light, saveStatus = "Save failed · tap to retry")
        compose.onNodeWithContentDescription("Save failed · tap to retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-mdpi")
    fun brushOptionsFitShortNarrowWindowWithLargeFonts() {
        render(360, ThemeMode.Light, 1.5f, height = 640)
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithText("Width").performScrollTo().assertIsDisplayed()
        val popup = compose.onNode(isPopup()).fetchSemanticsNode().boundsInRoot
        assertTrue("Brush menu is wider than the window: $popup", popup.width <= 360f)
        assertTrue("Brush menu is taller than the window: $popup", popup.height <= 640f)
    }

    @Test fun dialCanBeMovedInsideCanvas() = assertDrag(floating = false)
    @Test fun realPopupDragTracksScreenCoordinatesWithoutDrift() = assertDrag(floating = true)

    private fun assertDrag(floating: Boolean) {
        render(800, ThemeMode.Light, floating = floating)
        val before = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().positionOnScreen
        compose.onNodeWithContentDescription("Brush options").performTouchInput { swipe(center, center + Offset(220f, 150f), 600) }
        val after = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().positionOnScreen
        assertEquals(before.x + 220f, after.x, 32f)
        assertEquals(before.y + 150f, after.y, 32f)
        compose.onNodeWithContentDescription("Pen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lasso").performClick()
        compose.runOnIdle { assertEquals(Tool.Lasso, settings.tool) }
        // Large drags stop at the reserved header, footer and action gutter.
        compose.onNodeWithContentDescription("Brush options").performTouchInput { swipe(center, center + Offset(2000f, 2000f), 600) }
        val pen = compose.onNodeWithContentDescription("Pen").fetchSemanticsNode().positionOnScreen
        assertTrue(pen.x < 800f - 48f && pen.y < 800f - 68f)
    }

    @Test fun realPopupActionsExposeLiveIndicators() {
        render(800, ThemeMode.Light, floating = true)
        compose.onNodeWithContentDescription("Lasso").performClick()
        compose.runOnIdle { assertEquals(Tool.Lasso, settings.tool) }
        compose.onNodeWithContentDescription("Zoom 175 percent. Reset to fit width").performClick()
        compose.onNodeWithContentDescription("Document scroll").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.75f) }
        compose.runOnIdle { assertEquals(1, resets); assertEquals(0.75f, scrolled, 0.001f) }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithContentDescription("Redo").performClick()
        listOf("Export", "Paper", "Precision").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Export PDF").performScrollTo().performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Page paper").performScrollTo().performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, undos); assertEquals(1, redos); assertEquals(1, exports); assertEquals(1, papers); assertEquals(1, precision) }
    }

    private fun render(width: Int, theme: ThemeMode, fontScale: Float = 1f, floating: Boolean = false, saveStatus: String = "Autosave on", height: Int = 800,
        dial: PenDialState = PenDialState(), alwaysVisible: Boolean = true) {
        settings = SettingsStore(RuntimeEnvironment.getApplication()).also {
            it.tool = Tool.Pen; it.penSlots = SettingsStore.DEFAULT_PEN; it.highlighterSlots = SettingsStore.DEFAULT_HIGHLIGHTER
            it.toolDialAlways = alwaysVisible
        }
        val page = PagePlacement(2, 16f, -160f, width - 32f, width * 1.4f, 1f, width, 600, true)
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderView = view }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                NotiTheme(theme) {
                    Box(Modifier.requiredWidth(width.dp).height(height.dp).background(Color.Gray).testTag("editor")) {
                        DocumentViewport(Modifier.fillMaxSize()) {
                            DocumentBackground(Paper(template = PaperTemplate.Blank,
                                color = if (theme == ThemeMode.Dark) PaperColor.Dark else PaperColor.White), null,
                                { page.copy(visiblePages = listOf(page, page.copy(page = 3, top = page.top + page.height + 12f))) }, Modifier.fillMaxSize())
                        }
                        Box(Modifier.fillMaxSize().testTag("toolbar")) {
                            EditorToolbar(
                                title = "Untitled notebook", settings = settings, pagesOpen = false,
                                onBack = {}, onRename = {}, onTogglePages = {}, onPagePaper = { papers++ }, onFullScreen = {}, onSettings = { precision++ },
                                pdfPage = 2 to 30, onPage = { requestedPage = it }, canUndo = true, canRedo = true, zoom = 1.75f,
                                saveStatus = saveStatus, onSave = { saves++ }, onExport = { exports++ },
                                scrollFraction = { 0.3f }, viewportFraction = 0.15f, onResetZoom = { resets++ }, onScroll = { scrolled = it },
                                onUndo = { undos++ }, onRedo = { redos++ }, floatingWindows = floating, dial = dial,
                            )
                        }
                    }
                }
            }
        }
    }

    // ---- the dial the pen summons ----

    /** dp from the dial's centre to the middle of segment [index]. */
    private fun segment(index: Int): Offset {
        val angle = Math.toRadians(-90.0 + 360.0 / DialSegments * (index - 2))
        return Offset((76 * kotlin.math.cos(angle)).toFloat(), (76 * kotlin.math.sin(angle)).toFloat())
    }

    @Test fun everyPointOfTheSummonedDialPicksOneThing() {
        (0 until DialSegments).forEach { assertEquals(DialPick.Segment(it), pickAt(segment(it).x, segment(it).y)) }
        assertEquals(DialPick.Cancel, pickAt(0f, 0f))
        assertEquals(DialPick.Cancel, pickAt(30f, -30f))
        // Selection goes by direction, so overshooting the ring still counts.
        assertEquals(DialPick.Segment(0), pickAt(segment(0).x * 1.8f, segment(0).y * 1.8f))
        assertEquals(DialPick.Cancel, pickAt(segment(0).x * 3f, segment(0).y * 3f))
        assertEquals(DialPick.Lock, pickAt(0f, LockOffset))
        assertEquals(DialPick.Lock, pickAt(12f, LockOffset + 12f))
    }

    @Test fun summonedDialFollowsTheNibAndLiftChoosesCancelsOrLocks() {
        val dial = PenDialState().apply { area = androidx.compose.ui.geometry.Size(800f, 800f) }
        val nib = Offset(400f, 400f)
        dial.press(nib)
        assertEquals(PenDialState.Phase.Picking, dial.phase)
        assertEquals(nib, dial.center)
        dial.drag(nib + segment(1))
        assertEquals(DialPick.Segment(1), dial.hovered)
        assertEquals(1, dial.release(nib + segment(1)))
        assertEquals(PenDialState.Phase.Hidden, dial.phase)
        // Back to the hub: cancel.
        dial.press(nib); dial.drag(nib + segment(3))
        assertNull(dial.release(nib))
        assertEquals(PenDialState.Phase.Hidden, dial.phase)
        // Far outside: cancel.
        dial.press(nib)
        assertNull(dial.release(nib + Offset(0f, -400f)))
        assertEquals(PenDialState.Phase.Hidden, dial.phase)
        // The lock pip keeps it open.
        dial.press(nib)
        assertNull(dial.release(nib + Offset(0f, LockOffset)))
        assertEquals(PenDialState.Phase.Locked, dial.phase)
        dial.dismiss()
        // A press-tap opens it locked too.
        dial.press(nib); dial.drag(nib + Offset(3f, 3f))
        assertNull(dial.release(nib + Offset(4f, 4f)))
        assertEquals(PenDialState.Phase.Locked, dial.phase)
        // Re-summoning while locked moves it under the new nib.
        dial.press(Offset(300f, 500f))
        assertEquals(Offset(300f, 500f), dial.center)
        dial.cancel()
        assertEquals(PenDialState.Phase.Hidden, dial.phase)
    }

    @Test fun summonedDialStaysOnScreenAndAnEdgePressCannotPickByAccident() {
        val dial = PenDialState().apply { area = androidx.compose.ui.geometry.Size(800f, 600f) }
        dial.press(Offset(5f, 5f))
        assertEquals(Offset(108f, 34f + 108f), dial.center)
        dial.press(Offset(795f, 595f))
        assertEquals(Offset(800f - 108f, 600f - LockOffset - 22f - 4f), dial.center)
        // The nib now rests over a segment, but lifting without dragging locks instead of choosing it.
        dial.drag(Offset(796f, 594f))
        assertNull(dial.hovered)
        assertNull(dial.release(Offset(796f, 594f)))
        assertEquals(PenDialState.Phase.Locked, dial.phase)
    }

    @Test fun penEventsArriveInWindowPixels() {
        val dial = PenDialState().apply { area = androidx.compose.ui.geometry.Size(800f, 800f); origin = Offset(100f, 50f); density = 2f }
        dial.onPen(app.noti.ink.PenDialRelay.DOWN, 900f, 850f)
        assertEquals(Offset(400f, 400f), dial.center)
        val target = Offset(900f, 850f) + segment(DialWidths) * 2f
        dial.onPen(app.noti.ink.PenDialRelay.MOVE, target.x, target.y)
        assertEquals(DialWidths, dial.onPen(app.noti.ink.PenDialRelay.UP, target.x, target.y))
        dial.open()
        dial.onPen(app.noti.ink.PenDialRelay.DISMISS, 0f, 0f)
        assertEquals(PenDialState.Phase.Hidden, dial.phase)
    }

    @Test fun onHoldTheDialStaysHiddenUntilThePenSummonsIt() {
        val dial = PenDialState()
        render(800, ThemeMode.Light, dial = dial, alwaysVisible = false)
        tools.forEach { compose.onNodeWithContentDescription(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Expand tools").assertDoesNotExist()
        val nib = Offset(400f, 400f)
        compose.runOnIdle { dial.press(nib); dial.drag(nib + segment(1)) }
        compose.onNodeWithText("Highlighter").assertIsDisplayed()
        compose.onNodeWithContentDescription("Keep tools open").assertIsDisplayed()
        // Centred on the nib.
        val brush = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().boundsInRoot
        assertEquals(nib.x, brush.center.x, 1f); assertEquals(nib.y, brush.center.y, 1f)
        screenshot("summoned-picking-light")
        compose.runOnIdle { settings.applyDialSegment(dial.release(nib + segment(1))!!) }
        compose.onNodeWithContentDescription("Pen").assertDoesNotExist()
        compose.runOnIdle { assertEquals(Tool.Highlighter, settings.tool) }
    }

    @Test fun lockedDialTakesTapsUntilItIsClosed() {
        val dial = PenDialState()
        render(800, ThemeMode.Dark, dial = dial, alwaysVisible = false)
        compose.runOnIdle { dial.press(Offset(300f, 420f)); dial.release(Offset(301f, 421f)) }
        compose.onNodeWithText("Tap outside to close").assertIsDisplayed()
        compose.onNodeWithContentDescription("Lasso").performClick()
        compose.runOnIdle { assertEquals(Tool.Lasso, settings.tool); assertEquals(PenDialState.Phase.Locked, dial.phase) }
        compose.onNodeWithContentDescription("Quick color 2").performClick()
        compose.runOnIdle { assertEquals(Tool.Pen, settings.tool); assertEquals(1, settings.penSlots.color) }
        compose.onNodeWithContentDescription("Brush options").performClick()
        compose.onNodeWithText("Pressure sensitivity").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Color 3").performClick()
        compose.runOnIdle { assertEquals(2, settings.penSlots.color) }
        screenshot("summoned-locked-dark")
        compose.onNodeWithContentDescription("Close tools").performClick()
        compose.onNodeWithContentDescription("Pen").assertDoesNotExist()
    }

    @Test fun showToolsOpensTheHiddenDialFromMore() {
        val dial = PenDialState()
        render(360, ThemeMode.Light, dial = dial, alwaysVisible = false)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Collapse tools").assertDoesNotExist()
        compose.onNodeWithText("Show tools").performClick()
        compose.onNodeWithContentDescription("Close tools").assertIsDisplayed()
        assertTargetsApart(tools + listOf("Close tools"))
        compose.runOnIdle { dial.dismiss() }
        compose.onNodeWithContentDescription("Pen").assertDoesNotExist()
    }

    @Test fun realPopupSummonsAboveTheDockedDialAndReturnsIt() {
        val dial = PenDialState()
        render(800, ThemeMode.Light, floating = true, dial = dial)
        compose.onNodeWithContentDescription("Brush options").performTouchInput { swipe(center, center + Offset(120f, 80f), 600) }
        val docked = compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().positionOnScreen
        compose.runOnIdle { dial.press(Offset(500f, 500f)) }
        compose.onAllNodesWithContentDescription("Pen").assertCountEquals(1)
        compose.onNodeWithContentDescription("Keep tools open").assertIsDisplayed()
        compose.runOnIdle { dial.release(Offset(500f, 500f) + segment(3)) }
        compose.runOnIdle { settings.applyDialSegment(3) }
        assertEquals(docked, compose.onNodeWithContentDescription("Brush options").fetchSemanticsNode().positionOnScreen)
        compose.runOnIdle { assertEquals(Tool.Lasso, settings.tool) }
    }

    private fun assertControls() {
        assertTargetsApart(controls + tools + listOf("Brush options", "Collapse tools"))
        listOf("Export", "Paper", "Precision").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithContentDescription("Undo").performClick()
        compose.onNodeWithContentDescription("Redo").performClick()
        assertEquals(1, undos); assertEquals(1, redos)
    }

    private fun assertTargetsApart(labels: List<String>) {
        val toolbar = compose.onNodeWithTag("toolbar").fetchSemanticsNode().boundsInRoot
        val boxes = labels.map { label ->
            val b = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$label outside window: $b", b.left >= toolbar.left - 0.5f && b.right <= toolbar.right + 0.5f && b.top >= toolbar.top - 0.5f && b.bottom <= toolbar.bottom + 0.5f)
            label to b
        }
        for ((i, a) in boxes.withIndex()) for (b in boxes.drop(i + 1)) {
            assertFalse("Controls overlap: ${a.first} and ${b.first}", a.second.overlaps(b.second))
        }
    }

    /** The topmost window (a popup or menu), drawn on its own. */
    private fun popupScreenshot(name: String) {
        compose.waitForIdle()
        compose.runOnUiThread {
            val window = android.view.inspector.WindowInspector.getGlobalWindowViews().last { it.width > 0 && it.height > 0 }
            val bitmap = Bitmap.createBitmap(window.width, window.height, Bitmap.Config.ARGB_8888)
            window.draw(android.graphics.Canvas(bitmap))
            File(File("build/reports/editor-ui").apply { mkdirs() }, "$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun screenshot(name: String) {
        val directory = File("build/reports/editor-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            compose.captureRenderedView(renderView, "editor").compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

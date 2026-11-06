package app.noti.ui.settings

import app.noti.ui.common.NotiButton as Button
import app.noti.ui.common.NotiTextButton as TextButton
import app.noti.ui.common.NotiIconButton as IconButton
import app.noti.ui.common.NotiOutlinedButton as OutlinedButton
import app.noti.ui.common.NotiAlertDialog as AlertDialog
import app.noti.ui.common.NotiDropdownMenu as DropdownMenu
import app.noti.ui.common.NotiMenuItem as DropdownMenuItem
import app.noti.ui.common.NotiFilterChip as FilterChip

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.LaunchedEffect
import android.widget.Toast
import app.noti.ink.InkLayer
import app.noti.ui.common.PalmSpec
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.SmartButton
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.noti.ui.common.NotiGroup
import app.noti.ui.common.NotiGroupDivider
import app.noti.ui.theme.Noti
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.noti.data.Paper
import app.noti.data.Predictor
import app.noti.data.SettingsStore
import app.noti.data.ThemeMode
import app.noti.data.PenButtonAction
import app.noti.data.Tool
import app.noti.data.penButtonOptions
import app.noti.ink.PenButton
import app.noti.ink.PenHold
import app.noti.ink.PenVendor
import app.noti.ui.common.StylusDialogKeys
import app.noti.ui.common.BrushSpec
import app.noti.ui.common.EngineSpec
import app.noti.ui.common.InkCanvas
import app.noti.ui.common.PaperPicker
import app.noti.ui.common.greyedOut
import app.noti.ui.brand.DotiMascot
import app.noti.ui.brand.NotiWordmark
import app.noti.ui.brand.FriendlyNote
import app.noti.ui.theme.NotiBrand
import app.noti.R
import app.noti.transcribe.HandwritingLanguages

private enum class Section(val label: String, val icon: ImageVector) {
    Pen("Pen", Icons.Outlined.Create),
    Buttons("Pen buttons", Icons.Outlined.SmartButton),
    Writing("Writing", Icons.Outlined.Gesture),
    Palm("Palm rejection", Icons.Outlined.PanTool),
    Pages("Pages", Icons.Outlined.Description),
    Study("Study", Icons.Outlined.School),
    Appearance("Appearance", Icons.Outlined.Palette),
    About("About", Icons.Outlined.Info),
}

@Composable
fun SettingsScreen(settings: SettingsStore, onBack: () -> Unit) {
    StylusDialogKeys(settings.penButtonsEnabled && settings.penLegacyKeys)
    var section by remember { mutableStateOf(Section.Pen) }
    var sectionMenu by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact = maxWidth < 840.dp || maxHeight < 480.dp
            val body: @Composable () -> Unit = {
                key(section) {
                    when (section) {
                        Section.Pen -> PenSection(settings)
                        Section.Buttons -> PenButtonsSection(settings)
                        Section.Writing -> Pane {
                            SectionIntro("Your flow, your way.", "Small comforts for long writing sessions.")
                            WritingSection(settings)
                        }
                        Section.Palm -> PalmSection(settings)
                        Section.Pages -> Pane {
                            SectionIntro("Your kind of paper.", "A fresh page should feel like you.")
                            PagesSection(settings)
                        }
                        Section.Study -> Pane {
                            SectionIntro("Study smarter.", "Questions from your PDFs, checked on the board.")
                            StudySection(settings)
                        }
                        Section.Appearance -> Pane {
                            SectionIntro("A little change of scenery.", "Find the light that feels right.")
                            AppearanceSection(settings)
                        }
                        Section.About -> Pane { AboutSection() }
                    }
                }
            }
            if (compact) Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    Box {
                        Row(Modifier.heightIn(min = 48.dp).clip(CircleShape).background(colors.surfaceContainerHigh)
                            .clickable { sectionMenu = true }.semantics { contentDescription = "Choose settings section" }
                            .padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(section.icon, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                            Text(section.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                            Icon(Icons.Outlined.ExpandMore, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                        }
                        DropdownMenu(expanded = sectionMenu, onDismissRequest = { sectionMenu = false }) {
                            Section.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, leadingIcon = { Icon(s.icon, null) },
                                    onClick = { section = s; sectionMenu = false })
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { body() }
            } else Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(248.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 20.dp)) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                        Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
                    }
                    Section.entries.forEach { s -> NavPill(s.label, s.icon, section == s) { section = s } }
                }
                Surface(color = Noti.extras.sheet, shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 12.dp, end = 12.dp, bottom = 12.dp)) { body() }
            }
        }
    }
}

@Composable
private fun NavPill(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
            .background(if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) colors.onPrimaryContainer else colors.onSurface)
    }
}

/** Keep the native scratch canvas stationary while its settings scroll, even on a phone. */
@Composable
private fun SettingsSplit(controls: @Composable (Modifier) -> Unit, preview: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < 720.dp) {
            val previewHeight = (maxHeight * 0.38f).coerceIn(140.dp, 260.dp)
            Column(Modifier.fillMaxSize()) {
                controls(Modifier.weight(1f).fillMaxWidth())
                preview(Modifier.fillMaxWidth().height(previewHeight))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                controls(Modifier.weight(1f).fillMaxHeight())
                preview(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Pane(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 28.dp, vertical = 28.dp),
        ) { content() }
    }
}

/** The scratch canvas: a real ink engine on a sheet of paper. */
@Composable
private fun ScratchPane(title: String, clearLabel: String, modifier: Modifier, clearEnabled: Boolean = true,
    onClear: () -> Unit, canvas: @Composable (Modifier) -> Unit) {
    Column(modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear, enabled = clearEnabled) { Text(clearLabel) }
        }
        Spacer(Modifier.height(4.dp))
        canvas(
            Modifier
                .fillMaxSize()
                .shadow(4.dp, MaterialTheme.shapes.large, ambientColor = Noti.extras.shadow, spotColor = Noti.extras.shadow)
                .clip(MaterialTheme.shapes.large)
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        )
    }
}

/**
 * Pen settings beside a scratch area to try them right away. The scratch area is a real
 * ink engine, so it stays at a fixed position (outside the scrolling column).
 */
@Composable
private fun PenSection(settings: SettingsStore) {
    var clears by remember { mutableIntStateOf(0) }
    SettingsSplit(controls = { paneModifier ->
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = paneModifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp),
        ) {
            SectionIntro("Make your mark.", "Tune your pen, then try a little scribble.")
            NotiGroup {
            SwitchRow("Pressure sensitivity", "Line width follows how hard you press.", settings.pressureOn) {
                settings.pressureOn = it
            }
            NotiGroupDivider()
            LabeledSlider(
                title = "Pressure",
                description = "How hard you need to press for a full-width line.",
                start = "Light",
                end = "Firm",
                value = settings.pressureFirmness,
                enabled = settings.pressureOn,
            ) { settings.pressureFirmness = it }
            NotiGroupDivider()
            LabeledSlider(
                title = "Ink feel",
                description = "Write a few fast lines. Move right until the ink stays on the nib; stop before it runs ahead of it.",
                start = "Lags behind",
                end = "Runs ahead",
                value = settings.inkFeelMs,
                range = 0f..15f,
            ) { settings.inkFeelMs = it }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Prediction", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "How the engine guesses where the nib is going. Kalman is the tuned default.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Predictor.entries.forEach { p ->
                        FilterChip(selected = settings.predictor == p, onClick = { settings.predictor = p }, label = { Text(p.label) })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Button shortcuts", style = MaterialTheme.typography.titleSmall)
                Text(if (PenVendor.isSamsung()) "By default, click the S Pen button to toggle the eraser. " +
                    "Change this shortcut and test held buttons in Pen buttons."
                else "By default, click the writing button to toggle the eraser, or the screenshot button to toggle the lasso. " +
                    "Change these shortcuts and test held buttons in Pen buttons.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, preview = { paneModifier ->
        ScratchPane("Try it here", "Clear", paneModifier, onClear = { clears++ }) { canvasModifier ->
            val slots = settings.penSlots
            key(clears) {
                InkCanvas(
                    paper = Paper(),
                    brush = BrushSpec(
                        slots.currentColor, 1f, slots.currentWidth,
                        if (settings.pressureOn) settings.pressureGamma else null,
                    ),
                    engine = EngineSpec(settings.predictor.engineId, settings.inkFeelMs),
                    palm = settings.palmSpec(),
                    buttons = settings.penButtonOptions().copy(captureFocus = false),
                    modifier = canvasModifier,
                )
            }
        }
    })
}

/** A local, bounded tester: no logging during normal writing and no notebook changes. */
@Composable
private fun PenButtonsSection(settings: SettingsStore) {
    // The S Pen has one side button and no Focus button, and One UI never uses Page keys for it.
    val samsung = remember { PenVendor.isSamsung() }
    val writing = PenButton.Primary.title
    val inSentence = PenButton.Primary.phrase
    var events by remember { mutableStateOf(emptyList<String>()) }
    var clears by remember { mutableIntStateOf(0) }
    SettingsSplit(controls = { paneModifier ->
        Column(paneModifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NotiGroup {
                SwitchRow("Pen shortcuts", "Use the pen buttons while a canvas is open.", settings.penButtonsEnabled) {
                    settings.penButtonsEnabled = it
                }
                NotiGroupDivider()
                ButtonActionRow("$writing click", settings.primaryButtonAction) { settings.primaryButtonAction = it }
                if (!samsung) {
                    NotiGroupDivider()
                    ButtonActionRow("Screenshot button click", settings.secondaryButtonAction) { settings.secondaryButtonAction = it }
                    NotiGroupDivider()
                    ButtonActionRow("Focus button click", settings.focusButtonAction) { settings.focusButtonAction = it }
                }
            }
            Hint(if (samsung) "Click the S Pen button while it hovers over the page. Presses with the pen away from the screen (Air actions) stay with One UI."
                else "The Focus button normally stays with Xiaomi's pointer and camera controls. An assigned action works only if Xiaomi sends the press to Noti.")
            NotiGroup {
                HoldActionRow(settings.penButtonHold, inSentence) { settings.penButtonHold = it }
                if (settings.penButtonHold == PenHold.Dial) {
                    NotiGroupDivider()
                    SwitchRow("Always show the tool dial", if (settings.toolDialAlways)
                        "On: the dial stays on the page, and holding the $inSentence also opens it under the nib."
                    else "Off: the dial stays out of the way until you hold the $inSentence and touch the page. More → Show tools also opens it.",
                        settings.toolDialAlways) { settings.toolDialAlways = it }
                }
            }
            if (!samsung) Hint("Holding the screenshot button stays with HyperOS. Noti uses only its delivered short clicks.")
            NotiGroup {
                SwitchRow("S Pen button clicks", "Recognize clicks that the pen reports with its position, as the Samsung S Pen and most Wacom-style pens do. " +
                    "Pens that send button keys keep using them.", settings.penMotionClicks) { settings.penMotionClicks = it }
                if (!samsung) {
                    NotiGroupDivider()
                    SwitchRow("Xiaomi button compatibility", "Recognize pen buttons sent as Page Down and Page Up. Keyboard page navigation stays available.",
                        settings.penLegacyKeys) { settings.penLegacyKeys = it }
                    NotiGroupDivider()
                    SwitchRow("Swap Xiaomi button order", "Use this if the writing and screenshot buttons are reversed on your tablet.",
                        settings.penSwapLegacyKeys) { settings.penSwapLegacyKeys = it }
                }
            }
            if (samsung) Hint("If Air command opens when you click the S Pen button, turn it off in the system Settings " +
                "(on most One UI versions: Advanced features → S Pen → Air command).")
            Spacer(Modifier.height(4.dp))
            Text("Test click and hold", style = MaterialTheme.typography.titleSmall)
            Text((if (samsung) "Click the S Pen button while hovering, then hold it for two seconds near the screen. "
                else "Click each button, then hold only the writing button for two seconds, both away from and near the screen. ") +
                "If its click is reported before you release it, your tablet exposes clicks only; use toggle shortcuts. " +
                "Holding the $inSentence for ink does not also trigger its click action.", style = MaterialTheme.typography.bodyMedium)
            Text(if (settings.penButtonHold == PenHold.Erase) "The scratch area tests temporary erasing with the $inSentence. Click results appear below without changing your notebook or selected tool."
                else "Click results appear below without changing your notebook or selected tool. The tool dial opens in notebooks; here a held stroke draws normally.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { events = emptyList() }) { Text("Clear button history") }
            if (events.isEmpty()) Text("Waiting for pen button input…", style = MaterialTheme.typography.bodySmall)
            events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, preview = { paneModifier ->
        ScratchPane("Scratch area", "Clear ink", paneModifier, onClear = { clears++ }) { canvasModifier ->
            key(clears) {
                val slots = settings.penSlots
                InkCanvas(
                    paper = Paper(), brush = BrushSpec(slots.currentColor, 1f, slots.currentWidth,
                        if (settings.pressureOn) settings.pressureGamma else null, Tool.Pen),
                    engine = EngineSpec(settings.predictor.engineId, settings.inkFeelMs),
                    palm = settings.palmSpec(),
                    buttons = settings.penButtonOptions(),
                    onButtonClick = { button -> events = (listOf("${button.label}: click received") + events).take(16) },
                    onButtonDiagnostic = { line -> events = (listOf(line) + events).take(16) },
                    modifier = canvasModifier,
                )
            }
        }
    })
}

@Composable
private fun HoldActionRow(hold: PenHold, name: String, onChange: (PenHold) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        ValueRow("Hold $name", hold.label) { open = true }
        Text(when (hold) {
            PenHold.Dial -> "Hold the $name and touch the page: the tool dial opens under the nib. Drag to a tool, color or width and lift to choose it. " +
                "Lift on the center to cancel, or on the lock to keep the dial open."
            PenHold.Erase -> "Hold the $name while drawing to erase temporarily. Your selected tool returns on the next stroke after release."
            PenHold.Off -> "Holding the $name changes nothing. Its click action still works."
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PenHold.entries.forEach { value ->
                DropdownMenuItem(text = { Text(value.label) }, onClick = { onChange(value); open = false })
            }
        }
    }
}

@Composable
private fun ButtonActionRow(title: String, action: PenButtonAction, onChange: (PenButtonAction) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        ValueRow(title, action.label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PenButtonAction.entries.forEach { value ->
                DropdownMenuItem(text = { Text(value.label) }, onClick = { onChange(value); open = false })
            }
        }
    }
}

@Composable
private fun WritingSection(settings: SettingsStore) {
    SwitchRow(
        "Draw with finger",
        if (settings.fingerDrawing) {
            "On: one finger draws, two fingers move the page. Palms are still rejected, and while you use the pen a finger moves the page instead."
        } else {
            "Off: only the pen writes; fingers move and zoom the page."
        },
        checked = settings.fingerDrawing,
    ) { settings.fingerDrawing = it }
    SwitchRow("Two-finger tap to undo", "Double-tap with two fingers to leave full screen.", checked = settings.tapUndo) { settings.tapUndo = it }
    SwitchRow(
        "Ask before leaving a notebook", "Ink is saved automatically on this device.",
        checked = settings.confirmLeave,
    ) { settings.confirmLeave = it }
    HandwritingLanguageRow(settings)
}

/** The language the Transcribe tool reads. Its model downloads the first time it's used. */
@Composable
private fun HandwritingLanguageRow(settings: SettingsStore) {
    var choosing by remember { mutableStateOf(false) }
    val device = remember { HandwritingLanguages.displayName(HandwritingLanguages.resolve("").languageTag) }
    val current = settings.handwritingLanguage
    Column {
        ValueRow("Handwriting language", if (current.isEmpty()) "Match device" else HandwritingLanguages.displayName(current)) {
            choosing = true
        }
        Text("What the Transcribe tool reads. Each language downloads once (about 20 MB), then works offline.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (choosing) {
        val languages = remember { listOf("" to "Match device ($device)") + HandwritingLanguages.available() }
        AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Handwriting language") },
            text = {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(languages.size) { i ->
                        val (tag, name) = languages[i]
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                            .heightIn(min = 48.dp).selectable(selected = tag == current, role = Role.RadioButton) {
                                settings.handwritingLanguage = tag; choosing = false
                            }) {
                            RadioButton(selected = tag == current, onClick = null)
                            Text(name, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text("Close") } })
    }
}

private fun SettingsStore.palmSpec() = PalmSpec(fingerDrawing, palmModel, onModel = { palmModel = it })

/** What each recording is labelled as, for palm_bench. */
private enum class PalmScenario(val label: String, val title: String, val hint: String) {
    Palm("palm", "Writing hand", "Write normally with your hand resting on the screen. Every finger contact is counted as a palm."),
    Touch("touch", "Deliberate touches", "Don't use the pen. Tap, pan and pinch with your fingers. Every contact is counted as deliberate."),
}

/**
 * Palm rejection, live: a scratch canvas with the filter's counters and what it has
 * learned about your hand, plus a recorder that writes labelled sessions for palm_bench
 * (engine/tools/palm_bench.cpp) so the filter can be measured on your own hand.
 */
@Composable
private fun PalmSection(settings: SettingsStore) {
    val context = LocalContext.current
    var layer by remember { mutableStateOf<InkLayer?>(null) }
    var stats by remember { mutableStateOf(LongArray(9)) }
    var scenario by remember { mutableStateOf(PalmScenario.Palm) }
    var recording by remember { mutableStateOf(false) }
    var clears by remember { mutableIntStateOf(0) }
    // After "Forget", the closing canvas must not save back what it had learned.
    var modelGeneration by remember { mutableIntStateOf(0) }
    LaunchedEffect(layer) {
        while (layer != null) {
            layer?.palmStats { stats = it }
            delay(500)
        }
    }
    SettingsSplit(controls = { paneModifier ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = paneModifier.verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 28.dp),
        ) {
            Text(
                "Only the pen and deliberate touches get through. Every finger contact is scored from the pen's state, " +
                    "where your hand rests relative to the nib, the contact's size and how it moves, and its neighbours, " +
                    "and only confirmed touches move the page. The filter learns your hand as you write.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NotiGroup {
                Text("This session", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                StatRow("Palm contacts rejected", "${stats[0]}" + if (stats[4] > 0) "  (${stats[4]} flagged by the system)" else "")
                StatRow("Deliberate touches", if (stats[1] > 0) "${stats[1]}, confirmed in %.0f ms on average".format(stats[5] / 1000.0) else "0")
                StatRow("Finger strokes kept", "${stats[2]}")
                StatRow("Tentative strokes rolled back", "${stats[3]}")
            }
            NotiGroup {
                Text("What it has learned", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                val right = stats[6] / 1000f
                StatRow(
                    "Writing hand",
                    when {
                        right > 0.75f -> "right (%.0f%%)".format(right * 100)
                        right < 0.25f -> "left (%.0f%%)".format((1 - right) * 100)
                        else -> "not sure yet"
                    },
                )
                StatRow("Pause between strokes it waits out", "${stats[7]} ms")
                StatRow("Fingertip / palm size boundary", "%.1f mm".format(stats[8] / 1000.0))
                TextButton(onClick = {
                    modelGeneration++
                    settings.palmModel = null
                    clears++
                    Toast.makeText(context, "Forgot what it learned about your hand", Toast.LENGTH_SHORT).show()
                }) { Text("Forget") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Text("Record a test session", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Records every pen and touch sample on the canvas, labelled, so palm_bench can measure the filter on your hand.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PalmScenario.entries.forEach { s ->
                        FilterChip(selected = scenario == s, onClick = { if (!recording) scenario = s },
                            enabled = !recording || scenario == s, label = { Text(s.title) })
                    }
                }
                Text(scenario.hint, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!recording) {
                        Button(onClick = {
                            layer?.setPalmRecording(true, scenario.label)
                            recording = true
                        }, enabled = layer != null) { Text("Start recording") }
                    } else {
                        Button(onClick = {
                            val dir = File(context.getExternalFilesDir(null), "palm-lab").apply { mkdirs() }
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            val file = File(dir, "palm-${scenario.label}-$stamp.csv")
                            val l = layer
                            if (l == null) {
                                recording = false
                            } else {
                                l.exportPalmRecording(file.path) { rows ->
                                    l.setPalmRecording(false, scenario.label)
                                    recording = false
                                    val msg = if (rows >= 0) "Saved $rows samples to ${file.path}" else "Couldn't save the recording"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                }
                            }
                        }) { Text("Stop and save") }
                        OutlinedButton(onClick = {
                            layer?.setPalmRecording(false, scenario.label)
                            recording = false
                        }) { Text("Discard") }
                    }
                }
                Text(
                    "Files go to Android/data/app.noti/files/palm-lab. Copy them with adb pull, then run palm_bench --csv <files>.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }, preview = { paneModifier ->
        ScratchPane("Try it here", "Clear", paneModifier, clearEnabled = !recording, onClear = { clears++ }) { canvasModifier ->
            val slots = settings.penSlots
            key(clears) {
                InkCanvas(
                    paper = Paper(),
                    brush = BrushSpec(slots.currentColor, 1f, slots.currentWidth, if (settings.pressureOn) settings.pressureGamma else null),
                    engine = EngineSpec(settings.predictor.engineId, settings.inkFeelMs),
                    buttons = settings.penButtonOptions().copy(captureFocus = false),
                    palm = run {
                        val gen = modelGeneration
                        PalmSpec(settings.fingerDrawing, settings.palmModel) { m -> if (gen == modelGeneration) settings.palmModel = m }
                    },
                    onLayer = { layer = it },
                    modifier = canvasModifier,
                )
            }
        }
    })
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PagesSection(settings: SettingsStore) {
    Text("Paper for new notebooks", style = MaterialTheme.typography.titleMedium)
    PaperPicker(
        paper = Paper(settings.defaultTemplate, settings.defaultColor),
        onChange = {
            settings.defaultTemplate = it.template
            settings.defaultColor = it.color
        },
    )
}

@Composable
private fun AppearanceSection(settings: SettingsStore) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Text(
                "For toolbars and menus. Pages always keep their paper color.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        NotiGroup {
            ThemeMode.entries.forEachIndexed { i, m ->
                if (i > 0) NotiGroupDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .selectable(selected = settings.theme == m, role = Role.RadioButton, onClick = { settings.theme = m }),
                ) {
                    Text(m.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    RadioButton(selected = settings.theme == m, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    var licenseOpen by remember { mutableStateOf(false) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DotiMascot(Modifier.size(120.dp), description = "Doti, Noti’s little ink companion")
        Text("Noti", style = MaterialTheme.typography.headlineLarge)
        Text(NotiBrand.Tagline, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("For passing thoughts, happy scribbles, and the things you want to keep. " +
            "A quiet little home for your notes and PDFs.", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FriendlyNote("Meet Doti", "Part ink drop, part daydream. Always up for a fresh page.")
        Text("Version $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Made for handwriting with the Xiaomi Pad 7 and Focus Pen, and Samsung Galaxy Tab S tablets with the S Pen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { licenseOpen = true }) { Text("Typeface license") }
    }
    if (licenseOpen) {
        val license = remember { context.resources.openRawResource(R.raw.nunito_ofl).bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { licenseOpen = false }, title = { Text("Nunito") },
            text = { Text(license, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { licenseOpen = false }) { Text("Close") } })
    }
}

@Composable
private fun SectionIntro(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- rows ----

@Composable
private fun SwitchRow(
    title: String,
    description: String?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onChange(!checked) }.padding(vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    description: String,
    start: String,
    end: String,
    value: Float,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.greyedOut(!enabled).padding(vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(start, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(end, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Outlined.ExpandMore, null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 18.dp))
}

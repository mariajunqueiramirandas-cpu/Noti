package app.noti.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import app.noti.ui.common.StylusDialogKeys
import app.noti.ui.common.BrushSpec
import app.noti.ui.common.EngineSpec
import app.noti.ui.common.InkCanvas
import app.noti.ui.common.PaperPicker
import app.noti.ui.common.greyedOut

private enum class Section(val label: String, val icon: ImageVector) {
    Pen("Pen", Icons.Outlined.Create),
    Buttons("Pen buttons", Icons.Outlined.TouchApp),
    Writing("Writing", Icons.Outlined.TouchApp),
    Palm("Palm rejection", Icons.Outlined.PanTool),
    Pages("Pages", Icons.Outlined.Description),
    Appearance("Appearance", Icons.Outlined.Palette),
    About("About", Icons.Outlined.Info),
}

@Composable
fun SettingsScreen(settings: SettingsStore, onBack: () -> Unit) {
    StylusDialogKeys(settings.penButtonsEnabled && settings.penLegacyKeys)
    var section by remember { mutableStateOf(Section.Pen) }
    var sectionMenu by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val compact = maxWidth < 840.dp || maxHeight < 480.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                    Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                if (compact) {
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = { sectionMenu = true }) {
                            Icon(section.icon, null)
                            Text(section.label, Modifier.padding(horizontal = 8.dp))
                            Icon(Icons.Outlined.ExpandMore, "Choose settings section")
                        }
                        DropdownMenu(expanded = sectionMenu, onDismissRequest = { sectionMenu = false }) {
                            Section.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, leadingIcon = { Icon(s.icon, null) },
                                    onClick = { section = s; sectionMenu = false })
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (!compact) {
                        Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Section.entries.forEach { s ->
                                NavigationDrawerItem(
                                    label = { Text(s.label) }, icon = { Icon(s.icon, null) },
                                    selected = section == s, onClick = { section = s },
                                    colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.background),
                                )
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        key(section) {
                            when (section) {
                                Section.Pen -> PenSection(settings)
                                Section.Buttons -> PenButtonsSection(settings)
                                Section.Writing -> Pane { WritingSection(settings) }
                                Section.Palm -> PalmSection(settings)
                                Section.Pages -> Pane { PagesSection(settings) }
                                Section.Appearance -> Pane { AppearanceSection(settings) }
                                Section.About -> Pane { AboutSection() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keep the native scratch canvas stationary while its settings scroll, even on a phone. */
@Composable
private fun SettingsSplit(controls: @Composable (Modifier) -> Unit, preview: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < 720.dp) {
            val previewHeight = (maxHeight * 0.35f).coerceIn(128.dp, 240.dp)
            Column(Modifier.fillMaxSize()) {
                controls(Modifier.weight(1f).fillMaxWidth())
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                preview(Modifier.fillMaxWidth().height(previewHeight))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                controls(Modifier.weight(1f).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                preview(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun Pane(content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) { content() }
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
            modifier = paneModifier.verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            SwitchRow("Pressure sensitivity", "Line width follows how hard you press.", settings.pressureOn) {
                settings.pressureOn = it
            }
            LabeledSlider(
                title = "Pressure",
                description = "How hard you need to press for a full-width line.",
                start = "Light",
                end = "Firm",
                value = settings.pressureFirmness,
                enabled = settings.pressureOn,
            ) { settings.pressureFirmness = it }
            LabeledSlider(
                title = "Ink feel",
                description = "Write a few fast lines. Move right until the ink stays on the nib; stop before it runs ahead of it.",
                start = "Lags behind",
                end = "Runs ahead",
                value = settings.inkFeelMs,
                range = 0f..15f,
            ) { settings.inkFeelMs = it }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Prediction", style = MaterialTheme.typography.titleSmall)
                Text(
                    "How the engine guesses where the nib is going. Kalman is the tuned default.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Predictor.entries.forEach { p ->
                        FilterChip(selected = settings.predictor == p, onClick = { settings.predictor = p }, label = { Text(p.label) })
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Button shortcuts", style = MaterialTheme.typography.titleSmall)
                Text("By default, click the writing button to toggle the eraser, or the screenshot button to toggle the lasso. " +
                    "Change these shortcuts and test held buttons in Pen buttons.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, preview = { paneModifier ->
        Column(paneModifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Try it here", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { clears++ }) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
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
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                )
            }
        }
    })
}

/** A local, bounded tester: no logging during normal writing and no notebook changes. */
@Composable
private fun PenButtonsSection(settings: SettingsStore) {
    var events by remember { mutableStateOf(emptyList<String>()) }
    var clears by remember { mutableIntStateOf(0) }
    SettingsSplit(controls = { paneModifier ->
        Column(paneModifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SwitchRow("Pen shortcuts", "Use the pen buttons while a canvas is open.", settings.penButtonsEnabled) {
                settings.penButtonsEnabled = it
            }
            ButtonActionRow("Writing button click", settings.primaryButtonAction) { settings.primaryButtonAction = it }
            ButtonActionRow("Screenshot button click", settings.secondaryButtonAction) { settings.secondaryButtonAction = it }
            ButtonActionRow("Focus button click", settings.focusButtonAction) { settings.focusButtonAction = it }
            Text("The Focus button normally stays with Xiaomi's pointer and camera controls. An assigned action works only if Xiaomi sends the press to Noti.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow("Hold to erase or select", "Hold writing to erase, or screenshot to lasso. Your selected tool returns on the next stroke after release.",
                settings.penButtonHolds) { settings.penButtonHolds = it }
            SwitchRow("Xiaomi button compatibility", "Recognize pen buttons sent as Page Down and Page Up. Keyboard page navigation stays available.",
                settings.penLegacyKeys) { settings.penLegacyKeys = it }
            SwitchRow("Swap Xiaomi button order", "Use this if the writing and screenshot buttons are reversed on your tablet.",
                settings.penSwapLegacyKeys) { settings.penSwapLegacyKeys = it }
            HorizontalDivider()
            Text("Test click and hold", style = MaterialTheme.typography.titleSmall)
            Text("Click each button, then hold it for two seconds, both away from and near the screen. " +
                "If a click is reported before you release a held button, your tablet exposes clicks only; use toggle shortcuts. " +
                "A long press or use while writing does not also trigger the click action.", style = MaterialTheme.typography.bodyMedium)
            Text("The scratch area tests temporary erase/select. Click results appear below without changing your notebook or selected tool.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { events = emptyList() }) { Text("Clear button history") }
            if (events.isEmpty()) Text("Waiting for pen button input…", style = MaterialTheme.typography.bodySmall)
            events.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }, preview = { paneModifier ->
        Column(paneModifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Scratch area", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { clears++ }) { Text("Clear ink") }
            }
            Spacer(Modifier.height(8.dp))
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
                    modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                )
            }
        }
    })
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
            modifier = paneModifier.verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                "Only the pen and deliberate touches get through. Every finger contact is scored from the pen's state, " +
                    "where your hand rests relative to the nib, the contact's size and how it moves, and its neighbours, " +
                    "and only confirmed touches move the page. The filter learns your hand as you write.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("This session", style = MaterialTheme.typography.titleSmall)
                StatRow("Palm contacts rejected", "${stats[0]}" + if (stats[4] > 0) "  (${stats[4]} flagged by the system)" else "")
                StatRow("Deliberate touches", if (stats[1] > 0) "${stats[1]}, confirmed in %.0f ms on average".format(stats[5] / 1000.0) else "0")
                StatRow("Finger strokes kept", "${stats[2]}")
                StatRow("Tentative strokes rolled back", "${stats[3]}")
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What it has learned", style = MaterialTheme.typography.titleSmall)
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Column(paneModifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Try it here", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { clears++ }, enabled = !recording) { Text("Clear") }
            }
            Spacer(Modifier.height(8.dp))
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
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                )
            }
        }
    })
}

@Composable
private fun StatRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PagesSection(settings: SettingsStore) {
    Text("Paper for new notebooks", style = MaterialTheme.typography.titleSmall)
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
    Column {
        Text("Theme", style = MaterialTheme.typography.titleSmall)
        Text(
            "For toolbars and menus. Pages always keep their paper color.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        ThemeMode.entries.forEach { m ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .selectable(selected = settings.theme == m, onClick = { settings.theme = m })
                    .padding(end = 16.dp),
            ) {
                RadioButton(selected = settings.theme == m, onClick = null, modifier = Modifier.padding(12.dp))
                Text(m.label)
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Noti", style = MaterialTheme.typography.headlineSmall)
        Text("Version $version", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text("Low-latency notes and PDF annotation for the Xiaomi Pad 7 and Focus Pen.")
        Text(
            "Ink is drawn by a native front-buffer engine with its own input thread, so the interface never slows the pen down.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        modifier = modifier.clip(MaterialTheme.shapes.small).clickable { onChange(!checked) },
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
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
    Column(Modifier.greyedOut(!enabled)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(start, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(end, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

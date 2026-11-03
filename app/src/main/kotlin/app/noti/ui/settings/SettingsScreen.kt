package app.noti.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import app.noti.ui.common.BrushSpec
import app.noti.ui.common.EngineSpec
import app.noti.ui.common.InkCanvas
import app.noti.ui.common.PaperPicker
import app.noti.ui.common.comingSoon
import app.noti.ui.common.greyedOut

private enum class Section(val label: String, val icon: ImageVector) {
    Pen("Pen", Icons.Outlined.Create),
    Writing("Writing", Icons.Outlined.TouchApp),
    Pages("Pages", Icons.Outlined.Description),
    Appearance("Appearance", Icons.Outlined.Palette),
    About("About", Icons.Outlined.Info),
}

@Composable
fun SettingsScreen(settings: SettingsStore, onBack: () -> Unit) {
    var section by remember { mutableStateOf(Section.Pen) }
    BackHandler(onBack = onBack)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(220.dp).fillMaxHeight().padding(12.dp)) {
                    Section.entries.forEach { s ->
                        NavigationDrawerItem(
                            label = { Text(s.label) },
                            icon = { Icon(s.icon, null) },
                            selected = section == s,
                            onClick = { section = s },
                            colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = MaterialTheme.colorScheme.background),
                        )
                    }
                }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (section) {
                    Section.Pen -> PenSection(settings)
                    Section.Writing -> Pane { WritingSection(settings) }
                    Section.Pages -> Pane { PagesSection(settings) }
                    Section.Appearance -> Pane { AppearanceSection(settings) }
                    Section.About -> Pane { AboutSection() }
                }
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
    val buttonsSoon = comingSoon("Pen button actions")
    var clears by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
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
                SingleChoiceSegmentedButtonRow {
                    Predictor.entries.forEachIndexed { i, p ->
                        SegmentedButton(
                            selected = settings.predictor == p,
                            onClick = { settings.predictor = p },
                            shape = SegmentedButtonDefaults.itemShape(i, Predictor.entries.size),
                        ) { Text(p.label) }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.greyedOut()) {
                Text("Pen buttons", style = MaterialTheme.typography.titleSmall)
                ValueRow("Upper button", "Eraser", onClick = buttonsSoon)
                ValueRow("Lower button", "Lasso", onClick = buttonsSoon)
            }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
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
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium),
                )
            }
        }
    }
}

@Composable
private fun WritingSection(settings: SettingsStore) {
    val fingerSoon = comingSoon("Drawing with your finger")
    val undoSoon = comingSoon("Undo")
    SwitchRow(
        "Draw with finger", "Off: only the pen writes, so your palm and fingers never leave marks.",
        checked = false, modifier = Modifier.greyedOut(),
    ) { fingerSoon() }
    SwitchRow("Two-finger tap to undo", null, checked = false, modifier = Modifier.greyedOut()) { undoSoon() }
    SwitchRow(
        "Ask before leaving a notebook", "Ink isn't saved yet, so leaving a notebook clears it.",
        checked = settings.confirmLeave,
    ) { settings.confirmLeave = it }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(start, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp))
            Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled, modifier = Modifier.weight(1f))
            Text(end, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(84.dp).padding(start = 12.dp))
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

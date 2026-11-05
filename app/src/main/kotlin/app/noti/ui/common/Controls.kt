package app.noti.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val ActionShape = RoundedCornerShape(12.dp)

/** Shared action hierarchy, including real disabled colors and stable pressed shapes. */
@Composable
fun NotiButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ActionShape, destructive: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = shape,
        elevation = null, colors = ButtonDefaults.buttonColors(
            containerColor = if (destructive) c.error else c.primary,
            contentColor = if (destructive) c.onError else c.onPrimary,
            disabledContainerColor = c.surfaceContainerHighest,
            disabledContentColor = c.onSurface.copy(alpha = 0.38f)),
        contentPadding = contentPadding, content = content)
}

@Composable
fun NotiTextButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    destructive: Boolean = false, contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = ActionShape,
        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) c.error else c.onSurface,
            disabledContentColor = c.onSurface.copy(alpha = 0.38f)),
        contentPadding = contentPadding, content = content)
}

@Composable
fun NotiOutlinedButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = ActionShape,
        border = BorderStroke(1.dp, if (enabled) c.outlineVariant else c.outlineVariant.copy(alpha = 0.4f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.onSurface,
            disabledContentColor = c.onSurface.copy(alpha = 0.38f)),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), content = content)
}

@Composable
fun NotiTonalButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val c = MaterialTheme.colorScheme
    FilledTonalButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = ActionShape,
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.primaryContainer, contentColor = c.onPrimaryContainer),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp), content = content)
}

@Composable
fun NotiIconButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(ActionShape), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)), content = content)
}

@Composable
fun NotiFilterChip(
    selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, leadingIcon: (@Composable () -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    FilterChip(selected, onClick, label, modifier.heightIn(min = 48.dp), enabled = enabled,
        leadingIcon = leadingIcon, shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(containerColor = c.surface,
            labelColor = c.onSurface, selectedContainerColor = c.primaryContainer,
            selectedLabelColor = c.onPrimaryContainer, selectedLeadingIconColor = c.onPrimaryContainer),
        border = BorderStroke(1.dp, if (selected) c.primary.copy(alpha = 0.5f) else c.outlineVariant))
}

/** One explicit-width window frame: title and actions stay put while content scrolls. */
@Composable
fun NotiDialogFrame(
    onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    modifier: Modifier = Modifier, icon: (@Composable () -> Unit)? = null,
    maxWidth: Dp = 480.dp, actions: @Composable FlowRowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val width = (configuration.screenWidthDp.dp - 32.dp).coerceIn(160.dp, maxWidth)
    val height = (configuration.screenHeightDp.dp - 48.dp).coerceAtLeast(240.dp)
    val c = MaterialTheme.colorScheme
    Dialog(onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier.width(width), shape = RoundedCornerShape(20.dp), color = c.surface,
            border = BorderStroke(0.75.dp, c.outlineVariant), shadowElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = height)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).semantics { heading() }) {
                        ProvideTextStyle(MaterialTheme.typography.titleLarge, title)
                    }
                    if (icon != null) { Spacer(Modifier.width(16.dp)); icon() }
                }
                HorizontalDivider(thickness = 0.5.dp, color = c.outlineVariant)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.bodyMedium) { content() }
                }
                HorizontalDivider(thickness = 0.5.dp, color = c.outlineVariant)
                FlowRow(Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
            }
        }
    }
}

/** Slot-compatible confirmation/form dialog using the same frame as notebook creation. */
@Composable
fun NotiAlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier, dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    NotiDialogFrame(onDismissRequest, title = { title?.invoke() }, modifier = modifier, icon = icon,
        actions = { dismissButton?.invoke(); confirmButton() }) { text?.invoke() }
}

@Composable
fun NotiDropdownMenu(
    expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface, shadowElevation: Dp = 3.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(expanded, onDismissRequest, modifier, shape = RoundedCornerShape(14.dp),
        containerColor = containerColor, tonalElevation = 0.dp, shadowElevation = shadowElevation,
        border = if (containerColor == Color.Transparent) null else BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content)
}

@Composable
fun NotiMenuItem(
    text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true, destructive: Boolean = false,
) {
    val c = MaterialTheme.colorScheme
    DropdownMenuItem(text, onClick, modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp)).heightIn(min = 48.dp),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled,
        colors = MenuDefaults.itemColors(textColor = if (destructive) c.error else c.onSurface,
            leadingIconColor = if (destructive) c.error else c.onSurfaceVariant),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp))
}

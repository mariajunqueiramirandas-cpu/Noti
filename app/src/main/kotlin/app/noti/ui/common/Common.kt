package app.noti.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.noti.data.Paper
import app.noti.data.PaperColor
import app.noti.data.PaperSize
import app.noti.data.PaperTemplate

/** Match the opacity of disabled Material controls. */
fun Modifier.greyedOut(greyed: Boolean = true): Modifier = if (greyed) alpha(0.38f) else this

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(value.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value.text.trim()) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Template, color and size choices, shared by "New notebook" and "Page paper". */
@Composable
fun PaperPicker(paper: Paper, onChange: (Paper) -> Unit, showSize: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LabeledRow("Paper") {
            PaperTemplate.entries.forEach { t ->
                val selected = paper.template == t
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PaperPreview(
                        paper.copy(template = t),
                        modifier = Modifier
                            .width(64.dp)
                            .aspectRatio(0.75f)
                            .clip(MaterialTheme.shapes.small)
                            .border(
                                if (selected) 2.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                MaterialTheme.shapes.small,
                            )
                            .clickable { onChange(paper.copy(template = t)) },
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(t.label, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        LabeledRow("Color") {
            PaperColor.entries.forEach { c ->
                FilterChip(
                    selected = paper.color == c,
                    onClick = { onChange(paper.copy(color = c)) },
                    label = { Text(c.label) },
                    leadingIcon = {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape).background(Color(c.argb))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                    },
                )
                Spacer(Modifier.width(8.dp))
            }
        }
        if (showSize) {
            LabeledRow("Size") {
                PaperSize.entries.forEach { s ->
                    FilterChip(
                        selected = paper.size == s,
                        onClick = { onChange(paper.copy(size = s)) },
                        label = { Text(s.label) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp).padding(end = 8.dp),
        )
        Row(verticalAlignment = Alignment.Top) { content() }
    }
}

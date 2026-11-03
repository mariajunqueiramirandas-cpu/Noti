package app.noti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import app.noti.data.ThemeMode

// Calm, close to paper: warm off-white chrome, one ink-blue accent.
private val Light = lightColorScheme(
    primary = Color(0xFF2D5BD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FA),
    onPrimaryContainer = Color(0xFF0F2A73),
    secondary = Color(0xFF5B6070),
    secondaryContainer = Color(0xFFE6E4DF),
    onSecondaryContainer = Color(0xFF26282E),
    background = Color(0xFFF2F0EB),
    onBackground = Color(0xFF1E1F24),
    surface = Color(0xFFFAF9F6),
    onSurface = Color(0xFF1E1F24),
    surfaceVariant = Color(0xFFE9E7E2),
    onSurfaceVariant = Color(0xFF5E616B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F6F2),
    surfaceContainer = Color(0xFFF2F0EB),
    surfaceContainerHigh = Color(0xFFECEAE5),
    surfaceContainerHighest = Color(0xFFE6E4DF),
    outline = Color(0xFFB9B7B1),
    outlineVariant = Color(0xFFDAD8D2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    onPrimary = Color(0xFF0F2A73),
    primaryContainer = Color(0xFF29427F),
    onPrimaryContainer = Color(0xFFDDE5FA),
    secondary = Color(0xFFC3C6D2),
    secondaryContainer = Color(0xFF34363C),
    onSecondaryContainer = Color(0xFFE3E4EA),
    background = Color(0xFF151619),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF1C1D21),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF2A2C31),
    onSurfaceVariant = Color(0xFFA9ACB6),
    surfaceContainerLowest = Color(0xFF111214),
    surfaceContainerLow = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF1E1F23),
    surfaceContainerHigh = Color(0xFF26272C),
    surfaceContainerHighest = Color(0xFF2E3035),
    outline = Color(0xFF5C5F68),
    outlineVariant = Color(0xFF3A3C42),
)

private val NotiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

@Composable
fun NotiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = NotiShapes, content = content)
}

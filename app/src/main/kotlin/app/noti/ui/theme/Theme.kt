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

// Neutral paper chrome keeps the editor focused on ink. Color accents belong to the drawing.
private val Light = lightColorScheme(
    primary = Color(0xFF2D5BD3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FA),
    onPrimaryContainer = Color(0xFF0F2A73),
    secondary = Color(0xFF5B6070),
    secondaryContainer = Color(0xFFE2E7F0),
    onSecondaryContainer = Color(0xFF26282E),
    background = Color(0xFFF0F0EE),
    onBackground = Color(0xFF292A28),
    surface = Color(0xFFF5F5F3),
    onSurface = Color(0xFF292A28),
    surfaceVariant = Color(0xFFE8E8E5),
    onSurfaceVariant = Color(0xFF70716D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F0EE),
    surfaceContainer = Color(0xFFF0F0EE),
    surfaceContainerHigh = Color(0xFFE8ECF4),
    surfaceContainerHighest = Color(0xFFE2E7F0),
    outline = Color(0xFFB1B2AC),
    outlineVariant = Color(0xFFDADBD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB6FF),
    onPrimary = Color(0xFF0F2A73),
    primaryContainer = Color(0xFF29427F),
    onPrimaryContainer = Color(0xFFDDE5FA),
    secondary = Color(0xFFC3C6D2),
    secondaryContainer = Color(0xFF34363C),
    onSecondaryContainer = Color(0xFFE3E4EA),
    background = Color(0xFF181918),
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF242523),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF2A2C31),
    onSurfaceVariant = Color(0xFFA9ACB6),
    surfaceContainerLowest = Color(0xFF111214),
    surfaceContainerLow = Color(0xFF1D1E1C),
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

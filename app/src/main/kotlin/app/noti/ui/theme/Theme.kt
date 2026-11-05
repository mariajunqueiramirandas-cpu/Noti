package app.noti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noti.R
import app.noti.data.ThemeMode

/** Shared stationery colors. Accents decorate the library; the canvas stays neutral. */
object NotiBrand {
    val Ink = Color(0xFF30332E)
    val Paper = Color(0xFFFAF9F6)
    val Lilac = Color(0xFFDED2F4)
    val Butter = Color(0xFFF5D77E)
    val Peach = Color(0xFFF6B6A6)
    val Mint = Color(0xFFDDEBDD)
    const val Tagline = "Little ideas welcome."
}

val NotiFont = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

private val Light = lightColorScheme(
    primary = Color(0xFF6D568F), onPrimary = Color.White,
    primaryContainer = Color(0xFFECE4F6), onPrimaryContainer = Color(0xFF47315F),
    secondary = Color(0xFF626D59), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EDDF), onSecondaryContainer = Color(0xFF35432E),
    tertiary = Color(0xFF855A42), tertiaryContainer = Color(0xFFFFE8D9), onTertiaryContainer = Color(0xFF593D2B),
    background = NotiBrand.Paper, onBackground = NotiBrand.Ink,
    surface = Color(0xFFFDFCFA), onSurface = NotiBrand.Ink,
    surfaceVariant = Color(0xFFF1EFEA), onSurfaceVariant = Color(0xFF6A6E64),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F4F0),
    surfaceContainer = Color(0xFFF1EFEA), surfaceContainerHigh = Color(0xFFEDEAE4),
    surfaceContainerHighest = Color(0xFFE7E4DE),
    outline = Color(0xFFA4A99D), outlineVariant = Color(0xFFE1E3DA),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD4BEEB), onPrimary = Color(0xFF342344),
    primaryContainer = Color(0xFF44334F), onPrimaryContainer = Color(0xFFEEE0FA),
    secondary = Color(0xFFC2D2B8), secondaryContainer = Color(0xFF354230), onSecondaryContainer = Color(0xFFE1EEDB),
    tertiary = Color(0xFFF0C7A9), tertiaryContainer = Color(0xFF50392C), onTertiaryContainer = Color(0xFFFFE7D7),
    background = Color(0xFF1C1D1B), onBackground = Color(0xFFECEDE5),
    surface = Color(0xFF252623), onSurface = Color(0xFFECEDE5),
    surfaceVariant = Color(0xFF33352F), onSurfaceVariant = Color(0xFFB2B7AA),
    surfaceContainerLowest = Color(0xFF181916), surfaceContainerLow = Color(0xFF222320),
    surfaceContainer = Color(0xFF282A25), surfaceContainerHigh = Color(0xFF30322D),
    surfaceContainerHighest = Color(0xFF373A33),
    outline = Color(0xFF777D6F), outlineVariant = Color(0xFF3E4338),
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = NotiFont, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight)

private val NotiTypography = Typography(
    displayLarge = type(56, 64, FontWeight.ExtraBold), displayMedium = type(44, 52, FontWeight.ExtraBold),
    displaySmall = type(36, 44, FontWeight.ExtraBold), headlineLarge = type(32, 40, FontWeight.ExtraBold),
    headlineMedium = type(28, 36, FontWeight.Bold), headlineSmall = type(24, 32, FontWeight.Bold),
    titleLarge = type(22, 28, FontWeight.Bold), titleMedium = type(16, 24, FontWeight.Bold),
    titleSmall = type(14, 20, FontWeight.Bold), bodyLarge = type(16, 24), bodyMedium = type(14, 21),
    bodySmall = type(12, 18), labelLarge = type(14, 20, FontWeight.Bold),
    labelMedium = type(12, 16, FontWeight.SemiBold), labelSmall = type(11, 16, FontWeight.SemiBold),
)

private val NotiShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun NotiTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val colors = if (dark) Dark else Light
    MaterialTheme(colorScheme = colors, typography = NotiTypography, shapes = NotiShapes) {
        CompositionLocalProvider(LocalContentColor provides colors.onSurface, content = content)
    }
}

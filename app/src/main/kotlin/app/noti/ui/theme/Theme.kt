package app.noti.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.noti.R
import app.noti.data.ThemeMode

/**
 * A page on a desk at dusk: a soft greige desk, a lighter sheet for content, aubergine
 * charcoal ink, one plum accent shared with Doti, and honey only for favorite bookmarks.
 * Pastel covers mark notebooks; the canvas itself stays neutral.
 */
object NotiBrand {
    val Ink = Color(0xFF2A2830)
    val Paper = Color(0xFFF3F1EE)
    val Plum = Color(0xFF5C4A87)
    val Honey = Color(0xFFE3AE3F)
    val Lilac = Color(0xFFD9CFEE)
    val Butter = Color(0xFFF1DFA6)
    val Peach = Color(0xFFF2C6B8)
    val Mint = Color(0xFFCFE1D3)
    val Sky = Color(0xFFCBDCEB)
    const val Tagline = "Little ideas welcome."
}

/** Colors the Material scheme has no role for. */
@Immutable
data class NotiExtras(
    /** The content sheet that sits on the desk (background). */
    val sheet: Color,
    /** Notebook spines, muted enough to sit quietly beside each other. */
    val covers: List<Color>,
    val bookmark: Color,
    /** A soft drop shadow tint for floating chrome. */
    val shadow: Color,
)

val LocalNotiExtras = staticCompositionLocalOf {
    NotiExtras(Color.White, listOf(NotiBrand.Lilac), NotiBrand.Honey, Color.Black)
}

val NotiFont = FontFamily(
    Font(R.font.nunito_regular, FontWeight.Normal),
    Font(R.font.nunito_medium, FontWeight.Medium),
    Font(R.font.nunito_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_bold, FontWeight.Bold),
    Font(R.font.nunito_extrabold, FontWeight.ExtraBold),
)

private val Light = lightColorScheme(
    primary = NotiBrand.Plum, onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3F4), onPrimaryContainer = Color(0xFF392B5A),
    secondary = Color(0xFF6B6577), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE9EF), onSecondaryContainer = Color(0xFF34303C),
    tertiary = Color(0xFF8A6418), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF7E8C5), onTertiaryContainer = Color(0xFF4F3908),
    background = NotiBrand.Paper, onBackground = NotiBrand.Ink,
    surface = Color(0xFFFBFAF8), onSurface = NotiBrand.Ink,
    surfaceVariant = Color(0xFFEDEAE8), onSurfaceVariant = Color(0xFF6C6873),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF6F4F2),
    surfaceContainer = Color(0xFFF0EEEB), surfaceContainerHigh = Color(0xFFEBE8E5),
    surfaceContainerHighest = Color(0xFFE5E2DF),
    outline = Color(0xFFA8A3AD), outlineVariant = Color(0xFFE3DFE2),
    error = Color(0xFFB3261E), onError = Color.White,
    errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFCDBFF2), onPrimary = Color(0xFF2E2350),
    primaryContainer = Color(0xFF3D3459), onPrimaryContainer = Color(0xFFE9E1FF),
    secondary = Color(0xFFCAC4D3), secondaryContainer = Color(0xFF35323C), onSecondaryContainer = Color(0xFFE8E4EE),
    tertiary = Color(0xFFF0C46A), tertiaryContainer = Color(0xFF4A3912), onTertiaryContainer = Color(0xFFFBE4B5),
    background = Color(0xFF19181D), onBackground = Color(0xFFECE9F0),
    surface = Color(0xFF211F25), onSurface = Color(0xFFECE9F0),
    surfaceVariant = Color(0xFF2E2C33), onSurfaceVariant = Color(0xFFADA8B5),
    surfaceContainerLowest = Color(0xFF141317), surfaceContainerLow = Color(0xFF1E1D22),
    surfaceContainer = Color(0xFF26242A), surfaceContainerHigh = Color(0xFF2D2B32),
    surfaceContainerHighest = Color(0xFF35333A),
    outline = Color(0xFF7D7885), outlineVariant = Color(0xFF38353D),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
)

private val LightExtras = NotiExtras(
    sheet = Color(0xFFFBFAF8),
    covers = listOf(NotiBrand.Lilac, NotiBrand.Butter, NotiBrand.Mint, NotiBrand.Peach, NotiBrand.Sky),
    bookmark = NotiBrand.Honey,
    shadow = Color(0xFF2A2235),
)

private val DarkExtras = NotiExtras(
    sheet = Color(0xFF211F25),
    covers = listOf(Color(0xFF8E80B3), Color(0xFFB8A56A), Color(0xFF7FA088), Color(0xFFB98D7F), Color(0xFF7F99B2)),
    bookmark = Color(0xFFF0C46A),
    shadow = Color.Black,
)

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
    TextStyle(fontFamily = NotiFont, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight, letterSpacing = tracking.sp)

/** One rounded family with calm weights: Bold only where a heading carries the page. */
private val NotiTypography = Typography(
    displayLarge = type(56, 64, FontWeight.Bold, -1.2f), displayMedium = type(44, 52, FontWeight.Bold, -1f),
    displaySmall = type(36, 44, FontWeight.Bold, -0.8f), headlineLarge = type(32, 40, FontWeight.Bold, -0.6f),
    headlineMedium = type(28, 36, FontWeight.Bold, -0.5f), headlineSmall = type(24, 32, FontWeight.Bold, -0.3f),
    titleLarge = type(20, 28, FontWeight.Bold, -0.2f), titleMedium = type(16, 24, FontWeight.SemiBold),
    titleSmall = type(14, 20, FontWeight.SemiBold), bodyLarge = type(16, 24), bodyMedium = type(14, 21),
    bodySmall = type(12, 17), labelLarge = type(14, 20, FontWeight.SemiBold),
    labelMedium = type(12, 16, FontWeight.SemiBold), labelSmall = type(11, 14, FontWeight.SemiBold),
)

/** Radius follows hierarchy: controls 12, cards 16, panels 22, sheets and dialogs 28. */
private val NotiShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp), extraLarge = RoundedCornerShape(28.dp),
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
        CompositionLocalProvider(
            LocalContentColor provides colors.onSurface,
            LocalNotiExtras provides if (dark) DarkExtras else LightExtras,
            content = content,
        )
    }
}

object Noti {
    val extras: NotiExtras @Composable get() = LocalNotiExtras.current
}

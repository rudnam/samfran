package com.rudnam.samfran.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rudnam.samfran.core.designsystem.R

/** A pair of colours: a soft ground and the ink on it. */
@Immutable
data class Pair2(val bg: Color, val ink: Color)

/** One person's colours (styles.css --a-* / --b-*): soft ground, text, solid, and text on solid. */
@Immutable
data class PersonColors(val bg: Color, val text: Color, val solid: Color, val on: Color)

/**
 * "Quiet Pop": calm, warm off-white, with the two people's colours as
 * accents. The tokens at the top of web/src/styles.css, light and dark, so
 * the app and the website look the same. Material 3's roles are filled from
 * these, so its components look like the app.
 */
@Immutable
data class SamFranColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val sunken: Color,
    val text: Color,
    val muted: Color,
    val border: Color,
    val divider: Color,
    /** Tomato: the main action. */
    val accent: Color,
    val accentText: Color,
    /** Tomato as text. */
    val accentInk: Color,
    val star: Color,
    val starOff: Color,
    val danger: Color,
    /** The first to join (Fran) is red, the second (Sam) orange. */
    val a: PersonColors,
    val b: PersonColors,
    val tileYellow: Pair2,
    val tileGreen: Pair2,
    val tileRed: Pair2,
    val tileOrange: Pair2,
    /** A calendar day's square, by kind of shift (.cal-day.*). */
    val calEarly: Pair2,
    val calMiddle: Pair2,
    val calLate: Pair2,
    val calNight: Pair2,
    /** 有休 (paid leave): like a day off, with a faint mint wash. */
    val calLeave: Pair2,
    val calTraining: Pair2,
)

/** The shift dots, the same in light and dark (.shift-dot.*). */
object ShiftDotColors {
    val early = Color(0xFFF5B301)
    val middle = Color(0xFF2E9E5B)
    val late = Color(0xFFFF8A1F)
    val night = Color(0xFF5B4BC4)
    val off = Color(0xFFB9E6C8)
    val leave = Color(0xFF9FD8D0)
    val training = Color(0xFF9AA4B2)
}

val LightSamFranColors = SamFranColors(
    isDark = false,
    bg = Color(0xFFFAF8F4),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFF0ECE5),
    text = Color(0xFF1E1915),
    muted = Color(0xFF77706A),
    border = Color(0xFFEFEBE4),
    divider = Color(0xFFF1EDE7),
    accent = Color(0xFFE23D28),
    accentText = Color(0xFFFFFFFF),
    accentInk = Color(0xFFC42E1B),
    star = Color(0xFFF59E0B),
    starOff = Color(0xFFE6DFD4),
    danger = Color(0xFFB3261E),
    a = PersonColors(Color(0xFFFFE4DE), Color(0xFFB42A18), Color(0xFFE23D28), Color(0xFFFFFFFF)),
    b = PersonColors(Color(0xFFFFE9D4), Color(0xFF9A4600), Color(0xFFFF8A1F), Color(0xFF1E1915)),
    tileYellow = Pair2(Color(0xFFFFF1C9), Color(0xFF8A5A00)),
    tileGreen = Pair2(Color(0xFFDDF4E5), Color(0xFF1C6B3C)),
    tileRed = Pair2(Color(0xFFFFE4DE), Color(0xFFB42A18)),
    tileOrange = Pair2(Color(0xFFFFE9D4), Color(0xFF9A4600)),
    calEarly = Pair2(Color(0xFFFFE9A8), Color(0xFF5C4300)),
    calMiddle = Pair2(Color(0xFFC9EFD6), Color(0xFF0F4D2A)),
    calLate = Pair2(Color(0xFFFFD6AD), Color(0xFF6B3100)),
    calNight = Pair2(Color(0xFFDCD6FF), Color(0xFF2F2480)),
    calLeave = Pair2(Color(0xFFEEF7F2), Color(0xFF4F7A63)),
    calTraining = Pair2(Color(0xFFDFE4EA), Color(0xFF37404C)),
)

val DarkSamFranColors = SamFranColors(
    isDark = true,
    bg = Color(0xFF161311),
    surface = Color(0xFF211C19),
    sunken = Color(0xFF2B2420),
    text = Color(0xFFF5EFEA),
    muted = Color(0xFFA99D94),
    border = Color(0xFF322A25),
    divider = Color(0xFF2C2521),
    accent = Color(0xFFFF5A3C),
    accentText = Color(0xFF160E0B),
    accentInk = Color(0xFFFF8A73),
    star = Color(0xFFF59E0B),
    starOff = Color(0xFF463A32),
    danger = Color(0xFFFFB4A9),
    a = PersonColors(Color(0xFF4A1D16), Color(0xFFFFB4A6), Color(0xFFFF5A3C), Color(0xFF160E0B)),
    b = PersonColors(Color(0xFF45290F), Color(0xFFFFC58F), Color(0xFFFF9A3D), Color(0xFF160E0B)),
    tileYellow = Pair2(Color(0xFF3D3214), Color(0xFFFFD877)),
    tileGreen = Pair2(Color(0xFF183424), Color(0xFF8FDCAB)),
    tileRed = Pair2(Color(0xFF44201A), Color(0xFFFFAB9B)),
    tileOrange = Pair2(Color(0xFF45290F), Color(0xFFFFC58F)),
    calEarly = Pair2(Color(0xFF57440A), Color(0xFFFFE08A)),
    calMiddle = Pair2(Color(0xFF1C4A30), Color(0xFFA6E8BF)),
    calLate = Pair2(Color(0xFF5C3410), Color(0xFFFFC993)),
    calNight = Pair2(Color(0xFF37307A), Color(0xFFD3CCFF)),
    calLeave = Pair2(Color(0xFF1F2C26), Color(0xFFA6CDB7)),
    calTraining = Pair2(Color(0xFF3A424D), Color(0xFFD5DBE3)),
)

private val LocalSamFranColors = staticCompositionLocalOf { LightSamFranColors }

private fun SamFranColors.asColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = accentText,
        primaryContainer = a.bg,
        onPrimaryContainer = a.text,
        secondary = muted,
        onSecondary = surface,
        secondaryContainer = sunken,
        onSecondaryContainer = text,
        tertiary = b.solid,
        onTertiary = b.on,
        tertiaryContainer = b.bg,
        onTertiaryContainer = b.text,
        background = bg,
        onBackground = text,
        surface = bg,
        onSurface = text,
        surfaceVariant = sunken,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = bg,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = sunken,
        inverseSurface = text,
        inverseOnSurface = bg,
        inversePrimary = accentInk,
        outline = muted,
        outlineVariant = border,
        error = danger,
        onError = surface,
        scrim = Color.Black.copy(alpha = 0.45f),
    )
}

/** Plus Jakarta Sans, as on the website (converted by android/fonts.py). Japanese falls back to the phone's font. */
val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_semibold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extrabold, FontWeight.ExtraBold),
)

private val base = TextStyle(fontFamily = Jakarta)

/**
 * The website's type: 30sp extra-bold display headings with tight tracking
 * (.display), bold section headings (h2), 16sp text.
 */
val SamFranTypography = Typography(
    displaySmall = base.copy(fontSize = 30.sp, lineHeight = 35.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em),
    headlineMedium = base.copy(fontSize = 26.sp, lineHeight = 31.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em),
    headlineSmall = base.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
    titleLarge = base.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em),
    titleMedium = base.copy(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold),
    titleSmall = base.copy(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.copy(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = base.copy(fontSize = 13.5.sp, lineHeight = 19.sp),
    labelLarge = base.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = base.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = base.copy(fontSize = 11.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.em),
)

/** The website's radii: 12 for fields and buttons (--radius), 20 for cards (--radius-lg). */
private val SamFranShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The app's theme. [darkTheme] follows Us › Appearance; by default, the phone. No dynamic colour: SamFran has its own. */
@Composable
fun SamFranTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkSamFranColors else LightSamFranColors
    CompositionLocalProvider(LocalSamFranColors provides colors) {
        MaterialTheme(colorScheme = colors.asColorScheme(), typography = SamFranTypography, shapes = SamFranShapes, content = content)
    }
}

/** The app's own colours, for what Material's roles don't name (the two people, stars, tiles, shifts). */
object SamFranTheme {
    val colors: SamFranColors
        @Composable @ReadOnlyComposable
        get() = LocalSamFranColors.current
}

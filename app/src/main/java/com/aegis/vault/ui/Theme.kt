@file:OptIn(ExperimentalTextApi::class)

package com.aegis.vault.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.vault.R
import com.aegis.vault.aegis

/**
 * Typefaces. Google Sans Flex and Google Sans Code are bundled as variable fonts under the
 * SIL Open Font License 1.1 (see /licenses), so no network request is ever needed for fonts.
 */
object AegisFonts {
    private fun flex(weight: Int, round: Float) = Font(
        R.font.google_sans_flex, FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight), FontVariation.Setting("ROND", round),
        ),
    )

    private fun code(weight: Int) = Font(
        R.font.google_sans_code, FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    /** Body and UI text. */
    val Sans = FontFamily(flex(400, 0f), flex(500, 0f), flex(600, 0f), flex(700, 0f))

    /** Expressive display face: the same family with its roundness axis turned up. */
    val Display = FontFamily(flex(500, 100f), flex(600, 100f), flex(700, 100f), flex(800, 100f))

    /** Passwords, codes and card numbers. */
    val Mono = FontFamily(code(400), code(500), code(600))
}

/** Status colours that stay legible on both the dark and the light scheme. */
object AegisColors {
    internal var dark by mutableStateOf(true)
    val Success: Color get() = if (dark) Color(0xFF7FDBA6) else Color(0xFF1B7A4B)
    val Warning: Color get() = if (dark) Color(0xFFF2C16B) else Color(0xFF8A5A00)
    val Danger: Color get() = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
    val Info: Color get() = if (dark) Color(0xFFB9C3FF) else Color(0xFF4658B8)
    val Neutral: Color get() = if (dark) Color(0xFFC6C5D0) else Color(0xFF5A5D72)
}

// Tonal palette built from an indigo seed, with a mint tertiary for contrast.
private val Dark = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF14267B),
    primaryContainer = Color(0xFF2E3F92),
    onPrimaryContainer = Color(0xFFDDE1FF),
    inversePrimary = Color(0xFF4658B8),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF434659),
    onSecondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFF84D6C3),
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF005144),
    onTertiaryContainer = Color(0xFFA0F3DE),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceContainerLowest = Color(0xFF0D0E13),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34343A),
    inverseSurface = Color(0xFFE3E1E9),
    inverseOnSurface = Color(0xFF2F3036),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

private val Light = lightColorScheme(
    primary = Color(0xFF4658B8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF2B3D90),
    inversePrimary = Color(0xFFB9C3FF),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE1F9),
    onSecondaryContainer = Color(0xFF424659),
    tertiary = Color(0xFF006B5A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFA0F3DE),
    onTertiaryContainer = Color(0xFF005144),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE3E1E9),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    scrim = Color(0xFF000000),
)

// Expressive type: rounded, heavier display and title styles over a neutral body face.
private val AegisType = Typography().let { t ->
    fun TextStyle.body(weight: FontWeight = FontWeight.Normal) = copy(fontFamily = AegisFonts.Sans, fontWeight = weight)
    fun TextStyle.display(weight: FontWeight) = copy(fontFamily = AegisFonts.Display, fontWeight = weight)
    Typography(
        displayLarge = t.displayLarge.display(FontWeight.Bold).copy(letterSpacing = (-1).sp),
        displayMedium = t.displayMedium.display(FontWeight.Bold).copy(letterSpacing = (-0.6).sp),
        displaySmall = t.displaySmall.display(FontWeight.Bold).copy(letterSpacing = (-0.4).sp),
        headlineLarge = t.headlineLarge.display(FontWeight.Bold).copy(letterSpacing = (-0.4).sp),
        headlineMedium = t.headlineMedium.display(FontWeight.Bold).copy(letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.display(FontWeight.SemiBold).copy(letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.display(FontWeight.SemiBold),
        titleMedium = t.titleMedium.body(FontWeight.SemiBold).copy(letterSpacing = 0.sp),
        titleSmall = t.titleSmall.body(FontWeight.SemiBold).copy(letterSpacing = 0.sp),
        bodyLarge = t.bodyLarge.body().copy(lineHeight = 24.sp, letterSpacing = 0.1.sp),
        bodyMedium = t.bodyMedium.body().copy(lineHeight = 21.sp, letterSpacing = 0.1.sp),
        bodySmall = t.bodySmall.body().copy(lineHeight = 17.sp, letterSpacing = 0.1.sp),
        labelLarge = t.labelLarge.body(FontWeight.SemiBold).copy(letterSpacing = 0.1.sp),
        labelMedium = t.labelMedium.body(FontWeight.Medium).copy(letterSpacing = 0.2.sp),
        labelSmall = t.labelSmall.body(FontWeight.Medium).copy(letterSpacing = 0.2.sp),
    )
}

// Expressive shape scale: generous corners; controls are fully rounded.
private val AegisShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun isDarkTheme(mode: String): Boolean = when (mode) {
    "light" -> false
    "system" -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun AegisTheme(mode: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isDarkTheme(mode)
    val dynamic by context.aegis.prefs.dynamicColor.collectAsState()
    AegisColors.dark = dark
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = AegisType, shapes = AegisShapes, content = content)
}

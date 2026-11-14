@file:Suppress("RestrictedApi") // Material color utilities.

package fr.spotifeur.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.android.material.color.utilities.Hct

/** [hue] at [chroma] and [tone]; the chroma is clamped to what sRGB can show at that tone. */
private fun hct(hue: Double, chroma: Double, tone: Double): Color =
    Color(Hct.from(hue, chroma.coerceAtLeast(0.0), tone).toInt())

private fun Color.hct(): Hct = Hct.fromInt(toArgb())

/**
 * Dark scheme for a cover: deep backgrounds in the cover's own hue and light controls.
 *
 * Material's tonal schemes put containers at tone 30 with capped chroma, which turns most
 * covers into the same dull mid-tone, and the fixed roles at tone 80 in three different hues,
 * which gives pastel controls. Here every background takes the cover's hue at a low tone and
 * every control is a near-white tint of it, so the player reads as the cover, darkened, with
 * controls that stand out.
 *
 * Roles used by the player: `primaryContainer` (background), `onPrimaryContainer` (text and
 * seek bar), `secondaryFixedDim` (previous/next), `tertiaryFixedDim` (play/pause),
 * `primaryFixed`, `secondaryFixed`, `tertiaryFixed` (active shuffle, repeat, like).
 */
internal fun ColorScheme.deepenedForArtwork(source: Hct): ColorScheme {
    val hue = source.hue
    val chroma = source.chroma
    // The gamut limits chroma at low tones anyway; the cap keeps bright covers from glowing.
    val deep = chroma.coerceAtMost(56.0)
    val neutral = (chroma * 0.4).coerceAtMost(14.0)
    val tint = chroma.coerceAtMost(12.0)
    // Palette styles that rotate the accent hue (Expressive, Fruit Salad) keep doing so.
    val accentHue = primary.hct().hue
    val accent = chroma.coerceIn(16.0, 28.0).coerceAtMost(chroma * 1.5)
    val onLight = hct(hue, deep, 12.0)

    return copy(
        primary = hct(accentHue, accent, 84.0),
        onPrimary = hct(accentHue, deep, 14.0),
        primaryContainer = hct(hue, deep, 16.0),
        onPrimaryContainer = hct(hue, tint, 95.0),
        inversePrimary = hct(accentHue, accent, 36.0),
        secondary = hct(hue, (chroma * 0.6).coerceAtMost(28.0), 82.0),
        onSecondary = onLight,
        secondaryContainer = hct(hue, deep * 0.8, 30.0),
        onSecondaryContainer = hct(hue, tint, 92.0),
        tertiary = hct(hue, tint, 92.0),
        onTertiary = onLight,
        tertiaryContainer = hct(hue, deep * 0.6, 28.0),
        onTertiaryContainer = hct(hue, tint, 94.0),
        background = hct(hue, neutral, 6.0),
        onBackground = hct(hue, 6.0, 94.0),
        surface = hct(hue, neutral, 6.0),
        onSurface = hct(hue, 6.0, 94.0),
        surfaceVariant = hct(hue, neutral * 1.2, 22.0),
        onSurfaceVariant = hct(hue, chroma.coerceAtMost(16.0), 80.0),
        surfaceTint = hct(accentHue, accent, 84.0),
        inverseSurface = hct(hue, 6.0, 92.0),
        inverseOnSurface = hct(hue, neutral, 14.0),
        outline = hct(hue, neutral, 56.0),
        outlineVariant = hct(hue, neutral, 28.0),
        surfaceDim = hct(hue, neutral, 6.0),
        surfaceBright = hct(hue, neutral, 26.0),
        surfaceContainerLowest = hct(hue, neutral, 4.0),
        surfaceContainerLow = hct(hue, neutral, 11.0),
        surfaceContainer = hct(hue, neutral, 14.0),
        surfaceContainerHigh = hct(hue, neutral, 18.0),
        surfaceContainerHighest = hct(hue, neutral, 23.0),
        primaryFixed = hct(accentHue, accent.coerceAtMost(24.0), 88.0),
        primaryFixedDim = hct(accentHue, accent.coerceAtMost(24.0), 82.0),
        onPrimaryFixed = hct(accentHue, deep, 12.0),
        onPrimaryFixedVariant = hct(accentHue, deep, 24.0),
        // Light and barely tinted: a pastel at this tone is what reads as candy.
        secondaryFixed = hct(hue, (chroma * 0.35).coerceAtMost(14.0), 91.0),
        secondaryFixedDim = hct(hue, (chroma * 0.35).coerceAtMost(14.0), 89.0),
        onSecondaryFixed = onLight,
        onSecondaryFixedVariant = hct(hue, deep, 24.0),
        tertiaryFixed = hct(hue, tint, 96.0),
        tertiaryFixedDim = hct(hue, tint * 0.6, 97.0),
        onTertiaryFixed = onLight,
        onTertiaryFixedVariant = hct(hue, deep, 24.0),
    )
}

/**
 * The system's dark wallpaper scheme with stronger accents. Wallpaper palettes keep chroma
 * low, so selected chips, buttons and containers read as beige; this raises the chroma of
 * the accent roles in proportion (a grey wallpaper stays grey). Tones are kept: recent
 * system schemes pair some light containers with dark text, and that contrast must hold.
 */
internal fun ColorScheme.vividAccents(): ColorScheme {
    fun Color.vivid(cap: Double): Color {
        val h = hct()
        return hct(h.hue, (h.chroma * 1.7).coerceAtMost(maxOf(h.chroma, cap)), h.tone)
    }
    return copy(
        primary = primary.vivid(56.0),
        secondary = secondary.vivid(40.0),
        tertiary = tertiary.vivid(48.0),
        primaryContainer = primaryContainer.vivid(48.0),
        secondaryContainer = secondaryContainer.vivid(36.0),
        tertiaryContainer = tertiaryContainer.vivid(40.0),
        inversePrimary = inversePrimary.vivid(56.0),
        surfaceTint = surfaceTint.vivid(56.0),
        primaryFixed = primaryFixed.vivid(48.0),
        primaryFixedDim = primaryFixedDim.vivid(56.0),
        secondaryFixed = secondaryFixed.vivid(36.0),
        secondaryFixedDim = secondaryFixedDim.vivid(40.0),
        tertiaryFixed = tertiaryFixed.vivid(40.0),
        tertiaryFixedDim = tertiaryFixedDim.vivid(48.0),
    )
}

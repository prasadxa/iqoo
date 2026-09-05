package com.aasra.companion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AasraColorScheme = lightColorScheme(
    primary = PineInk,
    onPrimary = TextOnPine,
    secondary = MarigoldDeep,
    onSecondary = TextOnPine,
    tertiary = InkSoft,
    background = Paper,
    onBackground = InkOnPaper,
    surface = Paper,
    onSurface = InkOnPaper,
    surfaceVariant = PaperRaised,
    onSurfaceVariant = InkOnPaper,
    error = SosRed,
    onError = TextOnPine,
    outline = LineOnPaper,
    primaryContainer = PaperRaised,
    onPrimaryContainer = PineInk,
    secondaryContainer = PaperRaised,
    onSecondaryContainer = PineInk,
    tertiaryContainer = PaperRaised,
    onTertiaryContainer = PineInk,
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = PaperRaised,
    surfaceContainerHighest = PaperRaised,
    surfaceContainerLow = Paper,
    surfaceContainerLowest = TextOnPine,
    surfaceTint = PineInk,
)

@Composable
fun AasraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AasraColorScheme,
        typography = AasraTypography,
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = content
    )
}

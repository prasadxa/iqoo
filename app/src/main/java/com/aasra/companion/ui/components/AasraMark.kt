package com.aasra.companion.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.aasra.companion.ui.theme.PineInk
import com.aasra.companion.ui.theme.TextOnPine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun AasraMark(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val center = Offset(size.width / 2, size.height / 2)
        repeat(7) {
            val angle = it * 2f * PI.toFloat() / 7f
            drawCircle(PineInk, size.minDimension * 0.2f,
                center + Offset(cos(angle), sin(angle)) * (size.minDimension * 0.18f))
        }
        drawCircle(PineInk, size.minDimension * 0.2f, center)
        drawCircle(TextOnPine, size.minDimension * 0.06f, center)
    }
}

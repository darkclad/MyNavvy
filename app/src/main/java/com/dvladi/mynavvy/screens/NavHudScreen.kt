package com.dvladi.mynavvy.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.dvladi.mynavvy.game.NavData
import com.dvladi.mynavvy.render.NavHudRenderer

/**
 * The Simrad navigation HUD (corner panels + data-bar + heading tape) as a TRANSPARENT Compose
 * Canvas overlaid on the live MapLibre chart. The native-canvas [NavHudRenderer] picks portrait vs
 * landscape by aspect ratio. Recomposes when [data] changes (pushed from MainActivity.refreshNavUi).
 */
@Composable
fun NavHudScreen(data: NavData, modifier: Modifier = Modifier, topLeftInsetDp: Float = 0f) {
    val renderer = remember { NavHudRenderer() }
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, size.width, size.height, data, density, topLeftInsetDp * density)
        }
    }
}

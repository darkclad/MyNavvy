package com.dvladi.mynavvy.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.dvladi.mynavvy.game.HelmData
import com.dvladi.mynavvy.render.HelmRenderer

/**
 * The Helm (Simrad steering) gauges as a Compose Canvas: the pure android.graphics.Canvas
 * renderers (compass/track/boxes/inclinometer) draw into the native canvas. Recomposes when [data]
 * or the live [heelDeg]/[trimDeg] (gravity sensor) change.
 */
@Composable
fun HelmScreen(data: HelmData, heelDeg: Float, trimDeg: Float, modifier: Modifier = Modifier) {
    val renderer = remember { HelmRenderer() }
    Canvas(modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            renderer.draw(canvas.nativeCanvas, size.width, size.height, data, density, heelDeg, trimDeg)
        }
    }
}

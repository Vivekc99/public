package dev.nasexplorer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)

    fun reset() { scale = 1f; offset = Offset.Zero }
    fun toggle() { if (scale > 1f) reset() else scale = 3f }
}

/**
 * Two-finger pinch to zoom, one finger to pan once zoomed in. At normal size a one-finger
 * swipe is left alone so the gallery can move to the next file. Works over video views too.
 */
fun Modifier.pinchZoom(z: ZoomState): Modifier = this
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val multi = ev.changes.count { it.pressed } >= 2
                if (multi || z.scale > 1f) {
                    val zoom = ev.calculateZoom()
                    val pan = ev.calculatePan()
                    val ns = (z.scale * zoom).coerceIn(1f, 8f)
                    z.scale = ns
                    if (ns <= 1f) {
                        z.offset = Offset.Zero
                    } else {
                        val mx = size.width * (ns - 1f) / 2f
                        val my = size.height * (ns - 1f) / 2f
                        z.offset = Offset(
                            (z.offset.x + pan.x).coerceIn(-mx, mx),
                            (z.offset.y + pan.y).coerceIn(-my, my),
                        )
                    }
                    ev.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (ev.changes.any { it.pressed })
        }
    }
    .graphicsLayer(
        scaleX = z.scale, scaleY = z.scale,
        translationX = z.offset.x, translationY = z.offset.y,
    )

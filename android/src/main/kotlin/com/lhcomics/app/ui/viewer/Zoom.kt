package com.lhcomics.app.ui.viewer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged

private const val MAX_SCALE = 5f

/**
 * 한 페이지 단위 확대. 두 손가락으로 확대/이동, 두 번 탭으로 2.5배↔원래 크기.
 * 확대 중에는 한 손가락 드래그도 이동으로 쓰고, 페이저 스와이프는 막는다.
 */
@Composable
fun ZoomablePage(
    resetKey: Any,
    onZoomChanged: (Boolean) -> Unit,
    onTap: (Float, Float) -> Unit,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val zoomCallback by rememberUpdatedState(onZoomChanged)
    val tapCallback by rememberUpdatedState(onTap)

    LaunchedEffect(resetKey) {
        if (scale != 1f) {
            scale = 1f
            offset = Offset.Zero
            zoomCallback(false)
        }
    }

    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - tap) * (scale - 1f), scale, size.width, size.height)
                        }
                        zoomCallback(scale > 1f)
                    },
                    onTap = { tapCallback(it.x / size.width, it.y / size.height) },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pointers = event.changes.count { it.pressed }
                        if (pointers >= 2 || scale > 1f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            offset = clamp(offset + pan, scale, size.width, size.height)
                            if (scale > 1f || pointers >= 2) {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.05f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                    zoomCallback(scale > 1f)
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    ) { content() }
}

private fun clamp(offset: Offset, scale: Float, width: Int, height: Int): Offset {
    val maxX = width * (scale - 1f) / 2f
    val maxY = height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * 연속 스크롤용 두 손가락 확대. 한 손가락 이벤트는 그대로 흘려보내 목록 스크롤을 방해하지 않는다.
 * 세로 모드는 가로로, 가로 모드는 세로로만 이동한다(스크롤 축은 목록이 담당).
 */
fun Modifier.pinchZoom(
    vertical: Boolean,
    scale: () -> Float,
    offset: () -> Float,
    onTransform: (scale: Float, offset: Float) -> Unit,
): Modifier = pointerInput(vertical) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val s = (scale() * event.calculateZoom()).coerceIn(1f, MAX_SCALE)
                val pan = event.calculatePan()
                val extent = if (vertical) size.width else size.height
                val max = extent * (s - 1f) / 2f
                val delta = if (vertical) pan.x else pan.y
                onTransform(s, (offset() + delta).coerceIn(-max, max))
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (scale() < 1.05f) onTransform(1f, 0f)
    }
}

fun Modifier.zoomLayer(scale: () -> Float, offset: () -> Float, vertical: Boolean): Modifier = graphicsLayer {
    val s = scale()
    scaleX = s
    scaleY = s
    if (vertical) translationX = offset() else translationY = offset()
}

package com.dgcamp.paint.ui

/**
 * 屏幕像素坐标 → 离屏画布坐标 映射（镜像 PC coords.cpp 语义）。
 *
 * 真机上报触摸的是屏幕像素坐标（如 3240x2160），而 SDK 离屏画布是逻辑尺寸
 * （1080x720）。若直传，笔迹落在 `触摸 × 屏幕宽/画布宽` 处，与手指不重合。
 * 故按比例缩放：canvasX = screenX × canvasW / screenW。
 *
 * 纯函数、无 Android 依赖，可被 JVM 单测直接覆盖。
 */
fun mapScreenToCanvas(
    x: Float,
    y: Float,
    screenW: Float,
    screenH: Float,
    canvasW: Float,
    canvasH: Float,
): Pair<Float, Float> {
    if (screenW <= 0f || screenH <= 0f || canvasW <= 0f || canvasH <= 0f) return 0f to 0f
    return (x * canvasW / screenW) to (y * canvasH / screenH)
}

/* ── 缩放视口（D6-2，镜像 PC coords.cpp 语义）── */

const val ZOOM_MIN = 1f
const val ZOOM_MAX = 8f

fun clampZoom(zoom: Float): Float = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)

/**
 * 居中子视口：视口尺寸 = 整张画布 / zoom，中心与画布中心重合。
 * 返回 [viewX, viewY, viewW, viewH]，zoom=1 时退化为整张画布（0,0,cw,ch）。
 * 纯函数、无 Android 依赖，可被 JVM 单测直接覆盖。
 */
fun computeCanvasViewport(canvasW: Float, canvasH: Float, zoom: Float): FloatArray {
    val vw = canvasW / zoom
    val vh = canvasH / zoom
    return floatArrayOf(canvasW / 2f - vw / 2f, canvasH / 2f - vh / 2f, vw, vh)
}

/**
 * 屏幕像素 → 缩放画布坐标：先 mapScreenToCanvas 得到未缩放画布坐标 (fx,fy)，
 * 再按居中视口换算 out = view.xy + (fx,fy)/zoom。zoom=1 时与 mapScreenToCanvas 一致。
 */
fun mapScreenToCanvasZoomed(
    x: Float,
    y: Float,
    screenW: Float,
    screenH: Float,
    canvasW: Float,
    canvasH: Float,
    zoom: Float,
): Pair<Float, Float> {
    val (fx, fy) = mapScreenToCanvas(x, y, screenW, screenH, canvasW, canvasH)
    val (vx, vy, _, _) = computeCanvasViewport(canvasW, canvasH, zoom)
    return (vx + fx / zoom) to (vy + fy / zoom)
}

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

package com.dgcamp.paint.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceHolder

/**
 * A8-4：`SDK_SURFACE_VIEW` 呈现路径辅助函数（见 `docs/plans/A8-4.md` §3.2）。
 *
 * 目的：验证「跳过 Compose 状态派发 + 等 Choreographer 帧回调 + recompose + layout」这几步，
 * 对「输入→上屏」延迟是否有可测量改善——把已经读回的位图直接 `lockCanvas`/`drawBitmap`/
 * `unlockCanvasAndPost` 画到 `SurfaceView` 上，而不是写 `mutableStateOf<Bitmap?>` 触发
 * Compose 重组。
 */

/** [computeSurfaceDrawRects] 的结果：src/dst 均为整数像素矩形（配 `android.graphics.Rect`）。 */
internal data class SurfaceDrawRects(
    val srcLeft: Int, val srcTop: Int, val srcRight: Int, val srcBottom: Int,
    val dstLeft: Int, val dstTop: Int, val dstRight: Int, val dstBottom: Int,
)

/**
 * 给定 Surface 物理尺寸与画布逻辑尺寸+缩放，算出 `canvas.drawBitmap(src, dst)` 的矩形。
 *
 * 镜像 `Coords.kt` `computeCanvasViewport` 的居中子视口语义（与 Compose 路径
 * `drawImage(srcOffset/srcSize/dstSize)` 是同一份算法，保证 D6-2 缩放/居中视口在两条
 * 呈现路径下行为一致）：src 取画布内居中子视口（随 zoom 收缩），dst 恒为整个 surface
 * （铺满显示区，与 Compose `dstSize = IntSize(dstW, dstH)` 语义一致）。
 *
 * 纯 Kotlin 函数，不依赖 `android.graphics`，可被 JVM 单测直接覆盖（`PaintSurfaceHostTest`）。
 * src 矩形保证非退化（`right > left`、`bottom > top`），即使 canvasW/H 非整除 zoom。
 */
internal fun computeSurfaceDrawRects(
    surfaceW: Int, surfaceH: Int,
    canvasW: Float, canvasH: Float, zoom: Float,
): SurfaceDrawRects {
    val (vx, vy, vw, vh) = computeCanvasViewport(canvasW, canvasH, zoom)   // 复用 Coords.kt
    return SurfaceDrawRects(
        srcLeft = vx.toInt(), srcTop = vy.toInt(),
        srcRight = (vx + vw).toInt().coerceAtLeast(vx.toInt() + 1),
        srcBottom = (vy + vh).toInt().coerceAtLeast(vy.toInt() + 1),
        dstLeft = 0, dstTop = 0, dstRight = surfaceW, dstBottom = surfaceH,
    )
}

/**
 * 在调用方所在线程（约定：readback 单线程 dispatcher，序列化调用，不与其它线程并发
 * `lockCanvas`）里，把 [bitmap] 直接画到 [holder] 持有的 Surface 上。
 *
 * [holder] 为 `null`（Surface 尚未创建/已销毁，见计划 §9 R3）或 `lockCanvas()` 失败
 * （抛异常或返回 `null`，Surface 未 ready/已销毁的常见表现）时安全跳过、返回 `false`，
 * 不崩溃、不重试——下一次 `dirty` 周期会用最新内容再画一次，双缓冲语义保证不丢数据，
 * 只是可能跳过一帧显示，对 correctness 无影响。
 *
 * `lockCanvas()`/`unlockCanvasAndPost()` 用 `try/finally` 严格配对：即使 `drawBitmap`
 * 抛异常也会执行 `unlockCanvasAndPost`，避免 Surface 被永久锁死导致后续帧全部呈现失败
 * （消费端语境下与 SDK 侧「RAII/异常安全释放」原则对应的具体落地，见计划 §7.3）。
 */
internal fun presentBitmapToSurface(
    holder: SurfaceHolder?, bitmap: Bitmap, canvasW: Float, canvasH: Float, zoom: Float,
): Boolean {
    val h = holder ?: return false
    val canvas = try { h.lockCanvas() } catch (e: Exception) { null } ?: return false
    try {
        val r = computeSurfaceDrawRects(canvas.width, canvas.height, canvasW, canvasH, zoom)
        canvas.drawBitmap(
            bitmap,
            Rect(r.srcLeft, r.srcTop, r.srcRight, r.srcBottom),
            Rect(r.dstLeft, r.dstTop, r.dstRight, r.dstBottom),
            null,
        )
    } finally {
        h.unlockCanvasAndPost(canvas)
    }
    return true
}

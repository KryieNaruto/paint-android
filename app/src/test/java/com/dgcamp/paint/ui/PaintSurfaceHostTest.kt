package com.dgcamp.paint.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A8-4：`computeSurfaceDrawRects` 纯函数单测（TDD 先行，无 Android 依赖，JVM 无头）。
 *
 * 镜像 `CoordsTest.kt` 现有用例风格；`computeSurfaceDrawRects` 复用 `Coords.kt` 的
 * `computeCanvasViewport`（同一份居中子视口算法），保证 Compose `drawImage` 路径与
 * `SurfaceView` `canvas.drawBitmap` 路径的缩放/居中行为一致（D6-2 一致性，见计划 §3.2）。
 */
class PaintSurfaceHostTest {

    // zoom=1 时退化为整张画布：src 覆盖 (0,0)-(canvasW,canvasH)，dst 覆盖整个 surface。
    @Test
    fun `zoom one degenerates to full canvas as src rect`() {
        val r = computeSurfaceDrawRects(
            surfaceW = 1080, surfaceH = 1920,
            canvasW = 1080f, canvasH = 720f, zoom = 1f,
        )
        assertEquals(0, r.srcLeft)
        assertEquals(0, r.srcTop)
        assertEquals(1080, r.srcRight)
        assertEquals(720, r.srcBottom)
        assertEquals(0, r.dstLeft)
        assertEquals(0, r.dstTop)
        assertEquals(1080, r.dstRight)
        assertEquals(1920, r.dstBottom)
    }

    // zoom=2：src 子矩形应居中于画布，面积为画布的 1/4（与 CoordsTest
    // `viewport centered and shrinks with zoom` 的 computeCanvasViewport 断言同源）。
    @Test
    fun `zoom two centers src rect and shrinks it`() {
        val r = computeSurfaceDrawRects(
            surfaceW = 1080, surfaceH = 1920,
            canvasW = 1080f, canvasH = 720f, zoom = 2f,
        )
        val srcW = r.srcRight - r.srcLeft
        val srcH = r.srcBottom - r.srcTop
        assertEquals(540, srcW)
        assertEquals(360, srcH)
        // 居中：子矩形中心 == 画布中心 (540, 360)
        assertEquals(540f, (r.srcLeft + r.srcRight) / 2f, 1f)
        assertEquals(360f, (r.srcTop + r.srcBottom) / 2f, 1f)
        // dst 恒铺满整个 surface，与 zoom 无关（放大铺满显示区）
        assertEquals(1080, r.dstRight)
        assertEquals(1920, r.dstBottom)
    }

    // surfaceW/H 与 canvasW/H 比例不一致（surface 是竖屏 1080x2400，画布是横向 1080x720）
    // 时不应崩溃，dst 仍应恒等于整个 surface 尺寸（不做额外letterbox，铺满交给 drawBitmap
    // 的 src/dst 矩形缩放本身完成，与 Compose `drawImage(dstSize=...)` 语义一致）。
    @Test
    fun `mismatched surface and canvas aspect ratio does not crash`() {
        val r = computeSurfaceDrawRects(
            surfaceW = 1080, surfaceH = 2400,
            canvasW = 1080f, canvasH = 720f, zoom = 1f,
        )
        assertEquals(1080, r.dstRight)
        assertEquals(2400, r.dstBottom)
        assertTrue(r.srcRight > r.srcLeft)
        assertTrue(r.srcBottom > r.srcTop)
    }

    // 非整除边界：奇数画布尺寸 + zoom=3（非整除）不应产生退化空矩形（src right/bottom
    // 必须严格大于 left/top，否则 drawBitmap 会因空源矩形抛异常/绘制空白）。
    @Test
    fun `non divisible zoom keeps a non degenerate src rect`() {
        val r = computeSurfaceDrawRects(
            surfaceW = 800, surfaceH = 600,
            canvasW = 1081f, canvasH = 721f, zoom = 3f,
        )
        assertTrue("srcRight 必须严格大于 srcLeft", r.srcRight > r.srcLeft)
        assertTrue("srcBottom 必须严格大于 srcTop", r.srcBottom > r.srcTop)
        assertTrue(r.srcLeft >= 0)
        assertTrue(r.srcTop >= 0)
    }

    // 高 zoom（接近 ZOOM_MAX=8）下子矩形仍应落在画布边界内（不越界），且仍非退化。
    @Test
    fun `high zoom near max stays within canvas bounds`() {
        val r = computeSurfaceDrawRects(
            surfaceW = 1080, surfaceH = 1920,
            canvasW = 1080f, canvasH = 720f, zoom = 8f,
        )
        assertTrue(r.srcLeft >= 0)
        assertTrue(r.srcTop >= 0)
        assertTrue(r.srcRight <= 1080)
        assertTrue(r.srcBottom <= 720)
        assertTrue(r.srcRight > r.srcLeft)
        assertTrue(r.srcBottom > r.srcTop)
    }
}

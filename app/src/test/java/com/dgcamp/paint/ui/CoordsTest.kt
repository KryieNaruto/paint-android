package com.dgcamp.paint.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordsTest {

    // 报障场景：真机像素 3240x2160 触摸 (100,100)，映射到 1080x720 离屏画布 → (33.33, 33.33)，
    // 上屏拉伸铺满后回投仍落在 (100,100)（round-trip cx*3240/1080 == 100）。
    @Test
    fun `bug scenario maps screen pixel to canvas`() {
        val (cx, cy) = mapScreenToCanvas(100f, 100f, 3240f, 2160f, 1080f, 720f)
        assertEquals(33.33f, cx, 0.02f)
        assertEquals(33.33f, cy, 0.02f)
        assertEquals(100f, cx * 3240f / 1080f, 0.02f)
        assertEquals(100f, cy * 2160f / 720f, 0.02f)
    }

    // 通用 round-trip 性质：屏内若干点，map 后按显示缩放回投，与原点一致。
    @Test
    fun `round trip maps back to original screen point`() {
        val screenW = 1080f
        val screenH = 1920f
        val canvasW = 540f
        val canvasH = 960f
        val points = listOf(
            0f to 0f,
            100f to 200f,
            540f to 960f,
            1079f to 1919f,
        )
        for ((x, y) in points) {
            val (cx, cy) = mapScreenToCanvas(x, y, screenW, screenH, canvasW, canvasH)
            assertEquals(x, cx * screenW / canvasW, 0.02f)
            assertEquals(y, cy * screenH / canvasH, 0.02f)
        }
    }

    // 边界：任一尺寸 ≤ 0 → (0,0)。
    @Test
    fun `non positive dimensions return zero`() {
        assertEquals(0f to 0f, mapScreenToCanvas(1f, 1f, 0f, 100f, 50f, 50f))
        assertEquals(0f to 0f, mapScreenToCanvas(1f, 1f, 100f, -1f, 50f, 50f))
        assertEquals(0f to 0f, mapScreenToCanvas(1f, 1f, 100f, 100f, 0f, 50f))
        assertEquals(0f to 0f, mapScreenToCanvas(1f, 1f, 100f, 100f, 50f, -50f))
    }

    // ── 缩放视口（D6-2）──

    // clampZoom 边界：<1 收敛到 1，>8 收敛到 8，区间内原样。
    @Test
    fun `clamp zoom bounds`() {
        assertEquals(ZOOM_MIN, clampZoom(0f), 0f)
        assertEquals(ZOOM_MIN, clampZoom(-3f), 0f)
        assertEquals(ZOOM_MAX, clampZoom(99f), 0f)
        assertEquals(2f, clampZoom(2f), 0f)
    }

    // computeCanvasViewport：zoom=1 → 整张画布；zoom=2 → 1/4 面积且居中。
    @Test
    fun `viewport centered and shrinks with zoom`() {
        val full = computeCanvasViewport(1080f, 720f, 1f)
        assertEquals(0f, full[0], 0f); assertEquals(0f, full[1], 0f)
        assertEquals(1080f, full[2], 0f); assertEquals(720f, full[3], 0f)

        val half = computeCanvasViewport(1080f, 720f, 2f)
        assertEquals(540f, half[2], 0f); assertEquals(360f, half[3], 0f)
        // 居中：视口中心 == 画布中心 (540,360)
        assertEquals(540f, half[0] + half[2] / 2f, 0.02f)
        assertEquals(360f, half[1] + half[3] / 2f, 0.02f)
        // 视口面积 = 画布 / zoom²
        assertEquals(1080f * 720f / 4f, half[2] * half[3], 0.02f)
    }

    // mapScreenToCanvasZoomed：zoom=1 时与 mapScreenToCanvas 完全一致（无缩放回归）。
    @Test
    fun `zoomed mapping at zoom one equals unzoomed`() {
        val pts = listOf(0f to 0f, 100f to 200f, 3240f to 2160f)
        for ((x, y) in pts) {
            val unzoomed = mapScreenToCanvas(x, y, 3240f, 2160f, 1080f, 720f)
            val zoomed = mapScreenToCanvasZoomed(x, y, 3240f, 2160f, 1080f, 720f, 1f)
            assertEquals(unzoomed.first, zoomed.first, 0.02f)
            assertEquals(unzoomed.second, zoomed.second, 0.02f)
        }
    }
}

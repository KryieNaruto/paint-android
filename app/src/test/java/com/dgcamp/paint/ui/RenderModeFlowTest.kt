package com.dgcamp.paint.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A8-5：RenderMode 四态 + 循环/读回门控的纯函数单测（JVM 无头）。
 *
 * 覆盖决定 SWAPCHAIN 行为的三处纯逻辑：
 * 1. [nextRenderMode] 四段循环顺序——SDK → SWAPCHAIN → SDK_SURFACE_VIEW → INK → SDK；
 * 2. [RenderMode.readsBack] 读回门控——只有 SDK / SDK_SURFACE_VIEW 启动 readback worker，
 *    SWAPCHAIN（由 SDK present）与 INK（ink 自身上屏）不得启动读回循环；
 * 3. [RenderMode.hudLabel] 四态短名互异（顶部开关/浮层文案）。
 *
 * 红（加 SWAPCHAIN 前）：enum 无 SWAPCHAIN、无这些 helper → 本测试编译失败（red = 编译失败）。
 */
class RenderModeFlowTest {

    // nextRenderMode 四段循环：从默认态 SDK 出发，4 次点击回到 SDK，途中依次经过 SWAPCHAIN /
    // SDK_SURFACE_VIEW / INK，且 SWAPCHAIN 紧邻 SDK（主对照一次点击即达，plan §5.2）。
    @Test
    fun `mode cycle from SDK passes SWAPCHAIN then SDK_SURFACE_VIEW then INK and wraps to SDK`() {
        assertEquals(RenderMode.SWAPCHAIN, nextRenderMode(RenderMode.SDK))
        assertEquals(RenderMode.SDK_SURFACE_VIEW, nextRenderMode(RenderMode.SWAPCHAIN))
        assertEquals(RenderMode.INK, nextRenderMode(RenderMode.SDK_SURFACE_VIEW))
        assertEquals(RenderMode.SDK, nextRenderMode(RenderMode.INK))   // 回绕到默认态
    }

    // readsBack 门控：readback worker 只在 SDK/SDK_SURFACE_VIEW 启动；SWAPCHAIN/INK 不启动。
    // SWAPCHAIN 是 A8-5 的关键——显示由 SDK present 承担，无 readback（废弃 readback→Bitmap 上屏链路）。
    @Test
    fun `readback worker only runs for SDK and SDK_SURFACE_VIEW modes`() {
        assertTrue("SDK 走 readback 上屏", RenderMode.SDK.readsBack)
        assertTrue("SDK_SURFACE_VIEW 走 readback 上屏", RenderMode.SDK_SURFACE_VIEW.readsBack)
        assertFalse("SWAPCHAIN 由 SDK present，无 readback", RenderMode.SWAPCHAIN.readsBack)
        assertFalse("INK 由 ink 上屏，无 readback", RenderMode.INK.readsBack)
    }

    // 四态短名互异：顶部开关/浮层文案需要把四个模式区分开。
    @Test
    fun `hud labels are distinct across all four modes`() {
        val labels = RenderMode.entries.map { it.hudLabel }.toSet()
        assertEquals("四种模式短名应互异", 4, labels.size)
        assertTrue(labels.contains("SDK"))
        assertTrue(labels.contains("Swapchain"))
        assertTrue(labels.contains("SurfaceView"))
        assertTrue(labels.contains("INK"))
    }
}

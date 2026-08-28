package com.dgcamp.paint

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.dgcamp.paint.ui.PaintScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Bug #2 帧率解锁（消费端，plan §2.3 方案 (a)）：纯 Compose 上屏由窗口 vsync 驱动，
        // 上限 = 屏幕刷新率；Android 部分机型低功耗默认锁 60Hz。把窗口首选显示模式指到当前
        // Display 支持的最高刷新率模式（如 120/144Hz），摆脱 60Hz 锁屏刷。
        requestMaxRefreshRate()
        setContent {
            PaintScreen()
        }
    }

    // 帧率解锁：把 window.attributes.preferredDisplayModeId 指到最高刷新率模式。
    // 说明（方案选择依据，见 docs/plans/bugfix-settings-modeler-fps.md §2.3）：
    //   - 方案 (a) 最轻量且纯 Compose 内可落地——本实现即 (a)。
    //   - 方案 (b)（自建 SurfaceView+EGL + eglSwapInterval(0)）可突破「面板刷新率」给到
    //     无 vsync 的上限，但需把绘制/读回路径从 Compose Canvas 整体迁出，属更大重构，
    //     未在本期做。
    //   - 方案 (c)（把读回循环移出 withFrameNanos）只解耦 SDK 读回节奏，不改变 Compose
    //     上屏仍以 vsync 为节拍的现实，对感知帧率无增量，未单独做。
    // 注意：无法突破面板物理刷新率上限；量化 fps 依赖真机实测（交付时标注「待真机确认」）。
    private fun requestMaxRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val display = currentDisplay()
        if (display == null) return
        val supported = display.supportedModes
        if (supported.isEmpty()) return
        val best = supported.maxByOrNull { it.refreshRate } ?: return
        if (best.modeId != display.mode.modeId) {
            window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
        }
        // preferredRefreshRate（API 30+）作为逐项属性兜底：部分 OEM 仅认该项。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply { preferredRefreshRate = best.refreshRate }
        }
    }

    @Suppress("DEPRECATION")
    private fun currentDisplay(): android.view.Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
        else windowManager.defaultDisplay
}

package com.dgcamp.paint.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.ink.authoring.compose.InProgressStrokes
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.strokes.Stroke

/**
 * Mode B（Jetpack Ink）渲染画布。
 *
 * 用 [InProgressStrokes]（HWUI 前缓冲低延迟上屏）实时渲染进行中笔画，自带输入层
 * （处理未被上层消费的指针事件）与内置 stroke modeler（与 SDK `core/stroke_predictor`
 * 同算法，B1-5 白盒移植结论）。完成笔画经 [onStrokesFinished] 交回宿主（用于离屏
 * PNG 导出 / 累计笔画）。
 *
 * 与 Mode A（SDK Vulkan 离屏→readback→贴图）正交：无 readback、无 3.1MB memcpy，
 * 矢量 mesh 直接上屏，是本 A/B 对照要量化的「无 readback 延迟收益」来源。
 */
@Composable
fun InkStrokeCanvas(
    modifier: Modifier = Modifier,
    brush: Brush? = remember { defaultInkBrush() },
    onStrokesFinished: (List<Stroke>) -> Unit = {},
) {
    // TextureBitmapStore 恒返回 null：默认钢笔刷无需纹理贴图，也避免加载 StockBrushes 纹理。
    // remember 住避免每帧重组重建 SAM 实例。
    val textureBitmapStore = remember { TextureBitmapStore { null } }
    InProgressStrokes(
        defaultBrush = brush,
        textureBitmapStore = textureBitmapStore,
        onStrokesFinished = onStrokesFinished,
    )
}

/**
 * 默认 ink 笔刷：`StockBrushes.pressurePen()`（钢笔类，带压力响应与内置 modeler）。
 * 尺寸/epsilon 取屏幕像素坐标基准（[InProgressStrokes] 默认 identity 变换，stroke 坐标 =
 * 屏幕像素）。黑色不透明，对齐 SDK 默认笔色。
 */
fun defaultInkBrush(): Brush = Brush.createWithColorIntArgb(
    StockBrushes.pressurePen(),
    colorIntArgb = android.graphics.Color.BLACK,
    size = 10f,
    epsilon = 0.1f,
)

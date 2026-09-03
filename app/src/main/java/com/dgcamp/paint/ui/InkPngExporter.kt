package com.dgcamp.paint.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import java.io.File
import java.io.FileOutputStream

/**
 * ink 离屏导出（R5 硬约束的 ink 侧实现）：把已完成笔画（`List<Stroke>`）经
 * [CanvasStrokeRenderer] 逐笔画画到离屏 [Bitmap]，再 `compress(PNG)` 落盘。
 *
 * 与 SDK 基线（host `dgc_cli` → PNG）配对，作为 Mode B 的离屏执行图像。此导出路径
 * 仅在 INK 模式「导出 PNG」按钮触发，属调试/验收功能，不进绘制热路径。
 */
object InkPngExporter {

    /**
     * 把 [strokes] 渲染到 `width x height` 离屏画布并导出 PNG 到 [outFile]。
     *
     * @return 是否成功写出（空笔画、IO 异常返回 false）。
     */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    fun export(strokes: List<Stroke>, width: Int, height: Int, outFile: File): Boolean {
        if (strokes.isEmpty() || width <= 0 || height <= 0) return false
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.WHITE)                        // 纸白底，对齐 SDK 离屏画布底色
        val renderer = CanvasStrokeRenderer.create(TextureBitmapStore { null })
        // 恒等变换：笔画坐标即屏幕像素（与 InProgressStrokes 默认 identity 变换一致）。
        val transform = Matrix()
        for (stroke in strokes) {
            renderer.draw(canvas, stroke, transform)
        }
        return try {
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (_: Exception) {
            false
        }
    }
}

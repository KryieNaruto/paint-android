package com.dgcamp.paint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.dgcamp.paint.jni.PaintNative
import java.nio.ByteBuffer

/**
 * 绘画画布（SDK C API 接入）。
 *
 * 数据流：触摸输入 → JNI → dgcBeginStroke/StrokeTo/EndStroke → 引擎渲染 →
 *         每帧 dgcReadbackPixels 读回 RGBA8 → 复用单个 Bitmap → ImageBitmap 上屏。
 * 浮层显示 FPS / 帧时 ms / 读回耗时 ms（spec 验收 #3）。
 *
 * 依赖：B3-1 真实内核未合并时笔迹不可见（Null 内核），本期只验收链路 + FPS 浮层。
 */
@Composable
fun PaintScreen() {
    // 画布逻辑尺寸（1080x720 内，避免 readback 带宽过大；真机可按屏宽高）
    val cw = 1080
    val ch = 720

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var fps by remember { mutableFloatStateOf(0f) }
    var frameMs by remember { mutableFloatStateOf(0f) }
    var readMs by remember { mutableFloatStateOf(0f) }
    var lastError by remember { mutableStateOf("") }
    val ctx = remember { PaintNative }
    val started = remember { ctx.nativeInit(cw, ch) }
    DisposableEffect(Unit) { onDispose { ctx.nativeDestroy() } }

    // 复用单个 Bitmap，避免每帧 createBitmap 在主线程分配（3MB/帧）造成 GC churn。
    val bmp = remember { Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888) }

    // 每帧：readback → 复用 bitmap 更新 → 计算 fps/帧时/读回耗时
    LaunchedEffect(Unit) {
        var frames = 0; var last = System.nanoTime(); var prev = last
        while (true) {
            withFrameNanos { now ->
                val rb0 = System.nanoTime()
                val arr = ctx.nativeReadback()
                val rb1 = System.nanoTime()
                readMs = (rb1 - rb0) / 1_000_000f
                if (arr != null) {
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(arr))
                    bitmap = bmp   // 同一实例，避免重复分配
                } else {
                    lastError = "readback failed"
                }
                frames++
                if (now - last >= 500_000_000L) {
                    fps = frames * 1e9f / (now - last).toFloat()
                    frameMs = 1000f / (if (fps > 0f) fps else 1f)
                    frames = 0; last = now
                }
            }
        }
    }

    val canvasColor = Color(0xFFF5F2E8)
    val overlayColor = Color.Black.copy(alpha = 0.7f)

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(canvasColor)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> ctx.nativeStrokeBegin(offset.x, offset.y, 0.5f) },
                        onDrag = { change, _ -> change.consume(); ctx.nativeStrokeTo(change.position.x, change.position.y, 0.5f) },
                        onDragEnd = { ctx.nativeStrokeEnd() },
                    )
                },
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(bmp.asImageBitmap(), dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()))
                }
            }
            Text(
                text = if (!started) "SDK init failed" else
                    "FPS: ${"%.1f".format(fps)}\nFrame: ${"%.2f".format(frameMs)} ms\nReadback: ${"%.2f".format(readMs)} ms\n$lastError",
                color = overlayColor,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.TopStart).safeDrawingPadding().padding(12.dp),
            )
        }
    }
}

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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dgcamp.paint.BuildConfig
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
    // 脏标志：有新输入（gesture 回调置 true）才在下一帧 flush + 读回；空闲帧跳过读回，
    // 消除每帧 3.1MB 搬运（优化 3：读回移出每帧路径）。初值 true 让首帧上屏清好的画布。
    var dirty by remember { mutableStateOf(true) }
    // 显示区尺寸（像素），用于把屏幕触摸坐标映射到 1080x720 离屏画布坐标。
    // currentDisplayPx 供 pointerInput 协程内读取最新值（rememberUpdatedState 语义）。
    var displayPx by remember { mutableStateOf(IntSize.Zero) }
    val currentDisplayPx by rememberUpdatedState(displayPx)
    val ctx = remember { PaintNative }
    val started = remember { ctx.nativeInit(cw, ch) }
    DisposableEffect(Unit) { onDispose { ctx.nativeDestroy() } }

    // 复用单个 Bitmap + 单个 direct ByteBuffer（零分配读回，优化 3）。
    // direct buffer 由 Java 持有，JNI 经 GetDirectBufferAddress 让 SDK 直接写入其内存，
    // 消灭每帧 std::vector/NewByteArray/SetByteArrayRegion 的分配与 3.1MB 拷贝。
    val bmp = remember { Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888) }
    val rbBuf = remember { ByteBuffer.allocateDirect(cw * ch * 4) }

    // 帧循环：仅在 dirty 时 flush（等批量 composite 完成）→ 零分配读回 → 复用 bitmap 上屏。
    LaunchedEffect(Unit) {
        var frames = 0; var last = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                if (dirty) {
                    ctx.nativeFlush()          // drain 屏障：批量 composite 后必须等完成才读到新画布
                    val rb0 = System.nanoTime()
                    val rc = ctx.nativeReadback(rbBuf)
                    val rb1 = System.nanoTime()
                    readMs = (rb1 - rb0) / 1_000_000f
                    if (rc == 0) {
                        rbBuf.rewind()
                        bmp.copyPixelsFromBuffer(rbBuf)
                        bitmap = bmp           // 同一实例，避免重复分配
                        dirty = false
                    } else {
                        lastError = "readback failed rc=$rc"
                    }
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
                .onSizeChanged { displayPx = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dirty = true
                            // 屏幕像素 → 离屏画布坐标，避免笔迹落在 `触摸×屏宽/画布宽` 处
                            val (cx, cy) = mapScreenToCanvas(
                                offset.x, offset.y,
                                currentDisplayPx.width.toFloat(), currentDisplayPx.height.toFloat(),
                                cw.toFloat(), ch.toFloat(),
                            )
                            ctx.nativeStrokeBegin(cx, cy, 0.5f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dirty = true
                            val (cx, cy) = mapScreenToCanvas(
                                change.position.x, change.position.y,
                                currentDisplayPx.width.toFloat(), currentDisplayPx.height.toFloat(),
                                cw.toFloat(), ch.toFloat(),
                            )
                            ctx.nativeStrokeTo(cx, cy, 0.5f)
                        },
                        onDragEnd = {
                            dirty = true
                            ctx.nativeStrokeEnd()
                        },
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
            // 版本号：SDK 无自带版本 API，取 sdk submodule 实际签出 HEAD + 消费者自身 HEAD 的 git short SHA。
            Text(
                text = "sdk ${BuildConfig.SDK_GIT_SHA} · app ${BuildConfig.APP_GIT_SHA}",
                color = overlayColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(12.dp),
            )
        }
    }
}

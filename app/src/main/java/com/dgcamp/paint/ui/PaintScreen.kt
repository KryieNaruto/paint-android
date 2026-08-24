package com.dgcamp.paint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dgcamp.paint.jni.PaintNative

/**
 * 绘画画布（空壳期）。
 *
 * 本轮只做两件事：
 *  1. 纸白画布 + 触摸坐标 / 压感显示（输入桩，不转发）。
 *  2. JNI 自检：显示 PaintNative.nativeHello() 返回值，证明 native 库加载成功。
 *
 * SDK C API 接入后（等 B1-4）：
 *  - pointerInput 回调改为调用 dgcBeginStroke / dgcStrokeTo / dgcEndStroke（经 JNI）。
 *  - Canvas 改为贴 dgc_paint 渲染结果（TextureView / Surface 合成）。
 */
@Composable
fun PaintScreen() {
    var lastX by remember { mutableFloatStateOf(0f) }
    var lastY by remember { mutableFloatStateOf(0f) }
    var lastPressure by remember { mutableFloatStateOf(0f) }
    var jniMsg by remember { mutableStateOf(PaintNative.nativeHello()) }

    val canvasColor = Color(0xFFF5F2E8) // 纸白
    val overlayColor = Color.Black.copy(alpha = 0.7f)

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(canvasColor)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            lastX = offset.x
                            lastY = offset.y
                            // TODO(C API): dgcBeginStroke(ctx, offset.x, offset.y, 0.5f, 0f, 0f)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            lastX = change.position.x
                            lastY = change.position.y
                            // TODO(C API): dgcStrokeTo(ctx, position.x, position.y, 0.5f, 0f, 0f, 0)
                        },
                        onDragEnd = {
                            // TODO(C API): dgcEndStroke(ctx); dgcRender(ctx)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        lastX = offset.x
                        lastY = offset.y
                        lastPressure = 1f
                    }
                },
        ) {
            // 画布占位（纸白，无渲染内容）。
            Canvas(modifier = Modifier.fillMaxSize()) {}

            // 状态浮层。
            Text(
                text = "paint-android 外壳 · SDK C API 未接入\n$jniMsg\n触摸: (${lastX.toInt()}, ${lastY.toInt()}) 压感: ${"%.2f".format(lastPressure)}",
                color = overlayColor,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(12.dp),
            )
        }
    }
}

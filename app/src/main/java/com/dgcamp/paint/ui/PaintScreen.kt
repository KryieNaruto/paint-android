package com.dgcamp.paint.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dgcamp.paint.BuildConfig
import com.dgcamp.paint.jni.PaintNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/** D6-1 画笔参数滑杆规格：settingId/范围严格取 SDK docs/brush_settings_mapping.md，
 * 默认值对齐 paint-pc app.cpp（radius 20 / hardness 0.8 / opacity 1.0 / modeler 见映射表）。
 * effect：面向用户的通俗效果说明（Bug #2）——modeler（id>=4）逐条取自映射表
 * 「改参效果（人工可辨）」列；0-2 注明生效时机。渲染为滑杆 label 下方的次级说明文本。 */
internal data class BrushSettingSpec(
    val id: Int,
    val label: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val effect: String,
)

// settingId 0-2（radius/hardness/opacity）自 SDK bugfix 起经内核 Brush::setBase 实时生效于
// 下一笔 stroke（映射表注，笔画之间生效），保留控件与 PC 一致；4-12 为 stroke modeler 参数
// （惰性激活，生效于新笔画）。跳过 3（RADIUS_LOG，PC 也未接）。id 与 sdk_api/dgc_paint_c_api.h
// 的 DgcBrushSetting 枚举一致。spring 默认值对齐 SDK 新默认（K/m=40000、C/m=400，ωn=200 rad/s
// 临界阻尼，见 core/stroke_predictor.h bugfix Fix B 校准依据）。
internal val BRUSH_SETTINGS = listOf(
    // 0-2：笔刷内核基础参数（bugfix 起经内核 setBase 实时生效于下一笔 stroke），effect 注明生效时机。
    BrushSettingSpec(0, "半径 radius", 1f, 100f, 20f, "越大笔触越粗；改动在下一笔生效"),
    BrushSettingSpec(1, "硬度 hardness", 0f, 1f, 0.8f, "越大边缘越实、笔触越硬；改动在下一笔生效"),
    BrushSettingSpec(2, "不透明度 opacity", 0f, 1f, 1f, "越大颜色越浓、越不透明；改动在下一笔生效"),
    // 4-12：stroke modeler 参数（惰性激活，生效于新笔画）。effect 逐条取自
    // sdk/docs/brush_settings_mapping.md「改参效果（人工可辨）」列（Bug #2）。
    BrushSettingSpec(4, "抖动消除超时 wobble_timeout_ms", 0f, 200f, 40f, "越大越平滑但越迟滞跟手"),
    BrushSettingSpec(5, "抖动消除最低速度 wobble_speed_floor", 0f, 10f, 1.31f, "越大越容易判定为静止抖动而被压平"),
    BrushSettingSpec(6, "最小输出采样率 min_output_rate_hz", 20f, 500f, 180f, "越大补点越密、曲线越平滑，也决定预测点间距"),
    BrushSettingSpec(7, "抬笔停止距离 end_of_stroke_stopping_distance_mm", 0.01f, 5f, 0.1f, "越大末端预测点越倾向继续外推"),
    BrushSettingSpec(8, "弹簧质量常量 spring_mass_constant", 1000f, 100000f, 40000f, "越大响应越快、越跟手"),
    BrushSettingSpec(9, "弹簧阻尼常量 spring_drag_constant", 10f, 2000f, 400f, "越大抑制过冲越强、运动越粘滞"),
    BrushSettingSpec(10, "卡尔曼过程噪声 kalman_process_noise", 0.00001f, 0.01f, 0.0005f, "越大越信任最新输入，速度估计更灵敏但更抖"),
    BrushSettingSpec(11, "卡尔曼测量噪声 kalman_measurement_noise", 0.0001f, 0.1f, 0.004f, "越大越不信任单次量测，估计速度越平滑但滞后"),
    BrushSettingSpec(12, "预测间隔 prediction_interval_ms", 0f, 100f, 16f, "越大预测点越远，越易见抢跑漂移"),
)

/** 滑杆读数短格式：整数不带小数，小数值按 decimals 位取整后去尾零。 */
private fun formatSetting(value: Float, decimals: Int = 3): String {
    if (value % 1f == 0f) return value.toInt().toString()
    return "%.${decimals}f".format(value).trimEnd('0').trimEnd('.')
}

/**
 * 双缓冲「后台缓冲」选择（P7-3）：返回「当前未显示」的那块缓冲。
 *
 * 语义（供 JVM 无头单测验证的纯函数，仅依赖引用相等 `===`）：
 * - `current == null`（首帧，尚无上屏缓冲）→ 返回 `a`；
 * - `current === a` → 返回 `b`（严格交替）；
 * - `current === b` → 返回 `a`。
 * 返回值恒 ≠ `current`（除非 a===b，调用方保证两块不同实例），因此主线程把返回值写进
 * `mutableStateOf<Bitmap>` 时判等（Bitmap 未重写 equals，引用相等）恒 false → 必重组/重绘；
 * 且后台线程写「未显示」的 back、主线程只做引用交换，front/back 永不并发触碰。
 */
internal fun <T : Any> backBufferFor(current: T?, a: T, b: T): T =
    if (current === a) b else a

/**
 * 绘画画布（SDK C API 接入）。
 *
 * 数据流：触摸输入 → JNI → dgcBeginStroke/StrokeTo/EndStroke → 引擎渲染 →
 *         每帧 dgcReadbackPixels 读回 RGBA8 → 双缓冲交替引用（bmpA/bmpB）→ ImageBitmap 上屏。
 * 浮层显示 FPS / 帧时 ms / 读回耗时 ms（spec 验收 #3）。
 *
 * D6-1/2/3：右上角「⚙ 参数」开关展开调试面板——画笔参数（12 滑杆）/ 颜色（4 滑杆+预览色块）/
 * 画布操作（缩放 −/＋/重置 + 清空）。设置/颜色仅在 strokeActive==false（笔画之间）下发，
 * 缩放经居中视口映射触摸 + 子矩形采样上屏（镜像 PC coords.cpp 语义）。
 *
 * 依赖：B3-1 真实内核未合并时笔迹不可见（Null 内核），本期只验收链路 + FPS 浮层。
 */
@OptIn(ExperimentalCoroutinesApi::class)   // Dispatchers.IO.limitedParallelism(1)
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
    // ReadbackScheduler：把「事件 → 读回」节拍从 Compose vsync（withFrameNanos）解耦
    // （bugfix-frame-loop-vsync-decouple）。输入驱动 + 最小间隔节流；重绘信号改由
    // 双缓冲交替引用（bitmap 在 bmpA/bmpB 间交替写不同实例）承担，无需 frameVersion。
    val scheduler = remember { ReadbackScheduler() }
    // 显示区尺寸（像素），用于把屏幕触摸坐标映射到 1080x720 离屏画布坐标。
    // currentDisplayPx 供 pointerInput 协程内读取最新值（rememberUpdatedState 语义）。
    var displayPx by remember { mutableStateOf(IntSize.Zero) }
    val currentDisplayPx by rememberUpdatedState(displayPx)
    val ctx = remember { PaintNative }
    val started = remember { ctx.nativeInit(cw, ch) }
    DisposableEffect(Unit) { onDispose { ctx.nativeDestroy() } }

    // ── D6-1/2/3 状态 ──
    var zoom by remember { mutableFloatStateOf(1f) }            // D6-2 缩放（[1,8]，clampZoom）
    var strokeActive by remember { mutableStateOf(false) }      // 笔画守卫：画中不改参/改色
    var panelExpanded by remember { mutableStateOf(false) }     // 调试面板默认收起
    var brushColor by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f, 1f)) }  // D6-3 颜色，初值黑色不透明
    val settingValues = remember {
        mutableStateMapOf<Int, Float>().apply { BRUSH_SETTINGS.forEach { put(it.id, it.default) } }
    }
    // pointerInput 协程内读取最新 zoom（rememberUpdatedState 语义，与 currentDisplayPx 一致）
    val currentZoom by rememberUpdatedState(zoom)

    // 双缓冲（P7-3）：两块预分配 Bitmap 交替引用，零每帧分配（2×3.1MB≈6.2MB 可忽略）。
    // 读回/copy 在后台线程写「当前未显示」的 back 缓冲，主线程只做 `bitmap = back` 引用交换，
    // front/back 永不并发触碰，无需锁；交替新引用使 `mutableStateOf` 判等恒 false → 必重绘。
    // direct buffer 由 Java 持有，JNI 经 GetDirectBufferAddress 让 SDK 直接写入其内存，
    // 消灭每帧 std::vector/NewByteArray/SetByteArrayRegion 的分配与 3.1MB 拷贝。
    val bmpA = remember { Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888) }
    val bmpB = remember { Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888) }
    val rbBuf = remember { ByteBuffer.allocateDirect(cw * ch * 4) }
    // 单线程后台 dispatcher（P7-3）：串行化 nativeReadback+copyPixelsFromBuffer，避免并发触碰
    // rbBuf/back 位图。limitedParallelism(1) 是 Dispatchers.IO 的视图，无需 close（不泄漏 Executor）。
    val readbackDispatcher = remember { Dispatchers.IO.limitedParallelism(1) }

    // vsync 循环：只保留 HUD 计数（fps/frameMs）。readback 已移出 vsync 回调
    // （bugfix-frame-loop-vsync-decouple），回调内不再有重活，frameMs 反映纯 vsync 节拍
    // （用户 Bug #1：绘制中不掉到 ~20ms）。
    LaunchedEffect(Unit) {
        var frames = 0; var last = System.nanoTime()
        while (true) {
            withFrameNanos { now ->
                frames++
                if (now - last >= 500_000_000L) {
                    fps = frames * 1e9f / (now - last).toFloat()
                    frameMs = 1000f / (if (fps > 0f) fps else 1f)
                    frames = 0; last = now
                }
            }
        }
    }

    // 读回 worker（bugfix：从 vsync 回调移出，输入驱动 + 节流，解耦事件处理节奏）。
    // snapshotFlow { dirty }：gesture 置 dirty 即唤醒（主线程空闲窗口执行，非 vsync 相位）；
    // collect 内用 deadline 等待（delay(scheduler.timeUntilReadableNs())）代替忙轮询。
    // 失败重试：失败不清 pending，retry 回到循环头重试（保持旧「下次节拍重试」语义，
    // 避免 snapshotFlow 因 dirty 值不变不再重发导致画布停更）。
    // P7-3：nativeReadback + copyPixelsFromBuffer（3.1MB memcpy）包进单线程后台 dispatcher，
    // 移出主线程关键路径（30fps 根因，见 p7-2-android-fps-measure-gotcha）；scheduler 状态
    // 读写仍全部收敛在主线程（后台线程不触碰 scheduler），无需加锁。
    LaunchedEffect(Unit) {
        snapshotFlow { dirty }.collect { isDirty ->
            // dirty=false 是成功读回路径自己清的：此时快照已上屏，无需再读回。
            // 不判 isDirty 会在每次成功读回后（false 发射）再冗余读回一次，把读回速率翻倍、
            // 每读回一次 requestFlush→快照刷新，弱 GPU 上反更掉帧。
            if (!isDirty) return@collect
            var retry = true
            while (retry) {
                // 循环头：deadline 等待（节流，非忙轮询）。timeUntilReadableMs() 已做 ns→ms
                // ceil 换算（delay 按毫秒接收），避免 16ms 被当 16 亿 ms 的灾难等待。
                val waitMs = scheduler.timeUntilReadableMs()
                if (waitMs > 0) delay(waitMs)
                // 后台缓冲 = 当前未显示的那块（backBufferFor：bitmap===bmpA ? bmpB : bmpA，
                // 首帧 bitmap==null → bmpA）。与 Compose 正在绘制的 front 严格隔离。
                val back = backBufferFor(bitmap, bmpA, bmpB)
                // P7-2：不再显式调用阻塞 nativeFlush()——nativeReadback 内部的
                // dgcReadbackPixels 已经会对渲染线程做非阻塞 catch-up（P7-1 起，
                // P7-2 增加节流避免打散批量 composite），显式先 flush 再读回是
                // 重复且阻塞的（此前 Android 7fps 回归根因，见
                // docs/tasks/detail/PC-Android真机性能瓶颈修复.md 背景）。
                val (rc, ms) = withContext(readbackDispatcher) {
                    val rb0 = System.nanoTime()
                    val r = ctx.nativeReadback(rbBuf)
                    val rb1 = System.nanoTime()
                    val m = (rb1 - rb0) / 1_000_000f
                    if (r == 0) {
                        rbBuf.rewind()
                        back.copyPixelsFromBuffer(rbBuf)   // 3.1MB memcpy 在后台线程
                    }
                    r to m
                }
                readMs = ms                                      // 回主线程再写 Compose 状态
                if (rc == 0) {
                    bitmap = back               // 交替新引用 → == 恒不等 → 必重组/重绘
                    scheduler.onReadbackComplete()
                    dirty = false
                    retry = false
                } else {
                    lastError = "readback failed rc=$rc"
                    // 失败：pending 不清（onReadbackComplete 未调）→ 回到循环头重试
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
                            strokeActive = true
                            dirty = true
                            // 屏幕像素 → 缩放画布坐标（zoom=1 时退化为未缩放映射）
                            val (cx, cy) = mapScreenToCanvasZoomed(
                                offset.x, offset.y,
                                currentDisplayPx.width.toFloat(), currentDisplayPx.height.toFloat(),
                                cw.toFloat(), ch.toFloat(), currentZoom,
                            )
                            ctx.nativeStrokeBegin(cx, cy, 0.5f)
                            scheduler.onInput()   // 输入到达即申请读回（非 vsync 相位）
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            strokeActive = true
                            dirty = true
                            val (cx, cy) = mapScreenToCanvasZoomed(
                                change.position.x, change.position.y,
                                currentDisplayPx.width.toFloat(), currentDisplayPx.height.toFloat(),
                                cw.toFloat(), ch.toFloat(), currentZoom,
                            )
                            ctx.nativeStrokeTo(cx, cy, 0.5f)
                            scheduler.onInput()   // 输入到达即申请读回（非 vsync 相位）
                        },
                        onDragEnd = {
                            dirty = true
                            strokeActive = false
                            ctx.nativeStrokeEnd()
                            scheduler.onStrokeEnd()   // 抬笔：排空后补一次最终读回
                        },
                        onDragCancel = {
                            dirty = true
                            strokeActive = false
                            ctx.nativeStrokeEnd()
                            scheduler.onStrokeEnd()   // 取消：同样收尾读回
                        },
                    )
                },
        ) {
            val front = bitmap
            if (front != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 双缓冲交替引用：bitmap 在 bmpA/bmpB 间交替写不同实例，`==` 恒不等 →
                    // 每次读回必触发重组/重绘（替代已删除的 frameVersion 显式重绘信号）。
                    val img = front.asImageBitmap()
                    val dstW = this.size.width.toInt()
                    val dstH = this.size.height.toInt()
                    if (zoom > 1f) {
                        // D6-2 缩放：只采样居中视口子矩形并放大铺满显示区（等价 PC UV 子矩形）
                        val (vx, vy, vw, vh) = computeCanvasViewport(cw.toFloat(), ch.toFloat(), zoom)
                        drawImage(
                            image = img,
                            srcOffset = IntOffset(vx.toInt(), vy.toInt()),
                            srcSize = IntSize(vw.toInt().coerceAtLeast(1), vh.toInt().coerceAtLeast(1)),
                            dstSize = IntSize(dstW, dstH),
                        )
                    } else {
                        drawImage(img, dstSize = IntSize(dstW, dstH))
                    }
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
            // D6-1/2/3 调试面板开关（右上角）：展开/收起。
            Text(
                text = if (panelExpanded) "× 收起" else "⚙ 参数",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .background(Color(0x66000000), RoundedCornerShape(8.dp))
                    .clickable { panelExpanded = !panelExpanded }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )

            // D6-1/2/3 调试面板：画笔参数 12 滑杆 + 颜色 4 滑杆 + 缩放/清空。半透明可滚动，
            // 默认收起不挡画布。设置/颜色改动仅在 !strokeActive（笔画之间）时下发（复刻 PC）。
            if (panelExpanded) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(top = 56.dp, end = 12.dp)   // 让出右上角开关按钮
                        .widthIn(max = 420.dp)
                        .heightIn(max = 640.dp)
                        .verticalScroll(rememberScrollState())
                        .background(Color(0xEE242424), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("画笔参数 (D6-1)", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    if (strokeActive) {
                        Text(
                            "笔画进行中，参数改动会被丢弃",
                            color = Color(0xFFFFB74D),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    // Bug #2：Stroke Modeler 白话引导——用户不知道 4-12 这些 modeler 滑杆干什么。
                    Text(
                        text = "Stroke Modeler 引导：以下参数控制笔迹的平滑/预测引擎（stroke modeler）。" +
                            "数值越大 → 效果见各滑杆说明；改动仅在笔画之间生效，对新笔画生效。",
                        color = Color(0xFFBDBDBD),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    BRUSH_SETTINGS.forEach { spec ->
                        Text(
                            text = "${spec.label}  ${formatSetting(settingValues.getValue(spec.id), if (spec.max < 1f) 5 else 3)}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        // Bug #2：滑杆 label 下方渲染通俗效果说明（次级文本：字号更小、颜色更淡）。
                        Text(
                            text = spec.effect,
                            color = Color(0xFF9E9E9E),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        Slider(
                            value = settingValues.getValue(spec.id),
                            onValueChange = { v ->
                                settingValues[spec.id] = v
                                if (!strokeActive) ctx.nativeSetBrushSetting(spec.id, v.toDouble())
                            },
                            valueRange = spec.min..spec.max,
                        )
                    }
                    HorizontalDivider(color = Color(0x66FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                    Text("笔刷颜色 (D6-3)", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 预览色块 + 读数（Compose 无 ColorEdit4，滑杆+色块是等价替代）
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    Color(brushColor[0], brushColor[1], brushColor[2], brushColor[3]),
                                    RoundedCornerShape(6.dp),
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "R${formatSetting(brushColor[0])} G${formatSetting(brushColor[1])} " +
                                "B${formatSetting(brushColor[2])} A${formatSetting(brushColor[3])}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    listOf("R" to 0, "G" to 1, "B" to 2, "A" to 3).forEach { (name, idx) ->
                        Text(name, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = brushColor[idx],
                            onValueChange = { v ->
                                val next = brushColor.copyOf()
                                next[idx] = v
                                brushColor = next          // 换新数组引用，保证状态触发重组
                                if (!strokeActive) {
                                    ctx.nativeSetBrushColor(next[0], next[1], next[2], next[3])
                                }
                            },
                            valueRange = 0f..1f,
                        )
                    }
                    HorizontalDivider(color = Color(0x66FFFFFF), modifier = Modifier.padding(vertical = 6.dp))
                    Text("画布操作 (D6-2)", color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text("缩放 %.2fx".format(zoom), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { zoom = clampZoom(zoom * 1.25f) }) { Text("＋") }
                        Button(onClick = { zoom = clampZoom(zoom / 1.25f) }) { Text("－") }
                        Button(onClick = { zoom = 1f }) { Text("重置") }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 清空顺序为正确性关键（与 PC D6-2 一致）：
                    //   1) 若有进行中笔画先强制结束，避免半笔画残留
                    //   2) nativeFlush 排空已提交未合成的笔画（反序会 clear 后残留笔迹回写）
                    //   3) nativeClear 清成纸白（与 nativeInit 初始色一致）
                    //   4) dirty=true 让帧循环下一次读回拿到干净画布（无需额外读回）
                    Button(
                        onClick = {
                            if (strokeActive) { ctx.nativeStrokeEnd(); strokeActive = false }
                            ctx.nativeFlush()
                            ctx.nativeClear(0.96f, 0.95f, 0.91f, 1.0f)
                            dirty = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                    ) { Text("清空画布") }
                }
            }
        }
    }
}

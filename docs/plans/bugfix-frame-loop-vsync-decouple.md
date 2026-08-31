# 修复计划：帧循环 readback 解耦 vsync（绘制掉帧 + 事件延迟量化）

> 触发：`/bugfix-pipeline`（2026-08-30）。根因规格：`docs/specs/bugfix-frame-loop-vsync-decouple.md`。
> 走审阅门（≥80）→ 修复（TDD 先红后绿）→ 测试门（100）→ 收尾。SDK 侧零改动。

## 0. Bug 报告（用户原话）

1. 现在 Frame 一绘制，时间就跳到 20ms。直接帧数爆降。
2. 仍旧被垂直同步了。我需要事件处理必须不能按照垂直同步的处理 delta 来。不然延迟感明显。
3. 渲染上屏这一步的 Readback 已经稳定 0.6 ms，修改上述内容时，不要影响到 readback 的延迟。

## 1. 根因（摘要，详见 spec §3）

`PaintScreen.kt:142-173` 帧循环把 readback 绑死在 `withFrameNanos`（vsync）回调内：

- **事件→读回→上屏以 vsync 为节拍** → 延迟被 vsync 相位量化（#2）。
- **readback + copyPixelsFromBuffer + 重组 + drawImage 全在 vsync 回调内**，且每帧 readback 的
  `requestFlush` 逼渲染线程 composite + 快照刷新（GPU 工作）→ 弱 GPU 上帧预算被打爆 → frameMs 跳到
  20ms（#1）。
- **`bitmap = bmp` 同引用不触发 Compose 重组**（红绘依赖 `readMs` 每帧被写而巧合触发）→ 放大延迟感。

SDK 侧 `dgcReadbackPixels` 已是非阻塞纯 memcpy（0.6ms），**零改动**（保护 #3）。

## 2. 修复方案（app 侧）

### 2.1 新增 `ReadbackScheduler`（纯 Kotlin，可 JVM 无头测试）

新文件 `app/src/main/java/com/dgcamp/paint/ui/ReadbackScheduler.kt`：

```kotlin
class ReadbackScheduler(
    private val minIntervalNs: Long = DEFAULT_MIN_INTERVAL_NS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    companion object {
        // 16ms ≈ 60Hz：输入驱动读回的最密节流。**须 ≥ 单帧预算**——低于显示刷新率时读回速率
        // 会跟随输入速率（60–120Hz）超过 vsync，把「每读回一次 → requestFlush → 一次全画布
        // GPU 快照刷新」的 GPU 竞争推到 ≥ 刷新率，弱 GPU 上反更掉帧（review 反馈 2）。
        // 16ms 保证正常输入下读回 ≤60Hz（不劣于旧 vsync 对齐节拍），同时首读回仍输入即时、
        // 不与 vsync 相位锁定。高刷真机可调低。
        const val DEFAULT_MIN_INTERVAL_NS = 16_000_000L
    }
    private var lastReadNs = Long.MIN_VALUE / 2
    private var pending = false
    private var version = 0

    fun onInput() { pending = true }            // gesture 输入到达：申请读回
    fun onStrokeEnd() { pending = true }        // 抬笔：排空后须补一次最终读回
    fun shouldReadbackNow(): Boolean =          // 输入驱动 + 最小间隔节流，不受 vsync 相位约束
        pending && nowNs() - lastReadNs >= minIntervalNs
    /** 距下次可读回的剩余等待（ns，≥0）。供 worker 用 deadline 等待而非忙轮询（review 反馈 1）。 */
    fun timeUntilReadableNs(): Long =
        if (!pending) 0L else (minIntervalNs - (nowNs() - lastReadNs)).coerceAtLeast(0L)
    /** 距下次可读回的剩余等待（毫秒，ceil，≥0）。worker 的 `delay()` 按毫秒接收，**必须**用它，
     * 避免把纳秒当毫秒传（曾致 16ms 被当成 16 亿 ms 的灾难等待，test 门反馈补齐）。 */
    fun timeUntilReadableMs(): Long =
        (timeUntilReadableNs() + 999_999L) / 1_000_000L
    fun onReadbackComplete() {                  // 读回完成：记时、清 pending、bump 重绘版本
        lastReadNs = nowNs(); pending = false; version++
    }
    fun version(): Int = version
    fun isThrottled(): Boolean = pending && !shouldReadbackNow()
}
```

语义要点：
- **输入驱动**：`onInput()` 后 `shouldReadbackNow()` 立即为 true（首帧读回不受 vsync 相位约束）。
- **节流**：`minIntervalNs` 防止高频输入把读回风暴打进渲染线程（也保护弱 GPU）。
- **显式重绘版本**：`version()` 每读回 +1，作为 Compose 强制重绘信号（修复 `bitmap=bmp` 同引用
  不重组的潜在 bug）。

### 2.2 改造 `PaintScreen.kt` 帧循环

1. 保留单个复用 `bmp`/`rbBuf`（零分配读回，不回归）。新增状态：`scheduler`、`frameVersion`
   （`mutableIntStateOf`）、保留 `dirty`。
2. **gesture 回调**（`onDragStart/onDrag/onDragEnd/onDragCancel`）在现有 `nativeStrokeBegin/To/End`
   后追加 `scheduler.onInput()`（end/cancel 用 `onStrokeEnd()`）。`dirty = true` 保留。
3. **读回 worker**（新 `LaunchedEffect`，**不在 vsync 回调内**）：
   - `snapshotFlow { dirty }` 输入置 dirty 即唤醒（主线程空闲窗口执行，非 vsync 相位）；collect
     内用 **deadline 等待**（`delay(scheduler.timeUntilReadableNs())`）代替忙轮询（review 反馈 1）。
   - 执行 `ctx.nativeReadback(rbBuf)` → 成功则 `rbBuf.rewind(); bmp.copyPixelsFromBuffer(rbBuf);
     bitmap = bmp; frameVersion++; scheduler.onReadbackComplete(); dirty = false`；失败记 `lastError`。
   - **失败重试**（review 反馈 1）：读回失败后**不**清 pending，worker 用 `retry` 标志回到循环头
     重试（保持旧实现「下次节拍重试」语义，避免 snapshotFlow 因 dirty 值不变不再重发导致画布停更）。
   - `readMs` 在此测（仍在读回路径上，不随修复改变 0.6ms 语义）。
4. **vsync 循环**（原 `while(true){ withFrameNanos }`）**只保留 HUD 计数**（fps/frameMs），
   **移除 readback**——vsync 回调内不再有重活，frameMs 反映纯 vsync 节拍（#1）。
5. **Canvas** draw 块首行读取 `frameVersion`（订阅变化 → 强制重绘同引用 bitmap）。

### 2.3 不做的事（范围约束）

- **不改 SDK**（engine/vk_backend/C API 零改动，readback 仍 0.6ms memcpy）。
- **不新建线程**：readback worker 仍是主线程协程（gesture 与 Compose 状态本就在主线程，无跨线程
  JNI/状态竞争）。
- **不把显示移出 Compose**：上屏仍由 Compose 于 vsync 呈现（平台现实），本修复只解耦
  **事件→读回**处理节奏。

## 3. 回归用例设计（先红后绿，全部无头）

### 3.1 App JVM 用例 `ReadbackSchedulerTest`（新增，`app/src/test/`，对齐 CoordsTest 风格）

用注入 `nowNs` 的假时钟测纯逻辑：

1. **`input triggers immediate readback not gated by vsync`**：
   假时钟 T0，`onInput()` → 断言 `shouldReadbackNow()==true`（读回立即申请，不等 vsync）。
   **红（现状）**：旧实现在 vsync 回调内读回，无「输入即读回」语义 → 断言失败。
   **绿（修复后）**：scheduler 存在且语义成立。
2. **`readbacks throttled to min interval`**：T0 `onInput`+读回；T0+3ms `onInput` → 断言
   `shouldReadbackNow()==false`（节流）；T0+8ms（≥间隔）`onInput` → true。
3. **`version bumps on each readback forces redraw`**：两次读回 → `version()` 递增（修复
   `bitmap=bmp` 同引用不重绘）。
4. **`stroke end requests final readback`**：`onStrokeEnd()` → `shouldReadbackNow()==true`。
5. **`throttle window keeps pending`**：节流期内 `isThrottled()==true`，`shouldReadbackNow()==false`，
   且 `timeUntilReadableNs()>0`（deadline 而非忙轮询）。
6. **`deadline wait reaches readable exactly at interval`**：T0 读回 → T0+8ms `timeUntilReadableNs()≈
   8ms`；T0+16ms → `shouldReadbackNow()==true` 且 `timeUntilReadableNs()==0`。
7. **`pending survives without consume`**：`onInput()` 后未读回前重复 `onInput()`，`pending` 仍待读回
   （输入不丢失）；`onReadbackComplete()` 后才清 pending（失败重试语义基础）。
8. **`time until readable converts ns to ms with ceil`**（test 门反馈补）：`timeUntilReadableMs()` 对
   纳秒余量 ceil 到毫秒（8ms−1ns → 1ms，防 `delay(ns)` 当 ms 用 → 16ms 变 16 亿 ms；也防亚毫秒
   忙轮询），pending 清空 → 0。

### 3.2 SDK 回归套件（不改动，作为 #3 护栏，全绿）

`test_perf_regression` / `test_continuous_input_regression` / `test_snapshot_refresh_throttle` /
`test_readback_drain` / `test_midstroke_readback` —— readback 仍 0.6ms 量级纯 memcpy、非阻塞、
快照节流不回归。

### 3.3 离屏执行图像（涉及渲染硬约束）

`cli/dgc_cli run <script.json> --out <png>` 渲染笔迹 PNG（已产出 `/tmp/dgc_repro/stroke2.png`，
1080×720 valid），作为无头渲染验收基线；修复后重跑 SDK 回归 + App 单测即可确认渲染路径未受影响。

## 4. 影响面核对

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/dgcamp/paint/ui/ReadbackScheduler.kt` | **新增**（纯 Kotlin） |
| `app/src/main/java/com/dgcamp/paint/ui/PaintScreen.kt` | 帧循环重构：gesture 接 scheduler、读回 worker 移出 vsync、vsync 循环只留 HUD、Canvas 订阅 frameVersion |
| `app/src/test/java/com/dgcamp/paint/ui/ReadbackSchedulerTest.kt` | **新增**（回归） |
| SDK 全部 / `MainActivity.kt` | 零改动（MainActivity 帧率解锁注释可顺带补一句方案 (c) 已落地，非必须） |

## 5. 验证方式

1. `./gradlew :app:testDebugUnitTest --tests "*ReadbackSchedulerTest"` —— 新增回归绿（先红后绿可查记录）。
2. `sdk/build/host-linux` 下跑 §3.2 五个 SDK 回归 —— 全绿，证明 #3（readback 不回归）。
3. `cli/dgc_cli run` 重出离屏 PNG —— 渲染路径正常。
4. `./gradlew :app:assembleDebug` —— 编译门（真机量化帧率留给真机复核，交付标注「待真机确认」）。

## 6. 风险与边界

- **节流值调优**：16ms（60Hz）为默认（review 反馈 2：须 ≥ 单帧预算才能真正压读回/快照刷新频率）。
  弱 GPU 真机仍掉帧 → 继续增大；高刷真机求低延迟 → 可调低至 8ms。README/FPS 浮层可量化。
- **读回失败重试**（review 反馈 1）：失败不清 pending、worker retry 重试，维持旧「下次节拍重试」，
  不引入「瞬时失败后画布停更」回归。
- **接线正确性测试盲区**（review 反馈 3）：scheduler 纯逻辑可无头单测，但 gesture→`onInput()` 的
  Compose 接线无法无头跑（pointerInput 需真机）；以 `assembleDebug` 编译门 + 代码审读兜底，并在
  交付标注。worker 的 dirty→读回→version 决策已收敛进 scheduler（纯函数），可测。
- **真机掉帧兜底**（review 反馈 4）：若 #1 真机仍掉帧，优先核验读回 worker 是否跨 vsync 边界；
  必要时把 `copyPixelsFromBuffer`（3.1MB CPU 拷贝）挪到后台线程（JNI direct buffer 写 +
  `Bitmap.copyPixelsFromBuffer` 线程安全，主线程只做 Compose 状态提交），主线程负担进一步下降。
  属计划外追加项，需另行审阅，不在本期范围。
- **红绘信号**：`frameVersion++` 保证同引用 bitmap 强制重绘；读回 worker 与 gesture 同主线程，
  无并发写 bmp/rbBuf 竞争。
- **读回滞后**：`≤1 攒批` 滞后是 SDK 既有契约（快照在完整 composite 后发布），本修复不改变；
  抬笔后 `onStrokeEnd` 补最终读回覆盖尾部。

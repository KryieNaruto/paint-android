# 根因规格：绘制掉帧（Frame→20ms）+ 事件处理被 vsync 量化

> 触发：`/bugfix-pipeline`（2026-08-30）。对应计划：`docs/plans/bugfix-frame-loop-vsync-decouple.md`。
> 范围：paint-android 消费端帧循环。SDK 侧**不改**（readback 已稳定 0.6ms，见 §4 基线）。

## 1. Bug 报告（用户原话）

1. 现在 Frame 一绘制，时间就跳到 20ms。直接帧数爆降。
2. 仍旧被垂直同步了。我需要事件处理必须不能按照垂直同步的处理 delta 来。不然延迟感明显。
3. 渲染上屏这一步的 Readback 已经稳定 0.6 ms，修改上述内容时，不要影响到 readback 的延迟。

## 2. 无头复现

### 2.1 SDK 侧基线（确认 SDK readback 路径健康，为 #3 提供护栏证据）

- `sdk/build/host-linux/tests/test_perf_regression`：快速甩笔 + 每帧读回，`frames=300 avg=0.44ms p95=0.85ms max=2.13ms`，无单帧 >20ms，PASS。
- `sdk/build/host-linux/tests/test_continuous_input_regression`：producer 连续输入 + reader 持续 `dgcReadbackPixels`，`maxCallMs=0.211ms`（纯 memcpy 快照读），PASS。
- `sdk/build/host-linux/tests/test_snapshot_refresh_throttle`：`snapshotRefreshCount=3 / compositeCount=22 (ratio 0.136)` —— 快照刷新已节流到「消费者请求/结算时」，PASS。
- 离屏执行图像：`cli/dgc_cli run` 脚本 → 1080×720 PNG（`/tmp/dgc_repro/stroke2.png`），笔迹可见，valid。

**结论**：SDK 侧 `dgcReadbackPixels` = 一次原子 `requestFlush` + `VkBackend::readback` 纯 memcpy（`vk_backend.cpp:1075`，读渲染线程维护的快照缓存 `cache_`）。readback 调用本身恒快（0.6ms 量级）、非阻塞、不随 composite 批大小波动。**本 bug 不在 SDK，SDK 侧无需也**不**应改动（保护 #3）。**

### 2.2 App 侧缺陷（无头不可直接跑 Compose，以纯 Kotlin 形式化）

`app/src/main/java/com/dgcamp/paint/ui/PaintScreen.kt:142-173` 帧循环：

```kotlin
LaunchedEffect(Unit) {
    var frames = 0; var last = System.nanoTime()
    while (true) {
        withFrameNanos { now ->            // ← 由 Choreographer/vsync 驱动
            if (dirty) {
                val rb0 = System.nanoTime()
                val rc = ctx.nativeReadback(rbBuf)   // ← readback 绑死在 vsync 回调内
                val rb1 = System.nanoTime()
                readMs = (rb1 - rb0) / 1_000_000f
                if (rc == 0) {
                    rbBuf.rewind(); bmp.copyPixelsFromBuffer(rbBuf)
                    bitmap = bmp            // ← 同引用（remember 复用），不触发 Compose 重组
                    dirty = false
                }
            }
            frames++
            if (now - last >= 500_000_000L) { fps = ...; frameMs = ... }
        }
    }
}
```

三个缺陷叠在一起：

1. **readback 以 vsync 为节拍（#2）**：`withFrameNanos` 只在下一次 vsync 回调里才执行 readback。事件（gesture）在 T 时刻到达 → 最坏等一个 vsync 才读回 → 再等一个 vsync 才上屏。事件→读回→上屏全程量化到 vsync 相位，产生明显延迟感。
2. **readback 的重活在 vsync 回调内执行（#1）**：vsync 回调里做 `nativeReadback`（0.6ms）+ `copyPixelsFromBuffer`（3.1MB memcpy）+ 状态写入触发重组 + `drawImage`。且每帧 readback 的 `requestFlush` 逼渲染线程 composite + 快照刷新（GPU 工作）。弱 GPU / 软渲染模拟器上这些工作挤爆单帧预算 → 掉帧，frameMs 跳到 20ms（50fps）。
3. **`bitmap = bmp` 同引用不触发重组（潜在红绘 bug）**：`remember { mutableStateOf<Bitmap?>(null) }` 首帧置入后，之后每次 `bitmap = bmp` 引用相等 → Compose 判定无变化 → 不重组。绘制期间画布能逐帧刷新，只是**恰好**因为 `readMs` 每帧被写（值变化）顺带触发重组。一旦 readMs 连续两帧数值相同（时序稳定时会发生），画布就停更，笔迹滞后感进一步放大。

### 2.3 前次修复为何无效（#2 的"仍旧"）

`MainActivity.requestMaxRefreshRate()`（commit 41a2625，方案 (a)）只把 `preferredDisplayModeId` 指到最高刷新率模式，**仍是 vsync 上屏**（纯 Compose 由 Choreographer 驱动）。注释自认「无法突破面板物理刷新率上限」且「纯 Compose 上屏由窗口 vsync 驱动」。方案 (c)（把读回循环移出 withFrameNanos）在上一份计划中被以「不改变 Compose 上屏仍以 vsync 为节拍，对感知帧率无增量」为由**未做**——但用户现在明确要求的就是它：事件处理不得按 vsync delta 来。

## 3. 根因

**app 侧读回+上屏循环整体耦合到 Compose vsync 时钟（`withFrameNanos`），且 readback 在 vsync 回调内部执行。** 由此：

- 事件→读回的延迟被 vsync 相位量化（#2）；
- readback 及其逼出的 composite/快照刷新 GPU 工作落在 vsync 帧预算内（#1）；
- 同引用 bitmap 无显式重绘信号，红绘依赖巧合（放大 #2）。

SDK 三线程（input/brush/render）本身与 vsync 完全解耦、批量化正确；`VkBackend::readback` 是纯 memcpy 快照读（0.6ms，#3 基线）。**改动只在消费端 PaintScreen 帧循环。**

## 4. 影响面

| 调用方 | 是否受影响 | 说明 |
|---|---|---|
| `PaintScreen.kt` 帧循环 | **是（修复点）** | readback 移出 vsync 回调，改输入驱动 + 节流 |
| `MainActivity.kt` | 否（仅注释/说明可顺带更新） | 帧率解锁仍是 vsync 上屏，本修复不推翻 |
| SDK（`engine.cpp` / `vk_backend.cpp` / C API） | **否（零改动）** | readback 路径原样保留，保护 #3 |
| `paint-pc` / CLI / 其它 SDK 消费者 | 否 | 本次只改 paint-android 消费端 |

## 5. 验收口径（可度量）

- **#1**：帧循环 vsync 回调内不再出现 readback / copyPixelsFromBuffer / 大状态写入；`frameMs` 反映纯 vsync 节拍，绘制中不掉到 ~20ms（真机量化；无头以 JVM 单测断言「readback 不再由 vsync 驱动」）。
- **#2**：`ReadbackScheduler` 输入驱动：输入到达即申请读回（不受 vsync 相位约束）、最小间隔节流；JVM 单测断言该语义。
- **#3**：SDK 回归用例全绿（test_perf_regression / test_continuous_input_regression / test_snapshot_refresh_throttle / test_readback_drain / test_midstroke_readback），readback 仍 0.6ms 量级纯 memcpy，SDK 零改动。

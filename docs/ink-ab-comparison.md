# A8-1 · Jetpack Ink 渲染 A/B 对照报告

> 状态：**代码已落位，真机延迟/手感数据待真机确认（人工验收项）**。
> 本报告记录 A/B 对照口径、minSdk 结论、已知限制与「是否换渲染」建议；延迟/手感数字在真机采数后回填（下表占位处标注「待真机确认」）。

## 1. 对照对象

| | Mode A（SDK 基线） | Mode B（Jetpack Ink） |
|---|---|---|
| 渲染路径 | Vulkan 离屏 → readback（3.1MB memcpy）→ 贴图 | 矢量 mesh 低延迟上屏（HWUI 前缓冲） |
| 输入→上屏 | 输入→JNI→SDK modeler→离屏渲染→readback→贴图 | 输入→ink 内置 modeler→mesh 实时上屏 |
| readback | 有（HOST_CACHED + invalidate，后台线程） | 无 |
| modeler | SDK `core/stroke_predictor` | ink 内置 modeler（同算法，B1-5 白盒移植结论） |
| 开关 | 应用内顶部「渲染: SDK/INK」一次点击即切 | 同左 |

**混杂声明（取舍-2）**：对照目标是「渲染路径延迟」，但两模式 modeler 各自实现（虽已核实同算法），延迟差含「渲染 + modeler」综合差异，非纯渲染差异。预计 modeler 贡献可忽略（同算法），但本报告不宣称「纯渲染差异」。

## 2. minSdk 结论（取舍-1，已实测）

- 实测：解包 ink 1.0.0 各 `-android` AAR 的 `AndroidManifest.xml`，`uses-sdk android:minSdkVersion="23"`（Android 6.0）。
- 本项目 `minSdk = 26` > 23，**无需 bump minSdk，也无需 API 门控**。
- `android.graphics.Mesh`（API 34）仅 ink-rendering 内部 `CanvasMeshRenderer` 的**快路径**使用；API < 34 时 ink 自动回退 `Canvas.drawPath`（`CanvasMeshSupport` 内部按 `Build.VERSION` 门控），非本仓库责任。INK 模式在 API 26–33 设备上功能可用（走 path 渲染，延迟略高于 API 34 的 mesh 快路径）。
- **决策**：保持 `minSdk = 26`（方案 B 门控在此不必要，因 ink minSdk 已低于本项目 minSdk）。

## 3. 延迟/帧时量化口径（取舍-3）

- `FrameTimeAccumulator`：逐帧耗时样本（vsync 循环 `withFrameNanos` 相邻帧差），输出 p50/p99 分位帧时（最近秩分位）。
- `LatencyProbe`（**代理，非严格 input-to-photon**）：输入时刻（事件 `uptimeMillis`）→「帧就绪」时刻的差值，取均值。
  - SDK 模式：「帧就绪」= readback 成功（`bitmap = back` 之后）——读回完成才真正有新帧可上屏。
  - INK 模式：「帧就绪」= 下一 vsync 帧（ink 无 readback，输入即实时上屏）。
- 代理精度限制：不包含真实光子到屏幕的延迟；真机手感以人工记录为准，代理值不作真实端到端延迟。

## 4. 自动化验收结果（host，可复现）

| 项 | 结果 | 命令 |
|---|---|---|
| 构建 | ✅ 通过 | `./gradlew :app:assembleDebug` |
| 单测 | ✅ 全绿（LatencyMetricsTest 7 用例） | `./gradlew :app:testDebugUnitTest --tests "LatencyMetricsTest"` |
| SDK 零 diff | ✅ 零 diff（Mode A 原样保留，SDK submodule 未触碰） | `git diff --stat sdk/` |
| host PNG（SDK 基线） | ⏳ 用 sdk submodule 当前 commit（`be25725`）构建的 `dgc_cli` 离屏 → PNG | 见 §7 |

## 5. 延迟对比表（**待真机确认**）

> 采数口径（Task 5）：同一设备（记录型号/刷新率），两模式各连续画 20s，各 3 轮取中位。

| 指标 | Mode A（SDK） | Mode B（INK） | 备注 |
|---|---|---|---|
| 设备型号/刷新率 | 待真机确认 | 待真机确认 | |
| Frame p50 | 待真机确认 | 待真机确认 | HUD `Frame p50` |
| Frame p99 | 待真机确认 | 待真机确认 | HUD `Frame p99` |
| Readback ms | 待真机确认 | n/a（无 readback） | |
| 输入→帧 lag（代理均值） | 待真机确认 | 待真机确认 | HUD `输入→帧 lag` |
| 手感（领先/滞后/抖动） | 待真机确认 | 待真机确认 | 同一手势两模式各画 |

## 6. 手感对照（**待人工**）

同一手势（快速直线/圈/折线/快速点画）两模式各画，人工记录「领先 / 滞后 / 抖动」：

| 手势 | Mode A（SDK） | Mode B（INK） |
|---|---|---|
| 快速直线 | 待人工 | 待人工 |
| 圈 | 待人工 | 待人工 |
| 折线 | 待人工 | 待人工 |
| 快速点画 | 待人工 | 待人工 |

## 7. 离屏输出（R5 硬约束）

- **SDK 基线（host）**：用 sdk submodule **当前 commit `be25725`** 构建的 `dgc_cli`（`cmake --build build/host-linux`）离屏 → PNG。**禁止**用 demo 主仓更新版二进制，避免「sdk 零 diff」口径漂移（FEEDBACK #3）。
- **ink 侧（on-device）**：INK 模式「导出 PNG」按钮 → 累积 `onStrokesFinished` 笔画 → `InkPngExporter`（`CanvasStrokeRenderer.draw` → 离屏 `Bitmap` → `compress(PNG)`）→ `filesDir/ink_snapshot.png`。落盘需真机点按导出后人工确认文件。

## 8. 已知限制

1. **清空画布不清理进行中湿笔画**：`InProgressStrokes` 不暴露程序化 clear，`clearCanvas` 在 INK 模式仅清空已完成笔画累计（`inkFinishedStrokes.clear()`），进行中笔画需抬笔自然结束。属 ink API 边界，非本对照结论干扰项。
2. **延迟代理非 input-to-photon**（取舍-3）：两模式代理口径一致可比，但不等于真实端到端延迟。
3. **模型器混杂**（取舍-2）：延迟差含 modeler 实现微差，预计可忽略。
4. **ink 刷尺寸为屏幕像素基准**：`InProgressStrokes` 默认 identity 变换（stroke 坐标 = 屏幕像素），与 SDK 的 1080×720 逻辑画布 + 缩放上屏路径基准不同，两模式笔触粗细非严格逐像素对齐（不影响「能画/延迟对照」验收）。

## 9. 是否换渲染的结论建议

> 待真机数据回填后定稿。初步判断（基于架构，非实测结论）：

- ink 路径**天然无 readback、无 3.1MB memcpy**，输入到帧延迟应显著低于 SDK 路径——若真机 lag 代理 + 手感确认 ink 领先，则「以 ink 作为交互态低延迟渲染、SDK 作为离屏/导出基线」是值得推进的方向（即本对照的预期结论）。
- 但 ink 是 Android-only Compose 侧方案，**不替代** SDK 的跨平台离屏/导出能力；两者是「并行路径」而非「替换」，与本任务「对照非替换」定位一致。

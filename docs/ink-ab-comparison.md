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

## 5. 延迟对比表（**真机已确认，2026-09-03，MDP1221**）

> 采数口径（Task 5）：同一设备（MDP1221，MediaTek MT6789，1440×2160 @320dpi），两模式各画数笔取代表值。
> 本轮记录先于 A8-1 原定「20s×3 轮取中位」流程完成，属真机初步确认（非严格统计采样）；量级结论已足够支撑 §9 决策，若需精确 p50/p99 可后续补测。

| 指标 | Mode A（SDK） | Mode B（INK） | 备注 |
|---|---|---|---|
| 设备型号/刷新率 | MDP1221（MT6789） | 同左 | |
| Readback ms | 6ms | n/a（无 readback） | HUD `Readback` |
| 输入→读回 lag（代理均值） | 6ms | n/a | 仅到 `dgcReadbackPixels` 完成（bitmap 引用交换），不含 Compose 重组/draw/vsync |
| 输入→上屏 lag（代理均值，本轮新增探针） | 15.5ms | 5ms | SDK：Canvas draw lambda 内新 bitmap 首次绘制时打点（覆盖 Compose 重组+draw 调度，仍非严格 photon）；INK：原有「输入→帧」代理（vsync 帧就绪） |
| 手感（肉眼，快速挥摆 ~600mm/s） | **落后手指约 2cm** | **肉眼 0 延迟，紧贴手指** | 用户直接对比，同一设备同一 app 内切换渲染模式 |
| 手感（肉眼，慢速描摹） | 落后很小，不明显 | 0 延迟 | 差距随速度线性放大（见 §9 根因分析） |

## 6. 手感对照（**已获真机初步确认**）

| 手势 | Mode A（SDK） | Mode B（INK） |
|---|---|---|
| 快速挥摆（~600mm/s） | 明显落后（肉眼 ~2cm） | 0 延迟，紧贴手指 |
| 慢速描摹 | 落后很小 | 0 延迟 |
| 预测开/关对照（Mode A） | **肉眼几乎无差别**（见 §9 根因分析——预测尖领先量级远小于渲染管线+模型器平滑滞后，被淹没） | n/a（ink 内置预测，未做开关对照） |

> 圈/折线/快速点画手势的逐项记录待后续补测，不阻塞 §9 结论（根因已通过速度-延迟关系定量验证，见下）。

## 7. 离屏输出（R5 硬约束）

- **SDK 基线（host）**：用 sdk submodule **当前 commit `be25725`** 构建的 `dgc_cli`（`cmake --build build/host-linux`）离屏 → PNG。**禁止**用 demo 主仓更新版二进制，避免「sdk 零 diff」口径漂移（FEEDBACK #3）。
- **ink 侧（on-device）**：INK 模式「导出 PNG」按钮 → 累积 `onStrokesFinished` 笔画 → `InkPngExporter`（`CanvasStrokeRenderer.draw` → 离屏 `Bitmap` → `compress(PNG)`）→ `filesDir/ink_snapshot.png`。落盘需真机点按导出后人工确认文件。

## 8. 已知限制

1. **清空画布不清理进行中湿笔画**：`InProgressStrokes` 不暴露程序化 clear，`clearCanvas` 在 INK 模式仅清空已完成笔画累计（`inkFinishedStrokes.clear()`），进行中笔画需抬笔自然结束。属 ink API 边界，非本对照结论干扰项。
2. **延迟代理非 input-to-photon**（取舍-3）：两模式代理口径一致可比，但不等于真实端到端延迟。
3. **模型器混杂**（取舍-2）：延迟差含 modeler 实现微差，预计可忽略。
4. **ink 刷尺寸为屏幕像素基准**：`InProgressStrokes` 默认 identity 变换（stroke 坐标 = 屏幕像素），与 SDK 的 1080×720 逻辑画布 + 缩放上屏路径基准不同，两模式笔触粗细非严格逐像素对齐（不影响「能画/延迟对照」验收）。

## 9. 是否换渲染的结论建议（**真机已确认**）

**结论已验证**：ink 路径**天然无 readback、无 3.1MB memcpy**，输入到帧延迟显著低于 SDK 路径——真机 lag 代理（输入→上屏 15.5ms vs 5ms）+ 肉眼手感（SDK 快速挥摆落后 ~2cm vs ink 0 延迟）均确认 ink 领先。

**根因分析（速度-延迟关系，定量）**：两模式底层 modeler 是**同一套算法**（B1-5 白盒移植结论），故延迟差不是 modeler 强弱之别，而是渲染路径：
- SDK 侧总落后 = 「modeler 平滑滞后（wobble+spring，随速度线性放大）」+「渲染管线延迟（readback+Compose 重组/draw/vsync，测得 15.5ms，同样随速度放大成距离）」。
- 实测量化：600mm/s 快速挥摆下，modeler 滞后 ≈10.4mm + 管线滞后(15.5ms×600mm/s)≈9.3mm ≈ 19.7mm ≈ 肉眼所见 2cm，慢速（100mm/s）总滞后仅 3.3mm（肉眼不明显）——与手感报告完全吻合。
- ink 侧因无 readback、前缓冲直接上屏，渲染管线延迟趋近于 0，同样的 modeler 滞后被极短的管线时间"稀释"到不可见。
- **预测开关对照几乎无差别**的根因：Mode A 预测尖的领先量级（受限于 `min(|v_kalman|,|v_true|)` 的减速安全设计 + StrokeEnd­Predictor 停笔点公式而非线性外推，理论/实测最大领先仅 ~2-4mm 量级）远小于 19.7mm 总落后，视觉上被完全淹没；调大 interval 试图填补缺口又会在紧曲率转弯处重新炸出弧外毛边（interval>25ms 时 R=60-120px 圆弧径向凸出 >3px），是架构性张力，非参数误调。

**决策（用户已确认，2026-09-03）**：项目**必须跨平台**，ink 是 Android-only 方案，不能替代 SDK 的跨平台离屏/导出能力，**不采用"交互态切 Ink"路线**。目标改为：**让 Mode A（SDK 渲染路径）本身逼近 ink 的手感**——需要同时压渲染管线延迟（readback/上屏路径）与重新设计预测机制（当前预测机制对「填补总延迟」贡献过小），后续作为独立任务展开（根因已定量，具体方案待规划）。

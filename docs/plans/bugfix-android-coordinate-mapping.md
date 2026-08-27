# BUGFIX：Android 画笔坐标未映射 → 笔迹与手指不重合（≈3x 缩放）

- 日期：2026-08-26
- 修复对象：**paint-android**（消费仓 app 层）。SDK submodule 与 JNI 不改。
- 现象：手指从屏幕左上角 (0,0) 划到 (100,100)，笔迹却出现在 (0,0)→(300,300)（约 3x），与手指不重合。**PC 已修复，Android 仍不重合。**

---

## 一、根因（已无头复现，见「复现证据」）

坐标链路：触摸 → `PaintScreen.kt` `detectDragGestures` → `nativeStrokeBegin/To` → JNI 直通 → `dgcBeginStroke/StrokeTo` → 1080×720 离屏画布 → 每帧 `dgcReadbackPixels` 读回 → `drawImage(dstSize=屏幕尺寸)` 拉伸铺满上屏。

断点逐层核对：

1. `PaintScreen.kt` 固定 SDK 离屏画布 `cw=1080, ch=720`。
2. `detectDragGestures` 给出的是**屏幕/Composable 像素**坐标（`offset`/`change.position`），**原样**传给 `nativeStrokeBegin/To` —— **没有做屏幕→画布映射**。
3. `paint_android_jni.cpp` 是薄胶水，`nativeStrokeBegin/To` 直接把 x/y 透传给 `dgcBeginStroke/StrokeTo`（设计如此，正确）。
4. SDK 在 1080×720 画布上按给定坐标绘制（确定性，正确）。
5. 上屏 `drawImage(bmp, dstSize = this.size)` 把 1080×720 位图拉伸铺满整个屏幕：画布像素 c 显示在屏幕 `c × (screenW/1080, screenH/720)`。

**净效应**：手指在屏幕 s，笔迹显示在 `s × (screenW/1080, screenH/720)`。除非屏幕尺寸 == 画布尺寸，否则恒不重合。用户 3x 设备 → 输入 (100,100) 显示于 (300,300)。

PC 端已修复：`paint-pc/src/coords.cpp` 的 `MapCursorToCanvas(x,y,contentW,H,canvasW,H) = x×canvasW/contentW`，上屏 `GlCanvas` 拉伸铺满，二者互为逆映射 → 重合。**Android 无对应物**，即缺的正是这一步。

### 复现证据（无头 CLI 离屏渲染）

- 工具：`demo/build/host-linux/cli/dgc_cli`（B3-1 真实内核 + Vulkan 离屏），脚本在 `/tmp/dgc-repro/{broken,fixed}.json`。
- 场景：3x 屏 3240×2160，画布 1080×720，手指 0,0→100,100 → 正确映射应为画布 0,0→33,33。
- 实测墨迹 bbox（PIL 统计）：

| 图 | 行为 | 墨迹 bbox | 上屏显示 | 与手指 |
|---|---|---|---|---|
| `broken_raw.png` | 现状：原样传 (100,100) | x[0,107] y[0,107] | 0,0→300,300 | **差 3x，不重合** |
| `fixed_mapped.png` | 映射后 (33,33) | x[0,40] y[0,40] | 0,0→100,100 | **重合** |

- 标注图（红叉 = 手指 33,33）：`annotated_broken.png` / `annotated_fixed.png`；`_disp3x` 版为拉伸 3x 后的屏幕视图，直观复现用户所见。

---

## 二、影响面

- 受影响的错误调用方：**仅** `PaintScreen.kt` 的 `detectDragGestures`（`onDragStart` / `onDrag`）两处传坐标。
- JNI、SDK 共享代码正确，**不改**；PC 不受影响；无跨平台回归。

---

## 三、修复方案

1. **新增纯函数** `app/src/main/java/com/dgcamp/paint/ui/Coords.kt`：`mapScreenToCanvas(x, y, screenW, screenH, canvasW, canvasH)`，镜像 PC `coords.cpp` 语义：
   - 任一尺寸 ≤ 0 → `(0f, 0f)`（防除零，同 PC 守卫）；
   - 否则 → `(x × canvasW/screenW, y × canvasH/screenH)`。
2. **`PaintScreen.kt` 接线**：
   - 用 `Modifier.onSizeChanged` 捕获**上屏显示尺寸** `displayPx`（与 `drawImage` 的 `dstSize` 同源）；
   - `rememberUpdatedState(displayPx)` 供 `pointerInput` 协程读取最新尺寸；
   - `onDragStart`/`onDrag` 先 `mapScreenToCanvas` 再调 `nativeStrokeBegin/To`。
3. **显示层不动**（PC 同样拉伸铺满）。输入映射与显示缩放互为逆映射 → 笔迹与手指重合。

---

## 四、回归用例设计（先红后绿）

- 位置：`app/src/test/java/com/dgcamp/paint/ui/CoordsTest.kt`（JUnit4）。需在 `app/build.gradle.kts` 的 dependencies 加 `testImplementation("junit:junit:4.13.2")`。
- **RED**：先以「恒等映射桩」`mapScreenToCanvas`（返回原样坐标，复刻现状行为）跑测试：
  - 报障场景：屏 3240×2160、画布 1080×720、触摸 (100,100)。恒等映射 → 画布 (100,100) → 上屏 3x → (300,300) ≠ 触摸 (100,100)，round-trip 断言**失败** → 证明回归用例能捕获当前 bug。
- **GREEN**：实现真实映射后，同测试全绿。
- 用例清单：
  1. 报障场景：`mapScreenToCanvas(100,100,3240,2160,1080,720) == (33.33, 33.33)`；round-trip `显示(map(s)) == (100,100)`。
  2. 通用 round-trip 性质：对屏内任意点，`map` 后再按显示缩放回投 == 原点。
  3. 边界：任一尺寸 ≤ 0 → `(0,0)`。
- 无头运行：`cd paint-android && ./gradlew :app:testDebugUnitTest`（0 失败、0 跳过）。

---

## 五、验证方式（无头）

1. **gradle 单测**：回归用例全绿（0 失败 0 跳过）。
2. **CLI 离屏图像**：重跑 broken/fixed 脚本确认映射后笔迹 bbox 落在手指处（修复前已有证据，修复后复跑对照）。
3. **真机人工验收**（用户侧）：手指与笔迹重合。

---

## 六、风险与健壮性

- 首帧 layout 前 `displayPx` 为 0 → map 返回 (0,0)，与 PC 守卫一致，可接受。
- 上屏仍有宽高比拉伸（画布 3:2 vs 竖屏），属既有行为、**非本次报障范围**；坐标重合不受影响。若后续要等比显示，属独立改动。
- 改动局限在 app 层两个文件 + 一个纯函数文件，无共享代码风险。

# paint-android 消费者

Android UI 消费者仓库：自备 **JNI / Compose / Ink**，把触摸事件送入 SDK C API，再把渲染结果合成到屏幕。

SDK 只给 **C ABI**（`dgc_paint_c_api.h`），不提供 JNI 胶水——JNI 由消费者自备。

**当前状态**：空壳期 —— Compose 画布 + 触摸坐标/压感显示 + JNI 自检（`nativeHello`）。SDK C API 未接入（等 SDK B1-4 落地）。不绘制真实笔迹。

## 拓扑

```
paint-android ──submodule: sdk/──▶ KryieNaruto/paintDemo（SDK 基座）
```

## 目录

```text
paint-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties / gradlew / gradle/wrapper/
├── app/
│   ├── build.gradle.kts          # AGP + Kotlin + Compose；externalNativeBuild → ../CMakeLists.txt
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/dgcamp/paint/
│       │   ├── MainActivity.kt        # Compose 入口
│       │   ├── ui/PaintScreen.kt      # 画布 + 触摸捕获 + 状态浮层
│       │   └── jni/PaintNative.kt     # native 桥（nativeHello 自检）
│       └── res/                       # 图标 / 主题 / 字符串
├── jni/
│   └── paint_android_jni.cpp          # JNI 胶水（消费者自备，空壳期仅 nativeHello）
├── CMakeLists.txt                     # add_subdirectory(sdk) + paint_android_jni (SHARED) + 链接 dgc_paint
├── sdk/                               # git submodule: paintDemo（路径固定 sdk/）
└── .gitmodules
```

## 构建

```bash
# 1) 指向本机 Android SDK（AGP 需要）；仓库 .gitignore 已排除 local.properties
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# 2) 构建 APK（gradle wrapper 会按需下载 Gradle 8.9 + AGP/Compose 依赖，首次较慢）
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

- 需要：JDK 17+、Android SDK（platform 35 / build-tools 35 / NDK r28）、网络（拉 Gradle + Maven 依赖）。
- `externalNativeBuild` 用 **NDK + 根 CMakeLists.txt** 编 `paint_android_jni`，内部 `add_subdirectory(sdk)` 编 `dgc_paint`，`-DDGCPAIN_BUILD_TESTS=OFF` 关掉 SDK host 单测。
- 也可单独验 native：`export ANDROID_NDK_HOME=...; cmake -S . -B build/android -DDGCPAIN_BUILD_TESTS=OFF -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static && cmake --build build/android`。

## 当前空壳行为

- 纸白 Compose 画布。
- 左上角浮层：native 自检（`JNI OK…`，证明 .so 加载成功）、触摸坐标、压感。
- 触摸：`detectDragGestures` 捕获坐标并显示，**不**转发 C API。

## 接入 SDK（下一轮，等 B1-4 C API 落地）

1. SDK 跟随 `main` 最新，`scripts/setup.*` 会 `--remote` 拉到最新，不需手动钉版本。
2. `jni/paint_android_jni.cpp`：`#include "dgc_paint_c_api.h"`，把 `MotionEvent` 转成 `dgcBeginStroke` → `dgcStrokeTo`（`isPredicted` 按消费者策略送）→ `dgcEndStroke` → `dgcRender`；native window 经 `dgcSetSurface` 传入。
3. `PaintNative.kt` 增加对应 JNI 绑定。
4. 渲染结果 present 到屏幕（TextureView / Vulkan surface 合成）。

## 接入 SDK（submodule）

SDK 子模块**默认跟随 `main` 最新**（`.gitmodules` 里 `branch = main`），`scripts/setup.*` 每次构建都 `git submodule update --init --recursive --remote` 拉到最新，不钉版本：

```bash
.\scripts\setup.ps1          # Windows：拉最新 SDK + 构建
git add sdk && git commit -m "chore: sdk 跟随 main <sha>"   # 可选：记录本次拉到的 commit
```

> 如需临时钉某个 SDK commit/tag（例如发布快照），可 `git -C sdk checkout <commit>` 覆盖，但不作为长期约定。详见 [docs/git/README.md](https://github.com/KryieNaruto/paintDemo/blob/main/docs/git/README.md)。

## CMake 约定

- 只 `add_subdirectory(sdk)`，只链接 `dgc_paint`。
- 不要链接 SDK 内部 target，不要 include `core/`。
- 唯一 include：`#include "dgc_paint_c_api.h"`。

## JNI 职责（消费者自备）

- 把 Android `MotionEvent` 的坐标 / 压感 / tilt 转成 C API 调用：
  `dgcBeginStroke` → `dgcStrokeTo`（`isPredicted` 由消费者决定是否送预测点）→ `dgcEndStroke`。
- 把 `dgcSetSurface` 需要的 native window（ANativeWindow / Vulkan surface 句柄）从 Java 层传到 native 层。
- 把 `dgcRender` 的合成结果 present 到屏幕。

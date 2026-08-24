# paint-android 消费者

Android UI 消费者仓库：自备 **JNI / Compose / Ink**，把触摸事件送入 SDK C API，再把渲染结果合成到屏幕。

SDK 只给 **C ABI**（`dgc_paint_c_api.h`），不提供 JNI 胶水——JNI 由消费者自备。

## 拓扑

```
paint-android ──submodule: sdk/──▶ KryieNaruto/paintDemo（SDK 基座）
```

## 目录

```text
paint-android/
├── jni/
│   └── paint_android_jni.cpp    # JNI 胶水（消费者自备，当前为占位）
├── CMakeLists.txt               # add_subdirectory(sdk) + 链接 dgc_paint
├── sdk/                         # git submodule: paintDemo（路径固定 sdk/）
└── .gitmodules
```

> Android Studio 工程结构（app/、src/main/…）由消费者后续补充；本骨架保持纯 CMake 形态，对齐 G0-1 模板。

## 构建

Android 目标用 NDK 交叉编译（arm64-v8a）：

```bash
export ANDROID_NDK_HOME=<你的 NDK 路径>
cmake -S . -B build/android -DDGCPAIN_BUILD_TESTS=OFF \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static
cmake --build build/android
```

## 接入 SDK

```bash
/path/to/paintDemo/scripts/bootstrap-consumer.sh --tag <tag>
git add .gitmodules sdk && git commit -m "chore: submodule paintDemo SDK 到 sdk/"
```

钉 commit / tag，禁止长期漂浮跟踪 main。详见 [docs/git/README.md](https://github.com/KryieNaruto/paintDemo/blob/main/docs/git/README.md)。

## CMake 约定

- 只 `add_subdirectory(sdk)`，只链接 `dgc_paint`。
- 不要链接 SDK 内部 target，不要 include `core/`。
- 唯一 include：`#include "dgc_paint_c_api.h"`。

## JNI 职责（消费者自备）

- 把 Android `MotionEvent` 的坐标 / 压感 / tilt 转成 C API 调用：
  `dgcBeginStroke` → `dgcStrokeTo`（`isPredicted` 由消费者决定是否送预测点）→ `dgcEndStroke`。
- 把 `dgcSetSurface` 需要的 native window（ANativeWindow / Vulkan surface 句柄）从 Java 层传到 native 层。
- 把 `dgcRender` 的合成结果 present 到屏幕。

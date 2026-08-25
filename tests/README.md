# paint-android 测试口径

## 自动门（CI/本机必须全绿）

- `./gradlew assembleDebug` → `BUILD SUCCESSFUL`，0 编译错误 0 链接错误（含 JNI native 编译、Kotlin 编译、Manifest 合并、APK 打包）。
- 本仓库无 instrumented 单测；`externalNativeBuild` 以 `-DDGCPAIN_BUILD_TESTS=OFF` 关闭 SDK host 单测（消费者侧不编 SDK 测试）。

## 真机验证（人工后续项）

当前环境无 Android 真机/模拟器，以下项标注为**人工后续项**，接入真机后逐项验证：

1. **画布输入链路**：安装 APK 启动 `MainActivity`，横屏显示纸白画布 + FPS 浮层（FPS / Frame ms / Readback ms 三项），触摸拖拽不崩，浮层数字随笔画实时刷新。
2. **读回上屏**：读回成功后画布显示渲染结果（当前 Null 内核无可见笔迹属预期，真实笔迹依赖 B3-1 合并）。
3. **离屏导出自检**：

   ```bash
   adb shell am start -n com.dgcamp.paint/.DemoExportActivity
   adb logcat -s DemoExport:I   # 期望 ok=true path=... size>0
   ```

   导出 PNG 落在 `context.getCacheDir()/demo_export.png`，Toast 显示路径。

## 依赖说明

- 性能验收「稳定 60fps（120Hz 屏 120fps）」依赖 B3-1 真实内核合并后自然达标，本期只验收链路通 + FPS 浮层存在。
- 无真机时以「编译通过 + 代码审阅」为测试口径；真机验证为人工后续项。

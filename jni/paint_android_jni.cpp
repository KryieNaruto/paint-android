// paint-android JNI 占位胶水。JNI 桥接 / Compose / Ink 由消费者实现。
// 后续把 Android MotionEvent（坐标/压感/tilt）转成 C API 调用：
//   dgcBeginStroke → dgcStrokeTo（isPredicted 由消费者决定）→ dgcEndStroke → dgcRender。
// 把 native window（ANativeWindow / Vulkan surface 句柄）经 dgcSetSurface 传入 SDK。

// TODO: 实现 JNI_OnLoad + native 方法（JNIEnv 绑定）。
// 唯一 include（接入后）：#include "dgc_paint_c_api.h"

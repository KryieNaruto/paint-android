// paint-android JNI 胶水（消费者自备）。
//
// 空壳期：只有 nativeHello() 自检，证明 .so 加载成功。
// SDK C API 接入后（等 B1-4）：
//   把 Android MotionEvent（坐标/压感/tilt）转成 C API 调用：
//     dgcBeginStroke → dgcStrokeTo（isPredicted 由消费者决定）→ dgcEndStroke → dgcRender
//   把 native window（ANativeWindow / Vulkan surface 句柄）经 dgcSetSurface 传入 SDK。
// 唯一 include（接入后）：#include "dgc_paint_c_api.h"

#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeHello(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("JNI OK (paint_android_jni loaded)");
}

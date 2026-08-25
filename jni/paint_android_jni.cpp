// paint-android JNI 胶水（消费者自备）。
//
// 完整 C API 桥接：持 DgcContext*，把 Kotlin external fun 一一映射到 dgc_paint C API。
// 唯一 include：dgc_paint_c_api.h（禁止 include core/ 等 SDK 内部头）。
//
// 数据流：MotionEvent → JNI → dgcBeginStroke/StrokeTo/EndStroke → dgcRender（引擎线程）
//        每帧 dgcReadbackPixels 读回 RGBA8 → ImageBitmap 上屏。

#include <jni.h>
#include <vector>
#include <string>
#include "dgc_paint_c_api.h"

namespace {
DgcContext* g_sdk = nullptr;
int g_w = 0, g_h = 0;

// 清理旧实例：nativeInit 重复调用 / 换 Activity 时先销毁，防泄漏与状态串扰。
void ResetSdk() {
    if (g_sdk) { dgcDestroy(g_sdk); g_sdk = nullptr; }
    g_w = g_h = 0;
}
}

// 保留 self-check（PaintNative.nativeHello 仍声明），避免 declared-but-unimplemented。
extern "C" JNIEXPORT jstring JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeHello(JNIEnv* env, jobject) {
    return env->NewStringUTF("JNI OK (paint_android_jni loaded)");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeInit(JNIEnv* env, jobject, jint w, jint h) {
    (void)env;
    ResetSdk();                          // 先销毁旧 g_sdk（防泄漏/串扰）
    g_sdk = dgcCreate();
    if (!g_sdk) return JNI_FALSE;
    g_w = w; g_h = h;
    int rc = dgcSetOffscreenSurface(g_sdk, w, h);
    if (rc != 0) { ResetSdk(); return JNI_FALSE; }
    rc = dgcClear(g_sdk, 0.96f, 0.95f, 0.91f, 1.0f);
    if (rc != 0) { ResetSdk(); return JNI_FALSE; }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeStrokeBegin(JNIEnv*, jobject, jfloat x, jfloat y, jfloat pressure) {
    if (g_sdk) dgcBeginStroke(g_sdk, x, y, pressure, 0.f, 0.f);
}
extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeStrokeTo(JNIEnv*, jobject, jfloat x, jfloat y, jfloat pressure) {
    if (g_sdk) dgcStrokeTo(g_sdk, x, y, pressure, 0.f, 0.f, 0);
}
extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeStrokeEnd(JNIEnv*, jobject) {
    if (g_sdk) dgcEndStroke(g_sdk);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeReadback(JNIEnv* env, jobject) {
    if (!g_sdk) return nullptr;
    std::vector<uint8_t> buf((size_t)g_w * g_h * 4);
    int rc = dgcReadbackPixels(g_sdk, buf.data());   // 检查返回值
    if (rc != 0) return nullptr;
    jbyteArray arr = env->NewByteArray((jsize)buf.size());
    env->SetByteArrayRegion(arr, 0, (jsize)buf.size(), (const jbyte*)buf.data());
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeExportPng(JNIEnv* env, jobject, jstring path) {
    if (!g_sdk) return JNI_FALSE;
    const char* p = env->GetStringUTFChars(path, nullptr);
    dgcFlush(g_sdk);
    int rc = dgcExportPNG(g_sdk, p);
    env->ReleaseStringUTFChars(path, p);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeDestroy(JNIEnv*, jobject) {
    ResetSdk();
}

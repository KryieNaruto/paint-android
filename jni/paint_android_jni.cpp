// paint-android JNI 胶水（消费者自备）。
//
// 完整 C API 桥接：持 DgcContext*，把 Kotlin external fun 一一映射到 dgc_paint C API。
// 唯一 include：dgc_paint_c_api.h（禁止 include core/ 等 SDK 内部头）。
//
// 数据流：MotionEvent → JNI → dgcBeginStroke/StrokeTo/EndStroke → dgcRender（引擎线程）
//        有输入时 dgcFlush → dgcReadbackPixels 写进复用 direct ByteBuffer → ImageBitmap 上屏。
// 性能（优化 3）：读回零分配——由 Java 持有一个复用的 direct ByteBuffer，JNI 用
// GetDirectBufferAddress 让 SDK 直接写入，消灭每帧 std::vector + NewByteArray +
// SetByteArrayRegion 的 3.1MB 分配/拷贝/GC churn（app 内 readback 76-80ms → 纯 SDK ~13ms）。

#include <jni.h>
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
// P7-4：真实时间戳入口——Kotlin 把 MotionEvent 时间（uptimeMillis*1000, µs）透传进来，
// SDK 的 dgcStrokeToAt 用真实事件间隔校准 modeler 卡尔曼速度/预测长度（合成 180Hz 步长下
// 速度被高估 ~3x → 抢跑回扯，见 docs/调研/AD平台手感延迟分析.md §3 原因 A）。
extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeStrokeToAt(JNIEnv*, jobject, jfloat x, jfloat y, jfloat pressure, jdouble tUs) {
    if (g_sdk) dgcStrokeToAt(g_sdk, x, y, pressure, 0.f, 0.f, 0, tUs);
}
extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeStrokeEnd(JNIEnv*, jobject) {
    if (g_sdk) dgcEndStroke(g_sdk);
}

// 零分配读回：Java 传入复用的 direct ByteBuffer，SDK 直接写入其内存。
// 返回 dgcReadbackPixels 的 rc（0=成功）。GetDirectBufferCapacity 防越界。
extern "C" JNIEXPORT jint JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeReadback(JNIEnv* env, jobject, jobject buf) {
    if (!g_sdk) return -1;
    jlong cap = env->GetDirectBufferCapacity(buf);
    if (cap < (jlong)g_w * g_h * 4) return -3;  // 缓冲过小，防 SDK 越界写
    void* addr = env->GetDirectBufferAddress(buf);
    if (!addr) return -2;
    return dgcReadbackPixels(g_sdk, addr);
}

// drain 屏障：等引擎把已提交输入全部合批 composite 完。批量 composite 后必须 flush 再读回，
// 否则渲染线程可能仍在攒批，读回拿到旧画布。
extern "C" JNIEXPORT void JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeFlush(JNIEnv*, jobject) {
    if (g_sdk) dgcFlush(g_sdk);
}

// ── D6-1/D6-2/D6-3 消费端接线：笔刷参数 / 颜色 / 清屏 ──
// 全部固定用 DGC_DEFAULT_BRUSH——PC 端验证过 0-2 号 setting 用自建 handle 是死存储、
// 4-12 号 modeler 参数是与 handle 值无关的 context 级单例，直接用默认笔刷句柄最简单，
// 与 PC D6-3 的颜色调用方式一致（dgc_paint_c_api.h DGC_DEFAULT_BRUSH 注释）。
extern "C" JNIEXPORT jint JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeSetBrushSetting(JNIEnv*, jobject, jint settingId, jdouble value) {
    if (!g_sdk) return -1;
    return dgcSetBrushSetting(g_sdk, DGC_DEFAULT_BRUSH, settingId, value);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeSetBrushColor(JNIEnv*, jobject, jfloat r, jfloat g, jfloat b, jfloat a) {
    if (!g_sdk) return -1;
    return dgcSetBrushColor(g_sdk, DGC_DEFAULT_BRUSH, r, g, b, a);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_dgcamp_paint_jni_PaintNative_nativeClear(JNIEnv*, jobject, jfloat r, jfloat g, jfloat b, jfloat a) {
    if (!g_sdk) return -1;
    return dgcClear(g_sdk, r, g, b, a);
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

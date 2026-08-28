package com.dgcamp.paint.jni

import java.nio.ByteBuffer

/**
 * native 库的 Kotlin 桥接（消费者自备）。
 *
 * external fun 与 jni/paint_android_jni.cpp 的
 * Java_com_dgcamp_paint_jni_PaintNative_* 符号一一对应。
 */
object PaintNative {
    init {
        System.loadLibrary("paint_android_jni")
    }

    external fun nativeHello(): String            // 保留自检
    external fun nativeInit(w: Int, h: Int): Boolean
    external fun nativeStrokeBegin(x: Float, y: Float, pressure: Float)
    external fun nativeStrokeTo(x: Float, y: Float, pressure: Float)
    external fun nativeStrokeEnd()
    external fun nativeFlush()                    // drain 屏障：等 composite 完成
    external fun nativeReadback(buf: ByteBuffer): Int  // 零分配：直接写进复用 direct buffer
    external fun nativeExportPng(path: String): Boolean
    external fun nativeDestroy()
    // D6-1/D6-2/D6-3 消费端接线：笔刷参数 / 颜色 / 清屏（JNI 侧固定用 DGC_DEFAULT_BRUSH）
    external fun nativeSetBrushSetting(settingId: Int, value: Double): Int
    external fun nativeSetBrushColor(r: Float, g: Float, b: Float, a: Float): Int
    external fun nativeClear(r: Float, g: Float, b: Float, a: Float): Int

    fun init(w: Int, h: Int): Boolean = nativeInit(w, h)
}

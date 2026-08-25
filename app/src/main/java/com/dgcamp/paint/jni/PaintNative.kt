package com.dgcamp.paint.jni

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
    external fun nativeReadback(): ByteArray?
    external fun nativeExportPng(path: String): Boolean
    external fun nativeDestroy()

    fun init(w: Int, h: Int): Boolean = nativeInit(w, h)
}

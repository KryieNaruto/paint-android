package com.dgcamp.paint.jni

/**
 * native 库的 Kotlin 桥接（消费者自备）。
 *
 * 空壳期只有一个自检函数 nativeHello()，证明 paint_android_jni.so 加载成功。
 * SDK C API 接入后，dgcBeginStroke / dgcStrokeTo / dgcEndStroke / dgcRender 的
 * JNI 绑定在这里扩展。
 */
object PaintNative {
    init {
        System.loadLibrary("paint_android_jni")
    }

    external fun nativeHello(): String
}

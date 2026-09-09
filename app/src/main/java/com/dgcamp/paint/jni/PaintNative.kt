package com.dgcamp.paint.jni

import android.view.Surface
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
    external fun nativeStrokeToAt(x: Float, y: Float, pressure: Float, tUs: Double)  // P7-4 真实事件时间（µs）
    external fun nativeStrokeEnd()
    external fun nativeFlush()                    // drain 屏障：等 composite 完成
    external fun nativeReadback(buf: ByteBuffer): Int  // 零分配：直接写进复用 direct buffer
    external fun nativeExportPng(path: String): Boolean
    external fun nativeDestroy()
    // D6-1/D6-2/D6-3 消费端接线：笔刷参数 / 颜色 / 清屏（JNI 侧固定用 DGC_DEFAULT_BRUSH）
    external fun nativeSetBrushSetting(settingId: Int, value: Double): Int
    external fun nativeSetBrushColor(r: Float, g: Float, b: Float, a: Float): Int
    external fun nativeClear(r: Float, g: Float, b: Float, a: Float): Int

    // A8-5：把渲染承载切到外部 Surface（SWAPCHAIN 上屏）。Surface 非空 → JNI 经
    // ANativeWindow_fromSurface 转 ANativeWindow* 传给 dgcSetSurface（SDK 建 VkSurfaceKHR +
    // VkSwapchainKHR，把离屏 canvas 直接 blit→present 上去，无 readback）；Surface 为 null →
    // dgcSetSurface(NULL) 断开、回离屏（并释放 JNI 侧持有的 ANativeWindow 引用）。
    // w/h 传画布逻辑尺寸（离屏 canvas 保持该尺寸不变；swapchain extent 由 native window 决定）。
    external fun nativeSetSurface(surface: Surface?, w: Int, h: Int): Boolean

    /** 断开 surface：SDK 回离屏（present 回 no-op），并释放持有的 ANativeWindow（配对 ANativeWindow_fromSurface）。 */
    fun detachSurface() = nativeSetSurface(null, 0, 0)

    fun init(w: Int, h: Int): Boolean = nativeInit(w, h)
}

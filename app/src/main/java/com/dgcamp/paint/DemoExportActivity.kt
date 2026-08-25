package com.dgcamp.paint

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.dgcamp.paint.jni.PaintNative
import java.io.File

/** 离屏导出自检：画固定笔迹 → dgcExportPNG 到 cacheDir。满足离屏图像输出硬约束。 */
class DemoExportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PaintNative.init(640, 480)
        PaintNative.nativeStrokeBegin(50f, 50f, 0.5f)
        for (i in 0 until 10) PaintNative.nativeStrokeTo(50f + i * 50f, 50f + i * 20f, 0.5f)
        PaintNative.nativeStrokeEnd()
        val out = File(cacheDir, "demo_export.png")
        val ok = PaintNative.nativeExportPng(out.absolutePath)
        PaintNative.nativeDestroy()
        Toast.makeText(this, "export=${ok} ${out.absolutePath}", Toast.LENGTH_LONG).show()
        // 供无头验证：结果写入日志
        android.util.Log.i("DemoExport", "ok=$ok path=${out.absolutePath} size=${if (out.exists()) out.length() else -1}")
        finish()
    }
}

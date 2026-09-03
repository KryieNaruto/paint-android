package com.dgcamp.paint.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A8-1 延迟/帧时量化埋点单测（TDD：先红后绿，最终 4 用例全绿）。
 *
 * 覆盖：FrameTimeAccumulator.p50/p99、空样本返 0、容量淘汰；
 *       LatencyProbe.avgLagMs/无输入先上屏忽略/每输入至多一个样本。
 *
 * 纯 JVM 无头可跑；LatencyProbe 用注入假时钟确定性推进。
 */
class LatencyMetricsTest {

    // ── FrameTimeAccumulator ──

    // 用例 1：p50/p99 分位（升序排序 + floor(p*(n-1)) 下标）。
    @Test
    fun `frame time p50 p99 percentiles`() {
        val acc = FrameTimeAccumulator(capacity = 100)
        // 倒序灌入 1..100，验证内部排序不受插入顺序影响。
        (100 downTo 1).forEach { acc.record(it.toFloat()) }
        // n=100：p50 下标 floor(0.5*99)=49 → sorted[49]=50；p99 下标 floor(0.99*99)=98 → 99。
        assertEquals(50f, acc.p50(), 0.001f)
        assertEquals(99f, acc.p99(), 0.001f)
        assertEquals(100, acc.size())
    }

    // 用例 2：空样本返 0；非正值样本被忽略。
    @Test
    fun `empty and non-positive samples`() {
        val acc = FrameTimeAccumulator()
        assertEquals(0f, acc.p50(), 0f)
        assertEquals(0f, acc.p99(), 0f)
        acc.record(0f)
        acc.record(-1f)
        assertEquals("非正值样本被忽略", 0, acc.size())
        assertEquals(0f, acc.p50(), 0f)
    }

    // 用例 3：容量淘汰——超出容量只保留最近样本。
    @Test
    fun `capacity evicts oldest`() {
        val acc = FrameTimeAccumulator(capacity = 3)
        acc.record(10f); acc.record(20f); acc.record(30f)
        acc.record(40f)                              // 淘汰 10
        assertEquals(3, acc.size())
        // 保留 [20,30,40]：p50 = floor(0.5*2)=1 → 30。
        assertEquals(30f, acc.p50(), 0.001f)
    }

    // ── LatencyProbe ──

    // 用例 4：无输入先上屏忽略；有输入后上屏记录 lag；avgLag 求均值。
    @Test
    fun `latency probe ignores frame without prior input and averages lag`() {
        var now = 1000.0
        val probe = LatencyProbe(nowMs = { now })
        probe.onFramePresented()                     // 无输入 → 忽略
        assertEquals(0, probe.sampleCount())
        assertEquals(0f, probe.avgLagMs(), 0f)

        probe.onInput(990.0)                         // 输入 @990
        now = 1010.0
        probe.onFramePresented()                     // lag = 20
        assertEquals(20f, probe.lastLagMs(), 0.001f)

        probe.onInput(1000.0)                        // 第二次输入 @1000
        now = 1020.0
        probe.onFramePresented()                     // lag = 20
        assertEquals(20f, probe.avgLagMs(), 0.001f)  // (20+20)/2
        assertEquals(2, probe.sampleCount())
    }

    // 用例 5：多次输入在上一帧之间只保留最新一次，每输入至多产出一个样本。
    @Test
    fun `each input yields at most one lag sample`() {
        var now = 1000.0
        val probe = LatencyProbe(nowMs = { now })
        probe.onInput(980.0)
        probe.onInput(990.0)                         // 同帧窗口内最新输入覆盖旧值
        now = 1020.0
        probe.onFramePresented()                     // lag = 1020 - 990 = 30
        assertEquals(30f, probe.lastLagMs(), 0.001f)

        now = 1030.0
        probe.onFramePresented()                     // 无新输入 → 忽略
        assertEquals(1, probe.sampleCount())
    }

    // 用例 6：负 lag（时钟基准抖动）被丢弃，不污染样本。
    @Test
    fun `negative lag is discarded`() {
        var now = 1000.0
        val probe = LatencyProbe(nowMs = { now })
        probe.onInput(1010.0)                        // 输入时刻反而晚于 now（抖动）
        probe.onFramePresented()                     // lag = 1000 - 1010 < 0 → 丢弃
        assertEquals(0, probe.sampleCount())
        assertEquals(0f, probe.avgLagMs(), 0f)
    }

    // 用例 7：clear 清空样本与 pending 输入（模式切换重置）。
    @Test
    fun `clear resets samples and pending input`() {
        var now = 1000.0
        val probe = LatencyProbe(nowMs = { now })
        probe.onInput(990.0)
        now = 1010.0
        probe.onFramePresented()
        assertEquals(1, probe.sampleCount())
        probe.clear()
        assertEquals(0, probe.sampleCount())
        now = 1020.0
        probe.onFramePresented()                     // pending 已清 → 忽略
        assertEquals(0, probe.sampleCount())
    }
}

package com.dgcamp.paint.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug 回归（bugfix-frame-loop-vsync-decouple）：readback 调度器解耦 vsync。
 *
 * 红（修复前）：`ReadbackScheduler` 类不存在 → 本测试编译失败（red = 编译失败）。
 * 绿（修复后）：scheduler 存在且语义成立——输入驱动（非 vsync 相位）、最小间隔节流、
 * 显式重绘版本号、deadline 等待而非忙轮询、失败重试时 pending 不被吞。
 *
 * 用注入 `nowNs` 的假时钟确定性推进，纯 JVM 无头可跑。
 */
class ReadbackSchedulerTest {

    private val MS = 1_000_000L  // 1ms 的纳秒数

    /** 注入假时钟：手动推进时间轴，确定性。 */
    private class FakeClock(var t: Long = 0L) {
        fun now(): Long = t
    }

    // 用例 1（plan §3.1.1）：输入即申请读回，不受 vsync 相位约束。
    // 旧实现在 vsync 回调内读回，无「输入即读回」语义 → 红。
    @Test
    fun `input triggers immediate readback not gated by vsync`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(nowNs = clock::now)
        clock.t = 10 * MS
        s.onInput()
        assertTrue("onInput 后立即可读回（不等 vsync）", s.shouldReadbackNow())
    }

    // 用例 2（plan §3.1.2）：读回受最小间隔节流。
    @Test
    fun `readbacks throttled to min interval`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(minIntervalNs = 8 * MS, nowNs = clock::now)
        clock.t = 0
        s.onInput()
        assertTrue(s.shouldReadbackNow())
        s.onReadbackComplete()                 // 读回记时 t=0
        clock.t = 3 * MS
        s.onInput()
        assertFalse("间隔未到被节流", s.shouldReadbackNow())
        clock.t = 8 * MS
        s.onInput()
        assertTrue("到达最小间隔后可读回", s.shouldReadbackNow())
    }

    // 用例 3（plan §3.1.3）：每次读回 version+1，作为同引用 bitmap 强制重绘信号。
    @Test
    fun `version bumps on each readback forces redraw`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(minIntervalNs = 1 * MS, nowNs = clock::now)
        clock.t = 0
        s.onInput(); s.onReadbackComplete()
        assertEquals(1, s.version())
        clock.t = 2 * MS
        s.onInput(); s.onReadbackComplete()
        assertEquals(2, s.version())
    }

    // 用例 4（plan §3.1.4）：抬笔排空后须补一次最终读回。
    @Test
    fun `stroke end requests final readback`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(nowNs = clock::now)
        clock.t = 0
        s.onStrokeEnd()
        assertTrue("onStrokeEnd 后申请最终读回", s.shouldReadbackNow())
    }

    // 用例 5（plan §3.1.5）：节流期内 pending 保持、deadline 而非忙轮询。
    @Test
    fun `throttle window keeps pending`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(minIntervalNs = 16 * MS, nowNs = clock::now)
        clock.t = 0
        s.onInput(); s.onReadbackComplete()
        clock.t = 5 * MS
        s.onInput()
        assertTrue("节流期内 isThrottled", s.isThrottled())
        assertFalse("节流期内不可读回", s.shouldReadbackNow())
        assertTrue("剩余等待 >0（deadline 而非忙轮询）", s.timeUntilReadableNs() > 0)
    }

    // 用例 6（plan §3.1.6）：deadline 等待恰好在间隔处可读回。
    @Test
    fun `deadline wait reaches readable exactly at interval`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(nowNs = clock::now)   // 默认 16ms
        clock.t = 0
        s.onInput(); s.onReadbackComplete()             // T0 读回
        clock.t = 8 * MS
        s.onInput()                                     // T0+8ms 输入，进入节流
        assertEquals("距下次可读回还剩 8ms", 8 * MS, s.timeUntilReadableNs())
        assertFalse(s.shouldReadbackNow())
        clock.t = 16 * MS                               // T0+16ms = 恰达间隔
        assertTrue("到达间隔即可读回", s.shouldReadbackNow())
        assertEquals("deadline 等待归零", 0L, s.timeUntilReadableNs())
    }

    // 用例 7（plan §3.1.7）：pending 在未读回前不被重复输入吞掉；读回完成才清。
    @Test
    fun `pending survives without consume`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(nowNs = clock::now)
        clock.t = 0
        s.onInput()
        s.onInput()                                     // 重复输入：pending 不丢失
        assertTrue("pending 待读回", s.shouldReadbackNow())
        s.onReadbackComplete()
        assertFalse("读回完成才清 pending", s.shouldReadbackNow())
        // 失败重试语义基础：读回完成后越过节流间隔再输入，pending 仍可读回。
        clock.t = 17 * MS
        s.onInput()
        assertTrue(s.shouldReadbackNow())
    }

    // 用例 8（test 门反馈）：ns→ms ceil 换算——worker 的 delay() 按毫秒接收，必须直接用它，
    // 防「16ms 被当 16 亿 ms」的灾难等待；亚毫秒余量 ceil 到 1ms 保证 delay 不忙轮询。
    @Test
    fun `time until readable converts ns to ms with ceil`() {
        val clock = FakeClock()
        val s = ReadbackScheduler(minIntervalNs = 8 * MS, nowNs = clock::now)
        clock.t = 0
        s.onInput(); s.onReadbackComplete()             // T0 读回，lastRead=0
        clock.t = 5 * MS
        s.onInput()                                     // 剩 3ms
        assertEquals(3L, s.timeUntilReadableMs())
        clock.t = 7 * MS                                // 剩 1ms
        s.onInput()
        assertEquals(1L, s.timeUntilReadableMs())
        clock.t = 8 * MS - 1L                           // 剩 1ns → ceil 到 1ms（不忙轮询）
        s.onInput()
        assertEquals(1L, s.timeUntilReadableMs())
        clock.t = 8 * MS                                // 恰达间隔
        s.onInput()
        assertEquals(0L, s.timeUntilReadableMs())
        s.onReadbackComplete()                          // pending 清空 → 0
        assertEquals(0L, s.timeUntilReadableMs())
    }
}

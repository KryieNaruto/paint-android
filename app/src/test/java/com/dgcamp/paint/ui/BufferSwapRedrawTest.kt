package com.dgcamp.paint.ui

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P7-3：双缓冲交替引用语义（纯 JVM 无头，不依赖 Android 运行时）。
 *
 * 目标：验证 `backBufferFor` 的引用相等（`===`）选择逻辑，它是「双缓冲交替引用强制重绘」
 * 的核心——`bitmap` 状态在 `bmpA`/`bmpB` 间交替写**不同实例**，Compose `mutableStateOf`
 * 默认 structuralEqualityPolicy 判 `Bitmap.equals`（未重写 = 引用相等）恒 false → 每读回
 * 必重组、必重绘；同引用写（`bitmap = 当前同一实例`）则不产生新信号（no-op）。
 *
 * 用普通 `Any` 实例建模 Bitmap 的引用语义（不引入 android.graphics.Bitmap，保持无头可跑）。
 * 与 `ReadbackSchedulerTest` 互补：后者覆盖读回节流/版本号，本测试覆盖上屏重绘信号来源。
 */
class BufferSwapRedrawTest {

    // 用例 1：首帧无上屏缓冲（current == null）→ 选 A 作为首块上屏缓冲。
    @Test
    fun `first swap with no current buffer picks A`() {
        val a = Any()
        val b = Any()
        assertSame("首帧（current==null）应选 A", a, backBufferFor(null, a, b))
    }

    // 用例 2：连续 N 次交换严格在 A/B 间交替（写入前 target 必 ≠ current，否则 Compose `==`
    // 会判等跳过重组，导致上屏停更）。
    @Test
    fun `consecutive swaps strictly alternate between A and B`() {
        val a = Any()
        val b = Any()
        var front: Any? = null
        var last: Any? = null
        repeat(64) { i ->
            val next = backBufferFor(front, a, b)
            assertNotSame("写入前 target 必 ≠ 当前 front（否则 `==` 跳过重组）", front, next)
            val expect = if (i % 2 == 0) a else b
            assertSame("第 ${i + 1} 次交换应选 ${if (i % 2 == 0) "A" else "B"}（严格交替）", expect, next)
            last = next
            front = next
        }
        assertSame("末次写入必落在 B（偶数次交换后回到 B）", b, last)
    }

    // 用例 3：同引用写入 = no-op（不产生新缓冲引用）。建模「写回同一实例不会换出不同引用」，
    // 即 `bitmap = bmp` 同引用时不会出现交替新实例信号。
    @Test
    fun `same reference write produces no new reference`() {
        val a = Any()
        val b = Any()
        // 当前 front 是 A：backBufferFor 返回 B（不同引用 → 必触发重绘）。
        val nextFromA = backBufferFor(a, a, b)
        assertSame("当前 A → 选 B", b, nextFromA)
        // 若误把「当前缓冲」本身当 back 写回，等于同引用 no-op（不触发重绘）；本测试锁定
        // backBufferFor 绝不返回与 current 相同的实例。
        assertNotSame("绝不返回与 current 相同的实例", a, nextFromA)
    }

    // 用例 4：交换语义与具体实例无关，只依赖引用相等——即使两块缓冲内容值相同（如 `==`
    // 按内容判等），只要引用不同就应视为可交换的新缓冲（Bitmap 语义：引用相等）。
    @Test
    fun `selection relies on reference identity not content equality`() {
        val a = ObjectWithContent("same")
        val b = ObjectWithContent("same")   // 内容相同，引用不同
        assertNotSame("两块缓冲是不同实例", a, b)
        assertSame("当前 A → 选 B（引用判断，不看内容）", b, backBufferFor(a, a, b))
        assertSame("当前 B → 选 A（引用判断，不看内容）", a, backBufferFor(b, a, b))
    }

    private class ObjectWithContent(val content: String)
}

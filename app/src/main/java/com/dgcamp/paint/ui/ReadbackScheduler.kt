package com.dgcamp.paint.ui

/**
 * Readback 调度器：把「事件 → 读回」的节拍从 Compose vsync（withFrameNanos）解耦出来。
 *
 * 语义（bugfix-frame-loop-vsync-decouple 计划 §2.1）：
 * - **输入驱动**：`onInput()`/`onStrokeEnd()` 置 pending，`shouldReadbackNow()` 立即为 true
 *   （首读回不受 vsync 相位约束，输入到达即申请读回）。
 * - **节流**：`minIntervalNs` 防止高频输入把读回风暴打进渲染线程（每次读回 → requestFlush →
 *   一次全画布 GPU 快照刷新，须 ≤ 显示刷新率；默认 16ms≈60Hz，≥ 单帧预算）。
 * - **显式重绘版本**：`version()` 每次读回 +1，作为同引用 bitmap 强制重绘信号。
 * - **deadline 等待**：`timeUntilReadableNs()` 给出距下次可读回的剩余等待（ns），供 worker
 *   用 `delay()` 而非忙轮询。
 * - **失败重试基础**：pending 只在 `onReadbackComplete()` 才清；读回失败不清 pending，
 *   worker 可回到循环头重试（保持旧「下次节拍重试」语义）。
 *
 * 纯 Kotlin、无 Android/Compose 依赖，可 JVM 无头单测。
 */
class ReadbackScheduler(
    private val minIntervalNs: Long = DEFAULT_MIN_INTERVAL_NS,
    private val nowNs: () -> Long = System::nanoTime,
) {
    companion object {
        // 16ms ≈ 60Hz：输入驱动读回的最密节流。须 ≥ 单帧预算——低于显示刷新率时读回速率
        // 会跟随输入速率（60–120Hz）超过 vsync，把「每读回一次 → requestFlush → 一次全画布
        // GPU 快照刷新」的 GPU 竞争推到 ≥ 刷新率，弱 GPU 上反更掉帧。16ms 保证正常输入下
        // 读回 ≤60Hz（不劣于旧 vsync 对齐节拍），同时首读回仍输入即时、不与 vsync 相位锁定。
        // 高刷真机可调低。
        //
        // A8-3（2026-09-07，真机 MDP1221 实测，结论：不改，维持 16ms）：曾尝试把此值降到
        // 4ms（假设 4a 合并 GPU 提交落地后「每次读回代价降低」意味着可以更频繁读回、换来更低
        // 端到端延迟），真机数据**证伪了这个假设**——4ms 档实测「输入→上屏」lag 与 16ms 基线
        // 完全相同（均 16ms，spec §7 基线亦是 16ms/16ms），「输入→读回」lag 反而从 6ms 恶化到
        // 12ms（读回更频繁没换来端到端收益，怀疑是后台读回线程调度/竞争的副作用，机制未细究），
        // 预测开/关两态下现象一致，人工肉眼判定「预测开关手感无变化」。结论：端到端「输入→上屏」
        // 延迟被消费端读回节流值以外的因素钉死在 ~16ms（≈60Hz 下 1 vsync），与本计划 §5.3
        // 的既有分析一致——Compose 单一 Choreographer 帧管线下 `bitmap = back` 之后必须等下一个
        // vsync 才 recompose+draw，是该架构固有下限，不受读回调用频率影响。降低本节流值零收益、
        // 有轻微副作用，不采纳，维持 16ms。详见 spec
        // docs/superpowers/specs/2026-09-04-mode-a-ink-parity-design.md §8。
        const val DEFAULT_MIN_INTERVAL_NS = 16_000_000L
    }

    private var lastReadNs = Long.MIN_VALUE / 2
    private var pending = false
    private var version = 0

    /** gesture 输入到达：申请读回。 */
    fun onInput() {
        pending = true
    }

    /** 抬笔：排空后须补一次最终读回。 */
    fun onStrokeEnd() {
        pending = true
    }

    /** 输入驱动 + 最小间隔节流，不受 vsync 相位约束。 */
    fun shouldReadbackNow(): Boolean =
        pending && nowNs() - lastReadNs >= minIntervalNs

    /** 距下次可读回的剩余等待（ns，≥0）。供 worker 用 deadline 等待而非忙轮询。 */
    fun timeUntilReadableNs(): Long =
        if (!pending) 0L else (minIntervalNs - (nowNs() - lastReadNs)).coerceAtLeast(0L)

    /** 距下次可读回的剩余等待（毫秒，ceil，≥0）。worker 的 `delay()` 按毫秒接收，**必须**用它，
     * 避免把纳秒当毫秒传（曾致 16ms 被当成 16 亿 ms 的灾难等待，见 bugfix-frame-loop-vsync-decouple）。 */
    fun timeUntilReadableMs(): Long =
        (timeUntilReadableNs() + 999_999L) / 1_000_000L

    /** 读回完成：记时、清 pending、bump 重绘版本。 */
    fun onReadbackComplete() {
        lastReadNs = nowNs()
        pending = false
        version++
    }

    /** 已完成的读回次数（每次 +1），作为同引用 bitmap 强制重绘信号。 */
    fun version(): Int = version

    /** 节流期判定：有 pending 但尚未到可读回时刻。 */
    fun isThrottled(): Boolean = pending && !shouldReadbackNow()
}

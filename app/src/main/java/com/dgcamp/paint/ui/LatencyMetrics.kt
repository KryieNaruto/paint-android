package com.dgcamp.paint.ui

/**
 * A8-1 延迟/帧时量化埋点（纯 Kotlin，无 Android/Compose 依赖，可 JVM 无头单测）。
 *
 * 两个组件：
 * - [FrameTimeAccumulator]：累积逐帧耗时样本，输出 p50/p99 分位帧时。
 * - [LatencyProbe]：输入→帧 延迟**代理**（非严格 input-to-photon，见取舍-3）。
 *
 * 延迟代理语义（供 A/B 对照产出可比数字）：
 * - [onInput] 记录最新一次输入的「输入时刻」（调用方传系统启动 uptimeMillis，与 now 同基准）；
 * - [onFramePresented] 在「帧就绪上屏」时刻被调用：若有 pending 输入，则 lag = now - 输入时刻，
 *   记一个样本并清空 pending。因此每次输入至多产出一个「最新输入 → 随后第一帧」的 lag 样本，
 *   无输入先上屏（无 pending）时忽略——避免把空闲帧误记为延迟。
 * - 负 lag（时钟基准抖动，理论上不应出现）被丢弃，防止污染均值。
 */
class FrameTimeAccumulator(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** 默认样本容量：约 4 秒 @60Hz 的逐帧样本，足够稳定的 p50/p99。 */
        const val DEFAULT_CAPACITY = 240
    }

    private val samples = ArrayList<Float>(capacity)

    /** 记录一帧耗时（毫秒）。非正值（时钟异常/首帧无前帧基准）忽略。 */
    fun record(frameMs: Float) {
        if (frameMs <= 0f) return
        if (samples.size >= capacity) samples.removeAt(0)
        samples.add(frameMs)
    }

    /** 第 50 分位帧时（毫秒）。空样本返回 0。 */
    fun p50(): Float = percentile(0.50f)

    /** 第 99 分位帧时（毫秒）。空样本返回 0。 */
    fun p99(): Float = percentile(0.99f)

    /** 已累积的样本数。 */
    fun size(): Int = samples.size

    /** 清空样本（模式切换时重置，保证 A/B 各采一段独立数据）。 */
    fun clear() = samples.clear()

    /**
     * 最近秩分位：升序排序后取 `floor(p * (n-1))` 下标。简单、确定性、可单测；
     * HUD 用途无需插值精度。空样本返回 0。
     */
    private fun percentile(p: Float): Float {
        if (samples.isEmpty()) return 0f
        val sorted = samples.sorted()
        val index = (p * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[index]
    }
}

class LatencyProbe(
    private val nowMs: () -> Double = { System.nanoTime() / 1_000_000.0 },
) {

    private var pendingInputMs: Double? = null
    private val lagSamples = ArrayList<Float>()

    /** 记录一次输入到达（输入时刻 uptimeMillis，与 [nowMs] 同基准）。 */
    fun onInput(inputUptimeMs: Double) {
        pendingInputMs = inputUptimeMs
    }

    /** 帧就绪上屏：若自上次输入后尚未上屏，记一个「输入→帧」lag 样本。 */
    fun onFramePresented() {
        val t0 = pendingInputMs ?: return          // 无输入先上屏 → 忽略
        val lag = (nowMs() - t0).toFloat()
        if (lag >= 0f) lagSamples.add(lag)          // 丢弃负 lag（时钟基准抖动）
        pendingInputMs = null                        // 本次输入已被一帧消费
    }

    /** 平均「输入→帧」延迟（毫秒）。无样本返回 0。 */
    fun avgLagMs(): Float = if (lagSamples.isEmpty()) 0f else lagSamples.average().toFloat()

    /** 最近一次「输入→帧」延迟（毫秒）。无样本返回 0。 */
    fun lastLagMs(): Float = lagSamples.lastOrNull() ?: 0f

    /** 已采样的 lag 样本数。 */
    fun sampleCount(): Int = lagSamples.size

    /** 清空样本与 pending 输入（模式切换时重置）。 */
    fun clear() {
        lagSamples.clear()
        pendingInputMs = null
    }
}

package com.dgcamp.paint.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug #2 回归：modeler 参数（id>=4）必须带面向用户的通俗效果说明（effect），
 * 且说明不能是参数名/英文标识本身（否则用户仍不知道滑杆干什么）。
 *
 * 红（修复前）：`BrushSettingSpec` 无 `effect` 字段 → 本测试无法编译（red = 编译失败）。
 * 绿（修复后）：`effect` 字段存在，逐条取自 SDK docs/brush_settings_mapping.md
 * 「改参效果（人工可辨）」列，断言全部 modeler spec 通过。
 */
class BrushSettingSpecTest {

    /** 所有 modeler spec（id>=4）必须有非空、非技术标识本身的通俗效果说明。 */
    @Test
    fun `every modeler spec has a plain-language effect not equal to its identifier`() {
        val modeler = BRUSH_SETTINGS.filter { it.id >= 4 }
        assertTrue("modeler specs exist (id>=4)", modeler.isNotEmpty())

        for (spec in modeler) {
            val effect = spec.effect
            assertTrue("id ${spec.id} effect non-blank", effect.isNotBlank())

            // 效果说明 ≠ 参数名/英文标识本身（label = "中文名 英文标识"）。
            assertEquals("id ${spec.id} effect is not the full label", false, effect == spec.label)
            for (token in spec.label.split(' ')) {
                if (token.isNotBlank()) {
                    assertEquals("id ${spec.id} effect is not a label token", false, effect == token)
                }
            }

            // 通俗说明不应再包含下划线技术标识（如 wobble_timeout_ms）。
            assertFalse(
                "id ${spec.id} effect has no snake_case technical identifier: '$effect'",
                effect.contains('_'),
            )
            // 应是中文白话（映射表「改参效果」列全为中文），非纯英文标识。
            assertTrue("id ${spec.id} effect contains CJK text", effect.contains(Regex("[\\u4e00-\\u9fff]")))
        }
    }

    /** 笔刷内核基础参数（0-2）也应注明生效时机（下一笔生效），帮助用户理解改动何时起作用。 */
    @Test
    fun `brush kernel specs carry an effect note`() {
        val kernel = BRUSH_SETTINGS.filter { it.id in 0..2 }
        assertEquals("kernel specs 0-2 present", 3, kernel.size)
        for (spec in kernel) {
            assertTrue("id ${spec.id} effect non-blank", spec.effect.isNotBlank())
        }
    }

    /**
     * 回归（真机实测暴露）：预测开关默认态必须与 SDK modeler 激活态一致。
     *
     * 根因：SDK predictor 惰性激活——fresh 启动从不推送 modeler 参数（passthrough，无预测，
     * 见 sdk_api/dgc_paint_c_api.cpp dgcSetBrushSetting「首次设置才 make_unique+setPredictor」），
     * 但 id12 默认 16f 使「预测：开/关」开关初始显示「开」→ UI 显示开、实际无预测。
     *
     * 修复：id12 默认 0f（关）——fresh 启动显示「关」与 passthrough 无预测一致；用户点「开」
     * 才推送 16f 并激活 predictor。此断言锁住「默认关 = 显示态与激活态一致」，防回归。
     */
    @Test
    fun `prediction interval default is off matching lazy-activated passthrough`() {
        val spec = BRUSH_SETTINGS.first { it.id == 12 }
        assertEquals("id12 默认 0f（预测关，fresh 无预测）", 0f, spec.default, 0.0f)
    }
}

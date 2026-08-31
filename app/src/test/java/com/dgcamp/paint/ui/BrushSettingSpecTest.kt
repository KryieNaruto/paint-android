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
}

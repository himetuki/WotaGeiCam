package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 俯仰回正震动的「落值闸门」铁桩（2026-10-04 批次 D 修复）。
 *
 * 缺陷史：`onSensorChanged` 里俯仰的回正震动写在 EMIT_STEP 节流闸门**外侧**——
 * 节流吞帧时 `_pitch.value` 停在带外旧值（如 1.5°，不算回正），真值已跨进 ±1.5° 容差带
 * （如 1.45°）并停住，于是「上一状态非回正、本帧回正」在**每个样本**上都成立：
 * 100ms 单震按 ~50Hz 重触发成连震，直到俯仰再变化 ≥0.1° 或离开容差带才停。
 * roll 侧没有这个问题（`_isLevel` 边沿只在落值路径上更新），俯仰是唯一漏网。
 *
 * 证伪点（bodyOf 锁 `onSensorChanged` 函数体）：
 * 1. 闸门外的裸俯仰震动判定不许回来（回归即复现连震）；
 * 2. 俯仰落值闸门必须是花括号块，且震动判定出现在**块内** `_pitch.value = pitchDeg` 之后、
 *   块收括号**之前**（上一状态读落值前的存档值）。只有文本序 store<buzz 挡不住
 *   「提升局部变量+移出闸门+换序」的可编译回退形态，必须加包含性判定；
 * 3. roll 侧结构不许被顺手改坏：`_isLevel` 仍只在落值路径上翻转。
 */
class SensorBuzzGateGuardTest {

    private fun sensorChangedBody(): String =
        flatten(bodyOf(codeOnly(KotlinSourceScan.mainSourceText("camera/SensorLevel.kt")), "onSensorChanged"))

    @Test
    fun `俯仰震动不许写在落值闸门外的裸if里`() {
        assertFalse(
            "闸门外裸判定会让节流吞帧期「上帧未回正、本帧回正」逐帧成立，单震变连震",
            sensorChangedBody().contains("if (isLevelOf(pitchDeg) && !pitchWasLevel) buzz()")
        )
    }

    @Test
    fun `俯仰落值闸门必须是块且震动判定在落值之后`() {
        val body = sensorChangedBody()
        // 注意：_pitch.value = pitchDeg 在 primed 首帧分支里也有一处，必须锚定节流闸门之后取
        val gate = "if (abs(pitchDeg - _pitch.value) >= EMIT_STEP_DEG) {"
        val gateAt = body.indexOf(gate)
        assertTrue("俯仰落值闸门必须是花括号块（要装下落值+边沿震动两步）", gateAt >= 0)
        val storeAt = body.indexOf("_pitch.value = pitchDeg", gateAt)
        val buzzAt = body.indexOf("!pitchWasLevel && isLevelOf(pitchDeg)", gateAt)
        assertTrue("闸门块内必须有落值语句", storeAt >= 0)
        assertTrue(
            "震动判定必须在闸门块内、落值之后（上一状态取落值前的存档值）",
            buzzAt in (storeAt + 1)..body.length
        )
        // 包含性判定（flatten 保留花括号）：store 之后的第一枚收括号就是闸门块的收括号，
        // 判定被挪到块外（提升 val + 换序的可编译回退形态）时它在收括号之后，必红
        val gateCloseAt = body.indexOf('}', storeAt)
        assertTrue(
            "震动判定必须落在闸门块收括号之前：挪到块外即拿节流压住的存档值当上一状态，连震缺陷复活",
            gateCloseAt >= 0 && buzzAt < gateCloseAt
        )
    }

    @Test
    fun `roll的_isLevel边沿仍只在落值路径上更新`() {
        val body = sensorChangedBody()
        val earlyReturn = body.indexOf("if (!rollValid || abs(rollDeg - _roll.value) < EMIT_STEP_DEG) return")
        assertTrue("roll 节流早退必须还在", earlyReturn >= 0)
        // 只看尾段（primed 首帧分支里也有 _roll 落值，但那段不碰 _isLevel 翻转语义）
        val tail = body.substring(earlyReturn)
        val rollStore = tail.indexOf("_roll.value = rollDeg")
        val levelFlip = tail.indexOf("_isLevel.value = level")
        assertTrue("尾段必须有 _roll 落值", rollStore >= 0)
        assertTrue(
            "_isLevel 翻转必须晚于 _roll 落值（节流吞掉的帧不许碰边沿状态）",
            levelFlip in (rollStore + 1)..tail.length
        )
    }

    /** 守卫的输入必须是真实的 onSensorChanged 函数体（改名/挪走都要红，不许静默放行） */
    @Test
    fun `守卫锁的是onSensorChanged本体`() {
        val masked = codeOnly(KotlinSourceScan.mainSourceText("camera/SensorLevel.kt"))
        assertEquals(
            "onSensorChanged 函数体必须能唯一定位（守卫前提）",
            1,
            KotlinSourceScan.regionsOf(masked, "onSensorChanged").size
        )
    }
}

package com.wotagei.cam.record

import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制中自动停录与停止宽限的**判据核**单测（[AutoStopRules]，纯 Kotlin）。
 *
 * 三条判据都是产品安全边界，边界值逐个钉死：
 * - 电量：恰好等于阈值=停；读数无效（-1）=不停（宁漏巡检不误停）；
 * - 存储：严格小于门槛=停（等于门槛=继续——与「录制前 < 200MB 拒录」同一根线、同一口径）；
 * - 宽限：不足 500ms 返回差额，负时长（时钟未起走）按全程宽限钳制。
 *
 * 阈值引用常量而非字面量：变阈值只动 [AutoStopRules]，测试跟着常量走；但「边界语义」
 * （等于停不停、无效读数方向）必须在这里钉住，改口径必红。
 */
class AutoStopRulesTest {

    // ------------------------------------------------------------------ 电量判据

    @Test
    fun `电量判据_小于等于阈值停_大于阈值与无效读数不停`() {
        val cases = mapOf(
            AutoStopRules.BATTERY_STOP_PCT to true,   // 恰好等于阈值 = 停（含边）
            AutoStopRules.BATTERY_STOP_PCT - 1 to true,
            0 to true,
            AutoStopRules.BATTERY_STOP_PCT + 1 to false,
            100 to false,
            -1 to false,   // 粘性广播缺 EXTRA / 尚未收到：无效读数不许误停
            Int.MIN_VALUE to false,
        )
        cases.forEach { (pct, stop) ->
            assertEquals("电量 ${pct}% 判停结果应为 $stop", stop, AutoStopRules.batteryStopNeeded(pct))
        }
    }

    // ------------------------------------------------------------------ 存储判据

    @Test
    fun `存储判据_严格小于门槛停_等于门槛继续_探测失败值不停`() {
        val floor = WotaTiers.MIN_FREE_MB
        val cases = mapOf(
            floor - 1 to true,   // 差 1MB 也停：与录制前拒录同一根线
            0L to true,
            floor to false,      // 恰好等于门槛 = 继续（与 checkFreeSpace 的 `<` 同口径）
            floor + 1 to false,
            Long.MAX_VALUE to false,  // StatFs 探测失败的放行值（VideoStore.freeSpaceMb 同款）
        )
        cases.forEach { (mb, stop) ->
            assertEquals("可用 ${mb}MB 判停结果应为 $stop", stop, AutoStopRules.storageStopNeeded(mb))
        }
    }

    // ------------------------------------------------------------------ 停止宽限

    @Test
    fun `停止宽限_不足宽限线返回差额_到达或超过返回0_负时长按全程宽限钳制`() {
        val grace = AutoStopRules.STOP_GRACE_MS
        val cases = mapOf(
            0L to grace,
            1L to grace - 1,
            grace - 1 to 1L,
            grace to 0L,          // 恰好到达宽限线 = 立即收尾
            grace + 100 to 0L,
            -5L to grace,         // 时钟未起走：钳到全程宽限，不许给负延迟
        )
        cases.forEach { (elapsed, delay) ->
            assertEquals("已录 ${elapsed}ms 的宽限延迟应为 ${delay}ms", delay, AutoStopRules.stopGraceDelayMs(elapsed))
        }
    }

    // ------------------------------------------------------------------ 纯 JVM 纪律

    /** 判据核必须保持零 android import（平台读数薄壳在录制页/VideoStore，不许顺手塞进来） */
    @Test
    fun `AutoStopRules保持纯JVM_零android依赖`() {
        val masked = KotlinSourceScan.codeOnly(
            KotlinSourceScan.mainSourceText("record/AutoStopRules.kt")
        )
        assertTrue(
            "record/AutoStopRules.kt 必须保持纯 Kotlin（无 android/androidx import）：JVM 单测直打的前提",
            !masked.contains("import android") && !masked.contains("androidx")
        )
    }
}

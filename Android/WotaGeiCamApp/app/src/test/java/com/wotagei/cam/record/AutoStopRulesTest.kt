package com.wotagei.cam.record

import com.wotagei.cam.camera.DeviceStatus
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制中自动停录与停止宽限的**判据核**单测（[AutoStopRules]，纯 Kotlin）。
 *
 * 四条判据都是产品安全边界，边界值逐个钉死：
 * - 电量：恰好等于阈值=停；读数无效（-1）=不停（宁漏巡检不误停）；
 * - 存储：严格小于门槛=停（等于门槛=继续——与「录制前 < 200MB 拒录」同一根线、同一口径）；
 * - 设备丢失：只有「录制中由非 CLOSE 转移到 CLOSE」命中（快照/非录制/别的迁移都不许触发，
 *   转移横跨两拍的折叠由 [CameraLostWatch] 承担并在这里按**采样计划**直断言）；
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

    // ------------------------------------------------------------------ 设备丢失判据

    /**
     * 设备丢失判据全表（期望值手写）。负例三条是关键：
     * - CLOSE→CLOSE 快照不命中（CLOSE 同时是 [DeviceStatus] 初值：进页首拍/停录窗口都采样得到它）；
     * - 非录制态不命中（停录摘面重建会话撞上的 CLOSE 归停止路径自己收尾，这里再触发会与 stop 抢拆引擎）；
     * - 别的迁移（落到 OPEN_FAILED_* / 从 CLOSE 开起来）不命中。
     * 突变：把 `previous != DeviceStatus.CLOSE` 去掉 → 第 2 行必红；把 `recording` 去掉 → 第 3 行必红。
     */
    @Test
    fun `设备丢失判据_只有录制中非CLOSE到CLOSE的转移命中`() {
        // 真转移：录着像、设备态由任一非 CLOSE 态落到 CLOSE ⇒ 命中
        assertTrue(
            "录制中 OPEN_SUCCEED → CLOSE（系统收回）必须命中",
            AutoStopRules.cameraLostStopNeeded(true, DeviceStatus.OPEN_SUCCEED, DeviceStatus.CLOSE)
        )
        assertTrue(
            "录制中 OPEN_ING → CLOSE 同样命中（重开窗口里丢设备）",
            AutoStopRules.cameraLostStopNeeded(true, DeviceStatus.OPEN_ING, DeviceStatus.CLOSE)
        )
        assertTrue(
            "录制中 OPEN_FAILED_* → CLOSE 命中（失败态的后续收回也是真丢失）",
            AutoStopRules.cameraLostStopNeeded(true, DeviceStatus.OPEN_FAILED_IN_USED, DeviceStatus.CLOSE)
        )
        // 快照：初值就是 CLOSE，快照判定会把首拍误当丢失（本机制存在的另一半理由）
        assertFalse(
            "CLOSE → CLOSE 快照不许命中（初值/已停态，不是转移）",
            AutoStopRules.cameraLostStopNeeded(true, DeviceStatus.CLOSE, DeviceStatus.CLOSE)
        )
        // 非录制态：CLOSE 归停止路径，观察钩子不许代收
        assertFalse(
            "非录制态的 CLOSE 不许命中（与停止收尾抢拆引擎）",
            AutoStopRules.cameraLostStopNeeded(false, DeviceStatus.OPEN_SUCCEED, DeviceStatus.CLOSE)
        )
        assertFalse(
            "非录制态 + 快照 CLOSE 更不许命中",
            AutoStopRules.cameraLostStopNeeded(false, DeviceStatus.CLOSE, DeviceStatus.CLOSE)
        )
        // 别的迁移不是丢失（哪怕在录制中）：失败态本身由重开/失败路径接管，CLOSE 才是本判据的信号
        assertFalse(
            "OPEN_SUCCEED → OPEN_FAILED_IN_USED 是别的失败迁移，不许命中",
            AutoStopRules.cameraLostStopNeeded(true, DeviceStatus.OPEN_SUCCEED, DeviceStatus.OPEN_FAILED_IN_USED)
        )
        assertFalse(
            "CLOSE → OPEN_SUCCEED（开起来了）不许命中",
            AutoStopRules.cameraLostStopNeeded(true, DeviceStatus.CLOSE, DeviceStatus.OPEN_SUCCEED)
        )
    }

    /**
     * 「计划→行为」桥：把一串 `(录制态, 设备态采样)` 计划喂给 [CameraLostWatch]（运行时同款折叠），
     * 断言**触发拍号**。删掉/写反转移折叠（只看快照、命中后不清上一拍）在这里必红——
     * 判据单点全表还有个"折叠"错误维度，只有序列断言覆盖得到。
     */
    @Test
    fun `设备丢失观察_采样计划折叠成触发拍号`() {
        val watch = CameraLostWatch()
        // 计划：进页 → 开相机 → 起录 → 系统收回 → 后续仍 CLOSE → 收尾 → 第二场录制再丢一次
        val plan = listOf(
            false to DeviceStatus.CLOSE,        // 0 进页首拍：设备态初值快照——不许触发
            false to DeviceStatus.OPEN_ING,     // 1
            false to DeviceStatus.OPEN_SUCCEED, // 2
            true to DeviceStatus.OPEN_SUCCEED,  // 3 起录（设备态未变）
            true to DeviceStatus.CLOSE,         // 4 系统收回：唯一该在首场触发的拍
            true to DeviceStatus.CLOSE,         // 5 后续采样仍 CLOSE：不许重复触发（一次会话一发）
            false to DeviceStatus.CLOSE,        // 6 用户手动停/收尾窗口
            true to DeviceStatus.OPEN_SUCCEED,  // 7 第二场录制、设备已恢复
            true to DeviceStatus.CLOSE          // 8 再次收回：新会话可再触发一次
        )
        val fired = plan.withIndex()
            .filter { (_, tap) -> watch.onSample(tap.first, tap.second) }
            .map { it.index }
        assertEquals("触发拍号必须是 [4, 8]（首拍快照不算、命中后不重复、新会话再算一次）", listOf(4, 8), fired)
    }

    /**
     * 未录制时设备被收走的边界（本机制的**口径边界**，不是遗漏）：起录前设备态已落到 CLOSE 且一直
     * 不收，不带转移的快照不触发——这段画面本就起不来，由起录自检（~3s 窗口）判废兜底；
     * 若放宽成"录制中见到 CLOSE 快照即停"，每次起录都会在设备态未及刷新的那一拍误停。
     */
    @Test
    fun `设备丢失观察_未录制时收走的快照起录后不补触发`() {
        val watch = CameraLostWatch()
        val plan = listOf(
            false to DeviceStatus.OPEN_SUCCEED, // 预览中
            false to DeviceStatus.CLOSE,        // 起录准备窗口里设备被收走（非录制态：不触发）
            true to DeviceStatus.CLOSE,         // 起录时设备态还是 CLOSE（快照：不触发）
            true to DeviceStatus.CLOSE          // 设备一直没收回来：始终不触发
        )
        val fired = plan.withIndex()
            .filter { (_, tap) -> watch.onSample(tap.first, tap.second) }
            .map { it.index }
        assertEquals("不带转移的 CLOSE 快照一律不触发（起录准备窗口的丢失归起录自检）", emptyList<Int>(), fired)
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

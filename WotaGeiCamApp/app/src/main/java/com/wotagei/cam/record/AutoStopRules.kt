package com.wotagei.cam.record

import com.wotagei.cam.core.WotaTiers

/**
 * 录制中自动停录与停止宽限的**判据核**（纯 Kotlin，零 android import——JVM 单测直打的前提，
 * 与 UpdateChecker 同纪律）。平台读数薄壳不在这里：电量读数在录制页（registerReceiver 粘性广播）、
 * 存储读数复用 [VideoStore.freeSpaceMb]（StatFs 当前卷，探测失败返回 Long.MAX_VALUE 天然不停）。
 *
 * 来源（2026-10-12 调研，见 `research/refs/oss/opencamera-录制机制调研-2026-10-12.md`）：
 * OpenCamera（GPLv3，只借鉴机制与阈值，零代码复制）的两条实证防线——
 * 1. **电量 ≤3% 主动停**：设备关机瞬间编码器/muxer 收不了尾，整段废片（它家注释：一般设备
 *    1% 才关机，3% 提前停留出收尾窗口）；
 * 2. **可用空间不足主动停**：跨 app 竞争写满磁盘时保住已录内容——宁可早停出一个完整文件，
 *    不赌「写满那一刻还能正常收尾」。
 *
 * 本工程口径：存储门槛复用 [WotaTiers.MIN_FREE_MB]（录制前拒录同一根线——进不了录的空间
 * 也不该继续录）；电量阈值 3% 是产品策略常量，不是机型能力值（不受「禁按机型写死」约束，
 * 与 OpenCamera 实证值一致）。
 */
object AutoStopRules {

    /** 电量停录阈值（%）：≤ 此值主动收尾（OpenCamera 实证值） */
    const val BATTERY_STOP_PCT = 3

    /**
     * 巡检节流：两次自动停录巡检的最小间隔（录制页 pollTask 200ms 一拍上做时间闸）。
     * 电量掉 1% 通常分钟级、磁盘被别人写满也要过程，30s 足够及时；StatFs/广播读取不为零开销，
     * 不必 200ms 全速查。
     */
    const val CHECK_INTERVAL_MS = 30_000L

    /**
     * 快速停止宽限：START 后不足此时长的停止请求延迟到此时长再真正收尾。
     * OpenCamera 实证：MediaRecorder.start() 后立刻 stop() 会抛 RuntimeException 且文件损坏，
     * 它家直接忽略 <500ms 的停止请求；本工程取保守版——**不忽略**（用户点了停就该停），
     * 改为把真正收尾推到 500ms，秒点秒停得到正常收尾（极短成片或按既有 keep 判据判废）。
     */
    const val STOP_GRACE_MS = 500L

    /** [RecordResult.autoStopReason] 载荷：电量过低自动停录 */
    const val REASON_BATTERY = "BATTERY"

    /** [RecordResult.autoStopReason] 载荷：存储不足自动停录 */
    const val REASON_STORAGE = "STORAGE"

    /**
     * 电量停录判据（纯函数）：[levelPct] ≤ [BATTERY_STOP_PCT] 即停。
     * 读数无效（<0 = 粘性广播缺 EXTRA_LEVEL/EXTRA_SCALE 或尚未收到）**不停**——
     * 宁可漏一次巡检，也不许拿无效读数误停用户正在录的内容。
     */
    fun batteryStopNeeded(levelPct: Int): Boolean = levelPct in 0..BATTERY_STOP_PCT

    /**
     * 存储停录判据（纯函数）：< [WotaTiers.MIN_FREE_MB] 停（严格小于；读数薄壳探测失败时
     * 给 [Long.MAX_VALUE]，天然不停）。门槛与录制前拒录共用同一常量：能进录的空间才允许继续录。
     */
    fun storageStopNeeded(freeMb: Long): Boolean = freeMb < WotaTiers.MIN_FREE_MB

    /**
     * 快速停止宽限（纯函数）：已录 [elapsedMs] 不足 [STOP_GRACE_MS] 时返回**还需等待**的
     * 毫秒数（凑到宽限线再收尾），否则 0 = 立即收尾。负数已录时长（时钟未起走）按全程宽限处理。
     */
    fun stopGraceDelayMs(elapsedMs: Long): Long =
        (STOP_GRACE_MS - elapsedMs).coerceIn(0L, STOP_GRACE_MS)
}

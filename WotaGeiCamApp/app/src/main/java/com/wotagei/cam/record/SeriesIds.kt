package com.wotagei.cam.record

import java.util.concurrent.atomic.AtomicLong

/**
 * 分段录制系列 id（`media_series.seriesId` / `series_part.seriesId` 的那一列）。
 *
 * # 为什么必须是 [Long] 而不是别的
 * Room 侧 `MediaSeries.seriesId` 是 **Long 主键**、`SeriesPart.seriesId` 也是 Long。
 * 把实体改成 String 主键就是改列的 affinity ⇒ 改 schema ⇒ 撞上 `WotaDb` 的
 * `fallbackToDestructiveMigration()` ⇒ **升级即清空用户的收藏 / 标签 / 回收站 / 系列**。
 * 所以生成端必须归一到 Long，实体一个字都不动，也就不需要写 Migration。
 *
 * # 为什么不用「时间戳 + 随机数」
 * 老实现是 `"S" + System.currentTimeMillis() + "_" + Random.nextInt(1000)`
 * （`docs/scan/S2` M4-07 登记的碰撞项）：同毫秒内只有 1000 个值，批量起录或自动重录
 * 挤在同一毫秒时会撞。撞了的代价不是"重试一次"——两个系列的段落会互挂，组卡里出现
 * 别人的片段。
 *
 * 现在是**时间基 + 进程内单调序号**：
 * - 跨进程/重启：时间基不同 ⇒ 不撞；
 * - 同毫秒内并发：`AtomicLong.updateAndGet { max(it + 1, pack(now, 0)) }` 里的 `it + 1`
 *   保证进程内**严格单调、绝不重复**，哪怕时钟回拨；
 * - 数值安全：`nowMs * 1000` 上界约 1.7e15，远小于 Long 上限 9.2e18，不溢出。
 */
internal object SeriesIds {

    /** 序号占的位数：低位留给进程内序号，高位是时间基 */
    private const val SEQ_SPAN = 1000L

    private val last = AtomicLong(0L)

    /**
     * 打包：时间基占高位、同毫秒内序号占低位。
     * 固定 [nowMs] 下 [seq] 递增 ⇒ 结果严格递增（真断言，见 `SeriesIdTest`）。
     */
    fun packSeriesId(nowMs: Long, seq: Long): Long = nowMs * SEQ_SPAN + (seq % SEQ_SPAN)

    /**
     * 取下一个系列 id：严格单调，进程内绝不重复。
     *
     * `max(it + 1, …)` 里的 `it + 1` 是**关键**：时钟回拨、或同一毫秒内连续调用时，
     * 光靠时间基会给出相同或更小的值，靠它兜住。
     */
    fun nextSeriesId(nowMs: Long = System.currentTimeMillis()): Long =
        last.updateAndGet { prev -> maxOf(prev + 1, packSeriesId(nowMs, 0)) }

    /** 仅测试用：把状态归零（生产代码不许调） */
    internal fun resetForTest() = last.set(0L)
}

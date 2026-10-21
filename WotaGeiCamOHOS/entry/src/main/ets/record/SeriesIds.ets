/**
 * 直译自上游 record/SeriesIds.kt（harmony 分支）。
 * 平台差异：AtomicLong.updateAndGet 的原子性由 ArkTS 单线程事件循环语义承担
 * （生成端只在录制启动路径调用）；Kotlin Long 上界防御（TIME_CAP_MS）在 number
 *（双精度）下真实时钟（≈1.8e12 ms）打包值 < 2^53 完全精确，极端防御输入
 *（>9e15 ms）可能损失低位精度——只防回绕语义，可接受。
 */

/**
 * 分段录制系列 id（media_series.seriesId / series_part.seriesId 的那一列）。
 *
 * 为什么必须是整数而不是字符串：数据库侧该列是整数主键，改成字符串就是改 schema
 * ⇒ 升级即清空用户的收藏/标签/回收站/系列。所以生成端归一到整数，一个字都不动。
 *
 * 为什么不用「时间戳 + 随机数」：同毫秒内只有 1000 个值，批量起录挤在同一毫秒会撞；
 * 撞了的代价是两个系列的段落互挂，组卡里出现别人的片段。
 *
 * 现在是时间基 + 进程内单调序号：跨进程/重启时间基不同不撞；同毫秒内并发靠
 * `max(prev + 1, pack(now, 0))` 里的 +1 保证进程内严格单调、绝不重复，哪怕时钟回拨。
 */
export class SeriesIds {
  /** 序号占的位数：低位留给进程内序号，高位是时间基 */
  private static readonly SEQ_SPAN: number = 1000;

  /**
   * 时间基安全上界：`TIME_CAP_MS * 1000 + 999` 距真实时钟（约 1.8e12）还有约 28 万年
   * 的毫秒余量；夹住后同上界的多次生成仍由 nextSeriesId 的 max(prev+1, …) 保序。
   */
  private static readonly TIME_CAP_MS: number = 9_000_000_000_000_000;

  private static last: number = 0;

  /**
   * 打包：时间基占高位、同毫秒内序号占低位。固定 nowMs 下 seq 递增 ⇒ 结果严格递增。
   * nowMs 先夹进 [1, TIME_CAP_MS]：下界 1 是因为 0 是「无归属」哨兵值（record 见 0 即弃），
   * 打包结果不许是 0；上界防溢出语义。
   */
  static packSeriesId(nowMs: number, seq: number): number {
    const clamped: number = Math.min(SeriesIds.TIME_CAP_MS, Math.max(1, nowMs));
    return clamped * SeriesIds.SEQ_SPAN + (seq % SeriesIds.SEQ_SPAN);
  }

  /**
   * 取下一个系列 id：严格单调，进程内绝不重复。
   * `max(prev + 1, …)` 里的 +1 是关键：时钟回拨或同一毫秒内连续调用时，
   * 光靠时间基会给出相同或更小的值，靠它兜住。
   */
  static nextSeriesId(nowMs: number = Date.now()): number {
    const packed: number = SeriesIds.packSeriesId(nowMs, 0);
    SeriesIds.last = Math.max(SeriesIds.last + 1, packed);
    return SeriesIds.last;
  }

  /** 仅测试用：把状态归零（生产代码不许调） */
  static resetForTest(): void {
    SeriesIds.last = 0;
  }
}

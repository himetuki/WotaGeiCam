/**
 * 离线光弧修复的成功/可保留判据（直译上游 record/ArcRepairRunner.kt 的两个纯函数，对齐上游
 * 38d712a）。
 *
 * 为什么会存在这两条：
 * - 存储写满时 muxer 的 writeSampleData 会逐样本抛错（修复管线把异常吞掉只记日志），而 moov
 *   在 muxer stop 之前没写出去 ⇒ 成片只剩 ftyp 头；只看"mux 起了 + 轨建了"会把这份废片判成
 *   成功并 commit 进相册。零样本整废是必须拦的形态。
 * - 只拦零样本：部分样本失败（可播但缺帧）不判失败（把整段白修的产物改判失败代价不对称）；
 *   写失败另有日志与 writeFailCode 留痕。
 * - stop 必须单列一格：stop() 是写 moov 的步骤，它抛异常时文件通常只有 ftyp 头。旧 finish 把
 *   stop 异常吞成一行日志后仍 seal(keep=ok)，把坏容器 commit 进相册并报成功——这条判据把
 *   "容器没封口"从成功里剔出去。
 *
 * 纯逻辑（不 import 平台 Kit）；执行器（ArcRepairSession）留 T29。
 */

/** 修复成功判据：muxer 已 start + 视频轨已 addTrack + 至少写出一个视频样本 */
export function arcRepairOk(
  muxStarted: boolean,
  videoTrackAdded: boolean,
  writtenVideoSamples: number
): boolean {
  return muxStarted && videoTrackAdded && writtenVideoSamples > 0;
}

/** 修复产物"可保留"的最终判据：写样本成功（ok）且 muxer.stop() 正常返回 */
export function arcRepairKeep(ok: boolean, muxStopOk: boolean): boolean {
  return ok && muxStopOk;
}

/**
 * 编码器呈现时间戳的**网格口径**（PTS-GRID 批，2026-10-25；直译上游
 * `camera/NtscGrid.kt` + `GlRenderEngine.encoderSlotPtsNs/encoderAdaptivePtsNs/encoderSlotGap`）。
 *
 * 为什么需要（上游真机取证口径）：不落网格、直接用换帧瞬间的系统时钟，会因相机帧到达抖动
 * 凑不出公共帧长，成片 `r_frame_rate` 被报成 90000/1；而按**帧序号**取网格又隐含「源实到帧率 =
 * 目标帧率」，半精确档（24※）不满足该假设——真实 31.85s 的录制会写成 34.21s 视频轨。
 * 定版口径：**落网格 + 网格位置取自真实时钟**（`(now − base) / step`），源慢于名义时由
 * 自适应接管（逐帧判定，无跨帧状态）。
 *
 * 本文件只交付**纯算式**（零平台 import，可 full test）；native 侧逐帧打戳的接线属
 * Codec/GL 平台面（`cpp/codec_recorder.cpp` 现用墙钟戳 + 单调守卫），随真机批落地并验收
 * ——录制件只能靠录一段看 `r_frame_rate`/帧长分布验证，本仓纪律：不可验证的平台面不落地。
 */
export class EncoderPts {
  /** 目标帧率不可用（<=0）时的统一回退 */
  static readonly NO_FPS: number = 0;

  /**
   * NTSC 非整数档的网格步长（ns）。24/30/60 三档是 24000/1001、30000/1001、60000/1001，
   * `1e9/fps` 取整后的步长与真实周期差 0.1%（系统性，683 帧攒 7ms）。
   * 步长取 `round(1e9 × 1001 / (1000 × fps))`：24→41708333、30→33366667、60→16683333。
   * **不在表里返回 0**，调用方必须退回 `1e9/fps`——把 0 当步长会让整段戳并成一枚（成片只有一帧）。
   * 25/50 是 exact 档不进表；120/240 本工程可选档里不出现。
   */
  static ntscGridStepNs(fps: number): number {
    let den: number;
    if (fps === 24) {
      den = 24000;
    } else if (fps === 30) {
      den = 30000;
    } else if (fps === 60) {
      den = 60000;
    } else {
      return 0;
    }
    // +den/2 是整数四舍五入（分子恒正）
    return Math.floor((1000000000 * 1001 + den / 2) / den);
  }

  /** 整数档步长（ns）；fps 不可用返回 0 */
  static integerStepNs(fps: number): number {
    if (fps <= 0) {
      return 0;
    }
    return Math.floor(1000000000 / fps);
  }

  /**
   * 网格落戳的**共同内核**（上游 `slotGridPtsNs`，文件私有；此处为唯一真源）：
   * 退基准（elapsed<=0）+ 落网格（elapsed/step 取整）+ 夹地板（不退过上一枚）。
   * 两份网格算术各写一遍是本工程吃过的「恒等式假证明」之源，故只此一份。
   */
  private static slotGridPtsNs(baseNs: number, nowNs: number, stepNs: number, lastPtsNs: number): number {
    const elapsed: number = nowNs - baseNs;
    const pts: number = elapsed <= 0 ? baseNs : baseNs + Math.floor(elapsed / stepNs) * stepNs;
    return pts > lastPtsNs ? pts : lastPtsNs;
  }

  /**
   * 名义网格落戳（上游 `encoderSlotPtsNs`）：落网格保帧长齐，网格位置取自真实时钟保总时长。
   * 允许与上一枚相等（同网格多帧），绝不小于它。
   */
  static encoderSlotPtsNs(baseNs: number, nowNs: number, fps: number, lastPtsNs: number): number {
    const step: number = EncoderPts.integerStepNs(fps);
    if (step <= 0) {
      return lastPtsNs;
    }
    return EncoderPts.slotGridPtsNs(baseNs, nowNs, step, lastPtsNs);
  }

  /**
   * 逐帧自适应的网格戳（上游 `encoderAdaptivePtsNs`，采纳形态 B）：
   * 源慢于名义时 `PTS = max(base + elapsed, lastPts)` 接管（帧长恒定、无空洞），
   * 源快于名义时 `frameIndex × step >= elapsed` 恒真 → 走名义网格（与旧口径逐字相同）。
   *
   * 两处必须写实：① 段首退化只在 `frameIndex <= 0` 严格逐字同旧（frameIndex=1 时只要
   * elapsed > step 就已接管）；② 末帧跨度 = `(n−1)/实际fps + 末帧曝光`，与 `n/实际fps`
   * 差一帧量级——端到端断言必须落在容差带，不许当恒等式。
   *
   * @param stepNs 网格步长（ns）>0；整数档 `1e9/fps`，NTSC 档用 ntscGridStepNs。**本函数不认 fps**
   *   （NTSC 档把 fps 传进来会退回整数网格——上游 #2 的坑）。
   */
  static encoderAdaptivePtsNs(baseNs: number, nowNs: number, stepNs: number,
    lastPtsNs: number, frameIndex: number): number {
    if (stepNs <= 0) {
      return lastPtsNs;
    }
    // 段首无样本：必须早于接管判定（frameIndex=0 时 elapsed>0 恒真，会把首帧推成 nowNs）
    if (frameIndex <= 0) {
      return EncoderPts.slotGridPtsNs(baseNs, nowNs, stepNs, lastPtsNs);
    }
    const elapsed: number = nowNs - baseNs;
    if (elapsed <= 0) {
      return EncoderPts.slotGridPtsNs(baseNs, nowNs, stepNs, lastPtsNs);
    }
    const candidate: number = elapsed > frameIndex * stepNs
      ? baseNs + elapsed
      : EncoderPts.slotGridPtsNs(baseNs, nowNs, stepNs, lastPtsNs);
    return candidate > lastPtsNs ? candidate : lastPtsNs;
  }

  /**
   * 相邻两枚网格戳之间的**空档格数**（上游 `encoderSlotGap`，观测口径）：
   * 只数不改（如实反映真实缺口，不压缩）。首帧/未前进/档位不可用一律 0。
   */
  static encoderSlotGap(lastPtsNs: number, ptsNs: number, fps: number): number {
    if (fps <= 0 || lastPtsNs <= 0 || ptsNs < lastPtsNs) {
      return 0;
    }
    const step: number = EncoderPts.integerStepNs(fps);
    if (step <= 0) {
      return 0;
    }
    const jumped: number = Math.floor((ptsNs - lastPtsNs) / step);
    return jumped > 1 ? jumped - 1 : 0;
  }

  /** 段内实测帧率（厘帧/秒，调用方除 100 取两位）；n=0 或 span<=0 报 0（不崩不吹天文数字） */
  static measuredFpsCenti(frames: number, spanNs: number): number {
    if (frames <= 0 || spanNs <= 0) {
      return 0;
    }
    return Math.floor(frames * 100 * 1000000000 / spanNs);
  }
}

/**
 * 双轴时间线内核（player/CompareTimeline.ets）的行为级单测——对拍上游
 * app/src/test/java/com/wotagei/cam/player/CompareTimelineTest.kt（对齐上游 38d712a）。
 *
 * 覆盖分两批：
 * - T19 几何最小版（9 条）：TimelineGeometry + normalizeOffset + rightTargetFor +
 *   timelineSpan/timelineEnd；**off=0 两条回归红线原样保留**；
 * - T24 全量内核（16 条）：域判定/主钟/交接/入口态/纠偏/时间线末/AB/拖动/pre-roll 自洽，
 *   与上游 18 个测试方法逐一对位（几何 2 条已在前批）。期望值全部手写字面量。
 * - T26 审查修复（1 条）：rightResumeTargetOf 四域臂——右片重建恢复目标必须由**重建前快照**
 *   做域判定（右独播尾段/pre-roll/左主钟双素材/左独播 park），新引擎 attach 后的 0 不得参与。
 *
 * off=0 回归红线（两条独立用例钉死）：
 * - 几何红线：Tmin=0、Tmax=max(L,R)（右片没选 R=0 时 Tmax=L，与旧单左轴完全一致）；
 * - 纠偏红线：rightTargetFor(t, 0, R) = t（期望位=左片时间，旧公式取值一致）。
 */
import {
  CompareDomain, DecideCommands, DecideInput, TimelineGeometry, decide, domainOf, normalizeOffset,
  resolveT, rightResumeTargetOf, rightTargetFor, timelineEnd, timelineSpan
} from '../../entry/src/main/ets/player/CompareTimeline';

/** decide 输入底座（上游测试 input() 的 ArkTS 版：L=10s、R=20s、off=0、中段在播） */
function mkInput(
  geo: TimelineGeometry,
  leftLive: number,
  rightLive: number,
  userPlaying: boolean = true,
  abLoop: boolean = false,
  timelineLoop: boolean = true,
  dragging: boolean = false,
  blackLeft: boolean = false,
  blackRight: boolean = false,
  hardResyncMs: number = 250
): DecideInput {
  return new DecideInput(geo, leftLive, rightLive, userPlaying, abLoop, timelineLoop, dragging,
    blackLeft, blackRight, hardResyncMs);
}

test('off0 回归红线 Tmin0 Tmax取两片大者', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  assertEq(0, g.tMin(), 'L=10s R=6s Tmin');
  assertEq(10000, g.tMax(), 'L 大取 L');
  const g2: TimelineGeometry = new TimelineGeometry(4000, 9000, 0);
  assertEq(0, g2.tMin(), 'L=4s R=9s Tmin');
  assertEq(9000, g2.tMax(), 'R 大取 R');
  // 右片没选（R=0）：Tmax=L，与旧单左轴口径完全一致
  const g0: TimelineGeometry = new TimelineGeometry(10000, 0, 0);
  assertEq(0, g0.tMin(), 'R=0 Tmin');
  assertEq(10000, g0.tMax(), 'R=0 Tmax=L');
});

test('off0 回归红线 纠偏期望位等于自身', (): void => {
  // off=0：desired = T + 0 = T，纠偏/拖动落位与旧单左轴公式取值一致
  assertEq(0, rightTargetFor(0, 0, 10000), '头部');
  assertEq(4200, rightTargetFor(4200, 0, 10000), '中段');
  assertEq(9999, rightTargetFor(9999, 0, 10000), '尾前');
  assertEq(10000, rightTargetFor(12000, 0, 10000), '超尾夹到 R');
});

test('几何 pre-roll 与尾段的 park 落点（off>0）', (): void => {
  const pre: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  assertEq(-5000, pre.tMin(), 'Tmin=-off');
  assertEq(15000, pre.tMax(), 'Tmax=max(L,R-off)');
  assertEq(0, pre.leftTarget(-2000), 'pre-roll 左 park 到 0');
  assertEq(3000, pre.rightTarget(-2000), 'pre-roll 右在素材域内即 T+off');
  assertEq(0, pre.leftTarget(0), 'T=0 左');
  assertEq(5000, pre.rightTarget(0), 'T=0 右=off');
  // T 越过左尾（10s）而右还在：左 park 到 durMs（不是 dur-1），右照常落素材位 T+off
  assertEq(10000, pre.leftTarget(12000), '左越尾 park 到 durMs');
  assertEq(17000, pre.rightTarget(12000), '右照常 T+off');
});

test('几何 off 负 头段 park 与尾段（off<0）', (): void => {
  const neg: TimelineGeometry = new TimelineGeometry(10000, 20000, -3000);
  assertEq(0, neg.tMin(), 'Tmin=min(0,-off)=0 左头');
  assertEq(23000, neg.tMax(), 'Tmax=max(L,R-off)');
  assertEq(2500, neg.leftTarget(2500), '覆盖区内左原样');
  assertEq(0, neg.rightTarget(2500), '右素材段前 park 到 0');
  assertEq(0, neg.rightTarget(3000), '含边界 T=|off| 右=0（交接沿）');
  assertEq(10000, neg.leftTarget(13500), '左越尾 park 到 durMs');
  assertEq(10500, neg.rightTarget(13500), '右照常 T+off');
});

test('覆盖判定 两端开闭区间与偏移域', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  assertTrue(g.leftCovers(0), '左含起点 0');
  assertFalse(g.leftCovers(-1), 'pre-roll 左无画面');
  assertFalse(g.leftCovers(10000), '左开区间右端（dur 不覆盖）');
  assertTrue(g.leftCovers(9999), '左最后覆盖帧');
  assertFalse(g.rightCovers(-5001), '右素材段前');
  assertTrue(g.rightCovers(-5000), '右含起点 T=-off');
  assertTrue(g.rightCovers(14999), '右最后覆盖帧');
  assertFalse(g.rightCovers(15000), '右开区间右端');
  // 防御：负时长夹 0（本侧 TimelineGeometry 构造钳制，登记偏离上游 data class）
  const clamped: TimelineGeometry = new TimelineGeometry(-5, 10, 0);
  assertEq(10, clamped.tMax(), '负左时长夹 0 后 Tmax=R');
});

test('normalizeOffset 在负右时长到左时长区间夹紧', (): void => {
  assertEq(0, normalizeOffset(0, 10000, 20000), '0 不动');
  assertEq(2000, normalizeOffset(2000, 10000, 20000), '域内不动');
  assertEq(10000, normalizeOffset(99999, 10000, 20000), '超上界夹到 L');
  assertEq(-20000, normalizeOffset(-99999, 10000, 20000), '超下界夹到 -R');
  assertEq(-20000, normalizeOffset(-99999, 0, 20000), 'L=0 时上界 0 下界 -R');
  // 再往两边拉只会让一侧整段停帧，没有信息增量；-0 归一 +0（Object.is 口径）
  assertEq(0, normalizeOffset(-500, 0, 0), '双 0 域夹到 0');
});

test('rightTargetFor 夹到 0 到 R', (): void => {
  assertEq(5000, rightTargetFor(0, 5000, 20000), '左头 → 右落 off（域内）');
  assertEq(0, rightTargetFor(-3000, 0, 20000), '负左位夹 0');
  assertEq(20000, rightTargetFor(99999, 3000, 20000), '超 R 夹 R');
  assertEq(0, rightTargetFor(100, 5000, 0), 'R=0 一律 0');
  assertEq(7000, rightTargetFor(2000, 5000, 20000), '域内 = leftPos+off');
});

test('timelineSpan 与 timelineEnd', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  assertEq(15000, timelineEnd(g), 'end=tMax=max(L,R-off)');
  assertEq(20000, timelineSpan(g), 'span=tMax-tMin（负轴计入总长）');
  const off0: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  assertEq(10000, timelineSpan(off0), 'off=0 span=max(L,R)');
  assertEq(10000, timelineEnd(off0), 'off=0 end=max(L,R)');
  const neg: TimelineGeometry = new TimelineGeometry(10000, 20000, -3000);
  assertEq(23000, timelineSpan(neg), 'off<0 span');
});

test('park 越尾落素材 durMs 而非 dur-1（独立红线）', (): void => {
  // 拖到 Tmax 把两轨 park 到各自 durMs（ENDED 位）：落 dur-1 会被覆盖判定误判仍在域内
  const g: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  assertEq(10000, g.leftTarget(10000), '左 park durMs');
  assertEq(6000, g.rightTarget(10000), '右 park durMs');
  assertFalse(g.leftCovers(g.leftTarget(10000)), 'park 位在覆盖区外');
  assertFalse(g.rightCovers(g.rightTarget(10000)), 'park 位在覆盖区外');
});

// ===========================================================================
// T24 全量内核（16 条，对位上游 CompareTimelineTest 的域判定/交接/纠偏/末段/拖动）
// ===========================================================================

test('域判定 pre-roll入口与黑层在册消歧', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  // 无在册黑层：右钟报出 T<0 也认得 pre-roll（安全网）
  assertEq(CompareDomain.RIGHT_SOLO, domainOf(g, 0, 4900, false, false), 'pre-roll 安全网');
  // 在册右独播：左停在覆盖边界 0 是 park 不是双素材域——没有黑层回灌位这里会误判 DUAL
  assertEq(CompareDomain.RIGHT_SOLO, domainOf(g, 0, 4900, true, false), '黑层在册锁主钟');
  // T 回到 0：双覆盖区，交回双主钟
  assertEq(CompareDomain.DUAL, domainOf(g, 0, 5000, true, false), '回双覆盖即交接');
});

test('域判定 off负头段 park在0不误判 回含边界即交接', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, -3000);
  assertEq(CompareDomain.LEFT_SOLO, domainOf(g, 1000, 0, false, false), 'off<0 头段左独播');
  const c: DecideCommands = decide(mkInput(g, 1000, 0));
  assertTrue(c.pauseRight, '右 dormant 停');
  assertTrue(c.blackRight, '右窗黑层');
  assertNull(c.seekLeftMs, '不许 seek 左');
  assertNull(c.seekRightMs, '不许 seek 右');
  assertNull(c.nudgeRightDriftMs, '不许 nudge');
  // 交接沿是含边界：T 到 |off| → 双素材域，右轨从素材 0 起播（resume，不需要 seek）
  assertEq(CompareDomain.DUAL, domainOf(g, 3000, 0, false, true), '含边界交接');
  const c2: DecideCommands = decide(mkInput(g, 3000, 0, true, false, true, false, false, true));
  assertTrue(c2.resumeRight, '右唤醒');
  assertFalse(c2.blackRight, '撤右黑层');
  assertNull(c2.seekRightMs, '交接不 seek');
});

test('域判定 双素材到尾段两向与时间线末', (): void => {
  const a: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  assertEq(CompareDomain.DUAL, domainOf(a, 9900, 9900, false, false), '尾前双素材');
  assertEq(CompareDomain.RIGHT_SOLO, domainOf(a, 10000, 10000, false, false), '左尽右独播');
  assertEq(CompareDomain.RIGHT_SOLO, domainOf(a, 10000, 15000, false, false), '右继续独播');
  assertEq(CompareDomain.TIMELINE_END, domainOf(a, 10000, 20000, false, false), '双尽时间线末');
  const b: TimelineGeometry = new TimelineGeometry(20000, 10000, 0);
  assertEq(CompareDomain.DUAL, domainOf(b, 9900, 9900, false, false), '短右尾前双素材');
  assertEq(CompareDomain.LEFT_SOLO, domainOf(b, 10000, 10000, false, false), '右尽左独播');
  assertEq(CompareDomain.LEFT_SOLO, domainOf(b, 19999, 10000, false, false), '左继续独播');
  assertEq(CompareDomain.TIMELINE_END, domainOf(b, 20000, 10000, false, false), '双尽');
  // 在册左独播：左在覆盖区内、右没素材 → 左独播；左也尽 → 时间线末（末态优先于在册域）
  assertEq(CompareDomain.LEFT_SOLO, domainOf(b, 15000, 10000, false, true), '在册左独播');
  assertEq(CompareDomain.TIMELINE_END, domainOf(b, 20000, 10000, false, true), '末态优先');
});

test('域判定 off0拖到最尾 park在素材尽头不误判双素材域', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  assertEq(CompareDomain.TIMELINE_END, domainOf(g, 10000, 6000, false, false), '双 park 到尽头');
});

test('resolveT 按域取主钟', (): void => {
  const pre: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  assertEq(-1000, resolveT(pre, 0, 4000, true, false), '右独播取右钟');
  assertEq(0, resolveT(pre, 0, 5000, true, false), '双覆盖取左钟');
  const dual: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  assertEq(5000, resolveT(dual, 5000, 5000, false, false), '双素材取左钟');
  const tailL: TimelineGeometry = new TimelineGeometry(20000, 10000, 0);
  assertEq(15000, resolveT(tailL, 15000, 10000, false, true), '左独播取左钟');
  const end: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  assertEq(10000, resolveT(end, 10000, 6000, false, false), '时间线末取 Tmax');
});

test('交接 pre-roll到双素材 只唤醒不seek', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  // 拍 1：T=−100，右独播（左 park 在 0 黑层中）：断言左停 + 左窗黑，不许有任何 seek
  const c1: DecideCommands = decide(mkInput(g, 0, 4900, true, false, true, false, true, false));
  assertEq(CompareDomain.RIGHT_SOLO, domainOf(g, 0, 4900, true, false), '拍1 域');
  assertTrue(c1.pauseLeft, '拍1 左停');
  assertTrue(c1.blackLeft, '拍1 左黑');
  assertFalse(c1.blackRight, '拍1 右不黑');
  assertNull(c1.seekLeftMs, '拍1 不 seek 左');
  assertNull(c1.seekRightMs, '拍1 不 seek 右');
  // 拍 2：T=+50 跨过 0：双素材域——只唤醒左（用户在播）+ 撤黑层 + 软追赶，仍不许 seek
  const c2: DecideCommands = decide(mkInput(g, 0, 5050, true, false, true, false, true, false));
  assertTrue(c2.resumeLeft, '拍2 唤醒左');
  assertFalse(c2.blackLeft, '拍2 撤左黑');
  assertNull(c2.seekLeftMs, '拍2 不 seek 左');
  assertNull(c2.seekRightMs, '拍2 不 seek 右');
  assertEq(50, c2.nudgeRightDriftMs ?? -1, '拍2 软追赶 50ms');
});

test('交接 双素材到尾段右向 左置dormant不seek', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  const c1: DecideCommands = decide(mkInput(g, 9900, 9900));
  assertEq(CompareDomain.DUAL, domainOf(g, 9900, 9900, false, false), '拍1 双素材');
  assertNull(c1.seekLeftMs, '拍1 不 seek');
  assertFalse(c1.pauseLeft, '拍1 左在播');
  const c2: DecideCommands = decide(mkInput(g, 10000, 10000));
  assertEq(CompareDomain.RIGHT_SOLO, domainOf(g, 10000, 10000, false, false), '拍2 右独播');
  assertTrue(c2.pauseLeft, '拍2 左停');
  assertTrue(c2.blackLeft, '拍2 左黑');
  assertNull(c2.seekLeftMs, '拍2 不 seek 左');
  assertNull(c2.seekRightMs, '拍2 不 seek 右');
  const c3: DecideCommands = decide(mkInput(g, 10000, 20000));
  assertEq(CompareDomain.TIMELINE_END, domainOf(g, 10000, 20000, false, false), '拍3 末');
  assertFalse(c3.blackLeft, '末帧不盖左黑');
  assertFalse(c3.blackRight, '末帧不盖右黑');
});

test('交接 双素材到尾段左向 右置dormant不seek', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(20000, 10000, 0);
  const c: DecideCommands = decide(mkInput(g, 10000, 10000));
  assertEq(CompareDomain.LEFT_SOLO, domainOf(g, 10000, 10000, false, false), '左独播');
  assertTrue(c.pauseRight, '右停');
  assertTrue(c.blackRight, '右黑');
  assertFalse(c.blackLeft, '左不黑');
  assertNull(c.seekLeftMs, '不 seek 左');
  assertNull(c.seekRightMs, '不 seek 右');
  // 用户暂停时跨进左独播：右保持停；回双覆盖区再点播放：交接唤醒只在 userPlaying 时下发
  const paused: DecideCommands = decide(mkInput(g, 10000, 10000, false, false, true, false, false, true));
  assertTrue(paused.pauseRight, '暂停时右停');
  assertFalse(paused.resumeRight, '暂停时不唤醒');
  const woke: DecideCommands = decide(mkInput(g, 9000, 9000, true, false, true, false, false, true));
  assertTrue(woke.resumeRight, '在播时唤醒右');
  assertFalse(woke.blackRight, '撤右黑');
});

test('入口双暂停态 decide是静止单纯形 起播归接线层', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  const idle: DecideCommands = decide(mkInput(g, 0, 0, false));
  assertEq(CompareDomain.DUAL, domainOf(g, 0, 0, false, false), '入口双覆盖');
  assertFalse(idle.resumeLeft, '不自唤醒左');
  assertFalse(idle.resumeRight, '不自唤醒右');
  assertFalse(idle.pauseLeft, '不停左');
  assertFalse(idle.pauseRight, '不停右');
  assertFalse(idle.blackLeft, '不盖左黑');
  assertFalse(idle.blackRight, '不盖右黑');
  assertNull(idle.seekLeftMs, '不 seek 左');
  assertNull(idle.seekRightMs, '不 seek 右');
  assertEq(0, idle.nudgeRightDriftMs ?? -1, 'drift=0 零值软追赶');
  const playing: DecideCommands = decide(mkInput(g, 0, 0, true));
  assertFalse(playing.resumeLeft, '起播后不 resume 左');
  assertFalse(playing.resumeRight, '起播后不 resume 右');
  assertFalse(playing.pauseLeft, '起播后不停左');
  assertFalse(playing.pauseRight, '起播后不停右');
});

test('双覆盖软追赶 邻域nudge与漂出硬seek共存', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 3000);
  // 邻域内（|drift|=100 ≤ 250）：只软追赶，不动位置、不碰 pause/黑层
  const nudge: DecideCommands = decide(mkInput(g, 5000, 8100));
  assertEq(CompareDomain.DUAL, domainOf(g, 5000, 8100, false, false), '邻域双素材');
  assertEq(100, nudge.nudgeRightDriftMs ?? -1, '软追赶 100ms');
  assertNull(nudge.seekRightMs, '不硬 seek');
  assertNull(nudge.seekLeftMs, '不 seek 左');
  assertFalse(nudge.pauseLeft, '不停左');
  assertFalse(nudge.pauseRight, '不停右');
  assertFalse(nudge.blackLeft, '不黑左');
  assertFalse(nudge.blackRight, '不黑右');
  // 漂出邻域（drift=300 > 250）：一次硬 seek 拉回期望位（T+off），不下发 nudge
  const seek: DecideCommands = decide(mkInput(g, 5000, 8300));
  assertNull(seek.nudgeRightDriftMs, '漂出不下 nudge');
  assertEq(8000, seek.seekRightMs ?? -1, '硬 seek 到 T+off');
});

test('off0回归红线 纠偏期望位就等于左片时间', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 10000, 0);
  const c: DecideCommands = decide(mkInput(g, 4000, 4200));
  assertEq(200, c.nudgeRightDriftMs ?? -1, 'off0 软追赶=差值');
  const c2: DecideCommands = decide(mkInput(g, 4000, 4600));
  assertEq(4000, c2.seekRightMs ?? -1, 'off0 硬 seek 回左片时间');
});

test('时间线末 循环开到尾即回绕Tmin', (): void => {
  // off=5000：Tmin=−off=−5000（pre-roll 是时间线的一段），回绕落 T=−5000：
  // 左 park 到 0（黑层在册，播进 T≥0 自然醒）、右落到自己素材头 0
  const pre: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  const w1: DecideCommands = decide(mkInput(pre, 10000, 20000));
  assertEq(0, w1.seekLeftMs ?? -1, '回绕 seek 左 0');
  assertEq(0, w1.seekRightMs ?? -1, '回绕 seek 右 0');
  assertFalse(w1.resumeLeft, 'Tmin 处左无素材不 resume');
  assertTrue(w1.pauseLeft, 'Tmin 处左 park 停');
  assertTrue(w1.blackLeft, 'Tmin 处左黑层');
  assertTrue(w1.resumeRight, 'Tmin 处右有素材 resume');
  assertFalse(w1.pauseRight, '右不停');
  assertFalse(w1.blackRight, '右不黑');
  // off=−3000：Tmin=0（左头），回绕落 T=0：左起播、右 park 到 0 带 pause（右素材段从 T=3000 起）
  const neg: TimelineGeometry = new TimelineGeometry(10000, 20000, -3000);
  const w2: DecideCommands = decide(mkInput(neg, 10000, 20000, true, false, true));
  assertEq(0, w2.seekLeftMs ?? -1, 'off<0 回绕 seek 左 0');
  assertEq(0, w2.seekRightMs ?? -1, 'off<0 回绕 seek 右 0');
  assertTrue(w2.resumeLeft, 'off<0 左 resume');
  assertFalse(w2.pauseLeft, 'off<0 左不停');
  assertFalse(w2.blackLeft, 'off<0 左不黑');
  assertFalse(w2.resumeRight, 'off<0 右不 resume');
  assertTrue(w2.pauseRight, 'off<0 右停');
  assertTrue(w2.blackRight, 'off<0 右黑');
  // off=0 回绕臂（回归红线钉死）：Tmin=0 处两轨都有素材——seek 双 0、resume 双真、
  // pause/黑层双假，与旧单左轴回绕行为逐位一致
  const off0: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  const w3: DecideCommands = decide(mkInput(off0, 10000, 6000));
  assertEq(0, w3.seekLeftMs ?? -1, 'off0 回绕 seek 左 0');
  assertEq(0, w3.seekRightMs ?? -1, 'off0 回绕 seek 右 0');
  assertTrue(w3.resumeLeft, 'off0 双 resume 左');
  assertTrue(w3.resumeRight, 'off0 双 resume 右');
  assertFalse(w3.pauseLeft, 'off0 不停左');
  assertFalse(w3.pauseRight, 'off0 不停右');
  assertFalse(w3.blackLeft, 'off0 不黑左');
  assertFalse(w3.blackRight, 'off0 不黑右');
});

test('时间线末 循环关停末帧且不自动重启', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  const stop: DecideCommands = decide(mkInput(g, 10000, 20000, false, false, false));
  assertNull(stop.seekLeftMs, '自然播完不 seek 左');
  assertNull(stop.seekRightMs, '自然播完不 seek 右');
  assertFalse(stop.blackLeft, '停末帧不黑左');
  assertFalse(stop.blackRight, '停末帧不黑右');
  // 播放意图还挂着（轮询滞后）也不能回绕：ENDED 后点播放再重播是 setPlaying 的职责
  const noRestart: DecideCommands = decide(mkInput(g, 10000, 20000, true, false, false));
  assertNull(noRestart.seekLeftMs, '循环关不自动重启左');
  assertNull(noRestart.seekRightMs, '循环关不自动重启右');
  const replay: DecideCommands = decide(mkInput(g, 10000, 20000, true, false, false));
  assertNull(replay.seekLeftMs, '重播走 setPlaying 不给 wrap');
});

test('AB段循环激活时 decide不抢时间线回绕', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  const c: DecideCommands = decide(mkInput(g, 10000, 20000, true, true, true));
  assertNull(c.seekLeftMs, 'AB 激活不 seek 左');
  assertNull(c.seekRightMs, 'AB 激活不 seek 右');
  assertFalse(c.resumeLeft, 'AB 激活不 resume');
});

test('拖动让路 压seek/nudge/wrap 素材域pause黑层照做', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 3000);
  const d: DecideCommands = decide(mkInput(g, 5000, 8300, true, false, true, true));
  assertNull(d.seekRightMs, '双素材拖动不硬 seek');
  assertNull(d.nudgeRightDriftMs, '双素材拖动不 nudge');
  assertFalse(d.pauseLeft, '双素材拖动不 pause');
  const e: TimelineGeometry = new TimelineGeometry(10000, 6000, 0);
  const w: DecideCommands = decide(mkInput(e, 10000, 6000, true, false, true, true));
  assertNull(w.seekLeftMs, '末段拖动不回绕左');
  assertNull(w.seekRightMs, '末段拖动不回绕右');
  const s: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  const solo: DecideCommands = decide(mkInput(s, 10000, 15000, true, false, true, true));
  assertTrue(solo.pauseLeft, '独播域拖动仍停 dormant');
  assertTrue(solo.blackLeft, '独播域拖动黑层不闪');
});

test('pre-roll seek落park后 拍间状态自洽', (): void => {
  const g: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  assertEq(0, g.leftTarget(-2000), 'pre-roll 左 park 0');
  assertEq(3000, g.rightTarget(-2000), 'pre-roll 右落 T+off');
  const c: DecideCommands = decide(mkInput(g, 0, 3000, true, false, true, false, true, false));
  assertTrue(c.pauseLeft, '黑层在册左保持停');
  assertTrue(c.blackLeft, '黑层不闪');
  assertNull(c.seekLeftMs, '拍间不 seek 左');
  assertNull(c.seekRightMs, '拍间不 seek 右');
});

test('右片重建恢复目标 快照为右钟的四域臂', (): void => {
  // 审查修复 P2 的行为锚：恢复目标必须由**重建前快照**做域判定，不能用新引擎的 0。
  // 臂 1：off=0 右独播尾段（左已播尽、blackLeft=true、快照 15000）→ 必须回 15000
  // （若误用新引擎 0，会读成 DUAL、T=10000，右窗回跳）
  const tail: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  assertEq(15000, rightResumeTargetOf(tail, 10000, 15000, true, false) ?? -1,
    '右独播尾段保留快照素材位');
  // 臂 2：off=5000 pre-roll（blackLeft=true、快照 3000）→ 必须回 3000
  // （若误用新引擎 0，会被读成 tMin=-5000 → 右片回素材 0）
  const pre: TimelineGeometry = new TimelineGeometry(10000, 20000, 5000);
  assertEq(3000, rightResumeTargetOf(pre, 0, 3000, true, false) ?? -1,
    'pre-roll 右独播保留快照素材位');
  // 臂 3：off=0 左主钟双素材（black=false、左 5000/快照 5000）→ 对齐 T+off = 5000
  const dual: TimelineGeometry = new TimelineGeometry(10000, 20000, 0);
  assertEq(5000, rightResumeTargetOf(dual, 5000, 5000, false, false) ?? -1,
    '双素材按 T+off 对齐');
  // 臂 4：off=0 左独播（blackRight=true、左 15000、快照 10000=右片尽头 park）→ 保持 park
  const leftSolo: TimelineGeometry = new TimelineGeometry(20000, 10000, 0);
  assertEq(10000, rightResumeTargetOf(leftSolo, 15000, 10000, false, true) ?? -1,
    '左独播右片保持尽头 park');
  // 防御：无效快照返回 undefined（调用方回退原始值）
  assertNull(rightResumeTargetOf(dual, 5000, -1, false, false), '负快照无效');
});

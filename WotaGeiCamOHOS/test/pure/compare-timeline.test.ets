/**
 * 双轴时间线几何（player/CompareTimeline.ets）的行为级单测——对拍上游
 * app/src/test/java/com/wotagei/cam/player/CompareTimelineTest.kt 并**裁剪到已移植面**
 * （对齐上游 38d712a）：上游的 DecideCommands/decide/domainOf/resolveT/AB 回绕/拖动让路
 * 本侧未移植（手动偏移最小版），只测 TimelineGeometry 几何 + normalizeOffset +
 * rightTargetFor + timelineSpan/timelineEnd。期望值一律手写字面量。
 *
 * off=0 回归红线（两条独立用例钉死）：
 * - 几何红线：Tmin=0、Tmax=max(L,R)（右片没选 R=0 时 Tmax=L，与旧单左轴完全一致）；
 * - 纠偏红线：rightTargetFor(t, 0, R) = t（期望位=左片时间，旧公式取值一致）。
 */
import {
  TimelineGeometry, normalizeOffset, rightTargetFor, timelineEnd, timelineSpan
} from '../../entry/src/main/ets/player/CompareTimeline';

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

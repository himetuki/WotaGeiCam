/**
 * 刻度滑杆的梯子构造（ui/SliderLadder.ets）——直译上游
 * app/src/test/java/com/wotagei/cam/SliderLadderTest.kt 九用例（对齐上游 38d712a）。
 * 每类各钉两件事：① 产品档位/步长；② 运行时范围过滤（越界档一律不出现）。
 * nearestLadderIndex 另锁拖动吸附边界（低于首档 / 高于末档 / 恰中点取低档）。
 */
import {
  diopterLadder, evLadder, isoLadder, kelvinLadder, nearestLadderIndex, percentLadder,
  shutterLadder, tintLadder, zoomLadder
} from '../../entry/src/main/ets/ui/SliderLadder';

test('就近吸附 范围外贴端档 中点取低档', (): void => {
  const ladder: Array<number> = [10, 20, 30];
  assertEq(0, nearestLadderIndex(0, ladder), '低于首档落在索引 0');
  assertEq(2, nearestLadderIndex(99, ladder), '高于末档落在末索引');
  assertEq(0, nearestLadderIndex(12, ladder), '贴近低档');
  assertEq(2, nearestLadderIndex(28, ladder), '贴近高档');
  assertEq(0, nearestLadderIndex(15, ladder), '恰中点取低档（向下吸附）');
  assertEq(1, nearestLadderIndex(25, ladder), '恰中点取低档（第二段）');
  assertEq(0, nearestLadderIndex(5, []), '空表返回 0（调用方以禁用兜底）');
});

test('ISO 三分之一档序列按设备范围过滤', (): void => {
  const full: Array<number> = isoLadder(50, 12800);
  assertEq(25, full.length, '产品序列 25 档');
  assertEq(50, full[0], '首档 50');
  assertEq(12800, full[full.length - 1], '末档 12800');
  assertTrue(full.indexOf(125) >= 0, '含 125');
  assertTrue(full.indexOf(2500) >= 0, '含 2500');
  // 设备范围 100..16000：低于 100 的被滤掉，16000 不在序列里也不凭空生成
  const head: Array<number> = isoLadder(100, 700);
  assertEq(100, head[0], '越界低档滤除');
  assertEq(125, head[1], '次档');
  assertEq(160, head[2], '第三档');
  assertEq(200, head[3], '第四档');
  assertFalse(isoLadder(100, 16000).indexOf(50) >= 0, '低于设备下限不出现');
  assertEq(12800, isoLadder(100, 16000)[isoLadder(100, 16000).length - 1], '末档不超产品序列');
  const mid: Array<number> = isoLadder(130, 210);
  assertEq(160, mid[0], '范围落在档间也必须只留范围内档');
  assertEq(200, mid[1], '范围内末档');
});

test('EV 每个整数补偿步一档', (): void => {
  const ladder: Array<number> = evLadder(-2, 2);
  assertEq(-2, ladder[0], '首档 -2');
  assertEq(-1, ladder[1], '-1');
  assertEq(0, ladder[2], '0');
  assertEq(1, ladder[3], '1');
  assertEq(2, ladder[4], '末档 2');
  assertEq(25, evLadder(-12, 12).length, '-12..12 共 25 档');
  assertEq(0, evLadder(3, 2).length, '区间倒置没有梯');
});

test('色温 2000到10000共17档', (): void => {
  const k: Array<number> = kelvinLadder(2000, 10000);
  assertEq(17, k.length, '17 档');
  assertEq(2000, k[0], '首档 2000');
  assertEq(10000, k[k.length - 1], '末档 10000');
  for (const v of k) {
    assertTrue(v % 500 === 0, `按 500K 网格对齐: ${v}`);
  }
  // 设备范围收窄：仍按 500 网格对齐（2100 起 → 2500 第一档）
  const narrow: Array<number> = kelvinLadder(2100, 3000);
  assertEq(2500, narrow[0], '2100 起第一档 2500');
  assertEq(3000, narrow[1], '第二档 3000');
});

test('色调 负50到50共21档', (): void => {
  const t: Array<number> = tintLadder(-50, 50);
  assertEq(21, t.length, '21 档');
  assertEq(-50, t[0], '首档 -50');
  assertEq(50, t[t.length - 1], '末档 50');
  for (const v of t) {
    assertTrue(v % 5 === 0, `按 5 网格对齐: ${v}`);
  }
  const n: Array<number> = tintLadder(-7, 7);
  assertEq(-5, n[0], '负数范围向上对齐第一档');
  assertEq(0, n[1], '0 档');
  assertEq(5, n[2], '末档 5');
});

test('变焦梯按范围过滤且保留半点档', (): void => {
  const full: Array<number> = zoomLadder(0.5, 10);
  assertEq(0.5, full[0], '首档 0.5');
  assertEq(1, full[1], '1');
  assertEq(1.5, full[2], '半点档 1.5');
  assertEq(2.5, full[4], '半点档 2.5');
  assertEq(10, full[full.length - 1], '末档 10');
  assertEq(11, full.length, '全序列 11 档');
  const mid: Array<number> = zoomLadder(2, 6);
  assertEq(2, mid[0], '2');
  assertEq(2.5, mid[1], '2.5');
  assertEq(3, mid[2], '3');
  assertEq(4, mid[3], '4');
  assertEq(5, mid[4], '5');
  assertEq(6, mid[5], '6');
  assertEq(0, zoomLadder(12, 20).length, '范围外没有梯');
});

test('屈光度 lo到hi等分10段共11点', (): void => {
  const d: Array<number> = diopterLadder(0, 10);
  assertEq(11, d.length, '11 点');
  assertEq(0, d[0], '首点=range.lo');
  assertEq(10, d[d.length - 1], '末点=range.hi');
  assertEq(1, d[1], '等距 1.0/段');
  assertEq(0, diopterLadder(0, 0).length, '定焦镜头（hi<=lo）没有梯');
  assertEq(0, diopterLadder(0, -1).length, '倒置没有梯');
  // 下限不为 0 的镜头：梯必须以运行时 range.lo 起，不能造出域外刻度
  const offset: Array<number> = diopterLadder(2, 12);
  assertEq(2, offset[0], '以 range.lo 起');
  assertEq(12, offset[offset.length - 1], '以 range.hi 止');
});

test('百分比梯按步长生成', (): void => {
  const zebra: Array<number> = percentLadder(80, 100, 1);
  assertEq(21, zebra.length, '80..100 步 1 共 21 档');
  assertEq(80, zebra[0], '首档 80');
  assertEq(100, zebra[zebra.length - 1], '末档 100');
  assertEq(19, percentLadder(5, 95, 5).length, '5..95 步 5 共 19 档');
  const p: Array<number> = percentLadder(5, 15, 5);
  assertEq(5, p[0], '5');
  assertEq(10, p[1], '10');
  assertEq(15, p[2], '15');
  assertEq(0, percentLadder(5, 95, 0).length, '步长越界返回空');
  assertEq(0, percentLadder(10, 9, 1).length, '区间倒置返回空');
  // 起点不对齐网格时向上取第一档（与滑块产品档一致）
  const up: Array<number> = percentLadder(7, 15, 5);
  assertEq(10, up[0], '7 起第一档 10');
  assertEq(15, up[1], '末档 15');
});

test('快门梯过滤范围并去重升序', (): void => {
  // 上游 hi=41_666_664f 是 Float 精度截断（41,666,666f 落 41,666,664）；双精度下无损，
  // 上界直接用精确值（SliderLadder.ets 头注释已登记该精度差异）
  const tiers: Array<number> = [41666666, 20000000, 33333333, 20000000, 10000000];
  const ladder: Array<number> = shutterLadder(tiers, 15000000, 41666666);
  assertEq(20000000, ladder[0], '升序首档');
  assertEq(33333333, ladder[1], '次档');
  assertEq(41666666, ladder[2], '末档（双精度无损承载 1/24）');
  assertEq(3, ladder.length, '去重后 3 档');
  assertFalse(ladder.indexOf(10000000) >= 0, '范围外的档不进梯');
  const cut: Array<number> = shutterLadder(tiers, 15000000, 41666665);
  assertEq(2, cut.length, '上界差 1 时末档出局');
  assertEq(0, shutterLadder([], 0, 1).length, '空表没有梯');
});

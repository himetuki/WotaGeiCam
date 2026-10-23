/**
 * 双音轨偏移合成与结果分类（player/TrackSync.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/TrackSyncTest.kt（对齐上游 38d712a）与
 * TrackSyncAutoTest.kt（对齐上游 e950328：AUTO/MANUAL 落盘策略的纯函数桥）。
 *
 * 口径订正（T26 审查）：上游 TrackSyncTest 共 12 个 @Test = **11 条纯函数用例**
 * （合成 4 + resolve 2 + signed 3 + 失败分类 2）+ **1 条 extractor 源码结构守卫**
 * （依赖平台文件，本侧不移植，随 T27 以 ArkTS 结构检查补）。
 * 本侧 11 条覆盖全部 11 条纯函数用例；其中 `MIN_CONFIDENCE 同源` 是本侧**额外断言**
 * （钉 TrackSync.MIN_CONFIDENCE === AudioSync.MIN_CONFIDENCE 门槛复用），上游无此断言。
 *
 * TrackSyncAutoTest（e950328）9 个 @Test 中 8 条纯函数/纯 prefs 桥用例全部移植
 * （决策 6 + TOCTOU 复核 1 + 正常落盘 1）；`AUTO落盘必须走锁内复核入口` 是对 run()
 * 源码的接线扫描（run/锁是平台面留 T27），不移植。prefs 用纯 map 假件（上游 FakePrefs
 * 的最小面：本侧 SyncPrefs 只有 string 存取，五键编解码收在 TrackSync）。
 * 期望值一律手写字面量。
 */
import { AudioSync } from '../../entry/src/main/ets/player/AudioSync';
import {
  AutoDecision, AutoDecisionKind, Source, SyncPrefs, SyncSnapshot, TrackSync, TrackSyncFail
} from '../../entry/src/main/ets/player/TrackSync';

/** 纯 map 假 prefs（上游 FakePrefs 的 SyncPrefs 最小面） */
class FakeSyncPrefs implements SyncPrefs {
  map: Record<string, string> = {};

  getString(key: string, def: string): string {
    const v: string | undefined = this.map[key];
    return v !== undefined ? v : def;
  }

  putString(key: string, value: string): void {
    this.map[key] = value;
  }
}

test('合成 两轨同时起步只看内容差', (): void => {
  // 环境首样本 1_000_000us、内录首样本 1_000_000us：起点差 0，内容差即总偏移
  assertEq(120, TrackSync.combineOffsetMs(120, 1000000, 1000000), '起点差 0');
});

test('合成 内录首样本晚10ms要加进总偏移', (): void => {
  assertEq(130, TrackSync.combineOffsetMs(120, 1010000, 1000000), '内容差+10ms');
});

test('合成 负偏移与起点早的情形', (): void => {
  // 内录内容早 40ms、但轨起点早 5ms：-40 + (-5) = -45
  assertEq(-45, TrackSync.combineOffsetMs(-40, 995000, 1000000), '负内容差+早起点');
  // 负数向零截断（Kotlin Long 整除语义）：-5.5ms → -5，不是 floor 的 -6
  assertEq(-45, TrackSync.combineOffsetMs(-40, 994500, 1000000), '负亚毫秒向零截断');
});

test('合成 亚毫秒起点差向下取整', (): void => {
  // 1_500us 差 = 1.5ms：µs→ms 整除向下取 1ms（1ms 级误差在听感与导出语义下可忽略）
  assertEq(121, TrackSync.combineOffsetMs(120, 1001500, 1000000), '1.5ms → 1ms');
});

test('低置信度归零', (): void => {
  assertEq(0, TrackSync.resolveOffsetMs(357, true), '低置信度按录制起点对齐');
  assertEq(0, TrackSync.resolveOffsetMs(-357, true), '负偏移同样归零');
});

test('正常置信度原样保留', (): void => {
  assertEq(357, TrackSync.resolveOffsetMs(357, false), '正偏移');
  assertEq(-42, TrackSync.resolveOffsetMs(-42, false), '负偏移');
});

test('读数 正数带加号', (): void => {
  assertEq('+120', TrackSync.signedMs(120), '+120');
});

test('读数 负数自带减号', (): void => {
  assertEq('-45', TrackSync.signedMs(-45), '-45');
});

test('读数 零不带号', (): void => {
  assertEq('0', TrackSync.signedMs(0), '0');
});

test('失败分类 结构非双轨 如实报没有音轨', (): void => {
  const r: TrackSyncFail = TrackSync.failResult(false);
  assertFalse(r.ok, 'ok=false');
  assertFalse(r.dualAudio, '结构非双轨');
  assertEq('no-audio', r.reasonCode, '没有音轨');
});

test('失败分类 双轨在而读不出 报同步失败不误报没有音轨', (): void => {
  const r: TrackSyncFail = TrackSync.failResult(true);
  assertFalse(r.ok, 'ok=false');
  assertTrue(r.dualAudio, 'dualAudio 保持真');
  assertEq('sync-failed', r.reasonCode, '同步失败请重试');
  // 低置信度门槛复用 AudioSync 单一真源
  assertEq(AudioSync.MIN_CONFIDENCE, TrackSync.MIN_CONFIDENCE, 'MIN_CONFIDENCE 同源');
  assertEq(1.5, TrackSync.MIN_CONFIDENCE, '门槛字面量');
});

// ---- 以下对拍上游 TrackSyncAutoTest.kt（e950328：AUTO/MANUAL 落盘策略纯函数桥）----

test('自动校准永不覆盖手动结果', (): void => {
  const d1: AutoDecision = TrackSync.autoDecision(500, 9, Source.MANUAL);
  assertTrue(d1.sameAs(AutoDecision.manualProtected()), '高置信+手动已有 = ManualProtected');
  // 手动保护优先于低置信/越界：连"算出来不能用"都不改变保护结论
  const d2: AutoDecision = TrackSync.autoDecision(9999, 0.1, Source.MANUAL);
  assertTrue(d2.sameAs(AutoDecision.manualProtected()), '低置信+越界也仍是 ManualProtected');
});

test('旧记录无source视为手动', (): void => {
  assertTrue(TrackSync.sourceOfStored(false, undefined) === undefined, '未 done → 未校准');
  assertEq(Source.MANUAL, TrackSync.sourceOfStored(true, undefined), 'done 无 source = 老手动记录');
  assertEq(Source.MANUAL, TrackSync.sourceOfStored(true, 'manual'), 'manual 字面量');
  assertEq(Source.AUTO, TrackSync.sourceOfStored(true, 'auto'), 'auto 字面量');
  // 脏数据（未知来源）按老的手动记录处理：宁可不自动写，也不冲掉用户结果
  assertEq(Source.MANUAL, TrackSync.sourceOfStored(true, 'weird'), '未知来源按手动保护');
});

test('低置信不写', (): void => {
  const d1: AutoDecision = TrackSync.autoDecision(300, 1.99, undefined);
  assertTrue(d1.sameAs(AutoDecision.lowConfidence()), '置信 1.99 < 2.0 不写');
  const d2: AutoDecision = TrackSync.autoDecision(300, 1.5, Source.AUTO);
  assertTrue(d2.sameAs(AutoDecision.lowConfidence()), '旧 AUTO 也拦低置信');
});

test('置信过线才进入范围判定', (): void => {
  const d: AutoDecision = TrackSync.autoDecision(300, 2.0, undefined);
  assertTrue(d.sameAs(AutoDecision.write(300)), '恰 2.0 过线即写原值');
  assertEq(AutoDecisionKind.Write, d.kind, 'Write 态');
});

test('20毫秒以内归零_边界值照写', (): void => {
  assertTrue(TrackSync.autoDecision(0, 3, undefined).sameAs(AutoDecision.write(0)), '0 归零照写');
  assertTrue(TrackSync.autoDecision(19, 3, Source.AUTO).sameAs(AutoDecision.write(0)), '19 归零');
  assertTrue(TrackSync.autoDecision(-19, 3, Source.AUTO).sameAs(AutoDecision.write(0)), '-19 归零');
  assertTrue(TrackSync.autoDecision(20, 3, Source.AUTO).sameAs(AutoDecision.write(20)), '恰 20 原样');
  assertTrue(TrackSync.autoDecision(-20, 3, Source.AUTO).sameAs(AutoDecision.write(-20)), '恰 -20 原样');
});

test('超出正负1000毫秒只记日志', (): void => {
  assertTrue(TrackSync.autoDecision(1000, 3, undefined).sameAs(AutoDecision.write(1000)), '恰 1000 在界内');
  assertTrue(TrackSync.autoDecision(-1000, 3, undefined).sameAs(AutoDecision.write(-1000)), '恰 -1000 在界内');
  assertTrue(TrackSync.autoDecision(1001, 3, undefined).sameAs(AutoDecision.outOfRange()), '1001 越界');
  assertTrue(TrackSync.autoDecision(-1001, 3, undefined).sameAs(AutoDecision.outOfRange()), '-1001 越界');
});

test('突变_去掉手动保护必被表测出', (): void => {
  // 退化实现：不检查 existing，低置信以下才不写——正是"自动冲掉手动"的缺陷形态
  const mutant = (offsetMs: number, confidence: number): AutoDecision => {
    if (confidence < 2) {
      return AutoDecision.lowConfidence();
    }
    return AutoDecision.write(offsetMs);
  };
  const real: AutoDecision = TrackSync.autoDecision(500, 9, Source.MANUAL);
  assertTrue(real.sameAs(AutoDecision.manualProtected()), '真实现必须保护手动结果');
  assertFalse(real.sameAs(mutant(500, 9)), '突变体必须与真实现不同（否则这把尺测不出退化）');
});

test('先auto读旧值后manual写入_auto落盘必须被拒', (): void => {
  // 模拟 TOCTOU：AUTO 在解码前读到 AUTO（允许写），解码期间用户点了手动同步，
  // AUTO 最后落盘必须被写前复核拒绝——最终 prefs 仍是 MANUAL。
  const sp: FakeSyncPrefs = new FakeSyncPrefs();
  const key: string = 'content://media/external/video/media/1';
  TrackSync.writeSyncPrefs(sp, key, 100, Source.AUTO, 9, 1);
  const seenBeforeDecode: Source | undefined = TrackSync.sourceOfPrefs(sp, key);
  assertEq(Source.AUTO, seenBeforeDecode, '解码前读到的旧值是 AUTO');

  TrackSync.writeSyncPrefs(sp, key, 250, Source.MANUAL, 8, 2);
  const written: boolean = TrackSync.writeAutoPrefs(sp, key, 500, 9, 3);

  assertFalse(written, '写前复核必须拒绝 AUTO 覆盖 MANUAL');
  assertEq(Source.MANUAL, TrackSync.sourceOfPrefs(sp, key), '最终来源必须仍是 MANUAL');
  assertEq('250', sp.map['sync_offset:' + key], '最终偏移必须是手动值');
  assertEq('manual', sp.map['sync_source:' + key], '最终来源键必须是 manual');
  assertEq('2', sp.map['sync_at:' + key], '最终时刻必须是手动写入时刻');
  const snap: SyncSnapshot | undefined = TrackSync.cachedSyncOf(sp, key);
  assertTrue(snap !== undefined, '快照可读');
  if (snap !== undefined) {
    assertEq(250, snap.offsetMs, '快照偏移');
    assertEq(Source.MANUAL, snap.source, '快照来源');
    assertEq(8, snap.confidence, '快照置信度');
  }
});

test('无手动结果时auto正常落盘', (): void => {
  const sp: FakeSyncPrefs = new FakeSyncPrefs();
  const key: string = 'clip-auto';
  assertTrue(TrackSync.writeAutoPrefs(sp, key, -120, 3, 7), '无手动结果必须写入成功');
  assertEq(Source.AUTO, TrackSync.sourceOfPrefs(sp, key), '来源 AUTO');
  assertEq('-120', sp.map['sync_offset:' + key], '偏移 -120');
  assertEq('3', sp.map['sync_conf:' + key], '置信 3');
  assertEq('7', sp.map['sync_at:' + key], '落盘时刻 7');
  const snap: SyncSnapshot | undefined = TrackSync.cachedSyncOf(sp, key);
  assertTrue(snap !== undefined, '快照可读');
  if (snap !== undefined) {
    assertEq(-120, snap.offsetMs, '快照偏移 -120');
    assertEq(Source.AUTO, snap.source, '快照来源 AUTO');
    assertEq(3, snap.confidence, '快照置信 3');
  }
  // 未校准的 key：无 done → 无快照
  assertTrue(TrackSync.cachedSyncOf(sp, 'never-done') === undefined, '未校准无快照');
});

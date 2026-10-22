/**
 * 对比历史纯核心（player/CompareHistory.ets）的单测——对拍上游
 * player/CompareHistory.kt 的解析/去重/编码语义（对齐上游 38d712a）。
 *
 * 上游把 preferences 读写揉在同一 object；本侧纯核心只吃 raw 串/数组，preferences 适配
 * 留待 T25。期望值一律手写字面量（含编码串的键序 rightId/leftId/ts）。
 */
import {
  CompareHistory, CompareHistoryEntry
} from '../../entry/src/main/ets/player/CompareHistory';

function mkEntry(rightId: number, leftId: number, ts: number): CompareHistoryEntry {
  return new CompareHistoryEntry(rightId, leftId, ts);
}

test('缺键或空串返回空表', (): void => {
  assertEq(0, CompareHistory.entriesOf(undefined).length, 'undefined 空表');
  assertEq(0, CompareHistory.entriesOf('').length, '空串空表');
});

test('坏JSON返回空表', (): void => {
  assertEq(0, CompareHistory.entriesOf('not a json').length, '非 JSON 空表');
  assertEq(0, CompareHistory.entriesOf('{"a":1}').length, '非数组空表');
  assertEq(0, CompareHistory.entriesOf('null').length, 'null 空表');
});

test('正常解析且保持数组顺序', (): void => {
  const raw: string =
    '[{"rightId":5,"leftId":7,"ts":1700000000000},{"rightId":6,"leftId":8,"ts":1700000001000}]';
  const out: Array<CompareHistoryEntry> = CompareHistory.entriesOf(raw);
  assertEq(2, out.length, '两条记录');
  assertEq(5, out[0].rightId, '首条 rightId');
  assertEq(7, out[0].leftId, '首条 leftId');
  assertEq(1700000000000, out[0].ts, '首条 ts');
  assertEq(6, out[1].rightId, '次条 rightId');
  assertEq(8, out[1].leftId, '次条 leftId');
  assertEq(1700000001000, out[1].ts, '次条 ts');
});

test('坏行逐条跳过不抛', (): void => {
  const raw: string = '[' +
    '{"rightId":5,"leftId":7,"ts":100},' +
    '{"rightId":"x","leftId":7,"ts":100},' +
    '{"leftId":7,"ts":100},' +
    '{"rightId":0,"leftId":7,"ts":100},' +
    '{"rightId":-3,"leftId":7,"ts":100},' +
    '{"rightId":9,"leftId":7},' +
    'null,' +
    '{"rightId":6,"leftId":8,"ts":200}' +
    ']';
  const out: Array<CompareHistoryEntry> = CompareHistory.entriesOf(raw);
  assertEq(2, out.length, '仅两条合法行');
  assertEq(5, out[0].rightId, '首合法行 rightId');
  assertEq(6, out[1].rightId, '次合法行 rightId');
});

test('recordEntry 按rightId去重并插队首', (): void => {
  const base: Array<CompareHistoryEntry> = [mkEntry(1, 10, 100), mkEntry(2, 20, 200), mkEntry(3, 30, 300)];
  const out: Array<CompareHistoryEntry> = CompareHistory.recordEntry(base, 2, 99, 400);
  assertEq(3, out.length, '去重后不增条数');
  assertEq(2, out[0].rightId, '重复项插队首');
  assertEq(99, out[0].leftId, 'leftId 更新');
  assertEq(400, out[0].ts, 'ts 更新');
  assertEq(1, out[1].rightId, '其余保序 1');
  assertEq(3, out[2].rightId, '其余保序 3');
  // 不改入参
  assertEq(1, base[0].rightId, '入参队首未变');
  assertTrue(out !== base, '返回新数组');
});

test('recordEntry 超过12条从队尾截断', (): void => {
  const base: Array<CompareHistoryEntry> = [];
  for (let i: number = 1; i <= 12; i++) {
    base.push(mkEntry(i, i * 10, i * 100));
  }
  const out: Array<CompareHistoryEntry> = CompareHistory.recordEntry(base, 13, 130, 1300);
  assertEq(12, out.length, '上限 12');
  assertEq(13, out[0].rightId, '新条目队首');
  assertEq(11, out[11].rightId, '队尾旧 12 被截掉');
});

test('recordEntry rightId非正数 no-op', (): void => {
  const base: Array<CompareHistoryEntry> = [mkEntry(1, 10, 100), mkEntry(2, 20, 200)];
  const out: Array<CompareHistoryEntry> = CompareHistory.recordEntry(base, 0, 99, 999);
  assertEq(2, out.length, '条数不变');
  assertEq(1, out[0].rightId, '内容不变');
  assertTrue(out !== base, '仍返回新数组');
});

test('removeEntry 按rightId摘除', (): void => {
  const base: Array<CompareHistoryEntry> =
    [mkEntry(1, 10, 100), mkEntry(2, 20, 200), mkEntry(3, 30, 300)];
  const out: Array<CompareHistoryEntry> = CompareHistory.removeEntry(base, 2);
  assertEq(2, out.length, '摘掉一条');
  assertEq(1, out[0].rightId, '保留 1');
  assertEq(3, out[1].rightId, '保留 3');
  const miss: Array<CompareHistoryEntry> = CompareHistory.removeEntry(base, 99);
  assertEq(3, miss.length, '不存在的 rightId 不动作');
  assertTrue(out !== base, '返回新数组');
});

test('encodeEntries 字面量键序', (): void => {
  const out: string = CompareHistory.encodeEntries([mkEntry(5, 7, 1700000000000)]);
  assertEq('[{"rightId":5,"leftId":7,"ts":1700000000000}]', out, '键序 rightId/leftId/ts');
  assertEq('[]', CompareHistory.encodeEntries([]), '空表编码');
});

test('编码到解析往返', (): void => {
  const list: Array<CompareHistoryEntry> =
    [mkEntry(5, 7, 1700000000000), mkEntry(9, 11, 1700000005000)];
  const raw: string = CompareHistory.encodeEntries(list);
  const back: Array<CompareHistoryEntry> = CompareHistory.entriesOf(raw);
  assertEq(2, back.length, '往返条数');
  assertEq(5, back[0].rightId, '往返 0 rightId');
  assertEq(7, back[0].leftId, '往返 0 leftId');
  assertEq(1700000000000, back[0].ts, '往返 0 ts');
  assertEq(9, back[1].rightId, '往返 1 rightId');
  assertEq(11, back[1].leftId, '往返 1 leftId');
  assertEq(1700000005000, back[1].ts, '往返 1 ts');
  assertEq(0, CompareHistory.entriesOf('[]').length, '空数组往返空表');
});

/**
 * 分段系列归组的真值表（对齐上游 media/SeriesGroupTest.kt 全量）。
 *
 * 只测 `groupSpans`（吃 SeriesKey、吐索引）：`VideoClip` 带平台字段，规则若直接写在它上面
 * 就无法纯测；`galleryEntries` 只是薄适配层。末尾三条突变自证照抄生产逻辑、各只动一处——
 * 断言期望值只能证明输出对，证明不了判据能红。
 */
import {
  GroupSpan, MIN_GROUP_SIZE, SeriesKey, expandSeriesTargets, groupSpans
} from '../../entry/src/main/ets/media/SeriesGroup';
import { VideoClip } from '../../entry/src/main/ets/media/MediaRepo';

function k(id: number, seriesId?: number, partIndex?: number): SeriesKey {
  return new SeriesKey(id, seriesId, partIndex);
}

function indicesOf(spans: Array<GroupSpan>): string {
  return spans.map((s: GroupSpan): string => `[${s.indices.join(',')}]`).join('|');
}

// ------------------------------------------------------------------ 基本归组

test('空表返回空', () => {
  assertEq(0, groupSpans([]).length);
});

test('seriesId为undefined的片各自成一条', () => {
  const spans: Array<GroupSpan> = groupSpans([k(1), k(2), k(3)]);
  assertEq(3, spans.length, '三张单段片必须出三条');
  assertEq('[0]|[1]|[2]', indicesOf(spans));
  for (const s of spans) {
    assertTrue(s.seriesId === undefined, '全都不是组');
  }
});

test('两段乱序输入成组且按partIndex升序', () => {
  const spans: Array<GroupSpan> = groupSpans([k(11, 7, 1), k(10, 7, 0)]);
  assertEq(1, spans.length, '两段必须折成一条');
  assertEq(7, spans[0].seriesId as number);
  assertEq('[1,0]', indicesOf(spans), '必须按 partIndex 升序（输入是乱的：下标1 在前）');
});

test('三段乱序输入仍升序', () => {
  const spans: Array<GroupSpan> = groupSpans([k(13, 7, 2), k(11, 7, 0), k(12, 7, 1)]);
  assertEq(1, spans.length);
  assertEq('[1,2,0]', indicesOf(spans), '必须按 partIndex 0,1,2 排');
});

test('组内只剩一个可见成员时退化成单条', () => {
  const spans: Array<GroupSpan> = groupSpans([k(10, 7, 0)]);
  assertEq(1, spans.length);
  assertTrue(spans[0].seriesId === undefined,
    '只剩一段时不得再成组（否则组级动作会扇出到看不见的成员）');
  assertEq('[0]', indicesOf(spans));
});

test('输出顺序按输入顺序且组站在首见位', () => {
  const spans: Array<GroupSpan> = groupSpans([k(1), k(20, 7, 0), k(21, 7, 1), k(2)]);
  assertEq(3, spans.length, '三条 entry：单片 / 组 / 单片');
  assertEq('[0]|[1,2]|[3]', indicesOf(spans), '组站在它首见的位置（下标 1），不是排在最后');
});

test('两个组互不串', () => {
  const spans: Array<GroupSpan> = groupSpans([
    k(10, 1, 0), k(11, 1, 1), k(20, 2, 0), k(21, 2, 1)
  ]);
  assertEq(2, spans.length);
  assertEq(1, spans[0].seriesId as number);
  assertEq(2, spans[1].seriesId as number);
  assertEq('[0,1]', indicesOf([spans[0]]));
  assertEq('[2,3]', indicesOf([spans[1]]));
});

test('collapse为false时逐字等于输入', () => {
  const keys: Array<SeriesKey> = [k(1), k(10, 7, 0), k(11, 7, 1), k(20, 8, 0), k(21, 8, 1), k(2)];
  const spans: Array<GroupSpan> = groupSpans(keys, false);
  assertEq(keys.length, spans.length, '折叠关掉必须一条不折');
  assertEq('[0]|[1]|[2]|[3]|[4]|[5]', indicesOf(spans), '必须逐字等于输入（顺序也不许变）');
  for (const s of spans) {
    assertTrue(s.seriesId === undefined, '全部必须是单条');
  }
});

test('partIndex为undefined的脏数据排末尾不散组', () => {
  const spans: Array<GroupSpan> = groupSpans([k(10, 7, undefined), k(11, 7, 1), k(12, 7, 0)]);
  assertEq(1, spans.length, '不得散组');
  assertEq('[2,1,0]', indicesOf(spans), 'undefined 排末尾，0/1 按序');
});

test('成组门槛是2不是1', () => {
  assertEq(2, MIN_GROUP_SIZE, '门槛必须是 2');
  assertTrue(groupSpans([k(10, 7, 0)])[0].seriesId === undefined);
  assertEq(7, groupSpans([k(10, 7, 0), k(11, 7, 1)])[0].seriesId as number);
});

// ------------------------------------------------------------------ 扇出（expandSeriesTargets）

/** 最小 VideoClip 替身（纯字段构造，不碰平台 API） */
function clipOf(id: number, seriesId: number | undefined, partIndex: number | undefined): VideoClip {
  return new VideoClip(id, `/p/${id}.mp4`, `${id}.mp4`, `${id}.mp4`, 0, 1000, 1000, 1920, 1080,
    'video/mp4', new Set<string>(), false, seriesId, partIndex);
}

test('扇出_组内任一成员选中都展开到整组且去重', () => {
  const a: VideoClip = clipOf(1, 7, 0);
  const b: VideoClip = clipOf(2, 7, 1);
  const c: VideoClip = clipOf(3, undefined, undefined);
  const all: Array<VideoClip> = [a, b, c];
  // 选中首段 → 展开整组两段
  const one: Array<VideoClip> = expandSeriesTargets([a], all);
  assertEq(2, one.length);
  assertEq('1,2', one.map((x: VideoClip): number => x.id).join(','));
  // 同组两个成员都被选中 → 仍只动一次（按 id 去重）
  const both: Array<VideoClip> = expandSeriesTargets([a, b], all);
  assertEq(2, both.length, '同组两个成员都被选中时只应动一次');
  // 单片原样返回（不发明成员）
  const single: Array<VideoClip> = expandSeriesTargets([c], all);
  assertEq(1, single.length);
  assertEq(3, single[0].id);
});

test('扇出_组内只剩一段时不展开', () => {
  // 库里残留的头（seriesId 在、但可见成员只有 1 个）→ 不许扇出到看不见的成员
  const only: VideoClip = clipOf(9, 7, 0);
  const out: Array<VideoClip> = expandSeriesTargets([only], [only]);
  assertEq(1, out.length);
  assertEq(9, out[0].id);
});

test('扇出_空输入原样返回', () => {
  assertEq(0, expandSeriesTargets([], [clipOf(1, 7, 0)]).length);
  assertEq(0, expandSeriesTargets([], []).length);
});

// ------------------------------------------------------------------ 突变自证

function mutantCollapseAlwaysFalse(keys: Array<SeriesKey>): Array<GroupSpan> {
  const out: Array<GroupSpan> = [];
  for (let i = 0; i < keys.length; i++) {
    out.push(new GroupSpan(undefined, [i]));
  }
  return out;
}

function mutantNoSort(keys: Array<SeriesKey>): Array<GroupSpan> {
  const groups: Map<number, Array<number>> = new Map<number, Array<number>>();
  for (let i = 0; i < keys.length; i++) {
    const sid: number | undefined = keys[i].seriesId;
    if (sid === undefined) {
      continue;
    }
    const bucket: Array<number> | undefined = groups.get(sid);
    if (bucket === undefined) {
      groups.set(sid, [i]);
    } else {
      bucket.push(i);
    }
  }
  const out: Array<GroupSpan> = [];
  const emitted: Set<number> = new Set<number>();
  for (let i = 0; i < keys.length; i++) {
    const sid: number | undefined = keys[i].seriesId;
    if (sid === undefined) {
      out.push(new GroupSpan(undefined, [i]));
      continue;
    }
    if (emitted.has(sid)) {
      continue;
    }
    emitted.add(sid);
    const members: Array<number> = groups.get(sid) ?? [];
    if (members.length < MIN_GROUP_SIZE) {
      for (const idx of members) {
        out.push(new GroupSpan(undefined, [idx]));
      }
      continue;
    }
    out.push(new GroupSpan(sid, members)); // ← 唯一差别：不排序
  }
  return out;
}

test('突变自证一：collapse恒false则折组判据必红', () => {
  const keys: Array<SeriesKey> = [k(10, 7, 0), k(11, 7, 1)];
  assertEq(1, groupSpans(keys).length, '对照组：真实现两段折成一条');
  assertEq(2, mutantCollapseAlwaysFalse(keys).length, '突变体一（恒不折叠）必须出两条');
});

test('突变自证二：不排序则升序判据必红', () => {
  const keys: Array<SeriesKey> = [k(13, 7, 2), k(11, 7, 0), k(12, 7, 1)];
  assertEq('[1,2,0]', indicesOf(groupSpans(keys)), '对照组：必须按 partIndex 升序');
  assertEq('[0,1,2]', indicesOf(mutantNoSort(keys)), '突变体二（不排序）顺序必须不同');
});

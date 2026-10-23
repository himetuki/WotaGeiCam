/**
 * 分段系列归组（上游 media/SeriesGroup.kt 的移植，#5 Step 4c）。纯逻辑，零平台 import。
 *
 * 分组维度：`seriesId`，且**仅当可聚合到 ≥ MIN_GROUP_SIZE 个可见成员**时才成组；
 * 成员关系建立在「剩下的成员」上——各页签只负责过滤成员，不加特例，某段被单独删/回收
 * 导致只剩 1 段时该组**自动退化成单条**。
 *
 * 代表段 = `partIndex` 最小者（首段）：组级动作对全部 parts 生效，显示字段全取它。
 *
 * 顺序：严格保持输入顺序，一条 entry 的站位 = 该组成员在输入里**首次出现**的位置
 * （媒体库按时间倒序排，同一次录制的各段本就相邻，稳定排序才不让卡片跳位）。
 *
 * 「合并」是**视觉折叠**，不是媒体字节拼接（拼 MP4 要重编码时间线，本仓没有该能力）。
 * `collapse=false` 返回与输入逐字等价的全单条列表——既是今天的基线，也是回退开关。
 */
import { VideoClip } from './MediaRepo';

/** 可聚合到这么多个可见成员才成组 */
export const MIN_GROUP_SIZE: number = 2;

/** 归组判定要用的三个字段（不吃整个 VideoClip，规则才可纯测） */
export class SeriesKey {
  id: number;
  seriesId: number | undefined;
  partIndex: number | undefined;

  constructor(id: number, seriesId: number | undefined, partIndex: number | undefined) {
    this.id = id;
    this.seriesId = seriesId;
    this.partIndex = partIndex;
  }
}

/** 一条 entry 覆盖输入列表的哪几项（indices 已按 partIndex 升序排好） */
export class GroupSpan {
  seriesId: number | undefined;
  indices: Array<number>;

  constructor(seriesId: number | undefined, indices: Array<number>) {
    this.seriesId = seriesId;
    this.indices = indices;
  }
}

/** 媒体库的一条展示单元：单个片，或一次录制归成的系列组 */
export class GalleryEntry {
  /** 代表段（组内 partIndex 最小者）；组级动作与显示字段全取它 */
  clip: VideoClip;
  /** 组 id；单片恒为 undefined（undefined ⇔ 这条不是组） */
  seriesId: number | undefined;
  /** 升序成员；单片时是只含自己的单元素列表 */
  parts: Array<VideoClip>;

  constructor(clip: VideoClip, seriesId: number | undefined, parts: Array<VideoClip>) {
    this.clip = clip;
    this.seriesId = seriesId;
    this.parts = parts;
  }

  /** 组总时长（单片即它自己） */
  totalDurationMs(): number {
    let sum: number = 0;
    for (const p of this.parts) {
      sum += p.durationMs;
    }
    return sum;
  }

  /** 组标题：代表段（首段）的名字——不发明新名，用户认得出这是哪一次录制 */
  title(): string {
    return this.clip.name;
  }

  /** 组内段数；单片返回 undefined（UI 据此决定显不显示角标） */
  seriesCount(): number | undefined {
    if (this.seriesId === undefined || this.parts.length < MIN_GROUP_SIZE) {
      return undefined;
    }
    return this.parts.length;
  }
}

/**
 * 归组的纯核心：把键序列切成若干 GroupSpan（只吃 SeriesKey、只吐索引）。
 *
 * 超过 `Number.MAX_SAFE_INTEGER` 不参与（partIndex 是段序，实际远小于此）；脏数据
 * （partIndex 缺失）排到组末尾，**不许因此把整组打散**。
 */
export function groupSpans(keys: Array<SeriesKey>, collapse: boolean = true): Array<GroupSpan> {
  const out: Array<GroupSpan> = [];
  if (keys.length === 0) {
    return out;
  }
  // 不折叠 = 扁平列表基线：一条不折、顺序逐字不变（回退开关）
  if (!collapse) {
    for (let i = 0; i < keys.length; i++) {
      out.push(new GroupSpan(undefined, [i]));
    }
    return out;
  }
  // 按 seriesId 归堆（Map 保持插入序 = 首见序）
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
  const emitted: Set<number> = new Set<number>();
  for (let i = 0; i < keys.length; i++) {
    const sid: number | undefined = keys[i].seriesId;
    if (sid === undefined) {
      out.push(new GroupSpan(undefined, [i])); // 无归属的片各自成条
      continue;
    }
    if (emitted.has(sid)) {
      continue; // 这一组已在首见位整体处理过
    }
    emitted.add(sid);
    const members: Array<number> = groups.get(sid) ?? [];
    if (members.length < MIN_GROUP_SIZE) {
      // 组内只剩 1 个可见成员 ⇒ 退化成单条（各占各的站位）
      for (const idx of members) {
        out.push(new GroupSpan(undefined, [idx]));
      }
      continue;
    }
    // 脏数据（partIndex 缺失）排末尾，不许因此把整组打散；等值保持输入顺序（稳定排序）
    const ordered: Array<number> = members.slice();
    ordered.sort((a: number, b: number): number => {
      const pa: number = keys[a].partIndex === undefined ? Number.MAX_SAFE_INTEGER :
        keys[a].partIndex as number;
      const pb: number = keys[b].partIndex === undefined ? Number.MAX_SAFE_INTEGER :
        keys[b].partIndex as number;
      return pa - pb;
    });
    out.push(new GroupSpan(sid, ordered));
  }
  return out;
}

/** 媒体库条目 → 展示单元（薄适配层：规则全在 groupSpans） */
export function galleryEntries(clips: Array<VideoClip>, collapse: boolean = true): Array<GalleryEntry> {
  const keys: Array<SeriesKey> = [];
  for (const c of clips) {
    keys.push(new SeriesKey(c.id, c.seriesId, c.partIndex));
  }
  const out: Array<GalleryEntry> = [];
  for (const span of groupSpans(keys, collapse)) {
    const parts: Array<VideoClip> = [];
    for (const idx of span.indices) {
      parts.push(clips[idx]);
    }
    out.push(new GalleryEntry(parts[0], span.seriesId, parts));
  }
  return out;
}

/**
 * **组级动作扇出**：把选中的片展开成「要真正动到的那些片」。
 *
 * 多选里点中的是代表段，但分享 / 收藏 / 移入回收站 / 还原 / 彻底删除都必须落到该系列的
 * **每一个可见成员**上——否则会出现「只把首段移进回收站、其余段还在原地」的半截状态。
 * 只在**确实成组**（成员 ≥ MIN_GROUP_SIZE）时展开；同组两个成员都被选中时按 id 去重。
 */
export function expandSeriesTargets(picked: Array<VideoClip>, all: Array<VideoClip>):
  Array<VideoClip> {
  if (picked.length === 0 || all.length === 0) {
    return picked;
  }
  const members: Map<number, Array<VideoClip>> = new Map<number, Array<VideoClip>>();
  for (const c of all) {
    if (c.seriesId === undefined) {
      continue;
    }
    const bucket: Array<VideoClip> | undefined = members.get(c.seriesId as number);
    if (bucket === undefined) {
      members.set(c.seriesId as number, [c]);
    } else {
      bucket.push(c);
    }
  }
  if (members.size === 0) {
    return picked;
  }
  const out: Array<VideoClip> = [];
  const seen: Set<number> = new Set<number>();
  for (const c of picked) {
    const sid: number | undefined = c.seriesId;
    const group: Array<VideoClip> | undefined = sid === undefined ? undefined : members.get(sid);
    const targets: Array<VideoClip> = (group !== undefined && group.length >= MIN_GROUP_SIZE)
      ? group : [c];
    for (const t of targets) {
      if (seen.has(t.id)) {
        continue;
      }
      seen.add(t.id);
      out.push(t);
    }
  }
  return out;
}

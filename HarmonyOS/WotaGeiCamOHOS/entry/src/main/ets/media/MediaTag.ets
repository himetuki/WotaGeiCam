/**
 * 媒体库标签与页签纯逻辑（直译上游 media/MediaTag.kt，对齐上游 38d712a）。
 *
 * 平台差异（登记）：
 * - Android 资源文案 → ArkTS 常量中文（系统页签 All/Like/Trash 的 label）；
 * - sealed class ClipFilter → 枚举 kind + tag 字段（ArkTS 无 Kotlin sealed class）；
 * - customTags 本批由调用方传空表（自定义标签管理留待后续批），build 仍按上游顺序
 *   All → Like → Trash → 自定义。
 */
export class TagSpace {
  static readonly LIKE: string = 'like';
  static readonly TRASH: string = 'trash';
  static readonly SYSTEM: Array<string> = ['like', 'trash'];

  static isSystem(tag: string): boolean {
    return TagSpace.SYSTEM.indexOf(tag) >= 0;
  }

  static isCustom(tag: string): boolean {
    return !TagSpace.isSystem(tag);
  }

  /** 自定义 tag 合法性：去空白后非空、不撞保留词（大小写不敏感）、长度 ≤24 */
  static normalize(raw: string): string | undefined {
    const t: string = raw.trim();
    if (t.length === 0 || t.length > 24) {
      return undefined;
    }
    const lower: string = t.toLowerCase();
    if (TagSpace.SYSTEM.indexOf(lower) >= 0) {
      return undefined;
    }
    return t;
  }
}

/** 媒体库数据范围：本应用目录与手机相册（本批只实现 WotaLibrary） */
export enum MediaScope {
  WotaLibrary = 0,
  AllVideos = 1
}

/** 列表形态：时间线 / 网格 / 全屏滑动（本批只实现 Timeline） */
export enum ListMode {
  Timeline = 0,
  Grid = 1,
  Fullscreen = 2
}

/** 过滤条件种类（对应上游 ClipFilter 的四个子类） */
export enum ClipKind {
  All = 0,
  Like = 1,
  Trash = 2,
  Tagged = 3
}

/** 顶部页签过滤条件 */
export class ClipFilter {
  kind: ClipKind;
  /** kind === Tagged 时有效 */
  tag: string;

  constructor(kind: ClipKind, tag: string = '') {
    this.kind = kind;
    this.tag = tag;
  }

  static all(): ClipFilter {
    return new ClipFilter(ClipKind.All);
  }

  static like(): ClipFilter {
    return new ClipFilter(ClipKind.Like);
  }

  static trash(): ClipFilter {
    return new ClipFilter(ClipKind.Trash);
  }

  static tagged(tag: string): ClipFilter {
    return new ClipFilter(ClipKind.Tagged, tag);
  }

  equals(other: ClipFilter): boolean {
    return this.kind === other.kind && (this.kind !== ClipKind.Tagged || this.tag === other.tag);
  }
}

/** 页签：过滤条件 + 文案 */
export class GalleryTab {
  filter: ClipFilter;
  label: string;

  constructor(filter: ClipFilter, label: string) {
    this.filter = filter;
    this.label = label;
  }
}

/** 页签选中态（index + filter 必须成对矫正） */
export class GallerySelection {
  index: number;
  filter: ClipFilter;

  constructor(index: number, filter: ClipFilter) {
    this.index = index;
    this.filter = filter;
  }
}

export class GalleryTabs {
  static build(customTags: Array<string>): Array<GalleryTab> {
    const tabs: Array<GalleryTab> = [];
    tabs.push(new GalleryTab(ClipFilter.all(), '全部'));
    tabs.push(new GalleryTab(ClipFilter.like(), '收藏'));
    tabs.push(new GalleryTab(ClipFilter.trash(), '回收站'));
    for (const tag of customTags) {
      if (TagSpace.isCustom(tag)) {
        tabs.push(new GalleryTab(ClipFilter.tagged(tag), tag));
      }
    }
    return tabs;
  }

  /**
   * 页签收窄后的选中态矫正（上游 clampedSelection 逐行）：
   * index 夹回 [0,lastIndex]，filter 与选中页签不一致时以页签为准。
   */
  static clampedSelection(tabs: Array<GalleryTab>, index: number, filter: ClipFilter): GallerySelection {
    if (tabs.length === 0) {
      return new GallerySelection(index, filter);
    }
    const i: number = Math.min(tabs.length - 1, Math.max(0, index));
    const tabFilter: ClipFilter = tabs[i].filter;
    if (tabFilter.equals(filter)) {
      return new GallerySelection(index, filter);
    }
    return new GallerySelection(i, tabFilter);
  }
}

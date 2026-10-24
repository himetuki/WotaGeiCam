/**
 * 媒体库三列表形态的纯逻辑（G3a 批，2026-10-24，直译上游 MediaTag.ListMode +
 * MediaRepo.groupTimeline + WotaSettings.galleryColumns）。
 *
 * 上游媒体库有 时间线/网格/全屏滑动 三种列表形态（需求 29 行「按时间线/网格/全屏滑动
 * 查看」；`MediaTag.kt:34 ListMode`，顶栏三键切换，默认 Grid）。本文件收形态枚举、
 * 时间线按日分组（今天/昨天/N 月 N 日/跨年补年）与网格列数档位校验三条纯算式；
 * 全屏滑动形态的平台面（Swiper + 内嵌播放器）留 G3b（登记于 PORT_MAP，非留白）。
 *
 * 零平台 import：分组标签的「今天」基准由调用方传 nowMs，日期一律取**本地时区**
 * 日历日（上游 Calendar 口径），epochDay 用 days-from-civil 纯算式（不依赖运行时
 * 时区偏移，跨年不会误判「昨天」——上游 epochDay 注释同款理由）。
 */
import { VideoClip } from './MediaRepo';

/** 列表形态（上游 ListMode 逐字同序：Timeline/Grid/Fullscreen，默认 Grid） */
export const LIST_TIMELINE: string = 'timeline';
export const LIST_GRID: string = 'grid';
export const LIST_FULLSCREEN: string = 'fullscreen';
export const DEFAULT_LIST_MODE: string = LIST_GRID;

/** 网格列数档位与缺省（上游 GALLERY_COLUMN_TIERS = [2,3,4,5]、缺 2，逐字） */
export const GALLERY_COLUMN_TIERS: Array<number> = [2, 3, 4, 5];
export const GALLERY_COLUMNS_KEY: string = 'gallery_columns';

/** 存值校验：不在档位表内回 2（上游 galleryColumns 逐字） */
export function galleryColumnsOf(stored: number): number {
  return GALLERY_COLUMN_TIERS.indexOf(stored) >= 0 ? stored : 2;
}

/** 时间线分组（上游 TimelineGroup：label 给人看、dayKey 作列表 key） */
export class TimelineGroup {
  label: string;
  dayKey: string;
  clips: Array<VideoClip>;

  constructor(label: string, dayKey: string, clips: Array<VideoClip>) {
    this.label = label;
    this.dayKey = dayKey;
    this.clips = clips;
  }
}

/** 距 1970-01-01 **本地日历日**的天数（Howard Hinnant days-from-civil；m: 1..12） */
export function daysFromCivil(y: number, m: number, d: number): number {
  const yy: number = m <= 2 ? y - 1 : y;
  const era: number = Math.floor(yy / 400);
  const yoe: number = yy - era * 400;
  const doy: number = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe: number = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** ms → 本地日历日的 epochDay（上游 epochDay(Calendar) 等价） */
export function epochDayOf(ms: number): number {
  const dt: Date = new Date(ms);
  return daysFromCivil(dt.getFullYear(), dt.getMonth() + 1, dt.getDate());
}

/**
 * 按日分组（groupTimeline 直译）：输入序即组内序（不重排——上游吃仓库查询的
 * date_added DESC 序）；同键相邻合并走 Map 插入序（上游 LinkedHashMap 口径）。
 * 文案：今天 / 昨天 / 同年 `M 月 D 日` / 跨年 `YYYY 年 M 月 D 日`（strings_gallery.xml 逐字）。
 */
export function groupTimeline(clips: Array<VideoClip>, nowMs: number): Array<TimelineGroup> {
  const groups: Array<TimelineGroup> = [];
  if (clips.length === 0) {
    return groups;
  }
  const todayEpoch: number = epochDayOf(nowMs);
  const nowYear: number = new Date(nowMs).getFullYear();
  const index: Map<string, number> = new Map<string, number>();
  for (const c of clips) {
    const dt: Date = new Date(c.dateAddedSec * 1000);
    const ed: number = epochDayOf(dt.getTime());
    const diff: number = todayEpoch - ed;
    const sameYear: boolean = dt.getFullYear() === nowYear;
    const label: string =
      diff === 0 ? '今天'
        : diff === 1 ? '昨天'
          : sameYear ? `${dt.getMonth() + 1} 月 ${dt.getDate()} 日`
            : `${dt.getFullYear()} 年 ${dt.getMonth() + 1} 月 ${dt.getDate()} 日`;
    const key: string = `${ed}|${label}`;
    let gi: number | undefined = index.get(key);
    if (gi === undefined) {
      gi = groups.length;
      index.set(key, gi);
      groups.push(new TimelineGroup(label, String(ed), []));
    }
    groups[gi].clips.push(c);
  }
  return groups;
}

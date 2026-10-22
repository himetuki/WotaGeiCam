/**
 * 媒体库时间线/时长/大小格式化（直译上游 media/MediaRepo.kt 的 groupTimeline/formatDuration/formatSize）。
 * 纯函数、不 import 平台 Kit（便于与上游逐项对拍）。
 *
 * 平台差异（登记）：
 * - 上游 Calendar 取本地时区 → ArkTS Date 本地时区；epochDay 用本地午夜毫秒 / 86400000；
 * - String.format(Locale.US,"%.2f") → toFixed（小数位一致；四舍六入的极端边界有差异，登记）；
 * - TimelineGroup 用泛型 T（只要求 dateAddedSec），避免与 MediaRepo 循环依赖。
 */

export interface TimelineDated {
  dateAddedSec: number;
}

/** 时间线分组：label 展示文案、dayKey 稳定分组键、clips 组内条目（保持输入顺序） */
export class TimelineGroup<T> {
  label: string;
  dayKey: string;
  clips: Array<T>;

  constructor(label: string, dayKey: string, clips: Array<T>) {
    this.label = label;
    this.dayKey = dayKey;
    this.clips = clips;
  }
}

function pad2(v: number): string {
  return v < 10 ? `0${v}` : `${v}`;
}

/** 距 1970-01-01 的本地天数（跨年不会误判昨天） */
export function epochDaySec(epochSec: number): number {
  const d: Date = new Date(epochSec * 1000);
  const midnight: number = new Date(d.getFullYear(), d.getMonth(), d.getDate(), 0, 0, 0, 0).getTime();
  return Math.floor(midnight / 86400000);
}

/**
 * 今天 / 昨天 / 同年 M月D日 / 跨年 YYYY年M月D日。
 * 输入应已按 dateAddedSec 降序；输出保持首次见到的组顺序。
 */
export function groupTimeline<T extends TimelineDated>(clips: Array<T>): Array<TimelineGroup<T>> {
  if (clips.length === 0) {
    return [];
  }
  const now: Date = new Date();
  const todayEpochDay: number = epochDaySec(Math.floor(now.getTime() / 1000));
  const currentYear: number = now.getFullYear();
  const order: Array<string> = [];
  const groups: Map<string, TimelineGroup<T>> = new Map<string, TimelineGroup<T>>();
  for (const c of clips) {
    const d: Date = new Date(c.dateAddedSec * 1000);
    const key: string = `${epochDaySec(c.dateAddedSec)}`;
    const diff: number = todayEpochDay - epochDaySec(c.dateAddedSec);
    let label: string;
    if (diff === 0) {
      label = '今天';
    } else if (diff === 1) {
      label = '昨天';
    } else if (d.getFullYear() === currentYear) {
      label = `${d.getMonth() + 1}月${d.getDate()}日`;
    } else {
      label = `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`;
    }
    const existing: TimelineGroup<T> | undefined = groups.get(key);
    if (existing === undefined) {
      const created: TimelineGroup<T> = new TimelineGroup<T>(label, key, []);
      created.clips.push(c);
      groups.set(key, created);
      order.push(key);
    } else {
      existing.clips.push(c);
    }
  }
  const out: Array<TimelineGroup<T>> = [];
  for (const key of order) {
    const g: TimelineGroup<T> | undefined = groups.get(key);
    if (g !== undefined) {
      out.push(g);
    }
  }
  return out;
}

/** mm:ss / h:mm:ss（负值按 0） */
export function formatDuration(ms: number): string {
  const total: number = Math.max(0, Math.floor(ms / 1000));
  const h: number = Math.floor(total / 3600);
  const m: number = Math.floor((total % 3600) / 60);
  const s: number = total % 60;
  if (h > 0) {
    return `${h}:${pad2(m)}:${pad2(s)}`;
  }
  return `${pad2(m)}:${pad2(s)}`;
}

/**
 * 带符号时长（T23）：t < 0 → '-' + 绝对时长；其余（含 0）透传 formatDuration。
 * 负轴口径：毫秒 floor 截断（与 formatDuration 一致）且下限 1 秒——对比页偏移按 500ms
 * 步进，tMin=-500 若按 floor 会显示 `-00:00` 与 0 无法区分，故亚秒负值统一显示 `-00:01`
 * （-499 → -00:01；-1500 → -00:01 仍按 floor 截断，不向上夸大）。
 * 消费方：对比播放页时间线左端标签（off>0 时负轴 pre-roll，tMin 可为负）；上游无对应物
 * （Compose 侧直接拼字符串），此处落成纯函数供单测对拍（test/pure/media-format.test.ets）。
 */
export function formatSignedDuration(ms: number): string {
  if (ms < 0) {
    return `-${formatDuration(Math.max(1000, -ms))}`;
  }
  return formatDuration(ms);
}

/** 文件大小可读化（上游口径：GB 两位、MB 一位、其余 KB 整数；0/负值 = "0 B"） */
export function formatSize(bytes: number): string {
  if (bytes <= 0) {
    return '0 B';
  }
  const kb: number = 1024;
  const mb: number = kb * 1024;
  const gb: number = mb * 1024;
  if (bytes >= gb) {
    return `${(bytes / gb).toFixed(2)} GB`;
  }
  if (bytes >= mb) {
    return `${(bytes / mb).toFixed(1)} MB`;
  }
  return `${(bytes / kb).toFixed(0)} KB`;
}

/**
 * diag 纯函数层——上游 diag/Diag.kt 528–637 行纯函数区 + 常量区的逐字段直译
 * （对齐上游 38d712a），全文件零 import：格式锚点集中于此，是将来对拍单测的靶面。
 *
 * 平台差异（登记）：
 * - SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) → 手写 Date 取段 + pad（照
 *   core/Constants.ets 的 fixed1/pad2 先例；禁止 toLocale 系与 Intl）；
 * - Kotlin Char 级别位 → string（'V'/'D'/'I'/'W'/'E'），判定语义不变；
 * - msg.replace('\n', '⏎') 在 Kotlin 是全量替换；ArkTS 禁正则字面量，用 split/join
 *   达成同一「全部换行压平」语义，⏎ = '\u23CE' 不变；
 * - diagClearLogFiles(dir, ...) 改为纯名单函数 diagClearLogNames(names, ...)：
 *   不碰 IO，返回应删名单，删除动作由 Diag 主体执行（签名差异登记）。
 */

/** 内存环形缓冲行数上限（导出时会带上；够覆盖"从冷启到复现"的上下文） */
export const DIAG_RING_LINES: number = 3_000;

/**
 * 待落盘队列行数上限。写盘线程被系统 IO 卡住时 pending 只进不出——日志把内存吃穿
 * 比丢日志严重得多，所以必须封顶：超了就丢最老的。丢掉的条数记进 droppedPending
 * 并写进导出件头部（diagDroppedNote），不许静默消失。
 */
export const DIAG_PENDING_MAX_LINES: number = 3_000;

/** 单个日志文件上限，超过即轮转 */
export const DIAG_FILE_MAX_BYTES: number = 512 * 1024;

/** 轮转文件保留个数（含当前文件 ⇒ 磁盘占用上限 = 上限 × 个数） */
export const DIAG_FILE_KEEP: number = 3;

/** 导出件最多带多少字节（防手滑把几十 MB 发出去） */
export const DIAG_EXPORT_MAX_BYTES: number = 2 * 1024 * 1024;

/** 崩溃件里带多少行上下文 */
export const DIAG_CRASH_TAIL_LINES: number = 400;

/** 结构帧账最多留多少行（段收尾一行 + 会话清单一行） */
export const FRAME_ACCOUNT_MAX_LINES: number = 64;

/** 日志基础件名 */
export const BASE_NAME: string = 'wotagei.log';

/** 崩溃件名前缀 */
export const CRASH_PREFIX: string = 'crash-';

/** 导出件名前缀 */
export const EXPORT_PREFIX: string = 'wotagei-log-';

/** 日志目录名 */
export const LOG_DIR_NAME: string = 'logs';

/** 导出目录名（本批只写应用目录，公共目录留白随设置页/分享批） */
export const EXPORT_DIR_NAME: string = 'WotaGeiCam';

function pad2(v: number): string {
  return v < 10 ? `0${v}` : `${v}`;
}

function pad3(v: number): string {
  if (v < 10) {
    return `00${v}`;
  }
  if (v < 100) {
    return `0${v}`;
  }
  return `${v}`;
}

/** 行头时间戳：`MM-dd HH:mm:ss.SSS`（本地时区，够读且不拖长度） */
export function diagStamp(epochMs: number): string {
  const d: Date = new Date(epochMs);
  return `${pad2(d.getMonth() + 1)}-${pad2(d.getDate())} ${pad2(d.getHours())}:` +
    `${pad2(d.getMinutes())}:${pad2(d.getSeconds())}.${pad3(d.getMilliseconds())}`;
}

/** 文件名里的时间戳：`yyyyMMdd-HHmmss`（文件名不能带冒号） */
export function diagFileStamp(epochMs: number): string {
  const d: Date = new Date(epochMs);
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}-` +
    `${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`;
}

/** 日志行：时间 级别 TAG [线程] 正文（正文自带换行的会被压成一行，保证"一行一条"） */
export function diagLine(stamp: string, level: string, tag: string, thread: string, msg: string): string {
  const flattened: string = msg.split('\n').join('\u23CE');
  return `${stamp} ${level} ${tag} [${thread}] ${flattened}\n`;
}

/**
 * 该级别在当前档位下要不要记：默认档只记 `i/w/e`（关键事件），详细档才带 `d/v`。
 * 未知级别按要记处理（宁可多记，别把新增级别悄悄丢了）。
 */
export function diagLevelEnabled(level: string, verbose: boolean): boolean {
  if (level === 'V' || level === 'D') {
    return verbose;
  }
  return true;
}

/**
 * 常规记录的总闸（总开关 × 级别档位合成一个判据）：
 * 总开关关 ⇒ 全拒（默认关闭省空间，2026-10-13 用户裁决照搬）；开 ⇒ 再按级别档位判。
 * 崩溃件不走本判据——它同步直写文件（取证语义）。判定顺序钉死为"先总开关后级别"。
 */
export function diagRecordGated(level: string, enabled: boolean, verbose: boolean): boolean {
  if (!enabled) {
    return true;
  }
  return !diagLevelEnabled(level, verbose);
}

/** 当前文件是否该轮转 */
export function diagShouldRotate(currentBytes: number, max: number = DIAG_FILE_MAX_BYTES): boolean {
  return currentBytes >= max;
}

/** 待落盘队列当前 size、上限 max 时要丢几行（丢最老的）；未超限返回 0 */
export function diagPendingOverflow(size: number, max: number): number {
  return Math.max(0, size - max);
}

/** 轮转件名：`wotagei.log.1`（index 从 1 起） */
export function diagRotatedName(base: string, index: number): string {
  return `${base}.${index}`;
}

/** 导出件名 */
export function diagExportName(stamp: string): string {
  return `${EXPORT_PREFIX}${stamp}.txt`;
}

/** 崩溃件名 */
export function diagCrashName(stamp: string): string {
  return `${CRASH_PREFIX}${stamp}.txt`;
}

/**
 * 导出件头部的丢弃自曝行。没有它，被封顶丢掉的行就是静默消失——用户会把"这段时间
 * 没记录"当成"什么都没发生"。丢过才占一行；没丢过返回空串，正常路径导出行数不变。
 */
export function diagDroppedNote(dropped: number): string {
  if (dropped <= 0) {
    return '';
  }
  return `注意：待落盘队列超 ${DIAG_PENDING_MAX_LINES} 行封顶，有 ${dropped} 行未落盘（丢的是最老的）。\n`;
}

/**
 * 导出件里的结构帧账段。空表也留一行占位说明：区分"本次没录/没到段尾"与
 * "导出逻辑把帧账丢了"。段名与首尾标记同 diagHeader 的 === 风格。
 */
export function diagFrameAccountSection(lines: Array<string>): string {
  let out: string = '\n=== 录制帧账 ===\n';
  if (lines.length === 0) {
    out += '（本次会话没有段收尾帧账：未录制或尚未到段尾）\n';
  } else {
    for (const line of lines) {
      out += `${line}\n`;
    }
  }
  out += '=== 录制帧账结束 ===\n';
  return out;
}

/**
 * 导出件头一段：设备信息 + 用途说明 + 隐私声明。device 与 pkg 由调用方注入
 * （纯函数不碰平台 API；pkg 参数与上游签名对齐，正文不使用——上游同样如此）。
 */
export function diagHeader(device: string, pkg: string): string {
  return 'WotaGeiCam 诊断日志\n' +
    '==================\n' +
    '用途：这份文件用于把问题现场交给开发者定位（功能异常、被系统拒绝、崩溃等）。\n' +
    '隐私：仅本机生成、由你主动导出，不含画面与声音内容，也不会上传。\n\n' +
    `${device}\n`;
}

/**
 * 「清除日志」的删除判据（纯名单函数，不碰 IO）：命中 = 精确 baseName /
 * 前缀 baseName. / 前缀 crashPrefix；目录里的无关文件（如兜底的导出件）不动。
 * 返回应删名单，实际删除由 Diag 主体执行。
 */
export function diagClearLogNames(names: Array<string>, baseName: string, crashPrefix: string): Array<string> {
  const hit: Array<string> = [];
  for (const name of names) {
    if (name === baseName || name.startsWith(`${baseName}.`) || name.startsWith(crashPrefix)) {
      hit.push(name);
    }
  }
  return hit;
}

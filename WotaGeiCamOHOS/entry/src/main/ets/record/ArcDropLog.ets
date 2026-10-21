/**
 * 直译自上游 record/ArcDropLog.kt（harmony 分支）。
 * 平台差异：
 * - java.io.File 读写删改名 → @ohos.file.fs 同步 API（本 SDK 的 @kit.CoreFileKit 以 fileIo 名导出该模块）；
 * - Kotlin Pair<Int,Int> → 顶层导出小类 DropMark（out,n 两字段，语义同 (k, drops)）；
 * - Kotlin Regex → new RegExp(...)（ArkTS 禁正则字面量）；toIntOrNull/toInt → Number(x) +
 *   整数与 Int 上界（2^31−1）校验：上游超界返回 null/抛异常（经 readFrom 的 runCatching 归
 *   null），本侧同判整单 null；lostMs 上游是 toLongOrNull() ?: 0L（Long 域，超界归 0 非判坏），
 *   本侧 number 域 2^53 内精确，不做 Int 上界校验；
 * - trim('[',']',' ') → 自写 trimChars（两端剥集合内字符，语义同 Kotlin trim(vararg chars)）；
 * - renameFor 的 absolutePath 比较（无 canonicalize 可用时等价）→ sidecarFor 字符串等值比较。
 */

import { fileIo } from '@kit.CoreFileKit';

/** Kotlin Int 域上界（超界上游 toIntOrNull 得 null / toInt 抛异常，均归整单判坏） */
const INT_MAX: number = 2_147_483_647;

/**
 * 对应上游 Pair<Int,Int>：(输出位次 k, 该位次之前丢弃的源帧数)。
 * ArkTS 无元组/Pair，落成两字段小类。
 */
export class DropMark {
  out: number;
  n: number;

  constructor(out: number, n: number) {
    this.out = out;
    this.n = n;
  }
}

/**
 * 段清单单条：segment 段序号（与 VideoSegment.partIndex 同源）、first 该段首个输出位次、
 * count 该段输出帧数、lostMs 该段开始之前那次换段腿里丢掉的画面时长（ms；0 = 没丢，
 * 编码时 0 不落键 ⇒ 旧档案形状不变）。
 */
export class ArcDropSegment {
  segment: number;
  first: number;
  count: number;
  lostMs: number;

  constructor(segment: number, first: number, count: number, lostMs: number = 0) {
    this.segment = segment;
    this.first = first;
    this.count = count;
    this.lostMs = lostMs;
  }
}

/**
 * 录制期转换的被抽帧位次 sidecar（用户 2026-10-03 定版：抽帧时把位置记下来）。
 *
 * 文件与成片同目录同名，扩展名 `.drops.json`；纯档案/调试用途——仅抽帧模式下被抽帧的
 * 画面在录制时已弃，播放器无法事后补弧，弧连续由"自动抽帧补弧"模式在录制时一次完成。
 *
 * 位次的跨段口径（2026-10-07 订正，原注释与实现不符）：GPU 路换段（超过 MAX_FILE_BYTES
 * 轮转）会重挂 GL 编码面，重挂处把帧序号归零并清空位次账——所以 drops 里的 k 是当前段内
 * 的位次，且只含最后一段的丢弃记录，前面各段的丢弃记录已随换段清掉。这与 segments 段清单
 * 的全局 first/count 口径（录制器逐段真实样本数前缀和）不一致，是已知失真：要修需把 GL 账本
 * 按段持久化并跨段续号，因 sidecar 当前无读取方，暂记失真不改行为。排查者对不上前段位次时，
 * 以本失真为第一怀疑对象。sidecar 只随首段（parts[0]）落盘。每段一条 {segment,first,count}，
 * 数据源是录制器逐段已写视频样本数。当前无读取方，纯增量档案信息；
 * 空表不落键，旧档案（无 segments）解析结果不变。
 *
 * 编解码是手写的最小 JSON（数组里全是整数）：与上游一致不走系统 JSON，形状完全自控。
 */
export class ArcDropLog {
  mode: string;
  dstFps: number;
  /** (输出位次 k, 该位次之前丢弃的源帧数)；k 升序，段内位次起算（跨段失真见类注） */
  drops: Array<DropMark>;
  /** 段清单（可选增量字段，见类注）：按段序升序；空表 = 未记录/旧档案 */
  segments: Array<ArcDropSegment>;

  constructor(mode: string, dstFps: number, drops: Array<DropMark>, segments: Array<ArcDropSegment> = []) {
    this.mode = mode;
    this.dstFps = dstFps;
    this.drops = drops;
    this.segments = segments;
  }

  /**
   * 紧凑 JSON：`{"mode":"mend","dstFps":24,"drops":[[1,1],[6,1]]}`；
   * 有段清单时追加 `"segments":[{"segment":0,"first":0,"count":72},…]`
   * （空表不落键，保持旧形）；某段有换段丢失时该格追加 `,"lostMs":N`（0 不落键，
   * 与"空表不落键"同一口径：旧样例逐字不变，新样例只在真丢过内容时才多一个键）。
   * 非热路径，字符串拼接实现。
   */
  encode(): string {
    let s: string = `{"mode":"${this.mode}","dstFps":${this.dstFps}`;
    s += ',"drops":[';
    for (let i = 0; i < this.drops.length; i++) {
      if (i > 0) {
        s += ',';
      }
      s += `[${this.drops[i].out},${this.drops[i].n}]`;
    }
    s += ']';
    if (this.segments.length > 0) {
      s += ',"segments":[';
      for (let i = 0; i < this.segments.length; i++) {
        if (i > 0) {
          s += ',';
        }
        const seg: ArcDropSegment = this.segments[i];
        s += `{"segment":${seg.segment},"first":${seg.first},"count":${seg.count}`;
        if (seg.lostMs > 0) {
          s += `,"lostMs":${seg.lostMs}`;
        }
        s += '}';
      }
      s += ']';
    }
    s += '}';
    return s;
  }

  /** sidecar 扩展名（与成片同目录同名换此扩展名） */
  static readonly EXTENSION: string = '.drops.json';

  /** 成片路径 → sidecar 路径（同目录同名换扩展名） */
  static sidecarFor(videoPath: string): string {
    const dot: number = videoPath.lastIndexOf('.');
    const stem: string = dot > 0 ? videoPath.substring(0, dot) : videoPath;
    return stem + ArcDropLog.EXTENSION;
  }

  /** 写盘（失败返回 false；截断写，UTF-8） */
  static writeTo(videoPath: string, log: ArcDropLog): boolean {
    const p: string = ArcDropLog.sidecarFor(videoPath);
    let fd: number = -1;
    try {
      const f: fileIo.File = fileIo.openSync(p, fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fd = f.fd;
      fileIo.writeSync(fd, log.encode());
      return true;
    } catch (e) {
      return false;
    } finally {
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (e) {
          // 关句柄失败不再影响结果（写已在 try 主体返回）
        }
      }
    }
  }

  /** 手写解析：只认自己 encode 的形状，坏串/坏格返回 null（档案文件损坏不抛） */
  static decode(raw: string): ArcDropLog | null {
    const modeM: RegExpExecArray | null = new RegExp('"mode"\\s*:\\s*"([a-z]+)"').exec(raw);
    if (modeM === null) {
      return null;
    }
    const mode: string = modeM[1];
    const fpsM: RegExpExecArray | null = new RegExp('"dstFps"\\s*:\\s*(\\d+)').exec(raw);
    if (fpsM === null) {
      return null;
    }
    const dstFps: number = Number(fpsM[1]);
    if (!Number.isInteger(dstFps) || dstFps > INT_MAX) {
      return null;
    }
    // segments（可选）按 encode 顺序恒落在 drops 之后：先整键切掉，drops 按旧形状解，
    // 旧档案没有该键时原文直进下面流程（行为逐字不变——否则 lastIndexOf(']') 会咬到
    // segments 数组的收括号，把 drops 解坏）
    const segAnchor: number = raw.indexOf('"segments":[');
    const dropsRaw: string = segAnchor >= 0 ? trimEndChars(raw.substring(0, segAnchor), ', ') + ']' : raw;
    // drops 的捕获不能用 [^]] —— 成对括号里的 ] 会把捕获截断在第一对上；
    // 改用定位法：取 `"drops":[` 之后、最后一个 `]` 之前的全部（`[]` 时两位置重合 = 空）
    const anchor: number = dropsRaw.indexOf('"drops":[');
    if (anchor < 0) {
      return null;
    }
    const start: number = anchor + '"drops":['.length;
    const end: number = dropsRaw.lastIndexOf(']');
    if (end < start) {
      return null;
    }
    const body: string = dropsRaw.substring(start, end);
    const drops: Array<DropMark> = [];
    if (body.trim().length > 0) {
      const pairRe: RegExp = new RegExp('^(\\d+),(\\d+)$');
      const parts: Array<string> = body.split('],[');
      for (const part of parts) {
        // 按成对括号的缝拆开后，逐格剥掉残留括号再严格对整数对；任何一格不合形即整单判坏
        const m: RegExpExecArray | null = pairRe.exec(trimChars(part, '[] '));
        if (m === null) {
          return null;
        }
        const o: number = Number(m[1]);
        const n: number = Number(m[2]);
        if (!Number.isInteger(o) || !Number.isInteger(n) || o > INT_MAX || n > INT_MAX) {
          return null;
        }
        drops.push(new DropMark(o, n));
      }
    }
    const segments: Array<ArcDropSegment> | null =
      segAnchor < 0 ? [] : ArcDropLog.parseSegments(raw.substring(segAnchor));
    if (segments === null) {
      return null;
    }
    return new ArcDropLog(mode, dstFps, drops, segments);
  }

  /**
   * 解 `"segments":[{…},{…}]` 尾段：与 drops 同一口径——按 `},{` 缝拆开、剥掉缝上残留
   * 的括号后逐格严格对整数，任何一格不合形整单判坏（返回 null）。
   * encode 从不写空表，键在表空 = 损坏。
   */
  private static parseSegments(tail: string): Array<ArcDropSegment> | null {
    const start: number = tail.indexOf('[');
    const end: number = tail.lastIndexOf(']');
    if (start < 0 || end < start) {
      return null;
    }
    const body: string = tail.substring(start + 1, end).trim();
    if (body.length === 0) {
      return null;
    }
    const segRe: RegExp = new RegExp('^"segment":(\\d+),"first":(\\d+),"count":(\\d+)(?:,"lostMs":(\\d+))?$');
    const list: Array<ArcDropSegment> = [];
    const parts: Array<string> = body.split('},{');
    for (const part of parts) {
      // 首格缺收 `}`、尾格缺起 `{`（都被缝吃掉），trim 后统一成裸字段形再严格对；
      // lostMs 可选（0 不落键，见 encode）——缺席即 0，其余字段一字不许少
      const m: RegExpExecArray | null = segRe.exec(trimChars(part, '{} '));
      if (m === null) {
        return null;
      }
      const seg: number = Number(m[1]);
      const first: number = Number(m[2]);
      const count: number = Number(m[3]);
      if (!Number.isInteger(seg) || !Number.isInteger(first) || !Number.isInteger(count)
        || seg > INT_MAX || first > INT_MAX || count > INT_MAX) {
        return null;
      }
      let lostMs: number = 0;
      if (m[4] !== undefined) {
        // 上游 toLongOrNull() ?: 0L：Long 域、超界归 0 而非判坏，故此处不做 Int 上界校验
        const lm: number = Number(m[4]);
        if (!Number.isInteger(lm)) {
          return null;
        }
        lostMs = lm;
      }
      list.push(new ArcDropSegment(seg, first, count, lostMs));
    }
    return list;
  }

  /** 读档：sidecar 不是普通文件（含不存在）返回 null；坏串由 decode 判 null */
  static readFrom(videoPath: string): ArcDropLog | null {
    try {
      const p: string = ArcDropLog.sidecarFor(videoPath);
      if (!fileIo.statSync(p).isFile()) {
        return null;
      }
      return ArcDropLog.decode(fileIo.readTextSync(p));
    } catch (e) {
      return null;
    }
  }

  /** 彻底删除成片时连带删 sidecar（回收站软删不动它——文件还在） */
  static deleteFor(videoPath: string): boolean {
    try {
      fileIo.unlinkSync(ArcDropLog.sidecarFor(videoPath));
      return true;
    } catch (e) {
      return false;
    }
  }

  /**
   * 成片改名时连带搬 sidecar（位次档案与成片的对应关系不能断）；无可搬文件返回 false。
   * 目标已存在时**绝不覆盖**：Linux rename 语义是静默替换，会把目标处已有的别片档案吃掉
   * （与清扫侧 RENAME_ARCHIVE 的双保险同一条红线）。改名回查失败退回请求名恰好撞上
   * 他片时，宁可留着不搬也不能覆盖。
   * 平台差异：上游 absolutePath 比较（本方无 canonicalize 等价物）按 sidecarFor 字符串等值。
   */
  static renameFor(oldVideoPath: string, newVideoPath: string): boolean {
    try {
      const from: string = ArcDropLog.sidecarFor(oldVideoPath);
      if (!fileIo.statSync(from).isFile()) {
        return false;
      }
      const to: string = ArcDropLog.sidecarFor(newVideoPath);
      if (to === from) {
        return true;
      }
      if (fileIo.accessSync(to)) {
        return false;
      }
      const slash: number = to.lastIndexOf('/');
      if (slash > 0) {
        try {
          fileIo.mkdirSync(to.substring(0, slash), true);
        } catch (e) {
          // 父目录建不上（已存在/无权限）尽力而为，交给 renameSync 决定成败
        }
      }
      fileIo.renameSync(from, to);
      return true;
    } catch (e) {
      return false;
    }
  }
}

/**
 * 两端剥掉 chars 集合内的字符（对应 Kotlin String.trim(vararg chars)。
 * 上游用法：trim('[', ']', ' ') / trim('{', '}', ' ')）。
 */
function trimChars(s: string, chars: string): string {
  let start: number = 0;
  let end: number = s.length;
  while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
    start++;
  }
  while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
    end--;
  }
  return s.substring(start, end);
}

/** 尾端剥掉 chars 集合内的字符（对应 Kotlin String.trimEnd(vararg chars)，上游用法 trimEnd(',', ' ')） */
function trimEndChars(s: string, chars: string): string {
  let end: number = s.length;
  while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) {
    end--;
  }
  return s.substring(0, end);
}

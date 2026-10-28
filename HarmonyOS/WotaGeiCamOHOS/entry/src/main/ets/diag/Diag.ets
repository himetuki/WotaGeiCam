/**
 * 应用内诊断日志（完整版）——上游 diag/Diag.kt（637 行）的鸿蒙移植（对齐上游 38d712a）。
 * 格式与轮转规则的纯函数锚点在 diag/DiagPure.ets；本文件是状态机与 IO 侧。
 * 模式照搬：总开关默认关（关 = 常规记录一行不写）、崩溃件不受开关管、pending 封顶
 * 丢最老并自曝、环形缓冲 3000 行、单件 512KB × 留 3、导出 2MB 预算。
 *
 * 与上游的有意偏离（六条决策，登记）：
 * 1. hilog 恒写双通道（i/w/e）：上游装 Diag 后只写文件（EMUI 容器 logcat 不可见是病根）；
 *    OHOS hilog 可见且是当前主要取证通道，故 record() 对 i/w/e 恒写 hilog，文件侧才走开关门。
 *    2026-10-28 对齐上游 eefccc9 拆闸：**d/v 收详细档**（逐帧日志会把系统 hilog 环形缓冲
 *    冲掉）——「恒写取证通道」与「磁盘占用」解耦，但「别冲掉系统缓冲」仍是硬约束。
 * 2. 无独立写盘线程：Thread+writerLoop → setInterval(cb, 1000)，写盘用 fs 同步 API
 *    （每秒 ≤500 行量级可接受；单线程事件循环内同步批写天然无并发交错，clear/export
 *    无需等在途批）。后台杀定时器只影响落盘时延，pending 封顶兜底（与上游写盘线程
 *    被卡同款防线）。draining 旗标防重入保留。**导出/清除阻塞风险登记**：export/clear
 *    在 UI 线程同步读拼写（最多 2MB 预算 + 崩溃件），上游要求后台线程调用；本批不做
 *    TaskPool，后续用 TaskPool 包住（或分片 yield）再收敛。
 * 3. 崩溃钩子：Thread.setDefaultUncaughtExceptionHandler → errorManager.on('error')，
 *    onUnhandledException(errMsg: string) 收到的串即堆栈；写完不拦截进程退出，
 *    无「上一处理器链」。崩溃件同步直写（fs Sync 系）。
 * 4. SharedPreferences("wota_diag") → filesDir/logs/last-session.txt 单行文件同步读写
 *    （不引入 preferences 异步初始化）。
 * 5. 导出边界：三种落点——① `export` 写应用目录 filesDir/WotaGeiCam/（原口径）；
 *    ② `exportForShare`（DIAG-SHARE 批，对齐上游同名函数）写**缓存目录** cacheDir/share-out
 *    供系统分享面板用（缓存件被系统回收正合「分享完就没用」语义，且分享不被公共目录
 *    可写性卡住）；③ `reportText` 供系统保存选择器（DocumentViewPicker）落用户选定位置
 *    ——这是「公共 Documents」（上游写外部存储）在沙箱模型下的**零权限等价路径**
 *    （用户显式选址，与 MS 批相册入库同款思路）。
 * 6. deviceSummary：board/fingerprint 无对应字段省行；hardware 用 deviceInfo.hardwareModel
 *    近似（与 Android Build.HARDWARE 语义不完全相同——登记）；ABI 用 deviceInfo.abiList
 *    （已是逗号分隔串，等价上游 joinToString(",")）；设备用 deviceType（phone/tablet 等
 *    形态类别，与 Android Build.DEVICE 设备代号语义不同——登记）；时区用
 *    getTimezoneOffset() 换算 ±HH:MM（无 TimeZone id API）；[thread] 恒 main（单线程
 *    事件循环）。总开关/详细档持久化（上游 WotaSettings 的 diagEnabled/diagVerbose）
 *    与详细档 UI 入口留白（随设置页批），本批重启回落默认关。
 */
import { common, errorManager, bundleManager } from '@kit.AbilityKit';
import { deviceInfo } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { fileIo } from '@kit.CoreFileKit';
import {
  DIAG_RING_LINES, DIAG_PENDING_MAX_LINES, DIAG_FILE_KEEP, DIAG_EXPORT_MAX_BYTES,
  DIAG_CRASH_TAIL_LINES, FRAME_ACCOUNT_MAX_LINES, BASE_NAME, CRASH_PREFIX, EXPORT_DIR_NAME,
  LOG_DIR_NAME, diagStamp, diagFileStamp, diagLine, diagRecordGated, diagLevelEnabled,
  diagShouldRotate, diagPendingOverflow, diagRotatedName, diagExportName, diagCrashName,
  diagDroppedNote, diagFrameAccountSection, diagHeader, diagClearLogNames
} from './DiagPure';

/** 导出结果：文件路径 + 是否落在公共可见目录（本批恒 false = 应用私有目录）+ 导出件字节数 */
export class ExportResult {
  path: string;
  inPublicDir: boolean;
  bytes: number;

  constructor(path: string, inPublicDir: boolean, bytes: number) {
    this.path = path;
    this.inPublicDir = inPublicDir;
    this.bytes = bytes;
  }
}

/** 崩溃件/导出件的排序键（mtime 降序用；对象字面量必须有具名类型） */
class StampedName {
  name: string;
  mtime: number;

  constructor(name: string, mtime: number) {
    this.name = name;
    this.mtime = mtime;
  }
}

function tzPad2(v: number): string {
  return v < 10 ? `0${v}` : `${v}`;
}

/** 时区 ±HH:MM（getTimezoneOffset 返回 UTC−local 分钟，取负得东偏） */
function tzOffsetText(): string {
  const minutes: number = -new Date().getTimezoneOffset();
  const sign: string = minutes >= 0 ? '+' : '-';
  const abs: number = Math.abs(minutes);
  return `${sign}${tzPad2(Math.floor(abs / 60))}:${tzPad2(abs % 60)}`;
}

/**
 * 全量覆写小文件（last-session / 崩溃件 / 导出件共用）；失败返回 false。
 * fd=-1 + catch/finally 收尾（照 record/ArcDropLog.writeTo 模式）：writeSync 抛错
 * （磁盘满/配额/IO error）也必关句柄，不再随重试累积 fd。
 */
function writeTextSync(path: string, text: string): boolean {
  let fd: number = -1;
  try {
    const f: fileIo.File = fileIo.openSync(path,
      fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
    fd = f.fd;
    fileIo.writeSync(fd, text);
    return true;
  } catch (e) {
    return false;
  } finally {
    if (fd >= 0) {
      try {
        fileIo.closeSync(fd);
      } catch (e) {
        // 关句柄失败不再影响结果
      }
    }
  }
}

export class Diag {
  /** 详细档：开启后 d/v 也记（用户复现问题时用，持久化由设置页负责） */
  private static verbose: boolean = false;

  /** 总开关：默认关。关 = 常规记录一行不写；崩溃件不归它管（取证不许被开关卡住） */
  private static enabled: boolean = false;

  private static installed: boolean = false;
  private static ring: Array<string> = [];
  private static pending: Array<string> = [];
  private static droppedPending: number = 0;
  private static frameAccountLines: Array<string> = [];
  private static logDir: string = '';
  private static current: string = '';
  private static ctx: common.Context | undefined = undefined;
  private static lastPruneMs: number = 0;
  private static draining: boolean = false;
  private static writerTimer: number = -1;

  // region 记录入口（i/w/e/d 签名与既有调用点兼容，仅追加可选 err 参）

  static v(tag: string, msg: string, err?: Error): void {
    Diag.record('V', tag, msg, err);
  }

  static d(tag: string, msg: string, err?: Error): void {
    Diag.record('D', tag, msg, err);
  }

  static i(tag: string, msg: string, err?: Error): void {
    Diag.record('I', tag, msg, err);
  }

  static w(tag: string, msg: string, err?: Error): void {
    Diag.record('W', tag, msg, err);
  }

  static e(tag: string, msg: string, err?: Error): void {
    Diag.record('E', tag, msg, err);
  }

  /** 与上游 getStackTraceString 对齐；ArkTS Error.stack 格式与 JVM 打印不同，差异登记 */
  static getStackTraceString(err?: Error): string {
    return err?.stack ?? '';
  }

  /**
   * 与平台同义（转发 hilog.isLoggable）。Android↔hilog LogLevel 映射登记：
   * VERBOSE(2)→DEBUG(3)，DEBUG/INFO/WARN/ERROR(3..6) 原值，域外值钳到相邻档。
   */
  static isLoggable(tag: string, level: number): boolean {
    let mapped: hilog.LogLevel = hilog.LogLevel.DEBUG;
    if (level >= 7) {
      mapped = hilog.LogLevel.FATAL;
    } else if (level === 6) {
      mapped = hilog.LogLevel.ERROR;
    } else if (level === 5) {
      mapped = hilog.LogLevel.WARN;
    } else if (level === 4) {
      mapped = hilog.LogLevel.INFO;
    } else {
      mapped = hilog.LogLevel.DEBUG; // VERBOSE(2)/DEBUG(3)/域外低值
    }
    return hilog.isLoggable(0x0000, tag, mapped);
  }

  /**
   * 记录动作不许影响主流程：全程 try/catch 吞。hilog 通道 i/w/e **恒写**（取证通道与磁盘占用
   * 解耦，对齐上游 eefccc9 拆闸——真机排查不能再被「总开关默认关」卡死）；**d/v 仍受详细档**
   * （逐帧日志会把系统 hilog 环形缓冲冲掉，反而看不到关键事件）；文件落盘仍走总开关门
   * （diagRecordGated，先总开关后级别的次序钉死）；崩溃件不走这里（同步直写，取证语义）。
   */
  private static record(level: string, tag: string, msg: string, err?: Error): void {
    try {
      const msgFull: string = err !== undefined ? `${msg} | ${Diag.getStackTraceString(err)}` : msg;
      if (level === 'E') {
        hilog.error(0x0000, tag, '%{public}s', msgFull);
      } else if (level === 'W') {
        hilog.warn(0x0000, tag, '%{public}s', msgFull);
      } else if (level === 'I') {
        hilog.info(0x0000, tag, '%{public}s', msgFull);
      } else if (diagLevelEnabled(level, Diag.verbose)) {
        hilog.debug(0x0000, tag, '%{public}s', msgFull); // V/D 同走 debug；受详细档（不冲系统缓冲）
      }
      if (diagRecordGated(level, Diag.enabled, Diag.verbose)) {
        return;
      }
      if (!Diag.installed) {
        return;
      }
      const line: string = diagLine(diagStamp(Date.now()), level, tag, 'main', msgFull);
      Diag.ring.push(line);
      while (Diag.ring.length > DIAG_RING_LINES) {
        Diag.ring.shift();
      }
      Diag.pending.push(line);
      // 队列必须封顶：写盘被卡时这里只进不出（DIAG_PENDING_MAX_LINES 的病根说明见 DiagPure）
      const drop: number = diagPendingOverflow(Diag.pending.length, DIAG_PENDING_MAX_LINES);
      for (let i = 0; i < drop; i++) {
        Diag.pending.shift();
        Diag.droppedPending++;
      }
    } catch (e) {
      // 日志失败绝不连坐主流程
    }
  }

  // endregion

  // region 结构帧账

  /** 追加一行结构帧账（线程安全单线程下直写；超出上限丢最老的一行） */
  static frameAccount(line: string): void {
    try {
      Diag.frameAccountLines.push(line);
      while (Diag.frameAccountLines.length > FRAME_ACCOUNT_MAX_LINES) {
        Diag.frameAccountLines.shift();
      }
    } catch (e) {
    }
  }

  /** 新录制会话开始时清空帧账（只清结构段，不动常规日志环形缓冲） */
  static clearFrameAccount(): void {
    Diag.frameAccountLines = [];
  }

  private static frameAccountSnapshot(): Array<string> {
    return Diag.frameAccountLines.slice();
  }

  // endregion

  // region 生命周期与开关

  /** 用户切详细档（设置页调用；只影响之后的记录） */
  static setVerbose(on: boolean): void {
    Diag.verbose = on;
    Diag.i('WotaDiag', on ? '详细日志已开启（含 d/v 级）' : '详细日志已关闭（只记 i/w/e）');
  }

  /** 总开关当前状态（页面按钮反向初始化用；持久化留白随设置页批） */
  static isEnabled(): boolean {
    return Diag.enabled;
  }

  /** 用户切总开关。关的那条先写后置 false——告别行让导出件能看出"从哪一刻起没现场了" */
  static setEnabled(on: boolean): void {
    if (Diag.enabled === on) {
      return;
    }
    if (on) {
      Diag.enabled = true;
      Diag.i('WotaDiag', '日志已开启');
    } else {
      Diag.i('WotaDiag', '日志已关闭（此后不记录，崩溃件除外）');
      Diag.enabled = false;
    }
  }

  /**
   * 装日志（冷启调一次；重复调用只更新开关，照上游幂等语义）。必须在写第一条日志之前
   * 调用才能收全上下文。
   */
  static install(context: common.Context, verbose: boolean = false, enabled: boolean = false): void {
    Diag.enabled = enabled;
    Diag.verbose = verbose;
    if (Diag.installed) {
      return;
    }
    Diag.installed = true;
    Diag.ctx = context;
    let prev: string | undefined = undefined;
    try {
      Diag.logDir = `${context.filesDir}/${LOG_DIR_NAME}`;
      try {
        fileIo.mkdirSync(Diag.logDir);
      } catch (e) {
        // 已存在或父目录缺失都吞（filesDir 必在，logs 首次创建）
      }
      Diag.current = `${Diag.logDir}/${BASE_NAME}`;
      Diag.startWriter();
      Diag.installCrashHandler();
      try {
        prev = fileIo.readTextSync(`${Diag.logDir}/last-session.txt`);
      } catch (e) {
        prev = undefined; // 首启无记录
      }
    } catch (e) {
      // 目录级失败吞掉：hilog 通道仍活（决策 1）
    }
    Diag.i('WotaDiag', `日志启动：上一会话结束于 ${prev ?? '（无记录）'}`);
    Diag.i('WotaDiag', `设备与版本：${Diag.deviceSummary()}`);
  }

  /** 会话收尾留痕：下次冷启能看到"上一会话结束于"（区分"被杀"与"正常退出"） */
  static markCleanExit(): void {
    try {
      if (Diag.logDir !== '') {
        writeTextSync(`${Diag.logDir}/last-session.txt`, diagStamp(Date.now()));
      }
    } catch (e) {
    }
  }

  /** 导出件与工作日志的目录（设置页显示用；未装返回空串） */
  static workingDir(): string {
    return Diag.logDir;
  }

  /** 当前日志占用（设置页显示用；字节=statSync 口径） */
  static usedBytes(): number {
    try {
      if (Diag.logDir === '') {
        return 0;
      }
      let total: number = 0;
      const names: Array<string> = fileIo.listFileSync(Diag.logDir);
      for (const name of names) {
        total += fileIo.statSync(`${Diag.logDir}/${name}`).size;
      }
      return total;
    } catch (e) {
      return 0;
    }
  }

  /**
   * 清空全部诊断日志（设置页「清除日志」调用）：删当前件+轮转件+崩溃件（判据
   * diagClearLogNames），清内存缓冲与队列，重置丢弃/巡检计数。收尾那句「日志已清除」
   * 走常规记录门：总开关开着才落盘；关着就"清了就是清了"。
   */
  static async clear(): Promise<void> {
    try {
      Diag.drainToFile();
      Diag.pending = [];
      let removed: number = 0;
      try {
        if (Diag.logDir !== '') {
          const names: Array<string> = fileIo.listFileSync(Diag.logDir);
          const hit: Array<string> = diagClearLogNames(names, BASE_NAME, CRASH_PREFIX);
          for (const name of hit) {
            try {
              fileIo.unlinkSync(`${Diag.logDir}/${name}`);
              removed++;
            } catch (e) {
            }
          }
        }
      } catch (e) {
      }
      Diag.ring = [];
      Diag.droppedPending = 0;
      Diag.lastPruneMs = 0;
      Diag.i('WotaDiag', `日志已清除 件数=${removed}`);
    } catch (e) {
    }
  }

  // endregion

  // region 落盘与轮转

  /** 后台落盘：1 秒一拍（决策 2），日志 I/O 不进调用线程（定时器回调里同步写） */
  private static startWriter(): void {
    if (Diag.writerTimer >= 0) {
      return;
    }
    Diag.writerTimer = setInterval((): void => {
      // 落盘失败不许把定时器弄死（否则后续日志全丢）
      try {
        Diag.drainToFile();
      } catch (e) {
      }
    }, 1000);
  }

  /** 攒一拍写一次：pending 取 ≤500 行 → 轮转检查 → 追加当前件 → 巡检崩溃件 */
  private static drainToFile(): void {
    if (Diag.draining) {
      return;
    }
    Diag.draining = true;
    try {
      if (Diag.logDir === '' || Diag.current === '') {
        return;
      }
      const batch: Array<string> = [];
      while (Diag.pending.length > 0 && batch.length < 500) {
        const line: string | undefined = Diag.pending.shift();
        if (line !== undefined) {
          batch.push(line);
        }
      }
      if (batch.length === 0) {
        return;
      }
      Diag.rotateIfNeeded();
      // fd=-1 + finally 收尾：写失败（磁盘满/配额/IO error）也必关句柄，不随每秒一拍累积
      let fd: number = -1;
      try {
        const f: fileIo.File = fileIo.openSync(Diag.current,
          fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.APPEND);
        fd = f.fd;
        fileIo.writeSync(fd, batch.join(''));
      } finally {
        if (fd >= 0) {
          try {
            fileIo.closeSync(fd);
          } catch (e) {
            // 关句柄失败不影响下一拍
          }
        }
      }
      Diag.pruneOldFiles();
    } catch (e) {
    } finally {
      Diag.draining = false;
    }
  }

  /** 到上限就轮转：wotagei.log → .1 → .2，从老到新依次后移（最老一件先删） */
  private static rotateIfNeeded(): void {
    if (Diag.logDir === '' || Diag.current === '') {
      return;
    }
    let bytes: number = 0;
    try {
      bytes = fileIo.statSync(Diag.current).size;
    } catch (e) {
      bytes = 0; // 当前件还没建过
    }
    if (!diagShouldRotate(bytes)) {
      return;
    }
    try {
      fileIo.unlinkSync(`${Diag.logDir}/${diagRotatedName(BASE_NAME, DIAG_FILE_KEEP - 1)}`);
    } catch (e) {
    }
    for (let i = DIAG_FILE_KEEP - 2; i >= 1; i--) {
      const from: string = `${Diag.logDir}/${diagRotatedName(BASE_NAME, i)}`;
      const to: string = `${Diag.logDir}/${diagRotatedName(BASE_NAME, i + 1)}`;
      try {
        fileIo.renameSync(from, to);
      } catch (e) {
      }
    }
    try {
      fileIo.renameSync(Diag.current, `${Diag.logDir}/${diagRotatedName(BASE_NAME, 1)}`);
    } catch (e) {
    }
  }

  /** 顺带清掉过期的崩溃件（只留最近 3 份，避免导出越攒越大）；60s 节流 */
  private static pruneOldFiles(): void {
    const now: number = Date.now();
    if (now - Diag.lastPruneMs < 60_000) {
      return;
    }
    Diag.lastPruneMs = now;
    try {
      const stamped: Array<StampedName> = [];
      const names: Array<string> = fileIo.listFileSync(Diag.logDir)
        .filter((n: string): boolean => n.startsWith(CRASH_PREFIX));
      for (const name of names) {
        try {
          stamped.push(new StampedName(name, fileIo.statSync(`${Diag.logDir}/${name}`).mtime));
        } catch (e) {
        }
      }
      stamped.sort((a: StampedName, b: StampedName): number => b.mtime - a.mtime);
      for (let i = 3; i < stamped.length; i++) {
        try {
          fileIo.unlinkSync(`${Diag.logDir}/${stamped[i].name}`);
        } catch (e) {
        }
      }
    } catch (e) {
    }
  }

  // endregion

  // region 崩溃件

  /**
   * 崩溃捕获：设备信息 + 环形缓冲尾部 + 堆栈同步写进 crash-<时间戳>.txt（不走 record
   * 的开关门）。写完不拦截进程退出（决策 3：无上一处理器链）；pending 未落盘行已含于
   * ring 快照。
   */
  private static installCrashHandler(): void {
    try {
      errorManager.on('error', {
        onUnhandledException: (errMsg: string): void => {
          try {
            if (Diag.logDir !== '') {
              // pkg 参数与上游签名对齐、正文不使用（见 DiagPure.diagHeader 登记）
              let text: string = diagHeader(Diag.deviceSummary(), '');
              text += '\n=== 崩溃 ===\n线程：main\n';
              text += errMsg;
              text += `\n=== 崩溃前日志（末 ${DIAG_CRASH_TAIL_LINES} 行）===\n`;
              const tail: Array<string> = Diag.ring.slice(-DIAG_CRASH_TAIL_LINES);
              for (const line of tail) {
                text += line;
              }
              writeTextSync(`${Diag.logDir}/${diagCrashName(diagFileStamp(Date.now()))}`, text);
              Diag.drainToFile();
            }
          } catch (e) {
            // 进程要死了，写不进就算了
          }
        }
      });
    } catch (e) {
      // 注册失败不阻塞日志主体（崩溃件降级为导出时附 ring 尾，登记）
    }
  }

  // endregion

  // region 导出

  /**
   * 把全部日志（轮转件 + 当前件 + 崩溃件 + 头部信息）合并导出成一份 txt 落到应用目录
   * filesDir/WotaGeiCam/（决策 5：公共 Documents 与分享面板留白，随设置页/分享批）。
   */
  static async export(context: common.Context): Promise<ExportResult | null> {
    try {
      Diag.drainToFile();
      const name: string = diagExportName(diagFileStamp(Date.now()));
      const dir: string = `${context.filesDir}/${EXPORT_DIR_NAME}`;
      try {
        fileIo.mkdirSync(dir);
      } catch (e) {
      }
      const target: string = `${dir}/${name}`;
      const text: string = Diag.buildReport(context);
      if (!writeTextSync(target, text)) {
        Diag.e('WotaDiag', `日志导出失败: 写盘失败 path=${target}`);
        return null;
      }
      let bytes: number = 0;
      try {
        bytes = fileIo.statSync(target).size;
      } catch (e) {
        bytes = text.length;
      }
      Diag.i('WotaDiag', `日志已导出 path=${target} bytes=${bytes} 公共=false（应用目录）`);
      return new ExportResult(target, false, bytes);
    } catch (e) {
      Diag.e('WotaDiag', `日志导出失败: ${e}`);
      return null;
    }
  }

  /**
   * 生成专供**分享**的报告（DIAG-SHARE 批，直译上游 `exportForShare`）：落
   * `cacheDir/share-out`（缓存目录永远可写，分享不被「公共目录能不能写」卡住；
   * 缓存件被系统按空间回收，正合一次性语义），先清上次分享件防攒。
   */
  static async exportForShare(context: common.Context): Promise<ExportResult | null> {
    try {
      Diag.drainToFile();
      const dir: string = `${context.cacheDir}/share-out`;
      try {
        fileIo.mkdirSync(dir);
      } catch (e) {
        // 已存在或父目录缺失都吞（cacheDir 必在）
      }
      try {
        for (const name of fileIo.listFileSync(dir)) {
          try {
            fileIo.unlinkSync(`${dir}/${name}`);
          } catch (e) {
            // 旧分享件删不掉不影响本次（一次性语义，下次再清）
          }
        }
      } catch (e) {
        // 目录刚建/不可列：无旧件
      }
      const target: string = `${dir}/${diagExportName(diagFileStamp(Date.now()))}`;
      const text: string = Diag.buildReport(context);
      if (!writeTextSync(target, text)) {
        Diag.e('WotaDiag', `日志导出失败: 写盘失败 path=${target}`);
        return null;
      }
      let bytes: number = 0;
      try {
        bytes = fileIo.statSync(target).size;
      } catch (e) {
        bytes = text.length;
      }
      Diag.i('WotaDiag', `日志已导出 path=${target} bytes=${bytes} 公共=false（分享件）`);
      return new ExportResult(target, false, bytes);
    } catch (e) {
      Diag.e('WotaDiag', `日志导出失败: ${e}`);
      return null;
    }
  }

  /** 导出件默认名（另存选择器的默认文件名；命名规则单一真源在 DiagPure） */
  static exportName(): string {
    return diagExportName(diagFileStamp(Date.now()));
  }

  /** 报告正文（供系统保存选择器落用户选定位置；空串 = 生成失败） */
  static reportText(context: common.Context): string {
    try {
      Diag.drainToFile();
      return Diag.buildReport(context);
    } catch (e) {
      Diag.e('WotaDiag', `报告生成失败: ${e}`);
      return '';
    }
  }

  /** 报告正文：头部 → 丢弃自曝 → 帧账 → 崩溃件(新→旧) → 日志件(老→新) → 尾标记 */
  private static buildReport(context: common.Context): string {
    Diag.drainToFile();
    let body: string = '';
    body += diagHeader(Diag.deviceSummary(), '');
    // 丢弃自曝紧跟头部：用户读导出件是从上往下的
    body += diagDroppedNote(Diag.droppedPending);
    // 结构帧账：与常规日志开关无关，放在日志件之前先给结论
    body += diagFrameAccountSection(Diag.frameAccountSnapshot());
    // 崩溃件（新→旧）：最常被问到的就是"崩的时候发生了什么"
    const crashes: Array<StampedName> = [];
    try {
      const names: Array<string> = fileIo.listFileSync(Diag.logDir)
        .filter((n: string): boolean => n.startsWith(CRASH_PREFIX));
      for (const name of names) {
        try {
          crashes.push(new StampedName(name, fileIo.statSync(`${Diag.logDir}/${name}`).mtime));
        } catch (e) {
        }
      }
      crashes.sort((a: StampedName, b: StampedName): number => b.mtime - a.mtime);
    } catch (e) {
    }
    for (const c of crashes) {
      body += `\n=== 崩溃件 ${c.name} ===\n`;
      body += Diag.readBounded(`${Diag.logDir}/${c.name}`, c.name, DIAG_EXPORT_MAX_BYTES - body.length);
      if (body.length >= DIAG_EXPORT_MAX_BYTES) {
        break;
      }
    }
    // 日志：老 → 新（.2 .1 当前），读起来就是时间顺序
    const logs: Array<string> = [];
    if (Diag.logDir !== '') {
      for (let i = DIAG_FILE_KEEP - 1; i >= 1; i--) {
        const name: string = diagRotatedName(BASE_NAME, i);
        const p: string = `${Diag.logDir}/${name}`;
        let isFile: boolean = false;
        try {
          isFile = fileIo.statSync(p).isFile();
        } catch (e) {
          isFile = false;
        }
        if (isFile) {
          logs.push(p);
        }
      }
    }
    if (Diag.current !== '') {
      let curIsFile: boolean = false;
      try {
        curIsFile = fileIo.statSync(Diag.current).isFile();
      } catch (e) {
        curIsFile = false;
      }
      if (curIsFile) {
        logs.push(Diag.current);
      }
    }
    for (const p of logs) {
      if (body.length >= DIAG_EXPORT_MAX_BYTES) {
        break;
      }
      const displayName: string = p.substring(p.lastIndexOf('/') + 1);
      body += `\n=== 日志件 ${displayName} ===\n`;
      body += Diag.readBounded(p, displayName, DIAG_EXPORT_MAX_BYTES - body.length);
    }
    // 尾标记：导出件被截断时，只有头部的行尾没有"结束"可对。
    // 行数口径照上游 lineSequence().count()：末段无换行结尾也算一行
    const nl: number = body.split('\n').length - 1;
    const lineCount: number = body.endsWith('\n') ? nl : nl + 1;
    body += `=== 结束 行数=${lineCount} 字节=${body.length} 时间=${diagStamp(Date.now())} ===\n`;
    return body;
  }

  /**
   * 预算内读一件：预算 ≤0 → 占位；超长取尾部并自曝截断；读失败占位。
   * 口径登记：字节=字符数（String.length），与上游 String.length 同款混用口径。
   */
  private static readBounded(path: string, displayName: string, budget: number): string {
    const max: number = Math.min(Math.max(0, budget), DIAG_EXPORT_MAX_BYTES);
    if (max <= 0) {
      return '（已达导出上限，省略）\n';
    }
    try {
      let len: number = 0;
      try {
        len = fileIo.statSync(path).size;
      } catch (e) {
        len = 0;
      }
      const text: string = fileIo.readTextSync(path);
      if (len <= max) {
        return text;
      }
      Diag.i('WotaDiag', `日志导出截断：件 ${displayName} 总长${len}B 预算${max}B，只带尾部 ${max}B`);
      return text.substring(text.length - Math.floor(max));
    } catch (e) {
      Diag.e('WotaDiag', `日志导出读取失败 件 ${displayName} 预算${max}B: ${e}`);
      return '（读取失败）\n';
    }
  }

  // endregion

  /** 设备与版本摘要（导出与崩溃件的头一段）：board/fingerprint 无对应字段省行（决策 6） */
  private static deviceSummary(): string {
    try {
      let bundleName: string = '?';
      let versionName: string = '?';
      let versionCode: number = 0;
      try {
        const info: bundleManager.BundleInfo =
          bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
        bundleName = info.name;
        versionName = info.versionName ?? '?';
        versionCode = info.versionCode;
      } catch (e) {
      }
      return `应用=${bundleName} 版本=${versionName} (code ${versionCode})\n` +
        `系统=HarmonyOS (API ${deviceInfo.sdkApiVersion})` +
        ` 品牌=${deviceInfo.brand} 厂商=${deviceInfo.manufacture}` +
        ` 机型=${deviceInfo.productModel} 设备=${deviceInfo.deviceType}\n` +
        `硬件=${deviceInfo.hardwareModel}（近似 Android Build.HARDWARE，语义不同）\n` +
        `ABI=${deviceInfo.abiList}\n` +
        `时间=${diagStamp(Date.now())} 时区=${tzOffsetText()}\n`;
    } catch (e) {
      return `时间=${diagStamp(Date.now())} 时区=${tzOffsetText()}\n`;
    }
  }
}

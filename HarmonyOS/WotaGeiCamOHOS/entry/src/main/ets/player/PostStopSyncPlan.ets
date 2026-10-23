/**
 * 录制**完全停止后**的内录音轨自动校准——纯决策面（直译上游 player/PostStopSync.kt 的
 * 分段策略区，对齐上游 e950328；调度/线程/互斥是平台面，留 T27，见文件尾接口注释）。
 *
 * 上游在 stopInternal 末尾调度、不等待：解码两轨前 30s PCM + 互相关在 IO 线程跑，慢一点也不
 * 影响「已保存」提示与下一次起录；全部异常只记日志，纠偏失败绝不能丢成片或抛回停止路径。
 *
 * 纯决策面三件事：
 * 1. 段筛选：调用方传入的「请求了双音轨」只是请求（captureAudio），单音轨段（内录启动失败/
 *    中途降级/仅环境音）不许被标 sync_done——实际双轨由平台逐段复核（谓词注入）；
 * 2. 分段策略：段数 ≤ MAX_EXACT_SEGMENTS 时逐段各测一次（每段有独立容器起点差，复制会带
 *    误差）；超过时退回"测首段 + 复制"，并记「近似」（approximate=true）；
 * 3. 异常隔离：逐段测量的单段失败只跳过该段，不连坐后面的段；近似模式首段测不出则整场不写
 *    （copyKeys 只在首段测量成功后才允许落盘）。
 *
 * 纯逻辑（零平台 import）。
 */

/** 一段录制产物的调度请求（key = 段标识，生产侧为 uri 字符串） */
export class PostStopSegment {
  readonly key: string;
  /** 录制时**请求了**双音轨（实际是否双轨由平台复核，见 actualDualKeys） */
  readonly requestedDualAudio: boolean;

  constructor(key: string, requestedDualAudio: boolean) {
    this.key = key;
    this.requestedDualAudio = requestedDualAudio;
  }
}

/** 停止后校准的处置计划（measure=逐段实测；copy=首段成功后复制结果） */
export class PostStopPlan {
  /** true = 段数超上限，只测首段 + 复制（上游日志口径「近似」） */
  readonly approximate: boolean;
  /** 需要实测的段（exact 模式 = 全部实际双轨段；approximate 模式 = 仅首段） */
  readonly measureKeys: Array<string>;
  /** 仅 approximate 模式非空：首段测量**成功后**才允许复制（persistAuto 复核式写盘） */
  readonly copyKeys: Array<string>;

  constructor(approximate: boolean, measureKeys: Array<string>, copyKeys: Array<string>) {
    this.approximate = approximate;
    this.measureKeys = measureKeys;
    this.copyKeys = copyKeys;
  }
}

export class PostStopSyncPlan {
  /** 逐段精确校准的段数上限：再多人均 30s 解码会让后台占用过久，退回首段近似 */
  static readonly MAX_EXACT_SEGMENTS: number = 4;

  /** 段筛选：只留录制时请求了双音轨的段键（空结果 = 无需调度，上游 requested.isEmpty 直接回） */
  static requestedKeys(segments: Array<PostStopSegment>): Array<string> {
    const out: Array<string> = [];
    for (const s of segments) {
      if (s.requestedDualAudio) {
        out.push(s.key);
      }
    }
    return out;
  }

  /**
   * 实际双轨复核：谓词注入（生产侧 = 容器音轨数 ≥ 2，即上游 TrackSync.countAudioTracks；
   * 复核抛异常的段按非双轨处理，即上游 runCatching.getOrDefault(false)）。请求过双音轨
   * ≠ 真是双音轨片。
   */
  static actualDualKeys(requested: Array<string>, isActuallyDual: (key: string) => boolean): Array<string> {
    const out: Array<string> = [];
    for (const key of requested) {
      let dual = false;
      try {
        dual = isActuallyDual(key);
      } catch (e) {
        dual = false;
      }
      if (dual) {
        out.push(key);
      }
    }
    return out;
  }

  /**
   * 处置计划：≤ MAX_EXACT_SEGMENTS 段逐段精确（每段独立容器起点差，复制会带误差）；
   * 超过退回首段测量 + 复制并记「近似」。空列表产出空计划（消费方直接收工，不写任何段）。
   */
  static planFor(actualDual: Array<string>): PostStopPlan {
    if (actualDual.length <= PostStopSyncPlan.MAX_EXACT_SEGMENTS) {
      return new PostStopPlan(false, actualDual.slice(0), []);
    }
    const first: string = actualDual[0];
    return new PostStopPlan(true, [first], actualDual.slice(1));
  }
}

/**
 * 【T27 平台调度接口（只登记不实现）】上游 PostStopSync.schedule 的执行面：
 * - 立即返回、不阻塞停止收尾；独立作用域（页面可能正在退出），异常只记日志不抛回；
 * - 串行化（两场录制接连停止时不并发解码同一批文件、prefs 复合读写不交错）——OHOS 侧
 *   建议 taskpool 串行队列或串行 Promise 链承载，running 集合（播放页完成后重读偏移用）
 *   以 AppStorage/Emitter 落地；
 * - 逐段执行：measureKeys 逐段跑 autoRun（单段异常 continue，不连坐）；approximate 模式
 *   首段成功后对 copyKeys 逐段 writeAutoPrefs（复制也走复核式入口）。
 */

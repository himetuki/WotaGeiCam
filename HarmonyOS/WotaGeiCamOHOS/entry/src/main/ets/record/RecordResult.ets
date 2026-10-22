/**
 * 录制结果/失败码最小契约——直译自上游 record/Recorder.kt 的 RecordError/RecordResult
 * （对齐上游 38d712a）。本批只落机器码与首段结果字段；VideoSegment/health/parts/分段计数
 * /ambientLost 等字段随分段轮转与健康读数批（登记）。
 *
 * 约定照上游：面向用户的中文文案在 UI/资源侧按码映射，本文件只给机器可读码；
 * LOW_STORAGE 载荷 `:可用MB` 由产生方拼接（VideoStore.checkFreeSpace）。
 */

/** 全模块统一日志锚点（上游 Recorder.kt `const val TAG = "WotaRecord"`） */
export const WOTA_RECORD_TAG: string = 'WotaRecord';

export class RecordError {
  /** 存储余量不足，附 `:可用MB` */
  static readonly LOW_STORAGE: string = 'LOW_STORAGE';
  /** 输出目标打不开（无可用档位/无设备/输出面失败） */
  static readonly NO_OUTPUT: string = 'NO_OUTPUT';
  static readonly BAD_STATE: string = 'BAD_STATE';
  static readonly PREPARE_FAILED: string = 'PREPARE_FAILED';
  static readonly START_FAILED: string = 'START_FAILED';
  /** 引擎运行期报错（AVRecorder on('error')），附 `:code:message` */
  static readonly ENGINE_ERROR: string = 'ENGINE_ERROR';
  /** 录制时长 < RecordProfile.MIN_KEEP_MS，按废片删除 */
  static readonly TOO_SHORT: string = 'TOO_SHORT';
  static readonly STOP_FAILED: string = 'STOP_FAILED';
  /** 未开始就 release / stop */
  static readonly RELEASED: string = 'RELEASED';
}

/**
 * 一次停止的结果（首段口径）：path/bytes/durationMs 描述文件，error 为机器码或 null，
 * seriesId 供媒体库把分段归到同一录制片段（本批只记不落库）。
 */
export class RecordResult {
  path: string | null;
  bytes: number;
  durationMs: number;
  error: string | null;
  seriesId: number;

  constructor(path: string | null, bytes: number, durationMs: number, error: string | null, seriesId: number) {
    this.path = path;
    this.bytes = bytes;
    this.durationMs = durationMs;
    this.error = error;
    this.seriesId = seriesId;
  }

  /** 失败结果（无输出字段一律清零；seriesId 保留归属便于排查） */
  static fail(code: string, seriesId: number = 0): RecordResult {
    return new RecordResult(null, 0, 0, code, seriesId);
  }

  /** 人读摘要（页面直接显示；机器码保留原文便于对日志） */
  summary(): string {
    if (this.error !== null) {
      return `record failed: ${this.error} series=${this.seriesId}`;
    }
    return `record saved\npath=${this.path}\nbytes=${this.bytes}\nduration=${this.durationMs}ms\nseries=${this.seriesId}`;
  }
}

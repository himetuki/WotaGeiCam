/**
 * 录像文件输出最小版——直译自上游 record/VideoStore.kt（对齐上游 38d712a）的命名/余量/落盘子集。
 *
 * 平台差异（登记）：
 * - 落点：上游 MediaStore `Movies/WotaGeiCam/Camera`（pending 两段式入库）；OHOS 本批只写应用私有
 *   filesDir/videos（MediaStore pending 与相册可见性留待媒体库批）；
 * - 命名格式逐字符照上游：`%sVID_yyyyMMdd_HHmmss_%03d.mp4`（Locale.US 由手写 pad 等价，
 *   3 位随机后缀防同秒撞名——随机源差异登记）；
 * - freeSpaceMb：上游 StatFs → OHOS statvfs.getFreeSizeSync；探测失败返回 MAX_SAFE_INTEGER
 *   （"探测失败不拦录制"同口径）。
 */
import { fileIo, statfs } from '@kit.CoreFileKit';
import { common } from '@kit.AbilityKit';
import { WotaTiers } from '../core/Constants';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { RecordError, WOTA_RECORD_TAG } from './RecordResult';

/** 成片目录（应用私有；上游公共 Movies/WotaGeiCam/Camera 的私有等价，登记） */
const VIDEO_DIR_NAME: string = 'videos';
const EXTENSION: string = '.mp4';
const MB: number = 1024 * 1024;

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

/** 文件名时间戳：yyyyMMdd_HHmmss（手写，等价 SimpleDateFormat(Locale.US)） */
function nameStamp(epochMs: number): string {
  const d: Date = new Date(epochMs);
  return `${d.getFullYear()}${pad2(d.getMonth() + 1)}${pad2(d.getDate())}_` +
    `${pad2(d.getHours())}${pad2(d.getMinutes())}${pad2(d.getSeconds())}`;
}

export class VideoStore {
  static readonly VIDEO_DIR_NAME: string = VIDEO_DIR_NAME;
  static readonly EXTENSION: string = EXTENSION;

  /**
   * 成片文件名：`%sVID_yyyyMMdd_HHmmss_%03d.mp4`（namePrefix 非空时置于 VID 前）。
   * 每段用各自创建时刻，同秒碰撞由 3 位随机后缀吸收（上游 Random.nextInt(1000) 同口径）。
   */
  static displayName(createdAtMs: number = Date.now(), namePrefix: string = ''): string {
    const suffix: number = Math.floor(Math.random() * 1000);
    return `${namePrefix}VID_${nameStamp(createdAtMs)}_${pad3(suffix)}${EXTENSION}`;
  }

  /** 私有成片目录（首次调用创建） */
  static videosDir(ctx: common.Context): string {
    const dir: string = `${ctx.filesDir}/${VIDEO_DIR_NAME}`;
    try {
      fileIo.mkdirSync(dir);
    } catch (e) {
      // 已存在或父目录缺失都吞（filesDir 必在，videos 首次创建）
    }
    return dir;
  }

  static pathFor(ctx: common.Context, name: string): string {
    return `${VideoStore.videosDir(ctx)}/${name}`;
  }

  /** 当前卷可用空间（MB）；探测失败返回 MAX_SAFE_INTEGER（探测失败不拦录制，同上游口径） */
  static freeSpaceMb(ctx: common.Context): number {
    try {
      return Math.floor(statfs.getFreeSizeSync(ctx.filesDir) / MB);
    } catch (e) {
      Diag.e(WOTA_RECORD_TAG, `statvfs failed: ${e}`);
      return Number.MAX_SAFE_INTEGER;
    }
  }

  /** 录像前余量校验：可用 < MIN_FREE_MB → `LOW_STORAGE:可用MB`；null = 允许 */
  static checkFreeSpace(ctx: common.Context): string | undefined {
    const mb: number = VideoStore.freeSpaceMb(ctx);
    if (mb < WotaTiers.MIN_FREE_MB) {
      Diag.i(WOTA_RECORD_TAG, `refuse record, free=${mb}MB need>=${WotaTiers.MIN_FREE_MB}MB`);
      return `${RecordError.LOW_STORAGE}:${mb}`;
    }
    return undefined;
  }

  /** 文件字节数；读取失败返回 -1（= 不可观测，区别于 0 字节） */
  static sizeOf(path: string): number {
    try {
      return fileIo.statSync(path).size;
    } catch (e) {
      return -1;
    }
  }

  /** 删废片（MIN_KEEP_MS 判废 / 起录失败回收）；失败吞错返回 false */
  static discard(path: string): boolean {
    try {
      fileIo.unlinkSync(path);
      return true;
    } catch (e) {
      Diag.e(WOTA_RECORD_TAG, `discard failed: ${path} ${e}`);
      return false;
    }
  }

  /**
   * 幂等删除（T29 审查 P3）：文件已不存在（ENOENT 13900002）视为**已达成**、不记 ERROR。
   * 供半成品清理/兜底 reap 使用——重复删同一路径不该在日志里伪装成故障。
   */
  static discardQuiet(path: string): boolean {
    if (path === '') {
      return true;
    }
    try {
      fileIo.unlinkSync(path);
      return true;
    } catch (e) {
      const code: number = (e as BusinessError).code;
      if (code === 13900002) {
        return true; // 已不存在 = 目标已达成
      }
      Diag.w(WOTA_RECORD_TAG, `discardQuiet failed: ${path} ${e}`);
      return false;
    }
  }
}

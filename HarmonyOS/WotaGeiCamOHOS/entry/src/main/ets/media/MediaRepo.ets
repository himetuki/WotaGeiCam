/**
 * 媒体仓库（上游 media/MediaRepo.kt 的鸿蒙最小版，对齐上游 38d712a）：
 * - 媒体来源只限应用私有 filesDir/videos（不做 MediaStore/photoAccessHelper，登记留白）；
 * - 时长/分辨率：AVMetadataExtractor + 1.5s 超时，失败归零不阻塞列表；
 * - 标签/回收站/系列联表走 WotaDb（RDB）；DB 失败降级为仅文件列表，动作返回 false；
 * - 回收站语义：本地 DB 记录 + 文件原地保留；彻底删除才 unlink 文件与 .drops.json。
 *
 * 已知限制（登记）：
 * - 媒体 ID = 相对路径 32 位 FNV-1a；外部改名会让 ID 脱钩（本批无改名入口）；
 * - 系列字段只读表填充，系列归组 UI 留待后续批；
 * - 缩略图/播放/分享/相册入库不做。
 */
import { fileIo } from '@kit.CoreFileKit';
import { media } from '@kit.MediaKit';
import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { ArcDropLog } from '../record/ArcDropLog';
import { VideoStore } from '../record/VideoStore';
import { GlRecorder } from '../camera/GlRecorder';
import { WotaTag } from '../core/Constants';
import { ClipFilter, ClipKind, CustomTagRow, CustomTags, TagSpace } from './MediaTag';
import { MediaTagRow, SeriesPartRow, TrashItemRow, WotaDb } from './WotaDb';

const TAG: string = WotaTag.MEDIA_REPO;
const PROBE_TIMEOUT_MS: number = 1500;
const EXTENSION: string = '.mp4';
// 探测缓存放模块级：跨页面/重进共享，避免每个 MediaRepo 实例重新探测真库
const PROBE_CACHE_MAX: number = 512;
const PROBE_MAX_PENDING: number = 4;
const PROBE_NEG_TTL_MS: number = 3000;
const PROBE_NEG_MAX: number = 256;
const PROBE_STALE_MS: number = PROBE_TIMEOUT_MS * 2;
/** 探测代际：陈旧条目被剔除后自增，晚到结果只允许写入同代缓存 */
let PROBE_GENERATION: number = 0;
const PROBE_CACHE: Map<string, ProbeMeta> = new Map<string, ProbeMeta>();
/** 短 TTL 负缓存（失败/超时/零值）：到期前不再新建探测任务 */
const PROBE_NEG_UNTIL: Map<string, number> = new Map<string, number>();

class ProbePending {
  task: Promise<ProbeMeta>;
  startedAt: number;
  generation: number;

  constructor(task: Promise<ProbeMeta>, startedAt: number, generation: number) {
    this.task = task;
    this.startedAt = startedAt;
    this.generation = generation;
  }
}

/** 探测去重：key=path|size|mtime → 在途 task；startedAt 用于剔除陈旧条目 */
const PROBE_IN_FLIGHT: Map<string, ProbePending> = new Map<string, ProbePending>();
/**
 * 未 settle 任务计数（上限的唯一权威）：stale 只从 map 剔除、**不释放计数**，
 * 因此真正挂起的 open/fetch 最多 4 个（fd/extractor 泄漏上界，登记）；任务 settle 后归还。
 */
let PROBE_OUTSTANDING: number = 0;
/** 本次 refresh 是否撞到探测并发上限（页面据此提示“元数据暂不可用”） */
let PROBE_CAP_HIT: boolean = false;

/** 媒体条目（对应上游 VideoClip 的最小字段集） */
export class VideoClip {
  id: number;
  path: string;
  name: string;
  relativePath: string;
  dateAddedSec: number;
  sizeBytes: number;
  durationMs: number;
  width: number;
  height: number;
  mimeType: string;
  tags: Set<string>;
  isTrashed: boolean;
  seriesId?: number;
  partIndex?: number;

  constructor(id: number, path_: string, name: string, relativePath: string, dateAddedSec: number,
    sizeBytes: number, durationMs: number, width: number, height: number, mimeType: string,
    tags: Set<string>, isTrashed: boolean, seriesId?: number, partIndex?: number) {
    this.id = id;
    this.path = path_;
    this.name = name;
    this.relativePath = relativePath;
    this.dateAddedSec = dateAddedSec;
    this.sizeBytes = sizeBytes;
    this.durationMs = durationMs;
    this.width = width;
    this.height = height;
    this.mimeType = mimeType;
    this.tags = tags;
    this.isTrashed = isTrashed;
    this.seriesId = seriesId;
    this.partIndex = partIndex;
  }

  get liked(): boolean {
    return this.tags.has(TagSpace.LIKE);
  }
}

/**
 * 媒体 ID：相对路径 32 位 FNV-1a（0 → 1；私有目录无系统 ID，登记外部改名脱钩）。
 * 碰撞风险（审查登记）：生日界下 1 万条约 1.2%、10 万条约 69%；当前 media_tag 同时保留 path，
 * 升级路径为 64 位 FNV 或 (mediaId,path) 二次确认（留待标签管理/大库批）。
 */
export class MediaIds {
  static ofRelativePath(rel: string): number {
    let h: number = 0x811c9dc5;
    for (let i = 0; i < rel.length; i++) {
      h ^= rel.charCodeAt(i);
      h = Math.imul(h, 0x01000193);
    }
    const unsigned: number = h >>> 0;
    return unsigned === 0 ? 1 : unsigned;
  }
}

/** 元数据探测结果（失败全 0） */
class ProbeMeta {
  durationMs: number;
  width: number;
  height: number;

  constructor(durationMs: number, width: number, height: number) {
    this.durationMs = durationMs;
    this.width = width;
    this.height = height;
  }
}

function parseMetaNumber(raw: string | undefined): number {
  if (raw === undefined) {
    return 0;
  }
  const n: number = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

export class MediaRepo {
  private ctx: common.Context;
  private db: WotaDb | undefined = undefined;
  private dbGeneration: number = -1;
  private dbFailed: boolean = false;
  /** 孤儿清理每会话只做一次（deleteForever 清理失败会复位，下一次 load 重试） */
  private orphansCleaned: boolean = false;

  constructor(ctx: common.Context) {
    this.ctx = ctx;
  }

  static resetProbeCapHit(): void {
    PROBE_CAP_HIT = false;
  }

  static probeCapHit(): boolean {
    return PROBE_CAP_HIT;
  }

  /** 最近一次 DB 是否可用（false = 仅文件列表降级模式） */
  isDbAvailable(): boolean {
    return !this.dbFailed;
  }

  /**
   * 取 DB：dbFailed **只用于 UI/动作门控**（isDbAvailable），不阻止 load 拿已打开的 db 重试自愈；
   * 仅在从未打开成功（db === undefined）且已标记失败时返回 undefined。
   */
  private async ensureDb(): Promise<WotaDb | undefined> {
    if (this.db !== undefined) {
      if (this.dbGeneration === WotaDb.currentGeneration()) {
        return this.db;
      }
      this.db = undefined; // 单例被其他页面重开：本实例的 db 已过期
      this.dbGeneration = -1;
    }
    if (this.dbFailed) {
      return undefined;
    }
    try {
      this.db = await WotaDb.get(this.ctx);
      this.dbGeneration = WotaDb.currentGeneration();
      this.dbFailed = false;
      return this.db;
    } catch (e) {
      if (!this.dbFailed) {
        this.dbFailed = true;
        Diag.e(TAG, `db unavailable, degrade to files-only: ${e}`);
      }
      return undefined;
    }
  }

  /**
   * UI「重试数据库」：已打开实例先做一次显式查询校验；校验失败或从未打开则 close 单例后
   * 重新 WotaDb.get（重建库/建表）。成功返回 true（Gallery 随后 refreshAll，以 dbOk 为准）。
   */
  async retryDb(): Promise<boolean> {
    // 总是先失效单例（代际自增 + 等待旧 open + close），再真实重开并做一次查询探测
    this.db = undefined;
    this.dbGeneration = -1;
    this.dbFailed = false;
    await WotaDb.resetForRetry();
    try {
      const db: WotaDb = await WotaDb.get(this.ctx);
      this.db = db;
      this.dbGeneration = WotaDb.currentGeneration();
      await db.allTags(); // 真实探测：单例残留坏实例不会被当成功
      this.orphansCleaned = false; // 库恢复后允许重试孤儿清理
      return true;
    } catch (e) {
      this.db = undefined;
      this.dbGeneration = -1;
      this.dbFailed = true;
      Diag.e(TAG, `db retry open/validate failed: ${e}`);
      return false;
    }
  }

  /**
   * 探测时长/分辨率（fd 时序 + 无界累积 + 模块级缓存审查）：
   * - 缓存放模块级（跨页面共享）：PROBE_CACHE（512 上限）/ PROBE_NEG_UNTIL（3s）/ PROBE_IN_FLIGHT；
   * - open 也放进 owned task（create+fetch+release+close 同一 finally），超时不提前关 fd；
   * - 在途去重：同 key 复用；条目带 startedAt，超过 2×超时视为陈旧并删除（旧 task 晚到用身份比对
   *   只删自己，不误删新条目）；
   * - 失败/超时/零值写短 TTL 负缓存；晚到结果补缓存；并发上限 4，防止挂起任务无界累积。
   */
  private async probeMeta(path: string, size: number, mtime: number): Promise<ProbeMeta> {
    const key: string = `${path}|${size}|${mtime}`;
    const cached: ProbeMeta | undefined = PROBE_CACHE.get(key);
    if (cached !== undefined) {
      return cached;
    }
    const negUntil: number | undefined = PROBE_NEG_UNTIL.get(key);
    if (negUntil !== undefined) {
      if (negUntil > Date.now()) {
        return new ProbeMeta(0, 0, 0);
      }
      PROBE_NEG_UNTIL.delete(key);
    }

    const now: number = Date.now();
    let entry: ProbePending | undefined = PROBE_IN_FLIGHT.get(key);
    if (entry !== undefined && now - entry.startedAt > PROBE_STALE_MS) {
      PROBE_IN_FLIGHT.delete(key); // 陈旧条目：底层 task 仍由自身 finally 清理
      PROBE_GENERATION++; // 作废旧代晚到结果，防止覆盖新任务缓存
      entry = undefined;
    }
    if (entry === undefined) {
      if (PROBE_IN_FLIGHT.size >= PROBE_MAX_PENDING) {
        // 达上限先全局清陈旧项（防 4 个挂起项永久饿死整库元数据）
        const staleNow: number = Date.now();
        for (const staleKey of PROBE_IN_FLIGHT.keys()) {
          const staleEntry: ProbePending | undefined = PROBE_IN_FLIGHT.get(staleKey);
          if (staleEntry !== undefined && staleNow - staleEntry.startedAt > PROBE_STALE_MS) {
            PROBE_IN_FLIGHT.delete(staleKey);
            PROBE_GENERATION++;
          }
        }
      }
      if (PROBE_OUTSTANDING >= PROBE_MAX_PENDING) {
        // 仍满：本次跳过并写短负缓存（避免反复撞上限空转）
        PROBE_CAP_HIT = true;
        this.setProbeNegative(key, PROBE_GENERATION);
        Diag.w(TAG, `probe outstanding cap reached (${PROBE_OUTSTANDING}), skip ${path}`);
        return new ProbeMeta(0, 0, 0);
      }
      PROBE_OUTSTANDING++; // 未 settle 计数：只有 task settle 才归还（stale 删除不归还）
      const owned: Promise<ProbeMeta> = this.probeMetaOpenOwned(path, size);
      const generation: number = PROBE_GENERATION;
      let created: ProbePending | undefined = undefined;
      let settled: boolean = false;
      const settle = (): void => {
        if (!settled) {
          settled = true;
          PROBE_OUTSTANDING = Math.max(0, PROBE_OUTSTANDING - 1);
        }
      };
      const tracked: Promise<ProbeMeta> = owned.then((result: ProbeMeta): ProbeMeta => {
        settle();
        if (created !== undefined && PROBE_IN_FLIGHT.get(key) === created) {
          PROBE_IN_FLIGHT.delete(key);
        }
        this.rememberProbeResult(key, result, generation); // 晚到结果也补缓存（同代才写）
        return result;
      }).catch((e: Object): ProbeMeta => {
        settle();
        if (created !== undefined && PROBE_IN_FLIGHT.get(key) === created) {
          PROBE_IN_FLIGHT.delete(key);
        }
        if (generation === PROBE_GENERATION) {
          this.setProbeNegative(key, generation);
        }
        Diag.w(TAG, `probe task failed ${path}: ${e}`);
        return new ProbeMeta(0, 0, 0);
      });
      created = new ProbePending(tracked, Date.now(), generation);
      entry = created;
      PROBE_IN_FLIGHT.set(key, created);
    }

    let timer: number = -1;
    const timeout: Promise<ProbeMeta> = new Promise<ProbeMeta>(
      (resolve: (v: ProbeMeta) => void): void => {
        timer = setTimeout((): void => {
          resolve(new ProbeMeta(0, 0, 0));
        }, PROBE_TIMEOUT_MS);
      });
    const result: ProbeMeta = await Promise.race([entry.task, timeout]);
    if (timer >= 0) {
      clearTimeout(timer);
    }
    if (result.durationMs <= 0 && result.width <= 0 && result.height <= 0) {
      this.setProbeNegative(key, entry.generation); // 超时或失败：短 TTL 内不再新开探测
    }
    return result;
  }

  /** open 进超时任务；打开失败也走负缓存，不创建 fd；成功路径的 close 由 probeMetaOwned 统一负责 */
  private async probeMetaOpenOwned(path: string, size: number): Promise<ProbeMeta> {
    let file: fileIo.File | undefined = undefined;
    try {
      file = await fileIo.open(path, fileIo.OpenMode.READ_ONLY);
      return await this.probeMetaOwned(file, size, path);
    } catch (e) {
      Diag.w(TAG, `probe open failed ${path}: ${e}`);
      return new ProbeMeta(0, 0, 0);
    }
  }

  private rememberProbeResult(key: string, result: ProbeMeta, generation: number): void {
    if (generation !== PROBE_GENERATION) {
      return; // 陈旧代晚到结果：不得覆盖新任务缓存
    }
    if (result.durationMs > 0 || result.width > 0 || result.height > 0) {
      if (!PROBE_CACHE.has(key) && PROBE_CACHE.size >= PROBE_CACHE_MAX) {
        for (const oldest of PROBE_CACHE.keys()) {
          PROBE_CACHE.delete(oldest);
          break;
        }
      }
      PROBE_CACHE.set(key, result);
      PROBE_NEG_UNTIL.delete(key);
    } else {
      this.setProbeNegative(key, generation);
    }
  }

  private setProbeNegative(key: string, generation: number): void {
    if (generation !== PROBE_GENERATION) {
      return;
    }
    if (!PROBE_NEG_UNTIL.has(key) && PROBE_NEG_UNTIL.size >= PROBE_NEG_MAX) {
      for (const oldest of PROBE_NEG_UNTIL.keys()) {
        PROBE_NEG_UNTIL.delete(oldest);
        break;
      }
    }
    PROBE_NEG_UNTIL.set(key, Date.now() + PROBE_NEG_TTL_MS);
  }

  /** 资源所有者：无论成功/失败/超时后晚到，都在 finally 里 release extractor 并 close fd */
  private async probeMetaOwned(file: fileIo.File, size: number, path: string): Promise<ProbeMeta> {
    let extractor: media.AVMetadataExtractor | undefined = undefined;
    try {
      extractor = await media.createAVMetadataExtractor();
      const src: media.AVFileDescriptor = { fd: file.fd, offset: 0, length: size };
      extractor.fdSrc = src;
      const meta: media.AVMetadata = await extractor.fetchMetadata();
      return new ProbeMeta(parseMetaNumber(meta.duration), parseMetaNumber(meta.videoWidth),
        parseMetaNumber(meta.videoHeight));
    } catch (e) {
      Diag.w(TAG, `probe failed ${path}: ${e}`);
      return new ProbeMeta(0, 0, 0);
    } finally {
      if (extractor !== undefined) {
        try {
          await extractor.release();
        } catch (e) {
        }
      }
      try {
        fileIo.closeSync(file.fd);
      } catch (e) {
      }
    }
  }

  /** 扫描私有 videos/*.mp4：文件 + 元数据 + 标签/回收站/系列联表，按 dateAddedSec 降序 */
  async load(filter: ClipFilter): Promise<Array<VideoClip>> {
    const dir: string = VideoStore.videosDir(this.ctx);
    let names: Array<string> = [];
    let entries: Array<string> = [];
    try {
      entries = fileIo.listFileSync(dir);
      names = entries.filter((n: string): boolean => n.toLowerCase().endsWith(EXTENSION));
    } catch (e) {
      Diag.e(TAG, `list videos failed: ${e}`);
      return [];
    }
    const db: WotaDb | undefined = await this.ensureDb();
    let tagRows: Array<MediaTagRow> = [];
    let trashRows: Array<TrashItemRow> = [];
    let partRows: Array<SeriesPartRow> = [];
    if (db !== undefined) {
      try {
        tagRows = await db.allTags();
        trashRows = await db.allTrash();
        partRows = await db.allParts();
        this.dbFailed = false; // 联表成功即恢复可用（此前失败可自愈）
      } catch (e) {
        this.dbFailed = true;
        Diag.e(TAG, `join tags/trash failed, degrade: ${e}`);
        tagRows = [];
        trashRows = [];
        partRows = [];
      }
    }
    // 现存文件集合（含活动录制路径）：孤儿标签/回收站行据此清理，幂等可重试
    const existing: Set<string> = new Set<string>();
    for (const name of names) {
      existing.add(`${dir}/${name}`);
    }
    if (GlRecorder.getActiveRecordingPath() !== '') {
      existing.add(GlRecorder.getActiveRecordingPath());
    }
    if (db !== undefined && !this.orphansCleaned) {
      let dbCleanupOk: boolean = true;
      try {
        // 标签孤儿：按**行主键 id** 删除，绝不复用 clearTagsFor(mediaIds)（FNV 碰撞会误删有效片标签）
        const aliveTag = (row: MediaTagRow): boolean => {
          const tagName: string = row.path.substring(row.path.lastIndexOf('/') + 1);
          const rel: string = `${VideoStore.VIDEO_DIR_NAME}/${tagName}`;
          if (row.path !== rel && row.path !== `${dir}/${tagName}`) {
            return false; // 子目录/异常路径不认
          }
          return existing.has(`${dir}/${tagName}`);
        };
        const orphanTags: Array<MediaTagRow> = tagRows.filter((row: MediaTagRow): boolean => !aliveTag(row));
        if (orphanTags.length > 0) {
          const ids: Array<number> = [];
          for (const row of orphanTags) {
            ids.push(row.id);
          }
          await db.deleteTagsByIds(ids);
          const deleted: Set<number> = new Set<number>(ids);
          tagRows = tagRows.filter((row: MediaTagRow): boolean => !deleted.has(row.id));
        }
        // 回收站孤儿：按 uri 删除
        const orphanTrash: Array<string> = trashRows.filter((row: TrashItemRow): boolean =>
          !existing.has(row.uri)).map((row: TrashItemRow): string => row.uri);
        if (orphanTrash.length > 0) {
          await db.removeTrashAll(orphanTrash);
          const deletedUris: Set<string> = new Set<string>(orphanTrash);
          trashRows = trashRows.filter((row: TrashItemRow): boolean => !deletedUris.has(row.uri));
        }
        // 系列孤儿：按 (seriesId,partIndex) 主键删除，再收空壳头
        const orphanParts: Array<SeriesPartRow> = partRows.filter((row: SeriesPartRow): boolean =>
          !existing.has(row.uri));
        if (orphanParts.length > 0) {
          for (const row of orphanParts) {
            await db.deleteSeriesPart(row.seriesId, row.partIndex);
          }
          await db.pruneOrphanSeries();
          const deletedKeys: Set<string> = new Set<string>();
          for (const row of orphanParts) {
            deletedKeys.add(`${row.seriesId}|${row.partIndex}`);
          }
          partRows = partRows.filter((row: SeriesPartRow): boolean =>
            !deletedKeys.has(`${row.seriesId}|${row.partIndex}`));
        }
        Diag.i(TAG, `orphans cleaned tags=${orphanTags.length} trash=${orphanTrash.length} parts=${orphanParts.length}`);
      } catch (e) {
        dbCleanupOk = false;
        Diag.w(TAG, `orphan cleanup failed: ${e}`);
      }
      // 孤儿 sidecar（.drops.json 对应的 mp4 已不存在）：文件层清理，失败只记日志
      try {
        for (const name of entries) {
          if (!name.endsWith(ArcDropLog.EXTENSION)) {
            continue;
          }
          const videoName: string = `${name.substring(0, name.length - ArcDropLog.EXTENSION.length)}${EXTENSION}`;
          if (!existing.has(`${dir}/${videoName}`)) {
            try {
              fileIo.unlinkSync(`${dir}/${name}`);
              Diag.i(TAG, `orphan sidecar removed ${name}`);
            } catch (e) {
              Diag.w(TAG, `orphan sidecar remove failed ${name}: ${e}`);
            }
          }
        }
      } catch (e) {
        Diag.w(TAG, `orphan sidecar scan failed: ${e}`);
      }
      this.orphansCleaned = dbCleanupOk;
    }

    const tagsById: Map<number, Set<string>> = new Map<number, Set<string>>();
    for (const row of tagRows) {
      let set: Set<string> | undefined = tagsById.get(row.mediaId);
      if (set === undefined) {
        set = new Set<string>();
        tagsById.set(row.mediaId, set);
      }
      set.add(row.tag);
    }
    const trashedUris: Set<string> = new Set<string>();
    for (const row of trashRows) {
      trashedUris.add(row.uri);
    }
    const partByMedia: Map<number, SeriesPartRow> = new Map<number, SeriesPartRow>();
    for (const row of partRows) {
      partByMedia.set(row.mediaId, row);
    }

    const clips: Array<VideoClip> = [];
    for (const name of names) {
      const full: string = `${dir}/${name}`;
      const rel: string = `${VideoStore.VIDEO_DIR_NAME}/${name}`;
      let size: number = 0;
      let mtime: number = 0;
      try {
        const st: fileIo.Stat = await fileIo.stat(full);
        if (!st.isFile()) {
          continue; // listFile 可能混入目录
        }
        size = st.size;
        mtime = st.mtime;
      } catch (e) {
        Diag.w(TAG, `stat failed ${full}: ${e}`);
        continue;
      }
      // 正在录制的半成品排除在媒体库之外（防彻底删除已 unlink inode 的成片）
      if (full === GlRecorder.getActiveRecordingPath()) {
        continue;
      }
      const id: number = MediaIds.ofRelativePath(rel);
      const meta: ProbeMeta = await this.probeMeta(full, size, mtime);
      const part: SeriesPartRow | undefined = partByMedia.get(id);
      clips.push(new VideoClip(id, full, name, rel, mtime, size, meta.durationMs,
        meta.width, meta.height, 'video/mp4', tagsById.get(id) ?? new Set<string>(),
        trashedUris.has(full), part === undefined ? undefined : part.seriesId,
        part === undefined ? undefined : part.partIndex));
    }

    const filtered: Array<VideoClip> = clips.filter((c: VideoClip): boolean => {
      switch (filter.kind) {
        case ClipKind.All:
          return !c.isTrashed;
        case ClipKind.Like:
          return !c.isTrashed && c.tags.has(TagSpace.LIKE);
        case ClipKind.Trash:
          return c.isTrashed;
        case ClipKind.Tagged:
          return !c.isTrashed && c.tags.has(filter.tag);
        default:
          return false;
      }
    });
    filtered.sort((a: VideoClip, b: VideoClip): number => {
      if (b.dateAddedSec !== a.dateAddedSec) {
        return b.dateAddedSec - a.dateAddedSec;
      }
      return a.name.localeCompare(b.name);
    });
    return filtered;
  }

  /**
   * 按 id 查片：优先用路由带的 path 直接 stat/probe 单条（不扫全库）；未命中再退回
   * All/Trash 全量查找（旧链接/异常路径）。
   */
  async findById(id: number, pathHint: string = ''): Promise<VideoClip | undefined> {
    if (pathHint !== '') {
      const direct: VideoClip | undefined = await this.clipAtPath(pathHint);
      if (direct !== undefined && direct.id === id) {
        return direct;
      }
    }
    const normal: Array<VideoClip> = await this.load(ClipFilter.all());
    for (const c of normal) {
      if (c.id === id) {
        return c;
      }
    }
    const trashed: Array<VideoClip> = await this.load(ClipFilter.trash());
    for (const c of trashed) {
      if (c.id === id) {
        return c;
      }
    }
    return undefined;
  }

  /** 单条定位：stat + probe + 标签/回收站联表（不做目录扫描） */
  private async clipAtPath(full: string): Promise<VideoClip | undefined> {
    if (full === GlRecorder.getActiveRecordingPath()) {
      return undefined;
    }
    // pathHint 严格规范化：父目录必须等于 videosDir、文件名单一、无 ./..、且为 .mp4
    const videosDir: string = VideoStore.videosDir(this.ctx);
    const slash: number = full.lastIndexOf('/');
    if (slash <= 0) {
      return undefined;
    }
    const parent: string = full.substring(0, slash);
    const name: string = full.substring(slash + 1);
    if (parent !== videosDir) {
      return undefined;
    }
    if (name.length === 0 || name === '.' || name === '..' ||
      name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
      return undefined;
    }
    if (full.indexOf('/../') >= 0 || full.indexOf('/./') >= 0) {
      return undefined;
    }
    if (!name.toLowerCase().endsWith(EXTENSION)) {
      return undefined;
    }
    let size: number = 0;
    let mtime: number = 0;
    try {
      const st: fileIo.Stat = await fileIo.stat(full);
      if (!st.isFile()) {
        return undefined;
      }
      size = st.size;
      mtime = st.mtime;
    } catch (e) {
      return undefined;
    }
    const fileName: string = full.substring(full.lastIndexOf('/') + 1);
    const rel: string = `${VideoStore.VIDEO_DIR_NAME}/${fileName}`;
    const id: number = MediaIds.ofRelativePath(rel);
    const meta: ProbeMeta = await this.probeMeta(full, size, mtime);
    let tags: Set<string> = new Set<string>();
    let isTrashed: boolean = false;
    const db: WotaDb | undefined = await this.ensureDb();
    if (db !== undefined) {
      try {
        // 两条查询全部成功后再整体提交，避免半份标签/回收站态
        const tagList: Array<string> = await db.tagsOf(id);
        const trash: TrashItemRow | undefined = await db.trashByUri(full);
        tags = new Set<string>(tagList);
        isTrashed = trash !== undefined;
        this.dbFailed = false; // 联表成功即视为可用
      } catch (e) {
        this.dbFailed = true;
        tags = new Set<string>();
        isTrashed = false;
        Diag.w(TAG, `clipAtPath join failed: ${e}`);
      }
    }
    return new VideoClip(id, full, name, rel, mtime, size, meta.durationMs, meta.width, meta.height,
      'video/mp4', tags, isTrashed);
  }

  /** 收藏开关：like 标签插入/删除 */
  async setLike(clip: VideoClip, liked: boolean): Promise<boolean> {
    if (!this.isDbAvailable() || clip.path === GlRecorder.getActiveRecordingPath()) {
      return false;
    }
    const db: WotaDb | undefined = await this.ensureDb();
    if (db === undefined) {
      return false;
    }
    try {
      if (liked) {
        await db.insertTag(clip.id, clip.relativePath, TagSpace.LIKE, Date.now());
      } else {
        await db.deleteTag(clip.id, TagSpace.LIKE);
      }
      return true;
    } catch (e) {
      Diag.e(TAG, `setLike failed ${clip.name}: ${e}`);
      return false;
    }
  }

  /**
   * 加自定义标签（CT 批，上游 MediaActions.addTag 同构）：TagSpace.normalize 守卫
   * （去空白/≤24 字/不含保留词），非法返回提示；成功走 insertTag（同点去重交 DB
   * UNIQUE 约束——重复加同一 tag 幂等）。
   */
  async addTag(clip: VideoClip, rawTag: string): Promise<string> {
    if (!this.isDbAvailable() || clip.path === GlRecorder.getActiveRecordingPath()) {
      return '数据库不可用';
    }
    const tag: string | undefined = TagSpace.normalize(rawTag);
    if (tag === undefined) {
      return '标签无效（1-24 字，不能用保留词）';
    }
    const db: WotaDb | undefined = await this.ensureDb();
    if (db === undefined) {
      return '数据库不可用';
    }
    try {
      await db.insertTag(clip.id, clip.relativePath, tag, Date.now());
      return '';
    } catch (e) {
      Diag.e(TAG, `addTag failed ${clip.name}: ${e}`);
      return '加标签失败';
    }
  }

  /** 移除自定义标签（CT 批；上游 MediaActions.removeTag 同构） */
  async removeTag(clip: VideoClip, tag: string): Promise<boolean> {
    if (!this.isDbAvailable()) {
      return false;
    }
    const db: WotaDb | undefined = await this.ensureDb();
    if (db === undefined) {
      return false;
    }
    try {
      await db.deleteTag(clip.id, tag);
      return true;
    } catch (e) {
      Diag.e(TAG, `removeTag failed ${clip.name}: ${e}`);
      return false;
    }
  }

  /** 自定义标签清单（media_tag 行派生：isCustom 过滤 + createdAt 升序 + 去重） */
  async customTags(): Promise<Array<string>> {
    const db: WotaDb | undefined = await this.ensureDb();
    if (db === undefined) {
      return [];
    }
    try {
      const rows: Array<MediaTagRow> = await db.allTags();
      const ctRows: Array<CustomTagRow> = rows.map((r: MediaTagRow): CustomTagRow =>
        new CustomTagRow(r.tag, r.createdAt));
      return CustomTags.ofRows(ctRows);
    } catch (e) {
      Diag.e(TAG, `customTags failed: ${e}`);
      return [];
    }
  }

  /** 移入回收站：只写本地记录，文件原地保留（对齐上游 API29 本地回收站语义） */
  async moveToTrash(clip: VideoClip): Promise<boolean> {
    if (!this.isDbAvailable() || clip.path === GlRecorder.getActiveRecordingPath()) {
      return false;
    }
    const db: WotaDb | undefined = await this.ensureDb();
    if (db === undefined) {
      return false;
    }
    try {
      await db.insertTrash(new TrashItemRow(clip.path, clip.id, clip.name, Date.now(), clip.relativePath));
      return true;
    } catch (e) {
      Diag.e(TAG, `moveToTrash failed ${clip.name}: ${e}`);
      return false;
    }
  }

  /** 还原：删回收站记录（文件从未移动） */
  async restore(uri: string): Promise<boolean> {
    if (!this.isDbAvailable() || uri === GlRecorder.getActiveRecordingPath()) {
      return false;
    }
    const db: WotaDb | undefined = await this.ensureDb();
    if (db === undefined) {
      return false;
    }
    try {
      await db.removeTrash(uri);
      return true;
    } catch (e) {
      Diag.e(TAG, `restore failed ${uri}: ${e}`);
      return false;
    }
  }

  /**
   * 彻底删除：unlink 文件 → .drops.json → 标签/回收站/系列清账。
   * 口径（审查）：unlink 成功后才动档案；sidecar 存在但删除失败、或 DB 不可用/清账失败
   * 都返回 false（= partial，文件已删但可能有孤儿档案/行），由 UI 如实提示。
   */
  async deleteForever(clip: VideoClip): Promise<boolean> {
    if (clip.path === GlRecorder.getActiveRecordingPath()) {
      return false; // 活动录制文件禁止删除
    }
    try {
      fileIo.unlinkSync(clip.path);
    } catch (e) {
      const err: BusinessError = e as BusinessError;
      if (err.code !== 13900002) {
        // 真失败：保留 sidecar/DB（用户还可重试；不能把账先清了）
        Diag.w(TAG, `unlink failed ${clip.path}: ${e}`);
        return false;
      }
      // 13900002 = No such file：视为已删除，继续清账（删除幂等/可重试）
      Diag.w(TAG, `unlink ENOENT, treat as deleted ${clip.path}`);
    }
    let complete: boolean = true;
    // sidecar：不存在属正常（没开过弧转换）；存在但删失败 = partial
    let sidecarExists: boolean = false;
    try {
      const st: fileIo.Stat = fileIo.statSync(ArcDropLog.sidecarFor(clip.path));
      sidecarExists = st.isFile();
      if (!sidecarExists) {
        complete = false; // 同名路径不是普通文件：异常态，不静默当不存在
        Diag.w(TAG, `drops sidecar is not a file ${clip.path}`);
      }
    } catch (e) {
      const err: BusinessError = e as BusinessError;
      if (err.code !== 13900002) {
        // 13900002 = No such file（正常没有 sidecar）；权限/IO 错误不得当不存在
        complete = false;
        Diag.w(TAG, `drops sidecar stat failed ${clip.path}: ${err.code}`);
      }
    }
    if (sidecarExists) {
      try {
        if (!ArcDropLog.deleteFor(clip.path)) {
          complete = false;
          Diag.w(TAG, `drops sidecar delete failed ${clip.path}`);
        }
      } catch (e) {
        complete = false;
        Diag.w(TAG, `drops sidecar delete exception ${clip.path}: ${e}`);
      }
    }
    // DB：即使 UI 已降级（dbFailed），仍用已打开/可重开的库做 best-effort 清账
    let db: WotaDb | undefined = this.db;
    if (db === undefined) {
      try {
        db = await WotaDb.get(this.ctx);
        this.db = db;
        this.dbGeneration = WotaDb.currentGeneration();
        this.dbFailed = false;
      } catch (e) {
        db = undefined;
      }
    }
    if (db === undefined) {
      Diag.w(TAG, `db unavailable on delete, orphan rows may remain: ${clip.name}`);
      this.orphansCleaned = false; // 下一次 load / 重试库后自动清孤儿
      return false;
    }
    try {
      await db.clearTagsForPath(clip.relativePath);
      await db.removeTrash(clip.path);
      await db.clearSeriesForUri(clip.path);
      await db.pruneOrphanSeries();
      return complete;
    } catch (e) {
      Diag.e(TAG, `db clear failed ${clip.name}: ${e}`);
      this.orphansCleaned = false;
      return false;
    }
  }
}

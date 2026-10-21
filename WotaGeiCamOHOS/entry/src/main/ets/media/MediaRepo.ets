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
import { Diag } from '../diag/Diag';
import { ArcDropLog } from '../record/ArcDropLog';
import { VideoStore } from '../record/VideoStore';
import { GlRecorder } from '../camera/GlRecorder';
import { WotaTag } from '../core/Constants';
import { ClipFilter, ClipKind, TagSpace } from './MediaTag';
import { MediaTagRow, SeriesPartRow, TrashItemRow, WotaDb } from './WotaDb';

const TAG: string = WotaTag.MEDIA_REPO;
const PROBE_TIMEOUT_MS: number = 1500;
const EXTENSION: string = '.mp4';

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
  private dbFailed: boolean = false;
  private probeCache: Map<string, ProbeMeta> = new Map<string, ProbeMeta>();
  /** 探测去重：key=path|size|mtime → 在途 task（同 key 复用，避免重复 open fd） */
  private probeInFlight: Map<string, Promise<ProbeMeta>> = new Map<string, Promise<ProbeMeta>>();
  /** 短 TTL 负缓存（失败/超时/零值）：到期前不再新建探测任务 */
  private probeNegUntil: Map<string, number> = new Map<string, number>();
  private static readonly PROBE_NEG_TTL_MS: number = 3000;
  /** 在途探测上限（损坏文件永久挂起时防止 fd/extractor 无界累积） */
  private static readonly PROBE_MAX_PENDING: number = 4;

  constructor(ctx: common.Context) {
    this.ctx = ctx;
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
      return this.db;
    }
    if (this.dbFailed) {
      return undefined;
    }
    try {
      this.db = await WotaDb.get(this.ctx);
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
   * 探测时长/分辨率（fd 时序 + 无界累积审查）：
   * - 在途去重：同 key（path|size|mtime）复用同一个 owned task，不重复 open fd；
   * - 并发上限 PROBE_MAX_PENDING，超限直接返回零（不新建任务）；
   * - 外层 1.5s 竞速只放弃结果、不 close fd/extractor；task 在 fetch settle 后 release+close，
   *   晚到结果由 task 的回调补进缓存；
   * - 失败/超时/零值写短 TTL 负缓存，反复刷新不会重复创建挂起任务；
   * - timer 在竞速 settle 后 clearTimeout；create 与 fetch 都在 task 内（受同一超时约束的调用方等待）。
   */
  private async probeMeta(path: string, size: number, mtime: number): Promise<ProbeMeta> {
    const key: string = `${path}|${size}|${mtime}`;
    const cached: ProbeMeta | undefined = this.probeCache.get(key);
    if (cached !== undefined) {
      return cached;
    }
    const negUntil: number | undefined = this.probeNegUntil.get(key);
    if (negUntil !== undefined) {
      if (negUntil > Date.now()) {
        return new ProbeMeta(0, 0, 0);
      }
      this.probeNegUntil.delete(key);
    }

    let pending: Promise<ProbeMeta> | undefined = this.probeInFlight.get(key);
    if (pending === undefined) {
      if (this.probeInFlight.size >= MediaRepo.PROBE_MAX_PENDING) {
        Diag.w(TAG, `probe pending cap reached, skip ${path}`);
        return new ProbeMeta(0, 0, 0);
      }
      let file: fileIo.File;
      try {
        file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      } catch (e) {
        Diag.w(TAG, `probe open failed ${path}: ${e}`);
        this.setProbeNegative(key);
        return new ProbeMeta(0, 0, 0);
      }
      const owned: Promise<ProbeMeta> = this.probeMetaOwned(file, size, path);
      pending = owned.then((result: ProbeMeta): ProbeMeta => {
        this.probeInFlight.delete(key);
        this.rememberProbeResult(key, result); // 晚到结果也补缓存
        return result;
      }).catch((e: Object): ProbeMeta => {
        this.probeInFlight.delete(key);
        this.setProbeNegative(key);
        Diag.w(TAG, `probe task failed ${path}: ${e}`);
        return new ProbeMeta(0, 0, 0);
      });
      this.probeInFlight.set(key, pending);
    }

    let timer: number = -1;
    const timeout: Promise<ProbeMeta> = new Promise<ProbeMeta>(
      (resolve: (v: ProbeMeta) => void): void => {
        timer = setTimeout((): void => {
          resolve(new ProbeMeta(0, 0, 0));
        }, PROBE_TIMEOUT_MS);
      });
    const result: ProbeMeta = await Promise.race([pending, timeout]);
    if (timer >= 0) {
      clearTimeout(timer);
    }
    if (result.durationMs <= 0 && result.width <= 0 && result.height <= 0) {
      this.setProbeNegative(key); // 超时或失败：短 TTL 内不再新开探测
    }
    return result;
  }

  private rememberProbeResult(key: string, result: ProbeMeta): void {
    if (result.durationMs > 0 || result.width > 0 || result.height > 0) {
      this.probeCache.set(key, result);
      this.probeNegUntil.delete(key);
    } else {
      this.setProbeNegative(key);
    }
  }

  private setProbeNegative(key: string): void {
    this.probeNegUntil.set(key, Date.now() + MediaRepo.PROBE_NEG_TTL_MS);
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
    try {
      names = fileIo.listFileSync(dir).filter((n: string): boolean => n.toLowerCase().endsWith(EXTENSION));
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
        const st: fileIo.Stat = fileIo.statSync(full);
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
      // unlink 失败：保留 sidecar/DB（用户还可重试；不能把账先清了）
      Diag.w(TAG, `unlink failed ${clip.path}: ${e}`);
      return false;
    }
    let complete: boolean = true;
    // sidecar：不存在属正常（没开过弧转换）；存在但删失败 = partial
    let sidecarExists: boolean = false;
    try {
      sidecarExists = fileIo.accessSync(ArcDropLog.sidecarFor(clip.path));
    } catch (e) {
      sidecarExists = false;
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
        this.dbFailed = false;
      } catch (e) {
        db = undefined;
      }
    }
    if (db === undefined) {
      Diag.w(TAG, `db unavailable on delete, orphan rows may remain: ${clip.name}`);
      return false;
    }
    try {
      await db.clearTagsFor([clip.id]);
      await db.removeTrash(clip.path);
      await db.clearSeriesFor([clip.id]);
      await db.pruneOrphanSeries();
      return complete;
    } catch (e) {
      Diag.e(TAG, `db clear failed ${clip.name}: ${e}`);
      return false;
    }
  }
}

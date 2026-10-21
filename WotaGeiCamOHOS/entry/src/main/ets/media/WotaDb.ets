/**
 * 媒体库本地数据层（直译上游 media/WotaDb.kt 的四张 Room 表；对齐上游 38d712a）。
 *
 * 平台差异（登记）：
 * - Room → @ohos.data.relationalStore：Room 的 @Insert(REPLACE)/@Query 逐条落成 executeSql/querySql；
 * - Room 的 fallbackToDestructiveMigration → 本版 version=1；版本不匹配时先 close store 再
 *   deleteRdbStore 破坏性重建（收藏/标签/回收站一起丢，见 PORT_MAP）；打开/建表失败仍由
 *   MediaRepo 捕获后降级为仅文件列表；
 * - 表结构/字段名/唯一约束/索引逐字段对齐上游，便于后续跨平台核对：
 *   media_tag(id,mediaId,path,tag,createdAt) + UNIQUE(mediaId,tag) + INDEX(tag)
 *   trash_item(uri PK,mediaId,name,deletedAt,origin) + INDEX(mediaId)
 *   media_series(seriesId PK,startedAt,partCount)
 *   series_part(seriesId,partIndex,mediaId,uri,durationMs) + PK(seriesId,partIndex)
 * - 所有 DAO 返回 Promise，ResultSet 在 finally 中 close；SQL 失败记 Diag.e 后向上抛，
 *   页面层统一降级（不让数据库异常打崩 UI）。
 */
import { relationalStore } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';

const DB_NAME: string = 'wota_media.db';
const DB_VERSION: number = 1;
const TAG: string = 'WotaMediaDb';

const STORE_CONFIG: relationalStore.StoreConfig = {
  name: DB_NAME,
  securityLevel: relationalStore.SecurityLevel.S1
};

const SQL_CREATE_TABLES: Array<string> = [
  'CREATE TABLE IF NOT EXISTS media_tag (' +
    'id INTEGER PRIMARY KEY AUTOINCREMENT, mediaId INTEGER NOT NULL, path TEXT NOT NULL, ' +
    'tag TEXT NOT NULL, createdAt INTEGER NOT NULL)',
  'CREATE UNIQUE INDEX IF NOT EXISTS idx_media_tag_media_tag ON media_tag(mediaId, tag)',
  'CREATE INDEX IF NOT EXISTS idx_media_tag_tag ON media_tag(tag)',
  'CREATE TABLE IF NOT EXISTS trash_item (' +
    'uri TEXT PRIMARY KEY, mediaId INTEGER NOT NULL, name TEXT NOT NULL, ' +
    'deletedAt INTEGER NOT NULL, origin TEXT NOT NULL)',
  'CREATE INDEX IF NOT EXISTS idx_trash_item_media ON trash_item(mediaId)',
  'CREATE TABLE IF NOT EXISTS media_series (' +
    'seriesId INTEGER PRIMARY KEY, startedAt INTEGER NOT NULL, partCount INTEGER NOT NULL)',
  'CREATE TABLE IF NOT EXISTS series_part (' +
    'seriesId INTEGER NOT NULL, partIndex INTEGER NOT NULL, mediaId INTEGER NOT NULL, ' +
    'uri TEXT NOT NULL, durationMs INTEGER NOT NULL, PRIMARY KEY (seriesId, partIndex))'
];

/** media_tag 行（对应上游 @Entity MediaTag） */
export class MediaTagRow {
  id: number;
  mediaId: number;
  path: string;
  tag: string;
  createdAt: number;

  constructor(id: number, mediaId: number, path: string, tag: string, createdAt: number) {
    this.id = id;
    this.mediaId = mediaId;
    this.path = path;
    this.tag = tag;
    this.createdAt = createdAt;
  }
}

/** trash_item 行（uri 为成片绝对路径；origin 为相对私有目录路径） */
export class TrashItemRow {
  uri: string;
  mediaId: number;
  name: string;
  deletedAt: number;
  origin: string;

  constructor(uri: string, mediaId: number, name: string, deletedAt: number, origin: string) {
    this.uri = uri;
    this.mediaId = mediaId;
    this.name = name;
    this.deletedAt = deletedAt;
    this.origin = origin;
  }
}

/** media_series 行（分段录制系列；本批只落表/DAO，UI 留待系列批） */
export class MediaSeriesRow {
  seriesId: number;
  startedAt: number;
  partCount: number;

  constructor(seriesId: number, startedAt: number, partCount: number) {
    this.seriesId = seriesId;
    this.startedAt = startedAt;
    this.partCount = partCount;
  }
}

/** series_part 行（系列成员；seriesId+partIndex 唯一） */
export class SeriesPartRow {
  seriesId: number;
  partIndex: number;
  mediaId: number;
  uri: string;
  durationMs: number;

  constructor(seriesId: number, partIndex: number, mediaId: number, uri: string, durationMs: number) {
    this.seriesId = seriesId;
    this.partIndex = partIndex;
    this.mediaId = mediaId;
    this.uri = uri;
    this.durationMs = durationMs;
  }
}

function placeholders(n: number): string {
  let s: string = '';
  for (let i = 0; i < n; i++) {
    s += i === 0 ? '?' : ',?';
  }
  return s;
}

/**
 * 单例 RDB（进程内）：get(ctx) 首次打开并建表；ensureSchema 失败会 reject，
 * 调用方 MediaRepo 据此降级为仅文件列表。
 */
export class WotaDb {
  private static instance: WotaDb | undefined = undefined;
  private static opening: Promise<WotaDb> | undefined = undefined;

  private store: relationalStore.RdbStore;

  private constructor(store: relationalStore.RdbStore) {
    this.store = store;
  }

  static get(ctx: common.Context): Promise<WotaDb> {
    if (WotaDb.instance !== undefined) {
      return Promise.resolve(WotaDb.instance);
    }
    if (WotaDb.opening !== undefined) {
      return WotaDb.opening;
    }
    WotaDb.opening = WotaDb.open(ctx);
    return WotaDb.opening;
  }

  private static async open(ctx: common.Context): Promise<WotaDb> {
    let store: relationalStore.RdbStore | undefined = undefined;
    try {
      store = await relationalStore.getRdbStore(ctx, STORE_CONFIG);
      // 上游 Room fallbackToDestructiveMigration 的等价物：版本不匹配时先 close 再删库重建
      // （收藏/标签/回收站会一起丢，登记于 PORT_MAP）
      if (store.version !== 0 && store.version !== DB_VERSION) {
        Diag.w(TAG, `rdb version ${store.version} != ${DB_VERSION}, destructive rebuild`);
        await store.close();
        store = undefined;
        await relationalStore.deleteRdbStore(ctx, DB_NAME);
        store = await relationalStore.getRdbStore(ctx, STORE_CONFIG);
      }
      const active: relationalStore.RdbStore = store;
      const db: WotaDb = new WotaDb(active);
      await db.ensureSchema();
      WotaDb.instance = db;
      Diag.i(TAG, `rdb opened ${DB_NAME} v${active.version}`);
      return db;
    } catch (e) {
      if (store !== undefined) {
        try {
          await store.close();
        } catch (closeErr) {
        }
        store = undefined;
      }
      Diag.e(TAG, `rdb open/schema failed: ${e}`);
      WotaDb.opening = undefined;
      throw e as Error;
    }
  }

  private async ensureSchema(): Promise<void> {
    for (const sql of SQL_CREATE_TABLES) {
      try {
        await this.store.executeSql(sql);
      } catch (e) {
        Diag.e(TAG, `schema sql failed: ${sql} err=${e}`);
        throw e as Error;
      }
    }
    if (this.store.version !== DB_VERSION) {
      this.store.version = DB_VERSION; // 版本对不上先记录；破坏性重建策略登记
    }
  }

  // region media_tag

  async insertTag(mediaId: number, path: string, tag: string, createdAt: number): Promise<void> {
    try {
      await this.store.executeSql(
        'INSERT OR REPLACE INTO media_tag (mediaId, path, tag, createdAt) VALUES (?,?,?,?)',
        [mediaId, path, tag, createdAt]);
    } catch (e) {
      Diag.e(TAG, `insertTag failed mediaId=${mediaId} tag=${tag}: ${e}`);
      throw e as Error;
    }
  }

  async deleteTag(mediaId: number, tag: string): Promise<void> {
    try {
      await this.store.executeSql('DELETE FROM media_tag WHERE mediaId = ? AND tag = ?', [mediaId, tag]);
    } catch (e) {
      Diag.e(TAG, `deleteTag failed mediaId=${mediaId} tag=${tag}: ${e}`);
      throw e as Error;
    }
  }

  async allTags(): Promise<Array<MediaTagRow>> {
    const rows: Array<MediaTagRow> = [];
    let rs: relationalStore.ResultSet | undefined = undefined;
    try {
      rs = await this.store.querySql(
        'SELECT id, mediaId, path, tag, createdAt FROM media_tag ORDER BY createdAt');
      while (rs.goToNextRow()) {
        rows.push(new MediaTagRow(rs.getLong(0), rs.getLong(1), rs.getString(2), rs.getString(3),
          rs.getLong(4)));
      }
      return rows;
    } catch (e) {
      Diag.e(TAG, `allTags failed: ${e}`);
      throw e as Error;
    } finally {
      if (rs !== undefined) {
        try {
          rs.close();
        } catch (e) {
        }
      }
    }
  }

  /** 删文件后必须清标签（id 复用时脏标签会串到新视频上） */
  async clearTagsFor(mediaIds: Array<number>): Promise<void> {
    if (mediaIds.length === 0) {
      return;
    }
    try {
      await this.store.executeSql(
        `DELETE FROM media_tag WHERE mediaId IN (${placeholders(mediaIds.length)})`, mediaIds);
    } catch (e) {
      Diag.e(TAG, `clearTagsFor failed n=${mediaIds.length}: ${e}`);
      throw e as Error;
    }
  }

  // endregion

  // region trash_item

  async insertTrash(row: TrashItemRow): Promise<void> {
    try {
      await this.store.executeSql(
        'INSERT OR REPLACE INTO trash_item (uri, mediaId, name, deletedAt, origin) VALUES (?,?,?,?,?)',
        [row.uri, row.mediaId, row.name, row.deletedAt, row.origin]);
    } catch (e) {
      Diag.e(TAG, `insertTrash failed ${row.uri}: ${e}`);
      throw e as Error;
    }
  }

  async removeTrash(uri: string): Promise<void> {
    try {
      await this.store.executeSql('DELETE FROM trash_item WHERE uri = ?', [uri]);
    } catch (e) {
      Diag.e(TAG, `removeTrash failed ${uri}: ${e}`);
      throw e as Error;
    }
  }

  async removeTrashAll(uris: Array<string>): Promise<void> {
    if (uris.length === 0) {
      return;
    }
    try {
      await this.store.executeSql(
        `DELETE FROM trash_item WHERE uri IN (${placeholders(uris.length)})`, uris);
    } catch (e) {
      Diag.e(TAG, `removeTrashAll failed n=${uris.length}: ${e}`);
      throw e as Error;
    }
  }

  async allTrash(): Promise<Array<TrashItemRow>> {
    const rows: Array<TrashItemRow> = [];
    let rs: relationalStore.ResultSet | undefined = undefined;
    try {
      rs = await this.store.querySql(
        'SELECT uri, mediaId, name, deletedAt, origin FROM trash_item ORDER BY deletedAt DESC');
      while (rs.goToNextRow()) {
        rows.push(new TrashItemRow(rs.getString(0), rs.getLong(1), rs.getString(2), rs.getLong(3),
          rs.getString(4)));
      }
      return rows;
    } catch (e) {
      Diag.e(TAG, `allTrash failed: ${e}`);
      throw e as Error;
    } finally {
      if (rs !== undefined) {
        try {
          rs.close();
        } catch (e) {
        }
      }
    }
  }

  // endregion

  // region media_series / series_part（本批只备表与 DAO，UI 留待系列批）

  async upsertSeries(row: MediaSeriesRow): Promise<void> {
    try {
      await this.store.executeSql(
        'INSERT OR REPLACE INTO media_series (seriesId, startedAt, partCount) VALUES (?,?,?)',
        [row.seriesId, row.startedAt, row.partCount]);
    } catch (e) {
      Diag.e(TAG, `series upsert failed: ${e}`);
      throw e as Error;
    }
  }

  async upsertPart(row: SeriesPartRow): Promise<void> {
    try {
      await this.store.executeSql(
        'INSERT OR REPLACE INTO series_part (seriesId, partIndex, mediaId, uri, durationMs) VALUES (?,?,?,?,?)',
        [row.seriesId, row.partIndex, row.mediaId, row.uri, row.durationMs]);
    } catch (e) {
      Diag.e(TAG, `series upsert failed: ${e}`);
      throw e as Error;
    }
  }

  async allSeries(): Promise<Array<MediaSeriesRow>> {
    const rows: Array<MediaSeriesRow> = [];
    let rs: relationalStore.ResultSet | undefined = undefined;
    try {
      rs = await this.store.querySql('SELECT seriesId, startedAt, partCount FROM media_series');
      while (rs.goToNextRow()) {
        rows.push(new MediaSeriesRow(rs.getLong(0), rs.getLong(1), rs.getLong(2)));
      }
      return rows;
    } catch (e) {
      Diag.e(TAG, `allSeries failed: ${e}`);
      throw e as Error;
    } finally {
      if (rs !== undefined) {
        try {
          rs.close();
        } catch (e) {
        }
      }
    }
  }

  async allParts(): Promise<Array<SeriesPartRow>> {
    const rows: Array<SeriesPartRow> = [];
    let rs: relationalStore.ResultSet | undefined = undefined;
    try {
      rs = await this.store.querySql(
        'SELECT seriesId, partIndex, mediaId, uri, durationMs FROM series_part ORDER BY seriesId, partIndex');
      while (rs.goToNextRow()) {
        rows.push(new SeriesPartRow(rs.getLong(0), rs.getLong(1), rs.getLong(2), rs.getString(3),
          rs.getLong(4)));
      }
      return rows;
    } catch (e) {
      Diag.e(TAG, `allParts failed: ${e}`);
      throw e as Error;
    } finally {
      if (rs !== undefined) {
        try {
          rs.close();
        } catch (e) {
        }
      }
    }
  }

  async clearSeriesFor(mediaIds: Array<number>): Promise<void> {
    if (mediaIds.length === 0) {
      return;
    }
    try {
      await this.store.executeSql(
        `DELETE FROM series_part WHERE mediaId IN (${placeholders(mediaIds.length)})`, mediaIds);
    } catch (e) {
      Diag.e(TAG, `clearSeriesFor failed: ${e}`);
      throw e as Error;
    }
  }

  /** 收掉成员全被删后留下的空壳系列头 */
  async pruneOrphanSeries(): Promise<void> {
    try {
      await this.store.executeSql(
        'DELETE FROM media_series WHERE seriesId NOT IN (SELECT DISTINCT seriesId FROM series_part)');
    } catch (e) {
      Diag.e(TAG, `pruneOrphanSeries failed: ${e}`);
      throw e as Error;
    }
  }

  // endregion
}

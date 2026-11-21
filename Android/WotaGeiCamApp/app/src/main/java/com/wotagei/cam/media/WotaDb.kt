package com.wotagei.cam.media

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * 标签记录：收藏与用户自定义分类共用一张表（—— 标签模型优于媒体表加布尔列）。
 * 与 MediaStore 解耦：文件被外部改动时标签记录仍在。
 *
 * 清理链路按 `mediaId` 走（`MediaActions.deleteForever` 里 `clearFor`）——  就是为防
 * id 复用把脏标签串到新视频上。[path] 同时用于迁移导出的身份核对；受控改名只按原路径条件更新，
 * 外部删除后的孤儿标签对账仍未实现，不能据同一个 MediaStore id 猜测归属。
 */
@Entity(
    tableName = "media_tag",
    indices = [
        Index(value = ["mediaId", "tag"], unique = true),
        Index("tag")
    ]
)
data class MediaTag(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val path: String,
    val tag: String,
    val createdAt: Long
)

/**
 * 回收站记录。两条来源：
 * 1) API 30+ createTrashRequest 成功（系统侧 IS_TRASHED=1，本表记一份用于还原与展示元信息）；
 * 2) API 29 或系统不支持回收站时，本地标记 + 真删授权回路（origin 存原相对路径）。
 */
@Entity(tableName = "trash_item", indices = [Index(value = ["mediaId"])])
data class TrashItem(
    @PrimaryKey val uri: String,
    val mediaId: Long,
    val name: String,
    val deletedAt: Long,
    val origin: String
)

/** 分段录制系列（04 录制管线用 series_id 关联同一段落的多个分片） */
@Entity(tableName = "media_series")
data class MediaSeries(
    @PrimaryKey val seriesId: Long,
    val startedAt: Long,
    val partCount: Int
)

/** 分片归属：seriesId + partIndex 唯一 */
@Entity(tableName = "series_part", primaryKeys = ["seriesId", "partIndex"])
data class SeriesPart(
    val seriesId: Long,
    val partIndex: Int,
    val mediaId: Long,
    val uri: String,
    val durationMs: Long
)

@Dao
interface MediaTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: MediaTag)

    @Query("DELETE FROM media_tag WHERE mediaId = :mediaId AND tag = :tag")
    suspend fun deleteOne(mediaId: Long, tag: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnclaimed(tag: MediaTag): Long

    @Query("DELETE FROM media_tag WHERE mediaId = :mediaId AND tag = :tag AND path IN (:paths)")
    suspend fun deleteForPaths(mediaId: Long, tag: String, paths: List<String>)

    /** 受控改名成功后只搬同一原文件的标签，既不覆盖时间，也不认复用的 ID。 */
    @Query("UPDATE media_tag SET path = :newPath WHERE mediaId = :mediaId AND path = :oldPath")
    suspend fun movePathAfterRename(mediaId: Long, oldPath: String, newPath: String)

    /** 删除文件后必须清标签，否则 id 复用时脏标签会串到新视频上 */
    @Query("DELETE FROM media_tag WHERE mediaId IN (:mediaIds)")
    suspend fun clearFor(mediaIds: List<Long>)

    /**
     * 按**身份对**清标签：`(mediaId, path)` 双键必须同时命中。
     *
     * 彻底删除的收尾专用。裸 `mediaId` 在外部删片 + MediaStore 复用 ID 后会误清新片的标签；
     * 双键匹配不上时宁可留下孤儿行（下次受控改名/迁移对账会处理），也绝不把旧片状态带到新片。
     */
    @Query("DELETE FROM media_tag WHERE mediaId = :mediaId AND path = :path")
    suspend fun clearIdentity(mediaId: Long, path: String)

    @Query("SELECT * FROM media_tag")
    fun observeAll(): Flow<List<MediaTag>>

    @Query("SELECT * FROM media_tag WHERE mediaId = :mediaId ORDER BY createdAt")
    fun observeRowsOf(mediaId: Long): Flow<List<MediaTag>>

    @Query("SELECT * FROM media_tag WHERE mediaId = :mediaId ORDER BY createdAt")
    suspend fun rowsOf(mediaId: Long): List<MediaTag>

    @Query("DELETE FROM media_tag")
    suspend fun clearAll()
}

@Dao
interface TrashDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: TrashItem)

    @Query("DELETE FROM trash_item WHERE uri = :uri")
    suspend fun remove(uri: String)

    @Query("DELETE FROM trash_item WHERE uri IN (:uris)")
    suspend fun removeAll(uris: List<String>)

    /**
     * 旧聚合账键 → 具名卷行 URI 的**归一改写**（Y27）。只在判据（[legacyTrashAliasTarget]）
     * 证明「唯一且确认同一行」后调用；可能改写主键值，SQL 允许，schema 不动。
     */
    @Query("""
        UPDATE trash_item SET uri = :newUri
        WHERE uri = :oldUri AND name = :name AND origin = :origin AND deletedAt = :deletedAt
          AND NOT EXISTS (SELECT 1 FROM trash_item WHERE uri = :newUri)
    """)
    suspend fun rewriteUri(oldUri: String, newUri: String, name: String, origin: String, deletedAt: Long): Int

    @Query("SELECT * FROM trash_item ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashItem>>
}

@Dao
interface SeriesDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: MediaSeries)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPart(part: SeriesPart)

    @Query("SELECT * FROM series_part WHERE seriesId = :seriesId ORDER BY partIndex")
    suspend fun partsOf(seriesId: Long): List<SeriesPart>

    @Query("SELECT * FROM series_part WHERE mediaId = :mediaId LIMIT 1")
    suspend fun partOfMedia(mediaId: Long): SeriesPart?

    @Query("DELETE FROM series_part WHERE mediaId IN (:mediaIds)")
    suspend fun clearFor(mediaIds: List<Long>)

    /**
     * 按**身份对**清系列成员：`(mediaId, uri)` 双键必须同时命中。
     *
     * 与 [MediaTagDao.clearIdentity] 同一条红线：裸 ID 在 MediaStore 复用后会误删新片的成员关系。
     * 系列归组是跨进程稳定的弱关联，宁可留孤儿等 prune/对账，也不误伤同 ID 的新条目。
     */
    @Query("DELETE FROM series_part WHERE mediaId = :mediaId AND uri = :uri")
    suspend fun clearIdentity(mediaId: Long, uri: String)

    /**
     * 读侧归组用的两条全表流（#5 Step 4c）。
     *
     * ⚠ **只加查询、绝不碰实体、绝不加 `@Index`**：加索引会改 schema ⇒ 撞
     * `fallbackToDestructiveMigration()` ⇒ 升级即清空用户的收藏 / 标签 / 回收站 / 系列。
     * 全表扫在小体量媒体库里不是问题（一次只读、Room 自己有 in-memory 缓存兜底）。
     *
     * 读侧不 JOIN、不做联表查询：`media_series` 与 `series_part` 分两次发射、在
     * `MediaRepo` 里按 `mediaId` 归组成 Map——这样任一张表变化都能独立触发重算，
     * 也不必为一个新 JOIN 在 schema 上加东西。
     */
    @Query("SELECT * FROM media_series")
    fun observeAllSeries(): Flow<List<MediaSeries>>

    @Query("SELECT * FROM series_part")
    fun observeAllParts(): Flow<List<SeriesPart>>

    /**
     * 收掉没有成员系列的空壳头（#5 Step 4d）。
     *
     * 成员全被 `clearFor` 删掉后，`media_series` 里会留下一行 `seriesId` 谁也不指的记录；
     * 读侧按「成员数 ≥ 2 才成组」本来就会忽略它，但留着会让表无界增长。
     * 紧跟在 `clearFor` 之后调用（见 `MediaActions.finishDelete`）。
     */
    @Query("DELETE FROM media_series WHERE seriesId NOT IN (SELECT DISTINCT seriesId FROM series_part)")
    suspend fun pruneOrphans()
}

@Database(
    entities = [MediaTag::class, TrashItem::class, MediaSeries::class, SeriesPart::class],
    version = 1,
    // build.gradle 的 ksp room.schemaLocation 已经指到 app/schemas，这里必须同步导出，
    // 否则将来加字段时无历史 schema 可写 Migration，只能永远吃下面的破坏性迁移
    exportSchema = true
)
abstract class WotaDb : RoomDatabase() {

    abstract fun tagDao(): MediaTagDao
    abstract fun trashDao(): TrashDao
    abstract fun seriesDao(): SeriesDao

    companion object {

        private const val NAME = "wota_media.db"

        @Volatile
        private var instance: WotaDb? = null

        fun get(context: Context): WotaDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, WotaDb::class.java, NAME)
                    // 0.0.x 的策略：版本对不上就直接重建库（收藏与标签会一起丢）。
                    // 真要发版升级，必须先从 app/schemas 的 JSON 写出 Migration 再拿掉这行
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}

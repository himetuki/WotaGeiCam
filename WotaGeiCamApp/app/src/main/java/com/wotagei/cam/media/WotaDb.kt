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
 * id 复用把脏标签串到新视频上。[path] 目前是**预留列**：它 intended 的用途是「文件在系统相册里
 * 被删掉后回收孤儿标签」，那步对账还没实现，所以现在只有写入方在用，孤儿行只会让表变大。
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

    /** 删除文件后必须清标签，否则 id 复用时脏标签会串到新视频上 */
    @Query("DELETE FROM media_tag WHERE mediaId IN (:mediaIds)")
    suspend fun clearFor(mediaIds: List<Long>)

    @Query("SELECT * FROM media_tag")
    fun observeAll(): Flow<List<MediaTag>>

    @Query("SELECT tag FROM media_tag WHERE mediaId = :mediaId ORDER BY createdAt")
    fun observeTagsOf(mediaId: Long): Flow<List<String>>

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

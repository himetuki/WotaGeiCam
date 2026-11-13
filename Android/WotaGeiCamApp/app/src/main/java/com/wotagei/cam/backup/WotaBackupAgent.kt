package com.wotagei.cam.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.content.ContentUris
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.AtomicFile
import androidx.room.withTransaction
import com.wotagei.cam.diag.Diag
import com.wotagei.cam.media.MediaTag
import com.wotagei.cam.media.TagSpace
import com.wotagei.cam.media.VideoClip
import com.wotagei.cam.media.WotaDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Auto Backup / 换机只备份一份有界的用户数据快照；XML 白名单永远不直接开放运行目录或原始 DB。
 * 设置按键筛选；收藏/标签按相对路径、名称、大小、时长匹配，不复制本机 MediaStore ID。
 * 视频由系统相册/用户自行迁移；未到齐或尚未授权的媒体留待媒体库可读时匹配，不误贴到同 ID 新片。
 */
class WotaBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) = runBlocking {
        restoreMutex.withLock {
            // 恢复源与导出暂存共用白名单文件：先完成交接；失败必须在暂存/清理的 try 之前退出。
            consumeRestoreLocked(this@WotaBackupAgent)
            val staged = File(filesDir, PortableUserData.FILE_NAME)
            try {
                val settings = PortableUserData.settingsOf(getSharedPreferences(PortableUserData.PREFS_NAME, MODE_PRIVATE).all)
                val pending = readPending(this@WotaBackupAgent)?.tags.orEmpty()
                val tags = exportTags(this@WotaBackupAgent)
                writeSnapshot(staged, PortableUserData.Snapshot(settings, PortableUserData.mergeTags(pending, tags)))
                super.onFullBackup(data)
            } finally {
                // 整段与恢复共锁，媒体库重试不会把正在导出的快照误当恢复输入消费。
                AtomicFile(staged).delete()
            }
        }
    }

    override fun onRestoreFinished() {
        try {
            runBlocking { retryRestore(this@WotaBackupAgent) }
        } catch (_: Exception) {
            Diag.w("WotaBackup", "迁移输入尚未交接，保留供启动、媒体库或备份前重试")
        }
    }

    companion object {
        private val restoreMutex = Mutex()

        /** 启动预热、系统恢复回调共用；只交接有界快照，不查媒体库或创建 Room。 */
        internal suspend fun retryRestore(context: Context) = withContext(Dispatchers.IO) {
            restoreMutex.withLock { consumeRestoreLocked(context) }
        }

        /** 调用方持有 restoreMutex；合法源仅在两处持久化都成功后消费，任何 IO 失败均留源。 */
        private fun consumeRestoreLocked(context: Context) {
            val file = File(context.filesDir, PortableUserData.FILE_NAME)
            if (!file.exists() && !File(file.path + ".bak").exists()) return
            val atomic = AtomicFile(file)
            val snapshot = try {
                atomic.openRead().use { PortableUserData.read(it) }
            } catch (_: PortableUserData.InvalidSnapshotException) {
                // 已完整读到、但格式/版本无效的输入不是可重试 IO；不触碰旧 prefs/pending。
                atomic.delete()
                Diag.w("WotaBackup", "迁移快照格式无效，已丢弃损坏输入")
                return
            }
            val prefs = context.getSharedPreferences(PortableUserData.PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            PortableUserData.settingsToRestore(snapshot.settings, prefs.all).forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                }
            }
            // commit(false) 也可能已改内存；即使重试时没有缺键，仍提交以确保真正落盘。
            check(editor.commit()) { "迁移设置落盘失败" }
            writePending(context, PortableUserData.mergeTags(readPending(context)?.tags.orEmpty(), snapshot.tags))
            atomic.delete()
        }

        /** 媒体库查询在 IO 线程调用；仅返回本次成功重绑定的标签，不改 Room schema 或原有标签。 */
        internal suspend fun restoreTags(context: Context, clips: List<VideoClip>): List<MediaTag> =
            withContext(Dispatchers.IO) {
                restoreMutex.withLock {
                    // 权限/媒体到齐后的实际查询也重试交接，不只读取此前成功落盘的 pending。
                    consumeRestoreLocked(context)
                    val snapshot = readPending(context) ?: return@withLock emptyList()
                    // 一次建索引，匹配 O(媒体数 + 标签数)；指纹不完整/跨卷同名多候选均不恢复。
                    val byKey = PortableUserData.uniqueCandidates(clips.filter { it.id > 0 && !it.isTrashed }) {
                        it.portableKey()
                    }
                    val restored = arrayListOf<MediaTag>()
                    val remaining = arrayListOf<PortableUserData.Tag>()
                    for (tag in snapshot.tags) {
                        val clip = byKey[tag.media]
                        if (clip == null) {
                            remaining += tag
                            continue
                        }
                        val value = portableTag(tag.value) ?: continue
                        restored += MediaTag(mediaId = clip.id, path = clip.dataPath ?: clip.uri.toString(),
                            tag = value, createdAt = tag.createdAt)
                    }
                    if (remaining.size == snapshot.tags.size) return@withLock emptyList()
                    // Room 的发射会取消旧 flatMapLatest；落库+消费 pending 必须完整收尾，不能插一条就重试一轮。
                    withContext(NonCancellable) {
                        val db = WotaDb.get(context)
                        val dao = db.tagDao()
                        db.withTransaction {
                            val local = dao.observeAll().first().groupBy { it.mediaId }
                                .mapValues { (_, tags) -> tags.map { it.tag }.toSet() }
                            restored.forEach { row -> if (row.tag !in local[row.mediaId].orEmpty()) dao.insert(row) }
                        }
                        writePending(context, remaining)
                    }
                    restored
                }
            }

        private fun VideoClip.portableKey() = PortableUserData.MediaKey(relativePath, name, sizeBytes, durationMs)
        private fun portableTag(value: String): String? = if (value == TagSpace.LIKE) value else TagSpace.normalize(value)
        private fun pendingFile(context: Context) = File(context.noBackupFilesDir, PortableUserData.FILE_NAME)

        private fun readPending(context: Context): PortableUserData.Snapshot? {
            val file = pendingFile(context)
            if (!file.exists() && !File(file.path + ".bak").exists()) return null
            return AtomicFile(file).openRead().use { PortableUserData.read(it) }
        }

        private fun writePending(context: Context, tags: List<PortableUserData.Tag>) {
            val file = pendingFile(context)
            if (tags.isEmpty()) AtomicFile(file).delete()
            else writeSnapshot(file, PortableUserData.Snapshot(emptyMap(), tags))
        }

        private fun writeSnapshot(file: File, snapshot: PortableUserData.Snapshot) {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                PortableUserData.write(snapshot, output)
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                throw e
            }
        }

        @Suppress("DEPRECATION") // DATA 仅用于确认旧 tag 仍属于同一文件；不会写入迁移快照。
        private suspend fun exportTags(context: Context): List<PortableUserData.Tag> {
            val tags = WotaDb.get(context).tagDao().observeAll().first()
            val out = arrayListOf<PortableUserData.Tag>()
            val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION)
            for ((id, rows) in tags.groupBy { it.mediaId }) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                try {
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        if (!c.moveToFirst() || c.isNull(0)) return@use
                        val key = PortableUserData.MediaKey(c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                            c.getLong(3), c.getLong(4))
                        if (!key.valid()) return@use
                        rows.filter { it.path == c.getString(0) }.forEach { row ->
                            portableTag(row.tag)?.let { out += PortableUserData.Tag(key, it, row.createdAt) }
                        }
                    }
                } catch (e: SecurityException) {
                    // 未授予/部分授权只导出可确认身份的收藏，不把不可读内容的本机 ID 当迁移身份。
                    Diag.w("WotaBackup", "部分收藏当前不可读，未纳入迁移快照")
                }
            }
            return out
        }
    }
}

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32

/**
 * Auto Backup / 换机只备份一份有界的用户数据快照；XML 白名单永远不直接开放运行目录或原始 DB。
 * 设置按键筛选；收藏/标签按相对路径、名称、大小、时长匹配，不复制本机 MediaStore ID。
 * 视频由系统相册/用户自行迁移；未到齐或尚未授权的媒体留待媒体库可读时匹配，不误贴到同 ID 新片。
 *
 * 2026-11-19 的持久化边界（R14/R15/Y39/Y40/Y41）：
 * - 恢复源只有在设置和新 pending 都**回读确认**后才消费；发布失败/容量合并不下时保留源。
 * - 合并超限不再让媒体库重试永久失败：旧 pending 仍先匹配消化，源留待下一轮再交接。
 * - 导出先写 `.staging` 并落一个带指纹的事务标记，再发布为白名单文件；进程若在暂存后退出，
 *   下次启动按标记丢弃本机导出，绝不把本机标签当系统恢复输入。
 * - 部分授权/查询失败的 Room 行用本机导出账本保留最后已确认的便携指纹（Y40）。
 */
class WotaBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit
    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onFullBackup(data: FullBackupDataOutput) = runBlocking {
        restoreMutex.withLock {
            // 先交接系统恢复源；交接不成功（容量合并不下/暂存失败）绝不覆盖源文件。
            if (!consumeRestoreLocked(this@WotaBackupAgent)) {
                Diag.w("WotaBackup", "恢复源尚未交接，跳过本轮备份并保留源供后续重试")
                return@withLock
            }
            var published = false
            try {
                val settings = PortableUserData.settingsOf(getSharedPreferences(PortableUserData.PREFS_NAME, MODE_PRIVATE).all)
                val pending = readPending(this@WotaBackupAgent)?.tags.orEmpty()
                val tags = exportTags(this@WotaBackupAgent)
                val snapshot = try {
                    PortableUserData.Snapshot(settings, PortableUserData.mergeTags(pending, tags))
                } catch (e: IllegalArgumentException) {
                    Diag.w("WotaBackup", "本轮导出合并超过上限，保留旧备份并等待标签消化", e)
                    return@withLock
                }
                if (!stageForExport(this@WotaBackupAgent, snapshot)) {
                    Diag.w("WotaBackup", "导出暂存未确认发布，跳过本轮备份")
                    return@withLock
                }
                published = true
                super.onFullBackup(data)
            } finally {
                // 整段与恢复共锁；只有本轮暂存确实发布后才删白名单文件，
                // 绝不在暂存失败时误删可能存在的系统恢复输入。
                cleanupExport(this@WotaBackupAgent, published)
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
        internal const val EXPORT_STATE_NAME = "wota_user_backup.export"
        internal const val EXPORT_STAGING_NAME = "wota_user_backup.bin.staging"
        internal const val LEDGER_NAME = "wota_export_ledger.bin"

        private val restoreMutex = Mutex()

        /** 启动预热、系统恢复回调共用；只交接有界快照，不查媒体库或创建 Room。 */
        internal suspend fun retryRestore(context: Context) = withContext(Dispatchers.IO) {
            restoreMutex.withLock { consumeRestoreLocked(context) }
        }

        /**
         * 调用方持有 restoreMutex；合法源仅在两处持久化都成功后消费，任何 IO 失败均留源。
         * @return false = 设置已提交，但 pending 尚未交接（容量/发布失败）；源必须保留。
         */
        private fun consumeRestoreLocked(context: Context): Boolean {
            discardInterruptedExportLocked(context)
            val file = File(context.filesDir, PortableUserData.FILE_NAME)
            if (!file.exists() && !File(file.path + ".bak").exists()) return true
            val atomic = AtomicFile(file)
            val snapshot = try {
                atomic.openRead().use { PortableUserData.read(it) }
            } catch (_: PortableUserData.InvalidSnapshotException) {
                // 已完整读到、但格式/版本无效的输入不是可重试 IO；不触碰旧 prefs/pending。
                atomic.delete()
                Diag.w("WotaBackup", "迁移快照格式无效，已丢弃损坏输入")
                return true
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
            val oldTags = readPending(context)?.tags.orEmpty()
            val merged = try {
                PortableUserData.mergeTags(oldTags, snapshot.tags)
            } catch (_: IllegalArgumentException) {
                // R14：两份各自合法的 6000 条快照合并超 10,000 条上限。不能整体交接，
                // 但也不能在这里抛——restoreTags 每次重试都会先走本函数，抛错会让旧 pending
                // 永远没机会消化。下面改为「旧 pending 原样保留 + 新标签按余量分批交接」。
                null
            }
            if (merged != null) {
                if (!writePending(context, merged)) {
                    Diag.w("WotaBackup", "待匹配快照未确认发布，保留恢复源供重试")
                    return false
                }
                atomic.delete()
                return true
            }
            val (accepted, remaining) = PortableUserData.handoff(oldTags, snapshot.tags)
            if (accepted.isEmpty()) {
                // 旧 pending 已占满上限：先让媒体库把旧条目消化掉，源保持原样。
                Diag.w("WotaBackup", "旧待匹配已占满上限，保留恢复源等待旧条目消化")
                return false
            }
            val fitted = fitPending(context, oldTags, accepted) ?: run {
                Diag.w("WotaBackup", "分批交接写入失败，保留恢复源供重试")
                return false
            }
            val leftovers = accepted.drop(fitted.size) + remaining
            if (leftovers.isEmpty()) {
                atomic.delete()
                return true
            }
            // 已接手的标签已确认落盘；未接手的写回恢复源，下一轮旧 pending 缩小后继续。
            if (!writeSnapshot(file, PortableUserData.Snapshot(emptyMap(), leftovers))) {
                Diag.w("WotaBackup", "未交接标签回写恢复源失败，保留原源供重试")
                return false
            }
            return false
        }

        /**
         * 在 10,000 条 / 4MiB 双帽内找最大的可发布前缀；先试满额，字节超帽再二分。
         * 任一次 [writePending] 成功即已回读确认，失败的前缀不会覆盖上一次成功内容。
         */
        private fun fitPending(
            context: Context,
            existing: List<PortableUserData.Tag>,
            accepted: List<PortableUserData.Tag>
        ): List<PortableUserData.Tag>? {
            if (writePending(context, existing + accepted)) return accepted
            var low = 0
            var high = accepted.size - 1
            var best: List<PortableUserData.Tag>? = null
            while (low <= high) {
                val mid = (low + high) / 2
                val candidate = accepted.subList(0, mid)
                if (writePending(context, existing + candidate)) {
                    best = candidate
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return best
        }

        /**
         * 媒体库查询在 IO 线程调用；仅返回本次成功重绑定的标签，不改 Room schema 或原有标签。
         * 2 参重载供既有调用方/单测按「完整可见」处理；MediaRepo 会传实际权限态。
         */
        internal suspend fun restoreTags(context: Context, clips: List<VideoClip>): List<MediaTag> =
            restoreTags(context, clips, visibilityComplete = true)

        internal suspend fun restoreTags(
            context: Context,
            clips: List<VideoClip>,
            visibilityComplete: Boolean
        ): List<MediaTag> = withContext(Dispatchers.IO) {
            restoreMutex.withLock {
                // 权限/媒体到齐后的实际查询也重试交接，不只读取此前成功落盘的 pending。
                // 容量超限时交接返回 false，但旧 pending 仍继续匹配（R14 不能死锁）。
                consumeRestoreLocked(context)
                val snapshot = readPending(context) ?: return@withLock emptyList()
                // Y39：部分授权/卷不可见时，同一指纹可能还有一条看不见的候选；
                // 本次可见集合不足以消除歧义，全部保留待匹配，绝不消费 pending。
                if (!visibilityComplete) return@withLock emptyList()
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
                        restored.forEach { row ->
                            // Y39：mediaId+tag 相同还不够，既有行必须属于同一路径才算已恢复。
                            // ID 被复用时旧行会在这里先删后插，而不是被误判跳过；表上没有
                            // (mediaId,tag) 唯一约束，单靠 @Insert(REPLACE) 会留下旧路径脏行。
                            val sameMedia = local[row.mediaId].orEmpty()
                            if (sameMedia.any { it.tag == row.tag && it.path == row.path }) {
                                return@forEach
                            }
                            if (sameMedia.any { it.tag == row.tag }) dao.deleteOne(row.mediaId, row.tag)
                            dao.insert(row)
                        }
                    }
                    writePending(context, remaining)
                }
                restored
            }
        }

        /**
         * 导出暂存 + 发布（Y41）：先写独立 `.staging` 并落指纹标记，确认发布后才进入白名单路径。
         * 进程若在暂存落盘后退出，启动时 [discardInterruptedExportLocked] 会按指纹识别并丢弃，
         * 不会把本机标签当成系统恢复输入。
         */
        internal fun stageForExport(context: Context, snapshot: PortableUserData.Snapshot): Boolean {
            val staging = File(context.filesDir, EXPORT_STAGING_NAME)
            val target = File(context.filesDir, PortableUserData.FILE_NAME)
            val state = File(context.filesDir, EXPORT_STATE_NAME)
            File(staging.path + ".new").delete()
            // 正常流程开始前恢复源已消费；这里再遇既存文件说明状态异常，绝不覆盖。
            if (target.exists() || File(target.path + ".bak").exists()) return false
            if (!writeSnapshot(staging, snapshot)) return false
            val fingerprint = fingerprintOf(staging) ?: run {
                staging.delete()
                return false
            }
            if (!writeFingerprint(state, fingerprint)) {
                staging.delete()
                return false
            }
            if (!staging.renameTo(target)) {
                staging.delete()
                state.delete()
                return false
            }
            if (fingerprintOf(target) != fingerprint) {
                state.delete()
                AtomicFile(target).delete()
                return false
            }
            return true
        }

        private fun cleanupExport(context: Context, published: Boolean) {
            if (published) AtomicFile(File(context.filesDir, PortableUserData.FILE_NAME)).delete()
            for (name in listOf(EXPORT_STAGING_NAME, EXPORT_STATE_NAME)) {
                val file = File(context.filesDir, name)
                file.delete()
                File(file.path + ".new").delete()
            }
        }

        /** 只有指纹与标记一致的导出暂存才丢弃；不匹配的文件按真正的系统恢复源保留。 */
        private fun discardInterruptedExportLocked(context: Context) {
            val staging = File(context.filesDir, EXPORT_STAGING_NAME)
            val state = File(context.filesDir, EXPORT_STATE_NAME)
            if (!state.exists()) {
                staging.delete()
                File(staging.path + ".new").delete()
                return
            }
            val expected = readFingerprint(state)
            val target = File(context.filesDir, PortableUserData.FILE_NAME)
            val ours = expected != null && target.isFile && fingerprintOf(target) == expected
            if (ours) {
                AtomicFile(target).delete()
                Diag.w("WotaBackup", "丢弃上次进程中断留下的导出暂存，不当作恢复输入")
            }
            staging.delete()
            File(staging.path + ".new").delete()
            state.delete()
            File(state.path + ".new").delete()
        }

        private data class Fingerprint(val size: Long, val crc: Long)

        private fun fingerprintOf(file: File): Fingerprint? = try {
            val crc = CRC32()
            var size = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                    size += n
                }
            }
            Fingerprint(size, crc.value)
        } catch (e: Exception) {
            null
        }

        private fun writeFingerprint(file: File, fingerprint: Fingerprint): Boolean {
            val text = "${fingerprint.size}:${fingerprint.crc}"
            val atomic = AtomicFile(file)
            val output = try {
                atomic.startWrite()
            } catch (e: Exception) {
                return false
            }
            try {
                output.write(text.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                return false
            }
            return try {
                atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) == text }
            } catch (e: Exception) {
                false
            }
        }

        private fun readFingerprint(file: File): Fingerprint? = try {
            if (!file.exists()) null else {
                val parts = file.readText().split(':')
                if (parts.size == 2) Fingerprint(parts[0].toLong(), parts[1].toLong()) else null
            }
        } catch (e: Exception) {
            null
        }

        private fun VideoClip.portableKey() = PortableUserData.MediaKey(relativePath, name, sizeBytes, durationMs)
        private fun portableTag(value: String): String? = if (value == TagSpace.LIKE) value else TagSpace.normalize(value)
        private fun pendingFile(context: Context) = File(context.noBackupFilesDir, PortableUserData.FILE_NAME)
        private fun ledgerFile(context: Context) = File(context.noBackupFilesDir, LEDGER_NAME)

        private fun readPending(context: Context): PortableUserData.Snapshot? {
            val file = pendingFile(context)
            if (!file.exists() && !File(file.path + ".bak").exists()) return null
            val atomic = AtomicFile(file)
            return try {
                atomic.openRead().use { PortableUserData.read(it) }
            } catch (e: PortableUserData.InvalidSnapshotException) {
                // pending 只是可再生成的待匹配缓存；格式坏不可恢复，删掉避免每次备份/媒体加载都失败。
                // 真实 IO 异常不在这里吞：调用方保留源，等存储恢复后重试。
                atomic.delete()
                Diag.w("WotaBackup", "待匹配快照格式无效，已丢弃派生缓存")
                null
            }
        }

        /** @return true = 新 pending 已发布（或已确认清空）；false = 保留旧 pending/恢复源。 */
        private fun writePending(context: Context, tags: List<PortableUserData.Tag>): Boolean {
            val file = pendingFile(context)
            if (tags.isEmpty()) {
                AtomicFile(file).delete()
                return true
            }
            return writeSnapshot(file, PortableUserData.Snapshot(emptyMap(), tags))
        }

        /**
         * R15：`AtomicFile.finishWrite()` 没有失败回执，平台实现可能只记日志。
         * 写完必须回读并逐项比对归一化快照，确认新内容真的发布后才允许调用方消费源。
         */
        private fun writeSnapshot(file: File, snapshot: PortableUserData.Snapshot): Boolean {
            val atomic = AtomicFile(file)
            val output = try {
                atomic.startWrite()
            } catch (e: Exception) {
                Diag.w("WotaBackup", "快照暂存打开失败，保留旧文件", e)
                return false
            }
            try {
                PortableUserData.write(snapshot, output)
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                Diag.w("WotaBackup", "快照写入失败，保留旧文件", e)
                return false
            }
            return try {
                val published = atomic.openRead().use { PortableUserData.read(it) }
                val expected = PortableUserData.normalized(snapshot)
                if (published == expected) true else {
                    Diag.w("WotaBackup", "快照回读与本次提交不一致，视为未发布")
                    false
                }
            } catch (e: Exception) {
                Diag.w("WotaBackup", "快照发布后回读失败，视为未发布", e)
                false
            }
        }

        @Suppress("DEPRECATION") // DATA 仅用于确认旧 tag 仍属于同一文件；不会写入迁移快照。
        private suspend fun exportTags(context: Context): List<PortableUserData.Tag> {
            val tags = WotaDb.get(context).tagDao().observeAll().first()
            val previous = readLedger(context).associateBy { Triple(it.mediaId, it.path, it.tag) }
            val out = arrayListOf<PortableUserData.Tag>()
            val next = arrayListOf<PortableUserData.ExportEntry>()
            val nextKeys = HashSet<Triple<Long, String, String>>()
            val projection = arrayOf(MediaStore.Video.Media.DATA, MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION)
            for ((id, rows) in tags.groupBy { it.mediaId }) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                var cursorPath: String? = null
                var key: PortableUserData.MediaKey? = null
                try {
                    context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                        if (!c.moveToFirst() || c.isNull(0)) return@use
                        val candidate = PortableUserData.MediaKey(c.getString(1).orEmpty(), c.getString(2).orEmpty(),
                            c.getLong(3), c.getLong(4))
                        if (candidate.valid()) {
                            cursorPath = c.getString(0)
                            key = candidate
                        }
                    }
                } catch (e: SecurityException) {
                    // 未授予/部分授权：本轮算不出指纹，下面用导出账本保留上次已确认的指纹。
                    Diag.w("WotaBackup", "部分收藏当前不可读，改用上次已确认的迁移指纹")
                }
                val resolvedKey = key
                val resolvedPath = cursorPath
                if (resolvedKey != null && resolvedPath != null) {
                    rows.filter { it.path == resolvedPath }.forEach { row ->
                        val value = portableTag(row.tag) ?: return@forEach
                        out += PortableUserData.Tag(resolvedKey, value, row.createdAt)
                        val localKey = Triple(id, row.path, value)
                        if (nextKeys.add(localKey)) {
                            next += PortableUserData.ExportEntry(id, row.path, value, resolvedKey, row.createdAt)
                        }
                    }
                } else {
                    // Y40：部分授权/查询缺失时，不可核验的本机标签不能从下一份备份里消失。
                    // 只保留仍对应当前 Room 行的旧指纹；删除的行不会在这里复活。
                    rows.forEach { row ->
                        val value = portableTag(row.tag) ?: return@forEach
                        val localKey = Triple(id, row.path, value)
                        val confirmed = previous[localKey] ?: return@forEach
                        out += PortableUserData.Tag(confirmed.media, value, row.createdAt)
                        if (nextKeys.add(localKey)) next += confirmed.copy(createdAt = row.createdAt)
                    }
                }
            }
            writeLedger(context, next)
            return out.distinctBy { it.media to it.value }
        }

        private fun readLedger(context: Context): List<PortableUserData.ExportEntry> {
            val file = ledgerFile(context)
            if (!file.exists() && !File(file.path + ".bak").exists()) return emptyList()
            return try {
                AtomicFile(file).openRead().use { PortableUserData.readExportLedger(it) }
            } catch (e: Exception) {
                // 账本只是「上次已确认指纹」的派生缓存；坏账本不能阻断备份本身。
                Diag.w("WotaBackup", "导出账本不可读，按空账本继续", e)
                emptyList()
            }
        }

        private fun writeLedger(context: Context, entries: List<PortableUserData.ExportEntry>): Boolean {
            val file = ledgerFile(context)
            if (entries.isEmpty()) {
                AtomicFile(file).delete()
                return true
            }
            val atomic = AtomicFile(file)
            val output = try {
                atomic.startWrite()
            } catch (e: Exception) {
                return false
            }
            try {
                PortableUserData.writeExportLedger(entries, output)
                atomic.finishWrite(output)
            } catch (e: Exception) {
                atomic.failWrite(output)
                Diag.w("WotaBackup", "导出账本写入失败", e)
                return false
            }
            return try {
                val published = atomic.openRead().use { PortableUserData.readExportLedger(it) }
                published == PortableUserData.normalizedLedger(entries)
            } catch (e: Exception) {
                false
            }
        }
    }
}

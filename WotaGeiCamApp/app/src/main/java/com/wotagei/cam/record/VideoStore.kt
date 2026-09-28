package com.wotagei.cam.record

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import android.provider.MediaStore
import android.util.Log
import com.wotagei.cam.core.WotaTiers
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** `content://` 前缀判据：pending uri 走 FileDescriptor，其余走真实路径 */
private const val SCHEME_CONTENT = "content"

/**
 * 录像文件输出与入库（``、04 文件 §5）：
 *
 * - 命名：`VID_yyyyMMdd_HHmmss_%03d.mp4`（`Locale.US`，3 位随机后缀防同秒撞名）；
 * - 入库：MediaStore `IS_PENDING` 两段式——插入拿 pending uri（IS_PENDING=1）→ 写完 update 0；
 *   失败或废片必须 delete，否则相册留 0 字节幽灵条目；
 * - 全程不用 MediaScanner（pending 机制自带可见性）；
 * - 分段续写：本类给每一段取新 pending uri，引擎负责封段与重启；
 * - 空间门槛：`StatFs` 可用 < [WotaTiers.MIN_FREE_MB] 直接拒绝启动并给出原因码。
 */
class VideoStore(private val ctx: Context) {

    companion object {
        /** 成片目录（相对公共外部存储） */
        const val RELATIVE_PATH = "Movies/WotaGeiCam/Camera"
        const val MIME_MP4 = "video/mp4"
        private const val NAME_STAMP = "yyyyMMdd_HHmmss"
        private const val MB = 1024L * 1024L
    }

    /** 一次录制的分段归属标识（媒体库 Room 表用它把分段串成同一段落） */
    val seriesId: String = "S" + System.currentTimeMillis() + "_" + Random.nextInt(1000)

    private val resolver get() = ctx.contentResolver

    /** 成片文件名；每段用各自创建时刻，同秒碰撞由 3 位随机后缀吸收 */
    fun displayName(createdAtMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat(NAME_STAMP, Locale.US).format(Date(createdAtMs))
        return String.format(Locale.US, "VID_%s_%03d.mp4", stamp, Random.nextInt(1000))
    }

    /** 当前卷可用空间（MB）；取应用外部文件所在卷，避开已废弃的公共存储常量 */
    fun freeSpaceMb(): Long = try {
        val path = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        StatFs(path.absolutePath).availableBytes / MB
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "StatFs failed: ${e.message}")
        Long.MAX_VALUE // 探测失败不拦录制
    }

    /**
     * 录像前余量校验（``：可用 < 200MB 拒绝启动）。
     * @return null = 允许；非 null = 原因码 `LOW_STORAGE:可用MB`（UI 侧映射中文文案）
     */
    fun checkFreeSpace(): String? {
        val mb = freeSpaceMb()
        if (mb < WotaTiers.MIN_FREE_MB) {
            Log.i(TAG, "refuse record, free=${mb}MB need>=${WotaTiers.MIN_FREE_MB}MB")
            return "${RecordError.LOW_STORAGE}:$mb"
        }
        return null
    }

    /** 插入 pending 记录；失败返回 null（调用方按 [RecordError.NO_OUTPUT] 处理） */
    fun createPending(partIndex: Int): Uri? {
        checkFreeSpace()?.let {
            Log.e(TAG, "createPending rejected: $it")
            return null
        }
        val name = displayName()
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_MP4)
        }
        return try {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                Log.e(TAG, "insert returned null, part=$partIndex name=$name")
                null
            } else {
                Log.i(TAG, "pending part=$partIndex name=$name uri=$uri")
                uri
            }
        } catch (e: Exception) {
            Log.e(TAG, "insert failed: ${e.message}")
            null
        }
    }

    /** 写完清 pending 标志，相册立即可见（两段式的第二段，日志 "commit"） */
    fun commit(uri: Uri?): Boolean {
        if (uri == null) return false
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        return try {
            val n = resolver.update(uri, values, null, null)
            Log.i(TAG, "commit uri=$uri rows=$n")
            n > 0
        } catch (e: Exception) {
            Log.e(TAG, "commit failed: ${e.message}")
            false
        }
    }

    /** 失败/废片回收：删 pending 记录（content uri）或删真实文件（File sink） */
    fun discard(sink: OutputSink?) {
        if (sink == null) return
        try {
            when (sink) {
                is OutputSink.Pending -> {
                    val n = resolver.delete(sink.uri, null, null)
                    Log.i(TAG, "discard uri=${sink.uri} rows=$n")
                }
                is OutputSink.File ->
                    if (sink.f.exists() && !sink.f.delete()) Log.e(TAG, "discard file failed: ${sink.f.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "discard failed: ${e.message}")
        }
    }

    /** 产物大小：content uri 查 SIZE 列，File sink 直接 stat */
    fun sizeOf(sink: OutputSink?): Long = when (sink) {
        null -> 0L
        is OutputSink.Pending -> queryStr(sink.uri, MediaStore.MediaColumns.SIZE)?.toLongOrNull() ?: 0L
        is OutputSink.File -> if (sink.f.exists()) sink.f.length() else 0L
    }

    /** 真实路径（只用于回看兜底与调试；pending 项可能查不到，返回 null 即可） */
    fun pathOf(sink: OutputSink?): String? = when (sink) {
        null -> null
        is OutputSink.File -> sink.f.absolutePath
        is OutputSink.Pending -> queryStr(sink.uri, MediaStore.MediaColumns.DATA)
    }

    private fun queryStr(uri: Uri, column: String): String? {
        var c: Cursor? = null
        return try {
            c = resolver.query(uri, arrayOf(column), null, null, null)
            if (c != null && c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } catch (e: Exception) {
            Log.e(TAG, "query $column failed: ${e.message}")
            null
        } finally {
            c?.close()
        }
    }

    /**
     * 封一段：成功则清 IS_PENDING，废片则删记录，并回报本段元数据给引擎汇总。
     * @param keep false = 废片/失败（04 文件 §5.4）
     */
    fun seal(sink: OutputSink?, partIndex: Int, startMs: Long, durationMs: Long, keep: Boolean): VideoSegment {
        val bytes = sizeOf(sink)
        val path = pathOf(sink)
        val pending = sink as? OutputSink.Pending
        val committed = when {
            !keep -> true.also { discard(sink) }
            // File 输出不经 MediaStore，没有 IS_PENDING 可清，谈不上未登记
            pending == null -> true
            else -> {
                // 失败多半是 Provider 瞬时忙，立即重试一次；再失败只报不删——
                // 字节已经在盘上，这时候删记录就是把用户的成片丢掉
                val ok = commit(pending.uri) || commit(pending.uri)
                if (!ok) {
                    Log.e(TAG, "COMMIT_PENDING part=$partIndex uri=${pending.uri} 清 IS_PENDING 失败，相册可能看不到")
                }
                ok
            }
        }
        Log.i(
            TAG,
            "seal part=$partIndex keep=$keep bytes=$bytes dur=${durationMs}ms " +
                "uri=${pending?.uri} committed=$committed"
        )
        return VideoSegment(pending?.uri, path, partIndex, bytes, startMs, durationMs, committed)
    }
}

/**
 * 输出目标适配：`content://` 前缀 → `openAssetFileDescriptor("rw")` 取 FileDescriptor；
 * 其余 → 真实路径字符串。MediaRecorder 与 MediaMuxer 共用这一条判据，引擎层不碰入库细节。
 */
class OutputTarget private constructor(
    val sink: OutputSink,
    val path: String?,
    val fileDescriptor: FileDescriptor?,
    private val held: Closeable?
) {
    /** MediaRecorder 可用 fd 或 path；两者至少有其一 */
    val usable: Boolean get() = fileDescriptor != null || path != null

    val uri: Uri? get() = (sink as? OutputSink.Pending)?.uri

    /** 关输出句柄（MediaMuxer/MediaRecorder 释放之后再调） */
    fun close() {
        try {
            held?.close()
        } catch (e: Exception) {
            Log.i(TAG, "close output target: ${e.message}")
        }
    }

    companion object {
        /** 打不开返回 null（调用方按 [RecordError.NO_OUTPUT] 处理） */
        fun open(ctx: Context, sink: OutputSink): OutputTarget? = try {
            when (sink) {
                is OutputSink.Pending ->
                    if (sink.uri.scheme == SCHEME_CONTENT) {
                        val afd = ctx.contentResolver.openAssetFileDescriptor(sink.uri, "rw")
                        if (afd == null) {
                            Log.e(TAG, "openAssetFileDescriptor null: ${sink.uri}")
                            null
                        } else {
                            OutputTarget(sink, null, afd.fileDescriptor, afd)
                        }
                    } else {
                        val p = sink.uri.path
                        if (p == null) null else forPath(sink, File(p))
                    }
                is OutputSink.File -> forPath(sink, sink.f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "open sink failed: ${e.message}")
            null
        }

        private fun forPath(sink: OutputSink, f: File): OutputTarget? {
            val parent = f.parentFile
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                Log.e(TAG, "mkdirs failed: ${parent.path}")
                return null
            }
            return OutputTarget(sink, f.absolutePath, null, null)
        }
    }
}

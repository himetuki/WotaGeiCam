package com.wotagei.cam.media

import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.app.RemoteAction
import android.content.ContentValues
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.wotagei.cam.core.WotaStorage
import com.wotagei.cam.R
import com.wotagei.cam.ui.findActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 媒体库写操作日志锚点（launchOp 的失败兜底记日志用） */
private const val TAG = "WotaMedia"

/**
 * 媒体库写操作（收藏 / tag / 回收站 / 删除 / 重命名 / 分享入口）。
 *
 * 删除两条路都实现：
 * - API 30+：`MediaStore.createTrashRequest` / `createDeleteRequest` 批量授权；
 * - API 29：`resolver.delete/update` 抛 [RecoverableSecurityException]，取 `RemoteAction` 的 IntentSender 走授权回路。
 *
 * 授权必须由 Activity 侧起 Result，所以方法返回 [MediaOp]，由 UI 门面 [MediaOps] 启动 IntentSender 并回填结果。
 */
class MediaActions private constructor(
    private val app: Context,
    private val repo: MediaRepo,
    private val scope: CoroutineScope
) {

    private val dao get() = repo.db().tagDao()
    private val trashDao get() = repo.db().trashDao()
    private val seriesDao get() = repo.db().seriesDao()

    /** 授权后续链路里再次冒出的请求（批量删到第 N 个才需要授权）经此回调重投 UI */
    var opSink: ((MediaOp) -> Unit)? = null

    // region 收藏 / tag

    suspend fun setLike(clip: VideoClip, liked: Boolean): MediaOp {
        val path = clip.pathKey()
        withContext(Dispatchers.IO) {
            if (liked) dao.insert(MediaTag(mediaId = clip.id, path = path, tag = TagSpace.LIKE, createdAt = System.currentTimeMillis()))
            else dao.deleteOne(clip.id, TagSpace.LIKE)
        }
        repo.invalidate()
        return MediaOp.Done
    }

    suspend fun addTag(clip: VideoClip, rawTag: String): MediaOp {
        val tag = TagSpace.normalize(rawTag) ?: return MediaOp.Message(R.string.media_tag_invalid)
        withContext(Dispatchers.IO) {
            dao.insert(MediaTag(mediaId = clip.id, path = clip.pathKey(), tag = tag, createdAt = System.currentTimeMillis()))
        }
        repo.invalidate()
        return MediaOp.Done
    }

    suspend fun removeTag(clip: VideoClip, tag: String): MediaOp {
        withContext(Dispatchers.IO) { dao.deleteOne(clip.id, tag) }
        repo.invalidate()
        return MediaOp.Done
    }

    // endregion

    // region 重命名

    suspend fun rename(clip: VideoClip, rawName: String): MediaOp {
        val name = rawName.trim()
        if (name.isEmpty()) return MediaOp.Message(R.string.media_name_empty)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, ensureSuffix(name, clip.name))
        }
        return try {
            val oldPath = clip.dataPath
            val rows = withContext(Dispatchers.IO) { app.contentResolver.update(clip.uri, values, null, null) }
            if (rows > 0) {
                repo.invalidate()
                // MediaStore 改 DISPLAY_NAME 会连底层文件一起改名：sidecar（被抽帧位次档案）
                // 必须跟着搬，否则改名后位次档案脱钩（旧名孤儿 + 新片无档案）。
                // 撞名时 MediaStore 自行加序号后缀且 update 仍报成功，请求名不可信——
                // 与录制路（CameraScreen.stopInternal）同口径：回查真实名，回查失败退回请求名
                if (oldPath != null) withContext(Dispatchers.IO) {
                    val actualName = queryDisplayName(clip.uri)
                        ?: values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
                    val dir = oldPath.substringBeforeLast('/', "")
                    val newPath = if (dir.isEmpty()) actualName else "$dir/$actualName"
                    com.wotagei.cam.record.ArcDropLog.renameFor(oldPath, newPath)
                }
                MediaOp.Done
            } else {
                MediaOp.Message(deniedRes(R.string.media_rename_failed))
            }
        } catch (se: SecurityException) {
            // 改动别人的文件只能走系统授权框，那个框的语言与横屏布局都不归我们管，用户明确不要它：报失败即可
            MediaOp.Message(deniedRes(R.string.media_rename_failed))
        }
    }

    private fun ensureSuffix(name: String, oldName: String): String {
        val ext = oldName.substringAfterLast('.', "")
        return if (ext.isEmpty() || name.endsWith(".$ext", ignoreCase = true)) name else "$name.$ext"
    }

    /** 改名后回查真实 DISPLAY_NAME：MediaStore 撞名会自行加序号后缀，拼装名与落盘名可能分叉 */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull()

    // endregion

    // region 回收站

    /**
     * 进回收站（需求「回收站」）。
     * API 30+ 优先**直写 `IS_TRASHED`**：这些行是本应用经 MediaStore 建的、owner 就是自己，
     * 直写不会弹系统授权框（那个框的语言与横屏布局都不归我们管，真机反馈英文且按钮出界）。
     * 只有 owner 不是自己、且没拿到「所有文件访问」时才直写失败 —— 那时报我们自己的文案，
     * 让他去系统设置开权限，不再退回系统那个英文授权框（框已从代码里彻底移除）。
     * API 29 或系统不支持时退化为「本地 trash_item 记录 + 从常规页签隐藏」，还原语义成立且不删文件。
     */
    suspend fun moveToTrash(clips: List<VideoClip>): MediaOp {
        if (clips.isEmpty()) return MediaOp.Done
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!trashDirect(clips, toTrash = true)) return MediaOp.Message(deniedRes(R.string.media_trash_failed))
            recordTrashRows(clips)
            return MediaOp.Done
        }
        recordTrashRows(clips)
        return MediaOp.Done
    }

    suspend fun restoreFromTrash(clips: List<VideoClip>): MediaOp {
        if (clips.isEmpty()) return MediaOp.Done
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!trashDirect(clips, toTrash = false)) return MediaOp.Message(deniedRes(R.string.media_restore_failed))
            clearTrashRows(clips)
            return MediaOp.Done
        }
        clearTrashRows(clips)
        return MediaOp.Done
    }

    /** 逐行直写 IS_TRASHED；任一行被拒就整体返回 false，交给调用方走系统授权框 */
    private suspend fun trashDirect(clips: List<VideoClip>, toTrash: Boolean): Boolean {
        val done = writeTrashed(clips, toTrash)
        if (done.size == clips.size) return true
        // 半途失败必须先回滚已改的行：调用方接下来会去弹系统授权框，用户一取消就留下
        // 「一部分已经进回收站、本地却没有 trash_item 记录」的状态，
        // 而 IS_TRASHED=1 的行不会出现在常规查询里 —— 文件在所有页签上都看不见
        if (done.isNotEmpty()) writeTrashed(done, !toTrash)
        return false
    }

    /** 顺序直写，返回已经改成功的行（遇到第一个失败就停，保证失败点是单一边界） */
    private suspend fun writeTrashed(clips: List<VideoClip>, toTrash: Boolean): List<VideoClip> =
        withContext(Dispatchers.IO) { clips.takeWhile { writeTrashedOne(it, toTrash) } }

    private fun writeTrashedOne(clip: VideoClip, toTrash: Boolean): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_TRASHED, if (toTrash) 1 else 0)
        }
        app.contentResolver.update(clip.uri, values, null, null) > 0
    }.getOrDefault(false)

    private suspend fun recordTrashRows(clips: List<VideoClip>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        clips.forEach {
            trashDao.insert(
                TrashItem(uri = it.uri.toString(), mediaId = it.id, name = it.name, deletedAt = now, origin = it.relativePath)
            )
        }
        repo.invalidate()
    }

    private suspend fun clearTrashRows(clips: List<VideoClip>) = withContext(Dispatchers.IO) {
        trashDao.removeAll(clips.map { it.uri.toString() })
        repo.invalidate()
    }

    // endregion

    // region 彻底删除

    /**
     * 真删。删完必须清 media_tag（MediaStore id 复用会把旧标签串到新视频上 —— ）。
     *
     * 时序定版（v30–v33 教训：删完 finish() = 感知闪退；列表先刷 = pager 越界崩溃）：
     * 删文件 → 250ms → pop 播放页 → 900ms → 刷列表。
     * 期间 [MediaRepo.holdListRefresh] 关闸，Room 清标签引发的列表重算不下发，保证刷新晚于出栈。
     */
    suspend fun deleteForever(clips: List<VideoClip>, pop: () -> Unit, refresh: () -> Unit): MediaOp {
        if (clips.isEmpty()) return MediaOp.Done
        // 一律直删，绝不借系统授权框：有「所有文件访问」时连别的 app 录的行也能静默删掉；
        // 没有就报我们自己的文案，让他去系统设置开一次（那个英文框已从代码里彻底移除）
        return deleteOneByOne(clips, pop, refresh)
    }

    /**
     * 逐个直删，删不掉的（缺「所有文件访问」去改别人录的行）计数后继续剩余项，全程不弹任何系统框。
     *
     * 收尾只清**真删掉的**那些行的 media_tag / series_part / 缩略图缓存：MediaStore `_ID` 会复用，
     * 漏清会把旧标签串到新视频上，删掉的文件也还会在相册里留一张旧缩略图。
     */
    private suspend fun deleteOneByOne(
        pending: List<VideoClip>,
        pop: () -> Unit,
        refresh: () -> Unit
    ): MediaOp {
        val done = ArrayList<VideoClip>()
        var failed = 0
        for (clip in pending) {
            val err: Exception? = withContext(Dispatchers.IO) {
                try {
                    // ContentResolver 没有单参 delete(Uri)（android.jar 只有 3 参旧签名与 API 30+ Bundle 版），
                    // minSdk 29 只能用 where=null/args=null 的整行删除
                    app.contentResolver.delete(clip.uri, null, null)
                    null
                } catch (se: SecurityException) {
                    se
                } catch (e: Exception) {
                    e
                }
            }
            if (err == null) {
                // 连带删被抽帧位次 sidecar（2026-10-03 定版：sidecar 随视频生命周期走，不留残留）
                clip.dataPath?.let { p ->
                    com.wotagei.cam.record.ArcDropLog.deleteFor(p)
                    // API 30+ 系统回收站会把底层文件改名成 .trashed-<ts>-<原名>（DATA 列跟着变），
                    // 而 sidecar 仍挂在原显示名下：按目录+原名补删一次，否则「回收站→彻底删除」
                    // 留下孤儿档案；非回收站项两者同路径，不进这支
                    if (clip.isTrashed) {
                        val dir = p.substringBeforeLast('/', "")
                        val originPath = if (dir.isEmpty()) clip.name else "$dir/${clip.name}"
                        if (originPath != p) com.wotagei.cam.record.ArcDropLog.deleteFor(originPath)
                    }
                }
                done += clip
                continue
            }

            // 删不掉就是缺「所有文件访问」去改别人录的行。绝不退回去弹系统那个英文框：
            // 计数后继续删剩余项，收尾统一按权限状态给一句我们自己的文案（deniedRes）
            failed++
        }
        finishDelete(done, pop, refresh)
        return if (failed > 0) MediaOp.Message(deniedRes(R.string.media_delete_failed)) else MediaOp.Done
    }

    /**
     * 删后的收尾编排（关闸 → 清 Room/缓存 → POP_DELAY → pop → REFRESH_DELAY → 开闸 → refresh）
     * 全部委托给顶层 [runDeleteFinish]：那段时序必须抗取消，理由见那边的长注释。
     * 抽成顶层还能让 JVM 单测直接驱动这段时序（MediaRepo 要 Context/Room，测试里造不出来）。
     */
    private suspend fun finishDelete(clips: List<VideoClip>, pop: () -> Unit, refresh: () -> Unit) {
        runDeleteFinish(
            clipDeleted = clips.isNotEmpty(),
            popDelayMs = POP_DELAY_MS,
            refreshDelayMs = REFRESH_DELAY_MS,
            hold = { repo.holdListRefresh() },
            cleanup = {
                withContext(Dispatchers.IO) {
                    val ids = clips.map { it.id }
                    dao.clearFor(ids)
                    seriesDao.clearFor(ids)
                    trashDao.removeAll(clips.map { it.uri.toString() })
                    clips.forEach { ThumbLoader.evict(it.uri) }
                }
            },
            pop = pop,
            release = { repo.releaseListRefresh() },
            refresh = refresh
        )
    }

    // endregion

    // region 授权回路工具

    /**
     * 分享（resolve 的逐项磁盘 stat 在 WotaShare.share 内部走 IO 线程，chooser 回主线程起）。
     * 改成挂起返回 MediaOp 后，调用方（MediaOps.share）才能借 launchOp 的统一兜底：失败横幅 + 取消透传。
     */
    suspend fun share(clips: List<VideoClip>): MediaOp {
        val res = WotaShare.share(app, clips)
        return if (res == null) MediaOp.Done else MediaOp.Message(res)
    }

    /** 直写被拒就是缺「所有文件访问」：让他去系统设置开一次，而不是每回都弹那个英文框 */
    private fun deniedRes(fallback: Int): Int =
        if (WotaStorage.hasAllFilesAccess()) fallback else R.string.media_need_all_files

    // endregion

    companion object {
        private const val POP_DELAY_MS = 250L
        private const val REFRESH_DELAY_MS = 900L

        // 单例只持 applicationContext（见下方 get()），不会把 Activity 留在进程里
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: MediaActions? = null

        fun get(context: Context): MediaActions {
            val app = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: MediaActions(app, MediaRepo.get(app), CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
                    .also { instance = it }
            }
        }
    }
}

/**
 * 彻底删除的收尾时序：
 * 关闸 →（真删掉了才）清 media_tag/series_part/trash_item/缩略图缓存 → POP_DELAY → pop 播放页出栈
 * → REFRESH_DELAY → 开闸（内含 invalidate）→ refresh()。
 *
 * 抽成顶层挂起函数只为两件事：JVM 单测能直接驱动这段时序（[MediaRepo] 要 Context + Room，
 * 单测里造不出来），以及让「必须抗取消」这件事只在一个地方成立。
 *
 * **为什么整段必须 [NonCancellable]**（2026-10-02 真机缺陷，两轮录像-删除周期均复现）：
 * 这条链跑在**调用方屏的组合作用域**里 —— [MediaOps.launchOp] 用构造时传入的 CoroutineScope，
 * 而 [rememberMediaOps] 给的正是调用屏自己的 `rememberCoroutineScope()`。播放器「彻底删除」时
 * `pop = onBack` 语义就是「离开播放页」：PlayerScreen 一离屏，组合作用域连同挂起的
 * `delay(REFRESH_DELAY_MS)` 一起被取消，取消点恰好落在开闸之前 ——
 * [MediaRepo.releaseListRefresh] 永不执行，[MediaRepo.refreshGate] 永远停在 true。
 * 闸门是进程级的 MutableStateFlow，`clips`/`clipById` 全走它：闸门卡死 ⇒ 媒体库「全部/收藏/
 * 回收站」全是空列表、切页签与滚动都不恢复、再点开别的视频也只剩转圈，**只能杀进程重进**
 * （重启后 13 条完整、文件无损，是闸门没开而不是数据丢了）。媒体库内批量删除能活下来纯属侥幸：
 * 它的 pop 只清多选态、屏不离场，组合作用域还活着，同一条时序才走得完。
 * 关闸-开闸本质是一次**与调用方生死无关**的仓库自愈动作，所以用 NonCancellable 兜住，
 * 不去指望调用方「恰好还没死」。
 *
 * 为什么这与上一轮「`remember(repo, scope, filter)` 消除每重组重开查询」不冲突：
 * 那一轮改的是 **Flow 实例的缓存键**（GalleryScreen.kt 189–190 行的 remember），让冷管道不随
 * 重组重建，省下的是「每重组取消并重开一轮 MediaStore + Room 查询」的 churn；这里一个字节都
 * 没碰那条键，改的是**同一条已 remember 的管道的生产者能不能活着走到开闸**。两者方向正交：
 * 前者压重组开销，后者保证仓库级闸门不会把生产者永久掐死。键不动 ⇒ 上轮收益原样保留，
 * 修掉的只是「闸门关了再也开不了」这种进程级锁死。也不需要用 MediaStore content observer
 * 兜底：列表刷新本来就由 [MediaRepo.invalidate] 版本号驱动，移入回收站/收藏/tag 全靠它，
 * 链路是通的，缺的只是删除收尾能活着走到 invalidate 那一步。
 */
internal suspend fun runDeleteFinish(
    clipDeleted: Boolean,
    popDelayMs: Long,
    refreshDelayMs: Long,
    hold: () -> Unit,
    cleanup: suspend () -> Unit,
    pop: () -> Unit,
    release: () -> Unit,
    refresh: () -> Unit
) {
    withContext(NonCancellable) {
        try {
            if (clipDeleted) {
                hold()
                cleanup()
            }
            delay(popDelayMs)
            runCatching(pop)
            delay(refreshDelayMs)
        } finally {
            // 开闸无条件执行：闸门卡在 true 的代价是媒体库永久空列表（杀进程才恢复），
            // 比让异常继续冒出去严重得多。pop/refresh 抛错也照旧吞掉保住收尾（与原实现一致）。
            release()
        }
        runCatching(refresh)
    }
}

/** 写操作结果：完成 / 需提示 / 需系统授权 */
sealed class MediaOp {
    object Done : MediaOp()
    data class Message(val textRes: Int) : MediaOp()
}

/**
 * UI 门面：非挂起方法，屏内直接调用即可。
 * 由 [rememberMediaOps] 创建。
 */
class MediaOps internal constructor(
    private val actions: MediaActions,
    private val app: Context,
    private val scope: CoroutineScope
) {

    /** 提示文案出口（string res id），屏里设成 Snackbar/横幅回调 */
    var onMessage: ((Int) -> Unit)? = null

    fun setLike(clip: VideoClip, liked: Boolean) = launchOp { actions.setLike(clip, liked) }
    fun addTag(clip: VideoClip, tag: String) = launchOp { actions.addTag(clip, tag) }
    fun removeTag(clip: VideoClip, tag: String) = launchOp { actions.removeTag(clip, tag) }
    fun rename(clip: VideoClip, name: String) = launchOp { actions.rename(clip, name) }
    fun moveToTrash(clips: List<VideoClip>) = launchOp { actions.moveToTrash(clips) }
    fun restoreFromTrash(clips: List<VideoClip>) = launchOp { actions.restoreFromTrash(clips) }
    fun deleteForever(clips: List<VideoClip>, pop: () -> Unit, refresh: () -> Unit = {}) =
        launchOp { actions.deleteForever(clips, pop, refresh) }

    /**
     * #46 定版：回收站态的行**不分享**。FileProvider 递出去的是真实路径，而系统回收站会把文件
     * 改名成 `.trashed-<ts>-<原名>`，接收端拿到的就是这个带前缀的怪名字（实测走 content Uri 更糟：
     * 接收方查 DISPLAY_NAME 得到 null）。所以这里把 trashed 项剔掉并提示先还原。
     *
     * 整个调用走 [launchOp]：share 内部要逐项 `file.exists()`（磁盘 stat），批量多选几十个时
     * 直接在组合期线程跑就是主线程 N 次 stat —— 线程切换在 WotaShare.share 内部完成，这里只借
     * launchOp 的统一兜底（失败记日志 + 横幅文案，取消原样透传）。
     */
    fun share(clips: List<VideoClip>) {
        if (clips.isEmpty()) return
        val shippable = clips.filter { !it.isTrashed }
        if (shippable.isEmpty()) {
            onMessage?.invoke(R.string.media_share_trashed)
            return
        }
        launchOp { actions.share(shippable) }
    }

    private fun launchOp(block: suspend () -> MediaOp) {
        scope.launch {
            // 协程里冒出的异常（盘满的 SQLiteFullException、非 SecurityException 的 IO 失败等）
            // 没人接就是进程崩溃（SupervisorJob 不改未捕获即崩）：这里统一兜底，失败记日志并给
            // 一条横幅文案。取消不算失败——离开屏幕的正常取消原样抛回，不弹"操作失败"。
            val op = runCatching { block() }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "media op failed: ${e.javaClass.simpleName}", e)
                MediaOp.Message(R.string.media_op_failed)
            }
            dispatch(op)
        }
    }

    /** 授权回路中途再冒出的请求也走同一入口（MediaActions.opSink） */
    internal fun dispatch(op: MediaOp) {
        when (op) {
            MediaOp.Done -> Unit
            is MediaOp.Message -> onMessage?.invoke(op.textRes)
        }
    }
}

/** 单例仓库：LocalContext 只能在组合期取，remember 的计算体里只放非 composable 的求值 */
@Composable
fun rememberMediaRepo(): MediaRepo {
    val app = LocalContext.current.applicationContext
    return remember(app) { MediaRepo.get(app) }
}

/** 单例写操作入口：同上，composable 取值一律提到 remember 之外 */
@Composable
fun rememberMediaActions(): MediaActions {
    val app = LocalContext.current.applicationContext
    return remember(app) { MediaActions.get(app) }
}

/**
 * 生成 [MediaOps]。
 *
 * 这里**不再**注册 StartIntentSenderForResult 授权启动器：写链路只碰本应用自己录的行，
 * 别人的文件由 `isOurs` 提前挡掉并给出我们自己的横幅文案，系统那个英文授权框永远没机会出现
 * （连带 §39 的「弹窗挂着时临时锁竖屏」也不再需要）。
 */
@Composable
fun rememberMediaOps(actions: MediaActions = rememberMediaActions()): MediaOps {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val ops = remember(actions, scope, app) { MediaOps(actions, app, scope) }
    DisposableEffect(ops) {
        actions.opSink = { op -> ops.dispatch(op) }
        onDispose { actions.opSink = null }
    }
    return ops
}

private fun VideoClip.pathKey(): String = dataPath ?: uri.toString()

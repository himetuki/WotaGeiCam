package com.wotagei.cam.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext

/**
 * 一条彻底删除目标的**身份对**：MediaStore `_ID` + 该条目的路径/URI 键。
 *
 * 为什么不能只带 `_ID`：外部 app 删片后 MediaStore 会复用同一个 `_ID`。若清账只按裸 ID
 * 删 `media_tag` / `series_part`，复用后的新片会被当成老片清掉。这里把列表快照时的
 * `(mediaId, pathKey)` 一起带下去，清账 SQL 必须两个键都匹配；匹配不上宁可留下孤儿行，
 * 也绝不误伤同 ID 的另一条视频。
 *
 * [pathKey] 与 [MediaTag.path] 的写入口径逐字相同（`dataPath ?: uri`）；[uri] 是系列成员
 * 与回收站行的精确键。
 */
internal data class DeleteTarget(
    val mediaId: Long,
    val uri: String,
    val pathKey: String,
    val dataPath: String?,
    val name: String,
    val isTrashed: Boolean
)

/** 单行物理删除的结果：`rows` 是 ContentResolver 报的影响行数（不是"没抛异常"） */
internal data class RowDeleteResult(
    val rows: Int,
    val permissionDenied: Boolean = false,
    val error: Exception? = null
)

/** 一批彻底删除的账目：只有真正影响行数 > 0 的条目才算删成功 */
internal data class DeleteBatch(
    val deleted: List<DeleteTarget>,
    val failed: Int,
    val permissionDenied: Boolean
)

/** 调用方存活令牌：只用来决定"要不要替调用方出栈"，不参与删除/清账的所有权 */
internal class DeleteCallerToken(private val active: () -> Boolean) {
    fun isActive(): Boolean = active()

    companion object {
        /** 没有显式调用方（androidTest / 内部一次性调用）时按存活处理 */
        val ALWAYS = DeleteCallerToken { true }
    }
}

/**
 * 逐条物理删除。**成功判据是 `deleteRow` 返回的影响行数 > 0**，不是"delete() 没抛"：
 * 行可能已被外部移走/回收站清理，`delete()` 会安静返回 0；把 0 行当成功会让 sidecar、
 * Room 标签/系列/回收站记录与实际文件状态漂移。
 *
 * sidecar（被抽帧位次档案）只随**真正删掉的行**清，且在 [deleteRow] 成功之后执行。
 * 逐条异常只记账不中断：批量删除里一条坏行不该挡住其余可控条目。
 */
internal suspend fun deleteBatch(
    targets: List<DeleteTarget>,
    deleteRow: suspend (DeleteTarget) -> RowDeleteResult,
    clearSidecar: suspend (DeleteTarget) -> Unit
): DeleteBatch {
    val deleted = ArrayList<DeleteTarget>(targets.size)
    var failed = 0
    var permissionDenied = false
    for (target in targets) {
        val result = try {
            deleteRow(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RowDeleteResult(rows = 0, error = e)
        }
        if (result.rows > 0) {
            // 物理删除已经发生：先记账，再清 sidecar。即使 sidecar 失败也不能把这条
            // 重新算成"没删"，否则收尾不会再碰它（文件已不可逆地没了）。
            deleted += target
            try {
                clearSidecar(target)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // sidecar 清理失败不改变"文件已删"的账目；调用方在清理段统一留日志。
            }
        } else {
            failed++
            if (result.permissionDenied) permissionDenied = true
        }
    }
    return DeleteBatch(deleted = deleted, failed = failed, permissionDenied = permissionDenied)
}

/**
 * 从首次不可逆删除起，整批动作交给**独立所有者作用域**执行；调用方只等结果。
 *
 * 这是 R09 的核心隔离：`deleteForever` 的调用方是播放页/媒体库页的 `rememberCoroutineScope`。
 * 用户在删除途中退页时，那个作用域会被取消。若物理删除与清账仍跑在调用方协程里，就会出现
 * 「文件已删一半、Room 没清、刷新闸门没开」的半账状态。把整批放到 [owner]（应用级
 * `SupervisorJob + Dispatchers.IO`）后，调用方取消只影响"谁来消费结果"，不影响已经开始的
 * 不可逆动作。
 *
 * 等待用 [NonCancellable]：调用方即使已取消，也已发起的所有权工作必须被等到完成，
 * 不许把 CancellationException 当成"没发生过"。
 */
internal suspend fun <T> runOnDeleteOwner(
    owner: CoroutineScope,
    block: suspend CoroutineScope.() -> T
): T {
    val job = owner.async { block() }
    return withContext(NonCancellable) { job.await() }
}

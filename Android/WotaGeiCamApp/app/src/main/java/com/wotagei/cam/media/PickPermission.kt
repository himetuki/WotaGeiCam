package com.wotagei.cam.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 尽力而为地把选片回程 URI 的读授权持久化（跨进程重启后仍可读）。
 *
 * 为什么是 best-effort：只有 `ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT` 这类结果
 * 才带 `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`；对比页右槽常用的 `ACTION_PICK`（以及
 * `ACTION_GET_CONTENT`）回程通常不带这个标志，此时系统会直接抛 `SecurityException`。
 * 这是**常态而非故障**，所以整段吞掉、不记错误级别日志；本次会话的临时读授权不受影响，
 * 播放照常——失败最多意味着下次冷启动后该历史条目读不到，届时按不可读处理即可。
 *
 * @param context 任意 Context（内部只取 contentResolver）
 * @param uri 选片回程 URI
 */
suspend fun persistPickReadPermission(context: Context, uri: Uri) {
    // takePersistableUriPermission 是 ActivityManager binder 调用：挂起并放到 IO 上，
    // 调用点都在主线程协程里（选片回程），不能就地阻塞一帧
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}

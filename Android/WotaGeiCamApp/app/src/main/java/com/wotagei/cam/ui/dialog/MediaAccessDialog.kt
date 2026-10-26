package com.wotagei.cam.ui.dialog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.core.MediaAccessState
import com.wotagei.cam.core.WotaStorage
import com.wotagei.cam.ui.openAppSettings

/**
 * 媒体读取权限不足时的运行时提示（2026-10-26，对比播放右槽选片失败）。
 *
 * 与启动权限框的区别：**这不是再申请一次权限**，而是把一条用户看不见的限制说清楚——
 * Android 14+ 选过「选择照片和视频」的用户，启动门与设置页都显示「已授予」，但相册里
 * 未被选中的视频对本应用不可见，选片必然失败。提示只出现**失败之后**（不预先拦截），
 * 因为部分授权下选中已授权项仍可能成功，提前阻断会把可用路径也堵死。
 *
 * 出口是应用详情页（[openAppSettings]）：在那一页把「照片和视频」改成「允许全部」；
 * 应用设置页里的「所有文件访问」是另一条等效路径，文案里点明，不额外加第二个按钮
 * （AlertDialog 三键在窄屏会挤成一行小字，反而降低可读性）。
 */
@Composable
fun MediaAccessDialog(state: MediaAccessState, onDismiss: () -> Unit) {
    // 只有「部分授权 / 未授权」两态有引导意义；其它态误传进来时宁可不弹，也不给错文案
    if (state != MediaAccessState.PARTIAL_READ && state != MediaAccessState.NONE) return
    val context = LocalContext.current
    val partial = state == MediaAccessState.PARTIAL_READ
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (partial) R.string.perm_media_partial_title else R.string.perm_media_none_title))
        },
        text = {
            Column {
                Text(stringResource(if (partial) R.string.perm_media_partial_body else R.string.perm_media_none_body))
                // 「所有文件访问」是等效的第二条路，但 API 29 没有这个系统页——只在支持的机型上追加
                if (WotaStorage.supportsAllFilesAccess()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.perm_media_all_files_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    context.openAppSettings()
                }
            ) { Text(stringResource(R.string.perm_open_settings)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

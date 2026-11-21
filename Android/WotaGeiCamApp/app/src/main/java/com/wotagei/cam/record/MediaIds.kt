package com.wotagei.cam.record

import android.content.ContentUris
import android.net.Uri

/**
 * 日志里的媒体标识：只写 MediaStore 数字 id，不写完整 `content://` URI，也不写用户片名。
 *
 * 完整 URI 含卷、相对路径和展示名，一旦进日志就等于把用户片库名与目录结构抄进诊断文件（B06）。
 * 数字 id 足够在同一条日志里把"插入 → 提交 → 封轨"串起来，也能和 `content://media/.../123` 对照。
 */
internal fun mediaIdOf(uri: Uri?): String {
    if (uri == null) return "none"
    return runCatching { ContentUris.parseId(uri) }.getOrNull()?.toString() ?: "?"
}
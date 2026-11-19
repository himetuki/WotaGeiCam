package com.wotagei.cam.record

import java.io.File

/**
 * 「已封口、待发布」标记（同名旁路文件 `<成片路径>.sealed`）。
 *
 * 封口（`muxer.stop()` 过）与发布（清 `IS_PENDING=0`）是两件事：发布可能因 provider 瞬时忙
 * 连败，此时成片字节已在盘上、行仍是 pending（外部不可见）。只有这枚标记能把
 * 「我知道它已经封好了，只差发布」和「可能还在写的 pending 行」区分开：
 *
 * - 冷启救援（[StalePendingSweep.rescuePendingRows]）对带标记的行只等 [SEALED_RESCUE_MS]，
 *   不必和录制残片一起等满一小时；
 * - 标记**不能**替代完整性校验：文件尾段被截断/无视频轨时仍不晋升，只留原状等下一轮。
 *
 * 写入点（封口成功后发布失败/取消）与清除点（发布成功/文件丢弃）由各发布链负责；本对象
 * 只提供同一套命名与读写，避免两侧口径分叉。
 */
internal object SealMarker {

    /** 标记文件扩展名（旁路文件，不是媒体容器；清扫的 pending 形态判据不认它） */
    const val EXTENSION = ".sealed"

    /** 带明确封口标记的 pending 行救援年龄门槛（在飞的导出是分钟级，5 分钟足够隔开） */
    const val SEALED_RESCUE_MS = 5L * 60L * 1000L

    fun fileFor(videoPath: String): File = File(videoPath + EXTENSION)

    /** 封口成功但发布未完成：落标记（幂等；失败只少一条加速线索，不影响成片） */
    fun mark(videoPath: String?) {
        if (videoPath == null) return
        runCatching {
            val f = fileFor(videoPath)
            if (!f.isFile) f.writeText("sealed ${System.currentTimeMillis()}\n")
        }
    }

    /** 发布成功或文件已丢弃：清标记（幂等） */
    fun clear(videoPath: String?) {
        if (videoPath == null) return
        runCatching { fileFor(videoPath).delete() }
    }

    fun isSealed(videoPath: String?): Boolean =
        videoPath != null && runCatching { fileFor(videoPath).isFile }.getOrDefault(false)
}

/**
 * 封口收尾该不该落标记（纯函数，JVM 全表）：
 * 只有「保留产物 + MediaStore pending 行 + 发布未成功」这一种组合需要等待发布；
 * 其余（废片/File 输出/发布成功）都必须清掉旧标记，避免把已公开的成片误判成待发布。
 */
internal fun sealNeedsMarker(keep: Boolean, pendingRow: Boolean, committed: Boolean): Boolean =
    keep && pendingRow && !committed

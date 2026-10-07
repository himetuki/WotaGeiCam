package com.wotagei.cam.player

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.wotagei.cam.record.matchDecision

private const val TAG_REMUX_ORIENT = "WotaRemuxOrient"

/**
 * 纯 remux 导出链（[ClipExporter] / [TrackExporter]）的源旋转角**双源读取**。
 *
 * 两条导出链 extractor→muxer 逐样本直拷、不解码不重编码，**像素不动**：源带容器旋转
 * （App 自录的横屏片常带 90/180/270）而导出不写 `setOrientationHint` ⇒ 成片方向错。
 * 方向信息只能靠容器 metadata 透传，读数与 record/ArcRepairRunner 完全同一套口径：
 * retriever 的 `METADATA_KEY_VIDEO_ROTATION`（= 显示所需旋转角）是权威口径，
 * extractor 的 `KEY_ROTATION` 兜住"部分机型/容器给 0"——裁决交给
 * [com.wotagei.cam.record.matchDecision]（record/OrientationExport.kt 的现成纯函数）。
 *
 * 写出侧（ClipExport/TrackExport 各自）必须经 `exportOrientationHint(hint, ORIENTATION_MUXER_CCW)`，
 * 不许裸喂读数——与录制/修复路的 muxer 写出口径同源（ORIENTATION_MUXER_CCW=false 即归一化透传，
 * 写出的 hint = 源显示旋转角；源 hint=0 时写出 0，行为不变）。
 *
 * 本文件全是阻塞 IO（MediaExtractor / MediaMetadataRetriever），**必须在后台线程调用**；
 * 两条导出链的 export 整体跑在 Dispatchers.IO，满足该前提。
 */
internal fun readSourceRotationHint(ctx: Context, uri: Uri): Int {
    val extractorHint = readExtractorRotation(ctx, uri)
    val retrieverRotation = readRetrieverRotation(ctx, uri)
    return matchDecision(extractorHint, retrieverRotation, retrieverRotation >= 0)
}

/**
 * extractor 侧读数：视频轨 format 的 `KEY_ROTATION`（同 ArcRepairRunner.open 的取法，
 * 缺键/读不出视频轨按 0 —— 由 matchDecision 归一化）。
 */
private fun readExtractorRotation(ctx: Context, uri: Uri): Int {
    val ex = MediaExtractor()
    return try {
        ex.setDataSource(ctx, uri, null)
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (!mime.startsWith("video/")) continue
            return if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0
        }
        0
    } catch (e: Exception) {
        Log.i(TAG_REMUX_ORIENT, "读 extractor 旋转角失败，按 0 处理：${e.message}")
        0
    } finally {
        runCatching { ex.release() }
    }
}

/**
 * retriever 侧读数（权威口径），读法与 ArcRepairRunner.readRetrieverRotation 同一套：
 * 读不到返回 **-1**（调用方据此降级单源）。
 */
private fun readRetrieverRotation(ctx: Context, uri: Uri): Int {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: -1
    } catch (e: Exception) {
        Log.i(TAG_REMUX_ORIENT, "读 retriever 旋转角失败，降级单源：${e.message}")
        -1
    } finally {
        runCatching { r.release() }
    }
}

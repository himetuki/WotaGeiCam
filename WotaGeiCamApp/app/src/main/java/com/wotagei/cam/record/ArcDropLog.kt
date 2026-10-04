package com.wotagei.cam.record

import java.io.File

/**
 * 录制期转换的**被抽帧位次 sidecar**（用户 2026-10-03 定版：抽帧时把位置记下来）。
 *
 * 文件与成片同目录同名，扩展名 `.drops.json`；纯档案/调试用途——**仅抽帧**模式下被抽帧的
 * 画面在录制时已弃，播放器无法事后补弧，弧连续由"自动抽帧补弧"模式在录制时一次完成。
 *
 * **位次是跨段全局位次**：分段轮转（超过 MAX_FILE_BYTES 换段）沿用同一枚 GL 编码面，
 * 帧位次计数不随换段归零，所以 [drops] 里的 k 对应的是整次录制的第 k 个输出帧，不是首段内的位次；
 * sidecar 只随首段（parts[0]）落盘。按段拆分需要让 GL 感知换段边界并切账，当前未做。
 *
 * 编解码是手写的最小 JSON（数组里全是整数），不走 org.json：本类要在 JVM 单测里全量跑
 * （android.jar 的 org.json 是 stub）。
 */
data class ArcDropLog(
    val mode: String,
    val dstFps: Int,
    /** (输出位次 k, 该位次之前丢弃的源帧数)；k 升序，且为跨段全局位次（见类注） */
    val drops: List<Pair<Int, Int>>
) {

    /** 紧凑 JSON：`{"mode":"mend","dstFps":24,"drops":[[1,1],[6,1]]}` */
    fun encode(): String = buildString {
        append("{\"mode\":\"").append(mode).append("\",\"dstFps\":").append(dstFps)
        append(",\"drops\":[")
        drops.forEachIndexed { i, (out, n) ->
            if (i > 0) append(',')
            append('[').append(out).append(',').append(n).append(']')
        }
        append("]}")
    }

    companion object {

        const val EXTENSION = ".drops.json"

        /** 成片路径 → sidecar 路径（同目录同名换扩展名） */
        fun sidecarFor(videoPath: String): String {
            val dot = videoPath.lastIndexOf('.')
            val stem = if (dot > 0) videoPath.substring(0, dot) else videoPath
            return stem + EXTENSION
        }

        fun writeTo(videoPath: String, log: ArcDropLog): Boolean = runCatching {
            File(sidecarFor(videoPath)).writeText(log.encode())
        }.isSuccess

        /** 手写解析：只认自己 [encode] 的形状，坏串/坏格返回 null（档案文件损坏不抛） */
        fun decode(raw: String): ArcDropLog? {
            val mode = Regex("\"mode\"\\s*:\\s*\"([a-z]+)\"").find(raw)?.groupValues?.get(1) ?: return null
            val dstFps = Regex("\"dstFps\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            // drops 的捕获不能用 [^]] —— 成对括号里的 ] 会把捕获截断在第一对上；
            // 改用定位法：取 `"drops":[` 之后、最后一个 `]` 之前的全部（`[]` 时两位置重合 = 空）
            val anchor = raw.indexOf("\"drops\":[")
            if (anchor < 0) return null
            val start = anchor + "\"drops\":[".length
            val end = raw.lastIndexOf(']')
            if (end < start) return null
            val body = raw.substring(start, end)
            val drops = if (body.isBlank()) {
                emptyList()
            } else {
                val list = ArrayList<Pair<Int, Int>>()
                for (part in body.split("],[")) {
                    // 按成对括号的缝拆开后，逐格剥掉残留括号再严格对整数对；任何一格不合形即整单判坏
                    val m = Regex("^(\\d+),(\\d+)$").find(part.trim('[', ']', ' ')) ?: return null
                    list += m.groupValues[1].toInt() to m.groupValues[2].toInt()
                }
                list
            }
            return ArcDropLog(mode, dstFps, drops)
        }

        fun readFrom(videoPath: String): ArcDropLog? = runCatching {
            val f = File(sidecarFor(videoPath))
            if (!f.isFile) null else decode(f.readText())
        }.getOrNull()

        /** 彻底删除成片时连带删 sidecar（回收站软删不动它——文件还在） */
        fun deleteFor(videoPath: String): Boolean = runCatching {
            File(sidecarFor(videoPath)).delete()
        }.getOrDefault(false)

        /** 成片改名时连带搬 sidecar（位次档案与成片的对应关系不能断）；无可搬文件返回 false */
        fun renameFor(oldVideoPath: String, newVideoPath: String): Boolean = runCatching {
            val from = File(sidecarFor(oldVideoPath))
            if (!from.isFile) return@runCatching false
            val to = File(sidecarFor(newVideoPath))
            if (to.absolutePath == from.absolutePath) return@runCatching true
            to.parentFile?.mkdirs()
            from.renameTo(to)
        }.getOrDefault(false)
    }
}

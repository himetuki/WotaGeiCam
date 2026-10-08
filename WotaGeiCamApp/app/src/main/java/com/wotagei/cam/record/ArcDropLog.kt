package com.wotagei.cam.record

import java.io.File

/**
 * 录制期转换的**被抽帧位次 sidecar**（用户 2026-10-03 定版：抽帧时把位置记下来）。
 *
 * 文件与成片同目录同名，扩展名 `.drops.json`；纯档案/调试用途——**仅抽帧**模式下被抽帧的
 * 画面在录制时已弃，播放器无法事后补弧，弧连续由"自动抽帧补弧"模式在录制时一次完成。
 *
 * **位次的跨段口径（2026-10-07 订正，原注释与实现不符）**：GPU 路换段（超过 MAX_FILE_BYTES
 * 轮转）会重挂 GL 编码面，重挂处把帧序号归零并清空位次账（GlRenderEngine.replaceEncoderSurface）
 * ——所以 [drops] 里的 k 是**当前段内的位次**，且只含**最后一段**的丢弃记录，前面各段的丢弃
 * 记录已随换段清掉。这与 [segments] 段清单的全局 first/count 口径（录制器逐段真实样本数前缀和）
 * 不一致，是**已知失真**：要修需把 GL 账本按段持久化并跨段续号，因 sidecar 当前无读取方，
 * 暂记失真不改行为。排查者对不上前段位次时，以本失真为第一怀疑对象。
 * sidecar 只随首段（parts[0]）落盘。每段一条 `{segment,first,count}`，数据源是录制器逐段
 * 已写视频样本数。**当前无读取方**，纯增量档案信息；
 * 空表不落键，旧档案（无 segments）解析结果不变。
 *
 * 编解码是手写的最小 JSON（数组里全是整数），不走 org.json：本类要在 JVM 单测里全量跑
 * （android.jar 的 org.json 是 stub）。
 */

/** 段清单单条：[segment] 段序号（与 VideoSegment.partIndex 同源）、[first] 该段首个输出位次、
 *  [count] 该段输出帧数、[lostMs] 该段开始之前那次换段腿里**丢掉的画面时长**（ms；0 = 没丢，
 *  2026-10-08 换段停摆缺陷的账目，编码时 0 不落键 ⇒ 旧档案形状不变）。 */
data class ArcDropSegment(
    val segment: Int,
    val first: Int,
    val count: Int,
    val lostMs: Long = 0L
)

data class ArcDropLog(
    val mode: String,
    val dstFps: Int,
    /** (输出位次 k, 该位次之前丢弃的源帧数)；k 升序，段内位次起算（跨段失真见类注） */
    val drops: List<Pair<Int, Int>>,
    /** 段清单（可选增量字段，见类注）：按段序升序；空表 = 未记录/旧档案 */
    val segments: List<ArcDropSegment> = emptyList()
) {

    /**
     * 紧凑 JSON：`{"mode":"mend","dstFps":24,"drops":[[1,1],[6,1]]}`；
     * 有段清单时追加 `"segments":[{"segment":0,"first":0,"count":72},…]`（空表不落键，保持旧形）；
     * 某段有换段丢失时该格追加 `,"lostMs":N`（**0 不落键**，与"空表不落键"同一口径：
     * 旧样例逐字不变，新样例只在真丢过内容时才多一个键）。
     */
    fun encode(): String = buildString {
        append("{\"mode\":\"").append(mode).append("\",\"dstFps\":").append(dstFps)
        append(",\"drops\":[")
        drops.forEachIndexed { i, (out, n) ->
            if (i > 0) append(',')
            append('[').append(out).append(',').append(n).append(']')
        }
        append(']')
        if (segments.isNotEmpty()) {
            append(",\"segments\":[")
            segments.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append("{\"segment\":").append(s.segment)
                    .append(",\"first\":").append(s.first)
                    .append(",\"count\":").append(s.count)
                if (s.lostMs > 0L) append(",\"lostMs\":").append(s.lostMs)
                append('}')
            }
            append(']')
        }
        append("}")
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
            // segments（可选）按 encode 顺序恒落在 drops 之后：先整键切掉，drops 按旧形状解，
            // 旧档案没有该键时原文直进下面流程（行为逐字不变——否则 lastIndexOf(']') 会咬到
            // segments 数组的收括号，把 drops 解坏）
            val segAnchor = raw.indexOf("\"segments\":[")
            val dropsRaw = if (segAnchor >= 0) raw.substring(0, segAnchor).trimEnd(',', ' ') + "]" else raw
            // drops 的捕获不能用 [^]] —— 成对括号里的 ] 会把捕获截断在第一对上；
            // 改用定位法：取 `"drops":[` 之后、最后一个 `]` 之前的全部（`[]` 时两位置重合 = 空）
            val anchor = dropsRaw.indexOf("\"drops\":[")
            if (anchor < 0) return null
            val start = anchor + "\"drops\":[".length
            val end = dropsRaw.lastIndexOf(']')
            if (end < start) return null
            val body = dropsRaw.substring(start, end)
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
            val segments = if (segAnchor < 0) emptyList() else parseSegments(raw.substring(segAnchor)) ?: return null
            return ArcDropLog(mode, dstFps, drops, segments)
        }

        /**
         * 解 `"segments":[{…},{…}]}` 尾段：与 drops 同一口径——按 `},{` 缝拆开、剥掉缝上残留
         * 的括号后逐格严格对整数对，任何一格不合形整单判坏（返回 null）。
         * encode 从不写空表，键在表空 = 损坏。
         */
        private fun parseSegments(tail: String): List<ArcDropSegment>? {
            val start = tail.indexOf('[')
            val end = tail.lastIndexOf(']')
            if (start < 0 || end < start) return null
            val body = tail.substring(start + 1, end).trim()
            if (body.isEmpty()) return null
            val list = ArrayList<ArcDropSegment>()
            for (part in body.split("},{")) {
                // 首格缺收 `}`、尾格缺起 `{`（都被缝吃掉），trim 后统一成裸字段形再严格对；
                // lostMs 可选（0 不落键，见 encode）——缺席即 0，其余字段一字不许少
                val m = Regex("^\"segment\":(\\d+),\"first\":(\\d+),\"count\":(\\d+)(?:,\"lostMs\":(\\d+))?$")
                    .find(part.trim('{', '}', ' ')) ?: return null
                list += ArcDropSegment(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                    m.groupValues[4].toLongOrNull() ?: 0L
                )
            }
            return list
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

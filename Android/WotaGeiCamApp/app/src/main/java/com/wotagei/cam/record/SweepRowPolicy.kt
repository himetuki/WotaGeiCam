package com.wotagei.cam.record

/**
 * 冷启库一致性修补的**判据本体**（纯函数，JVM 全表）。
 *
 * 旧实现把「`File(path).exists() == false`」直接当「文件缺失」并删行（Y22）。在 FUSE 下
 * 路径不可见、卷暂时离线、DATA 列过期、provider 查询失败都会让 stat 回 false——这些是
 * **不可观测**，不是缺失；按缺失删行会把还在盘上的成片连行一起删掉（MediaStore 删行连带删
 * 文件）。所以文件状态必须三值，只有「确认不可见」（父目录可读且列举不包含该条目）+ 卷可见 +
 * 按 URI 复核行身份仍一致，才允许走删行。
 *
 * 救援侧同一套判据：pending 行必须有完整封口证据（容器尾段完整 + 视频轨）才清 IS_PENDING；
 * 显式封口标记（[SealMarker]）只用来**缩短年龄门槛**，不能替代完整性校验——标记之后被截断的
 * 文件照样不许晋升。
 */
internal enum class RowFileState {
    /** 至少一条独立探测确认文件在盘（stat 或 provider 打开成功） */
    PRESENT,

    /** 父目录可读且确认不包含该条目：可以继续做删行前的卷/URI 复核 */
    ABSENT,

    /** 无法确认（路径未知 / 卷离线 / provider 失败 / 父目录不可读 / FUSE 不可见）：只许标记 */
    UNOBSERVABLE
}

internal enum class RowFix {
    /** 不满足年龄门槛、卷不可见，或文件在盘但不需要/不能处置 */
    KEEP,

    /** 文件确认缺失 + 卷可见 + URI 复核行身份一致 */
    DELETE_ROW,

    /** pending 行、文件在盘且通过封口完整性校验 */
    RESCUE_ROW,

    /** 不可观测：留原状，只落一条可检索的标记日志 */
    MARK_UNOBSERVABLE
}

/**
 * 单行处置（纯函数）。
 *
 * @param pending 行 IS_PENDING=1
 * @param state [RowFileState]
 * @param ageOk 年龄门槛（sealed 标记可缩短，见 [sweepAgeOk]）
 * @param volumeMounted 行所在卷在 [android.provider.MediaStore.getExternalVolumeNames] 里
 * @param rowMatches 按 URI 复核：行仍在且 DATA/DISPLAY_NAME 与快照一致
 * @param playable 容器尾段完整且有视频轨（[MoovSeal.complete] + 轨道探测）
 */
internal fun rowFixDecision(
    pending: Boolean,
    state: RowFileState,
    ageOk: Boolean,
    volumeMounted: Boolean,
    rowMatches: Boolean,
    playable: Boolean
): RowFix {
    if (!ageOk) return RowFix.KEEP
    if (state == RowFileState.UNOBSERVABLE) return RowFix.MARK_UNOBSERVABLE
    if (state == RowFileState.ABSENT) {
        // 删行是唯一不可逆动作：卷不可见 / 行身份复核不上，都只能当"不可观测"
        return if (volumeMounted && rowMatches) RowFix.DELETE_ROW else RowFix.MARK_UNOBSERVABLE
    }
    // PRESENT：永不删除
    if (!volumeMounted) return RowFix.KEEP
    if (!pending) return RowFix.KEEP
    // 封口标记不能替代完整性校验：缺尾段/无视频轨一律不晋升
    if (!playable) return RowFix.KEEP
    return RowFix.RESCUE_ROW
}

/**
 * 年龄门槛（纯函数）：普通遗留走 1 小时；带明确封口标记的 pending 行只等
 * [SEALED_RESCUE_MS]（封口后 commit 失败是"已经写好、只差发布"，不必和录制残片一样等一小时）。
 * 标记只缩短等待，不跳过 [rowFixDecision] 的完整性校验。
 */
internal fun sweepAgeOk(ageMs: Long, sealed: Boolean): Boolean =
    ageMs >= if (sealed) SealMarker.SEALED_RESCUE_MS else StalePendingSweep.SWEEP_STALE_MS

/**
 * 卷可见性（纯函数）：[visible] 为 null = 查询失败，一律按不可见处理（不许据此删行）。
 */
internal fun volumeVisible(volume: String?, visible: Set<String>?): Boolean =
    volume != null && visible != null && volume in visible

/**
 * MP4 顶层 box 完整性（纯函数，可喂合成字节流测试）：从 offset 0 顺序走完所有顶层 box，
 * 任何 box 声明长度越过文件尾、长度非法、或头读不全都判不完整。
 *
 * 这正是「缺尾段」的判据：MediaMuxer 的 mdat 声明长度大于剩余字节时，`readSampleData` 会在
 * 缺失处截断；只看 `trackCount > 0` 探测不出来，但 box 账目当场对不上。
 *
 * @param fileLength 文件字节数
 * @param readAt (offset, count) → 恰好 count 字节，越界/读不全返回 null
 */
internal fun mp4TopLevelComplete(
    fileLength: Long,
    readAt: (Long, Int) -> ByteArray?,
    requireMoov: Boolean = false
): Boolean {
    if (fileLength < 8L) return false
    var off = 0L
    var boxes = 0
    var haveMoov = false
    while (off < fileLength) {
        val remaining = fileLength - off
        // 文件尾允许 1..7 字节零填充；非零残留视为截断的 box 头。
        if (remaining < 8L) {
            val tail = readAt(off, remaining.toInt()) ?: return false
            if (tail.size != remaining.toInt() || tail.any { it != 0.toByte() }) return false
            break
        }
        if (boxes++ >= MAX_MP4_BOXES) return false
        val head = readAt(off, 8) ?: return false
        if (head.size != 8) return false
        val size32 = u32(head, 0)
        val headerLen = if (size32 == 1L) 16L else 8L
        val size = when (size32) {
            0L -> remaining // box 延伸到文件尾。
            1L -> {
                if (remaining < headerLen) return false
                val ext = readAt(off + 8L, 8) ?: return false
                if (ext.size != 8) return false
                u64(ext, 0)
            }
            else -> size32
        }
        if (size < headerLen || size > remaining) return false
        if (u32(head, 4) == MOOV_TYPE) haveMoov = true
        off += size
    }
    return !requireMoov || haveMoov
}

private const val MAX_MP4_BOXES = 10_000
private const val MOOV_TYPE = 0x6D6F6F76L

private fun u32(b: ByteArray, at: Int): Long =
    ((b[at].toLong() and 0xFF) shl 24) or
        ((b[at + 1].toLong() and 0xFF) shl 16) or
        ((b[at + 2].toLong() and 0xFF) shl 8) or
        (b[at + 3].toLong() and 0xFF)

private fun u64(b: ByteArray, at: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
    return v
}

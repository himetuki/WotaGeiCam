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
 * 行所在**具体卷**（纯函数）：只有拿到真实卷名，行 URI / 行操作才钉得住"哪一条行"。
 *
 * `VOLUME_NAME` 可能是合成卷 `external`（旧版本反查/老行）：它是**跨所有卷**的聚合集合，
 * 再用 `_ID` 拼条目就是聚合 URI + id——跨卷 `_ID` 可以重号，会打开/删/救援另一卷的同号行
 * （Y22）。读不到卷名（null/空串）同样拼不回具体卷。两种都返回 null ⇒ 调用方只能按
 * 不可观测处理（只标记，不动行）。
 */
internal fun concreteRowVolume(volume: String?): String? {
    val v = volume?.trim().orEmpty()
    return if (v.isEmpty() || v == SYNTHETIC_VOLUME_EXTERNAL) null else v
}

/** `MediaStore.VOLUME_EXTERNAL` 的值：纯函数文件不引 android.jar（JVM 测试可直跑） */
private const val SYNTHETIC_VOLUME_EXTERNAL = "external"

/**
 * owner 闸门（纯函数）：明确属于**别的包**的成片行一律不碰（删行连带删文件，救援会改别人的
 * pending 态，两者都是动别人的数据）。`OWNER_PACKAGE_NAME` 只在 API 30+ 有值，null/空串 =
 * 当前系统读不到 ⇒ 保持老行为（不据此拒绝，否则老设备上所有行都会被拒）。
 */
internal fun ownerAllowed(owner: String?, ourPackage: String): Boolean =
    owner.isNullOrEmpty() || owner == ourPackage

internal data class SweepWriteSelection(val where: String, val args: List<String>)

/**
 * 与具体卷条目 URI 一起交给 Provider。空字段用 IS NULL 原样核对，文件名/路径只作为绑定值。
 * API 30+ 的修改代数比秒级日期更精确；API 29 不查询也不比较新列。
 */
internal fun sweepWriteSelection(
    displayName: String?,
    dataPath: String?,
    relativePath: String?,
    addedSec: Long,
    sizeBytes: Long?,
    modifiedSec: Long?,
    pending: Boolean,
    owner: String?,
    generationModified: Long?,
    hasGeneration: Boolean
): SweepWriteSelection? {
    if (displayName.isNullOrBlank() || dataPath.isNullOrBlank() || relativePath.isNullOrBlank() ||
        addedSec <= 0L || (hasGeneration && generationModified == null)
    ) return null
    val clauses = ArrayList<String>(10)
    val args = ArrayList<String>(10)
    fun match(column: String, value: String?) {
        if (value == null) {
            clauses += "$column IS NULL"
        } else {
            clauses += "$column = ?"
            args += value
        }
    }
    match("_display_name", displayName)
    match("_data", dataPath)
    match("relative_path", relativePath)
    match("date_added", addedSec.toString())
    match("_size", sizeBytes?.toString())
    match("date_modified", modifiedSec?.toString())
    match("is_pending", if (pending) "1" else "0")
    if (hasGeneration) {
        match("owner_package_name", owner)
        match("generation_modified", generationModified?.toString())
        match("is_trashed", "0")
    }
    return SweepWriteSelection(clauses.joinToString(" AND "), args)
}

/**
 * 单行处置（纯函数）。
 *
 * @param pending 行 IS_PENDING=1
 * @param state [RowFileState]
 * @param ageOk 年龄门槛（sealed 标记可缩短，见 [sweepAgeOk]）
 * @param volumeMounted 行所在卷在 [android.provider.MediaStore.getExternalVolumeNames] 里
 *   且卷名能构造具体集合（见 [concreteRowVolume]）。
 * @param rowMatches 按具体卷条目 URI 复核，行仍在且身份与快照一致
 * @param playable 容器尾段完整且有视频轨（[MoovSeal.complete] + 轨道探测）
 * @param ownerOurs 行的 OWNER_PACKAGE_NAME 是我们的（或本系统读不到，见 [ownerAllowed]）
 */
internal fun rowFixDecision(
    pending: Boolean,
    state: RowFileState,
    ageOk: Boolean,
    volumeMounted: Boolean,
    rowMatches: Boolean,
    playable: Boolean,
    ownerOurs: Boolean
): RowFix {
    // 他人的行先挡死：任何分支都不许动（不进删行、不进救援，连不可观测标记都省了——
    // 那不是我们的行，连"我们在管它"都不该记录）
    if (!ownerOurs) return RowFix.KEEP
    if (!ageOk) return RowFix.KEEP
    if (state == RowFileState.UNOBSERVABLE) return RowFix.MARK_UNOBSERVABLE
    if (state == RowFileState.ABSENT) {
        // 删行是唯一不可逆动作：卷不可见 / 行身份复核不上，都只能当"不可观测"
        return if (volumeMounted && rowMatches) RowFix.DELETE_ROW else RowFix.MARK_UNOBSERVABLE
    }
    // PRESENT：永不删除
    if (!volumeMounted) return RowFix.KEEP
    if (!pending) return RowFix.KEEP
    if (!rowMatches) return RowFix.MARK_UNOBSERVABLE
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

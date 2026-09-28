package com.wotagei.cam.core

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log

/** 产品语义的镜头档位；AUTO 是「由 App 自行选档」的伪档位，不出现在枚举表里 */
enum class LensType { SUPER_WIDE, WIDE, TELEPHOTO, SUPER_TELEPHOTO, AUTO, FRONT }

/**
 * 一个可打开的镜头。ability 按「这颗镜头自己」的 characteristics 收，切镜头即整套换挡
 */
data class LensSlot(
    val type: LensType,
    val logicId: String,
    val physicalId: String?,
    val eqFocal: Float,
    val ability: CameraAbility
) {
    /**
     * 槽位唯一键。档位会重复（多颗同焦段后摄、多颗前摄），只有键能唯一指认一颗镜头，
     * 所以「当前镜头 / 切镜头」一律按 key 走，[type] 只作展示与兜底。
     */
    val key: String get() = if (physicalId == null) logicId else "$logicId#$physicalId"
}

/** 一颗待归档的镜头（物理焦距只用来排序，档位名按整组后摄的相对顺序给） */
private data class LensEntry(
    val logicId: String,
    val physicalId: String?,
    val focalMm: Float,
    val eqFocal: Float,
    val ability: CameraAbility,
    val front: Boolean
)

/**
 * 枚举镜头（第 3 步）：
 * 1. 镜头 id 用 [discoverCameraIds] 取，**不只信 `cameraIdList`**——真机取证：某 ROM 的 cameraIdList
 *    只给 2 颗，而 dumpsys 报 4 台 HAL 设备，按数字 id 仍能读能力并打开（隐藏了超广角子头与逻辑头）；
 * 2. 逻辑多摄（能力位 11，SDK≥30）按 `physicalCameraIds` 展开：子头若已被单独列出就不再重复，
 *    否则把没暴露的子头各自补成一颗镜头；子头全部已暴露时逻辑头本身不再占档
 *    （同一颗传感器列两遍会让档位互相打架，还会把组合头误标成某个焦段档）；
 * 3. 后摄按物理焦距升序整组归档（超广角→广角→长焦→超长焦），**多颗后摄各自成档，不只取第一个**；
 *    前摄统一 [LensType.FRONT]，靠 [LensSlot.key] 区分具体哪一颗；
 * 4. 能力按「这颗镜头自己」的 characteristics 收，切镜头整套换挡。
 */
fun enumerateLenses(mgr: CameraManager): List<LensSlot> {
    val ids = discoverCameraIds(mgr)
    val openable = ids.toSet()
    val entries = mutableListOf<LensEntry>()

    for (id in ids) {
        val cc = tryOrNullLens { mgr.getCameraCharacteristics(id) } ?: continue
        val facing = tryOrNullLens { cc.get(CameraCharacteristics.LENS_FACING) }
            ?: CameraMetadata.LENS_FACING_BACK
        val front = facing == CameraMetadata.LENS_FACING_FRONT
        val capabilities = tryOrNullLens { cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) }
            ?: IntArray(0)
        val logical = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

        val hiddenPhysicals: List<String> =
            if (logical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                (tryOrNullLens { cc.physicalCameraIds } ?: emptySet())
                    .sorted()
                    .filterNot { openable.contains(it) } // 已是独立镜头，不再当子头列一遍
            } else emptyList()
        if (logical && hiddenPhysicals.isEmpty()) continue

        entries += hiddenPhysicals.mapNotNull { pid ->
            val pcc = tryOrNullLens { mgr.getCameraCharacteristics(pid) } ?: return@mapNotNull null
            LensEntry(
                logicId = pid,
                physicalId = null,
                focalMm = shortestFocalMm(pcc) ?: 0f,
                eqFocal = eqFocalMmOf(pcc),
                // 能力按物理头收：同一逻辑摄下超广角与长焦的 ISO/曝光/区域上限常常不同
                ability = CameraAbility.from(pid, pcc),
                front = front
            )
        }
        if (logical) continue
        entries += LensEntry(
            logicId = id,
            physicalId = null,
            focalMm = shortestFocalMm(cc) ?: 0f,
            eqFocal = eqFocalMmOf(cc),
            ability = CameraAbility.from(id, cc),
            front = front
        )
    }

    val rears = entries.filterNot { it.front }.sortedWith(compareBy({ it.focalOrder() }, { it.eqOrder() }))
    val tiers = assignRearTiers(rears.size)
    val fronts = entries.filter { it.front }
    return rears.mapIndexed { index, e -> e.toSlot(tiers.getOrElse(index) { tiers.last() }) } +
        fronts.map { it.toSlot(LensType.FRONT) }
}

/**
 * 数字 id 命名空间的探测上限：只是「往外探几个号」的边界，不是机型参数——
 * 真实颗数仍由每个 id 的 characteristics 能否读到、有没有可用输出流决定。
 */
private const val HIDDEN_ID_PROBE_LIMIT = 10

/**
 * 可打开的镜头 id = `cameraIdList` ∪ 按数字 id 探到的隐藏镜头。
 * 隐藏 id 必须同时满足「characteristics 可读」+「PRIVATE 输出表非空」才算一颗镜头，探不到的直接跳过。
 */
fun discoverCameraIds(mgr: CameraManager): List<String> {
    val found = LinkedHashSet(tryOrNullLens { mgr.cameraIdList.toList() } ?: emptyList())
    for (probe in 0 until HIDDEN_ID_PROBE_LIMIT) {
        val id = probe.toString()
        if (found.contains(id)) continue
        val cc = tryOrNullLens { mgr.getCameraCharacteristics(id) } ?: continue
        val usable = tryOrNullLens {
            cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.PRIVATE)?.isNotEmpty() == true
        } ?: false
        if (usable) found.add(id)
    }
    return found.toList()
}

/** 未知焦距（部分 HAL 不给 LENS_INFO_AVAILABLE_FOCAL_LENGTHS）排最后，避免抢掉广角档 */
private fun LensEntry.focalOrder(): Float = if (focalMm > 0f) focalMm else Float.MAX_VALUE

private fun LensEntry.eqOrder(): Float = if (eqFocal > 0f) eqFocal else Float.MAX_VALUE

private fun LensEntry.toSlot(type: LensType): LensSlot = LensSlot(
    type = type,
    logicId = logicId,
    physicalId = physicalId,
    eqFocal = if (eqFocal > 0f) eqFocal else focalMm,
    ability = ability
)

/**
 * 纯函数：后摄颗数 → 档位分配（表），按「物理焦距升序」逐位对应，返回表长度恒等于颗数；
 * ≥4 时多出的镜头并入超长焦档。焦段数值本身不参与（避免写死机型焦段）。
 */
fun assignRearTiers(count: Int): List<LensType> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(LensType.WIDE)
    count == 2 -> listOf(LensType.SUPER_WIDE, LensType.WIDE)
    count == 3 -> listOf(LensType.SUPER_WIDE, LensType.WIDE, LensType.TELEPHOTO)
    else -> listOf(LensType.SUPER_WIDE, LensType.WIDE, LensType.TELEPHOTO) +
        List(count - 3) { LensType.SUPER_TELEPHOTO }
}

/**
 * 单击循环镜头的纯算式：在**运行时枚举出的**槽位键列表里取当前那颗的下一颗，走完最后一颗回第一颗。
 *
 * 只认 [LensSlot.key]（档位会重名，只有键能唯一指认一颗）；列表顺序即 `enumerateLenses` 的归档顺序
 * （超广角→广角→长焦→超长焦→前摄），这里不出现任何机型硬编码。
 * 不足两颗时返回 null，由调用方决定是提示还是什么都不做。
 */
fun nextLensKey(keys: List<String>, currentKey: String?): String? {
    if (keys.size < 2) return null
    val index = keys.indexOf(currentKey)
    return keys[if (index < 0) 0 else (index + 1) % keys.size]
}

/** M1 门禁：`adb logcat -s WotaAbility` 打印镜头列表 + 每档能力范围 */
fun dumpLenses(slots: List<LensSlot>) {
    Log.i(WotaTag.LENS, "lenses total=${slots.size}")
    slots.forEach { slot ->
        Log.i(WotaTag.LENS, "lens ${slot.type} key=${slot.key} eq=${slot.eqFocal}mm" +
            " videoSizes=${slot.ability.videoSizes.size} previewSizes=${slot.ability.previewSizes.size}")
        slot.ability.dump(slot.key)
    }
}

private inline fun <T> tryOrNullLens(block: () -> T): T? = try { block() } catch (e: Throwable) { null }

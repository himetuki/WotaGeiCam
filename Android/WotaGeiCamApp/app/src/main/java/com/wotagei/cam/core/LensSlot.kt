package com.wotagei.cam.core

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import com.wotagei.cam.diag.Diag as Log

/** 产品语义的镜头档位；AUTO 是「由 App 自行选档」的伪档位，不出现在枚举表里 */
enum class LensType { SUPER_WIDE, WIDE, TELEPHOTO, SUPER_TELEPHOTO, AUTO, FRONT }

/**
 * 一个镜头入口：openCamera 使用 logicId，非空 physicalId 经该设备的输出路由选择物理头。
 * ability 按「这颗镜头自己」的 characteristics 收；厂商隐藏独立入口仍是待实际打开的候选。
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
 * 1. 镜头候选用 [discoverCameraIds] 取，保留厂商实际能打开的数字隐藏扩展；可读能力本身不证明能独立打开。
 * 2. 逻辑多摄（能力位 11，API 28+）按 `physicalCameraIds` 展开：公开且能力可读的独立入口不重复，
 *    未公开物理头必须保留父逻辑 ID，并在输出面上选择物理 ID，不能拿数字探测结果当作独立入口证明。
 *    只有所有子头都有公开、能力可读且有 PRIVATE 输出的替代入口时才可隐藏逻辑头；其余保留逻辑兜底，
 *    包括物理集合为空、读取失败、子头全部或部分展开失败。
 * 3. 后摄按物理焦距升序整组归档（超广角→广角→长焦→超长焦），**多颗后摄各自成档，不只取第一个**；
 *    前摄统一 [LensType.FRONT]，靠 [LensSlot.key] 区分具体哪一颗；
 * 4. 能力按「这颗镜头自己」的 characteristics 收，切镜头整套换挡。
 */
fun enumerateLenses(mgr: CameraManager): List<LensSlot> {
    val publicIds = (tryOrNullLens { mgr.cameraIdList.toList() } ?: emptyList()).toSet()
    val ids = discoverCameraIds(mgr, publicIds)
    val readable = ids.mapNotNull { id ->
        tryOrNullLens { mgr.getCameraCharacteristics(id) }?.let { id to it }
    }.toMap()
    val independentIds = readable.filter { (id, cc) -> id in publicIds && hasPrivateOutput(cc) }.keys
    val physicalIdsByParent = readable.mapValues { (_, cc) ->
        val logical = tryOrNullLens { cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) }
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true
        if (logical) tryOrNullLens { cc.physicalCameraIds } ?: emptySet() else emptySet()
    }
    val publicPhysicalIds = physicalIdsByParent.filterKeys { it in publicIds }.values.flatten().toSet()
    val entries = mutableListOf<LensEntry>()

    for ((id, cc) in readable) {
        // 即使数字探测也读得到，已知属于公开逻辑设备的未公开物理头仍必须走父逻辑路由。
        // 不在该集合里的隐藏厂商入口继续保留，不能用品牌/ID 黑名单删掉真实可用扩展。
        if (id !in publicIds && id in publicPhysicalIds) continue
        val facing = tryOrNullLens { cc.get(CameraCharacteristics.LENS_FACING) }
            ?: CameraMetadata.LENS_FACING_BACK
        val front = facing == CameraMetadata.LENS_FACING_FRONT
        val capabilities = tryOrNullLens { cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) }
            ?: IntArray(0)
        val logical = capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)

        // physicalCameraIds 自 API 28 起可用；集合读取失败也不能跳过可用逻辑头。
        val physicalIds = physicalIdsByParent[id].orEmpty()
        val hiddenPhysicals = physicalIds.sorted().filterNot { it in independentIds }
        if (shouldHideLogicalHead(logical, physicalIds, hiddenPhysicals)) continue

        entries += hiddenPhysicals.mapNotNull { pid ->
            val pcc = tryOrNullLens { mgr.getCameraCharacteristics(pid) } ?: return@mapNotNull null
            LensEntry(
                logicId = id,
                physicalId = pid,
                focalMm = shortestFocalMm(pcc) ?: 0f,
                eqFocal = eqFocalMmOf(pcc),
                // 能力按物理头收：同一逻辑摄下超广角与长焦的 ISO/曝光/区域上限常常不同。
                // AF 模式传逻辑头的做回退（docs/plan/27）：HAL 常把 AF 只挂在逻辑头层面，
                // 子头报空/全 OFF 时借逻辑头的，否则广角子头会被误判成定焦（marble 实测）
                ability = CameraAbility.from(
                    pid,
                    pcc,
                    if (logical) {
                        (tryOrNullLens { cc.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) }
                            ?: IntArray(0)).toSet()
                    } else null
                ),
                front = front
            )
        }
        // 走到这里即没有完整的公开独立替代集，展开是否成功都保留父逻辑兜底。
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
 * 可读的镜头候选 = `cameraIdList` ∪ 数字隐藏扩展；不在枚举期打开设备，也不把 PRIVATE 表当作独立可开证明。
 * 已知物理头的路由与逻辑兜底由 [enumerateLenses] 处理，厂商独立候选仍交实际 openCamera 验证。
 */
fun discoverCameraIds(mgr: CameraManager): List<String> =
    discoverCameraIds(mgr, tryOrNullLens { mgr.cameraIdList.toList() } ?: emptyList())

private fun discoverCameraIds(mgr: CameraManager, publicIds: Collection<String>): List<String> {
    val found = LinkedHashSet(publicIds)
    for (probe in 0 until HIDDEN_ID_PROBE_LIMIT) {
        val id = probe.toString()
        if (found.contains(id)) continue
        val cc = tryOrNullLens { mgr.getCameraCharacteristics(id) } ?: continue
        if (hasPrivateOutput(cc)) found.add(id)
    }
    return found.toList()
}

private fun hasPrivateOutput(cc: CameraCharacteristics): Boolean = tryOrNullLens {
    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.PRIVATE)?.isNotEmpty() == true
} ?: false

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
 * 纯函数：逻辑多摄头是否该从档位表里隐藏。
 *
 * 只有一种情形能隐藏：它确实是逻辑头、`physicalCameraIds` 读到了非空集合、且所有子头都已作为
 * 独立镜头暴露（hiddenPhysicals 为空）。其余情形——尤其 `physicalCameraIds` 为空（读取失败、
 * HAL 不报、API 29 旧行为）——必须**保留逻辑头本身**，否则这颗摄像头会从 `cameraIdList` 里
 * 整颗消失（Android 10 上曾因此让所有逻辑后摄被跳过，只剩前摄或直接打不开相机）。
 *
 * JVM 可直接测：参数只是布尔/集合，不碰 CameraManager。
 */
internal fun shouldHideLogicalHead(
    logical: Boolean,
    physicalIds: Set<String>,
    hiddenPhysicals: List<String>
): Boolean = logical && physicalIds.isNotEmpty() && hiddenPhysicals.isEmpty()

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

/** M1 门禁：`adb logcat -s WotaAbility` 打印镜头列表 + 每档能力范围 */
fun dumpLenses(slots: List<LensSlot>) {
    Log.i(WotaTag.LENS, "lenses total=${slots.size}")
    slots.forEach { slot ->
        Log.i(WotaTag.LENS, "lens ${slot.type} key=${slot.key} eq=${slot.eqFocal}mm" +
            " videoSizes=${slot.ability.videoSizes.size} previewSizes=${slot.ability.previewSizes.size}")
        slot.ability.dump(slot.key)
    }
}

/**
 * **能力协商的出厂默认镜头**（2026-10-26 用户指令「能力协商，而非能力假设」）：
 * 用户从未显式选过镜头时，默认打开**后摄里最大视频档最大的那颗**，不认档位标签。
 *
 * 为什么：档位标签由焦距排序归档（[assignRearTiers]），个别机型的 HAL 会把小底定焦头
 * 排进「广角」档——真机 marble（Redmi Note 12 Turbo/MIUI14）实测：出厂默认开出的
 * 「广角」videoSizes 最大只有 1600x1200 且定焦（afModes=[0]），用户由此只能选 720p。
 * 按能力协商后，这类机型首启直接打开真主摄；正常机型主摄本来就是最大视频档，行为不变。
 * 用户显式切换过一次后（[LensPickPreference]），完全尊重用户选择，本函数不再参与。
 *
 * 纯函数（JVM 可测）：前置一律排除；全空或所有镜头都报不出视频档时返回 null（调用方
 * 退回既有的档位/首颗链路）。
 */
fun pickCapabilityDefault(slots: List<LensSlot>): LensSlot? =
    slots.filter { it.type != LensType.FRONT }
        .maxByOrNull { slot -> slot.ability.videoSizes.maxOfOrNull { it.pixels } ?: 0L }
        ?.takeIf { slot -> slot.ability.videoSizes.maxOfOrNull { it.pixels } ?: 0L > 0L }

/**
 * 「用户显式选过镜头」的持久化位：能力协商默认（[pickCapabilityDefault]）只对**从未选过**
 * 的用户生效；用户在镜头面板里切过一次，之后每次冷启都完全尊重持久化的档位/槽位。
 *
 * 读写收在这一个对象里：prefs 文件名与键名只在这里出现一次，camera 层不自己拼
 * `getSharedPreferences` 字面量。单开一个小文件而不复用 ui 层 `WotaSettings` 的
 * `wota_settings`，是为了不让 core 反向依赖 ui 的常量；这份位丢失的最大代价只是
 * 退回能力默认（再选一次即恢复），可接受。
 */
object LensPickPreference {

    private const val PREFS = "wota_lens_pick"
    private const val KEY = "lens_user_picked"

    fun isPicked(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }.getOrDefault(false)

    fun markPicked(context: Context) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY, true).apply()
        }
    }
}

private inline fun <T> tryOrNullLens(block: () -> T): T? = try { block() } catch (e: Throwable) { null }

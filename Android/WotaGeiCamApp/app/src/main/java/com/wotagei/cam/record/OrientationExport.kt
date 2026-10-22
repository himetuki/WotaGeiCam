package com.wotagei.cam.record

import android.media.MediaFormat

/**
 * Muxer 旋转约定开关（全工程写出口的**唯一裁决点**）。
 *
 * - `false` = `MediaMuxer.setOrientationHint` 与 `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION`
 *   同一约定：值就是"显示所需旋转角"，原样透传；
 * - `true` = muxer 侧按**相反旋向**解释（`θ` 与 `360−θ`），写出前取补。
 *
 * 本批取 `false`（原样透传），依据是 2026-10-06 的真机产物：源片与光弧修复成片的
 * `side_data_list.rotation` **都是 -180**，成片原始像素却比源片多转了 180°（成片 vs 源片 6.5 dB，
 * 成片 vs `hflip,vflip(源片)` 36.6 dB ≈ 逐像素同一张图）。写出值两边一致，说明**不是这里写反了**——
 * 真因是解码器把容器旋转烘进了像素、metadata 又写了一次（双重旋转，见 [decoderFormatOf]）。
 *
 * ⚠ 历史教训：本常量原来的注释把 180° 归因于"读错/写反"（"只有 90↔270 互换才看得出差 180°"），
 * 已被上述产物证伪——那双源读数确实没错，错的是像素被多转了一次。注释不许留谎言，故改写。
 */
const val ORIENTATION_MUXER_CCW = false

/**
 * 归一化到 0/90/180/270 最近档：先取模到 [0,360)，再四舍五入到 90 的倍数；
 * 恰落在 45/135/225/315（两档等距）时**向上取档**（例如 45→90、359→0）。
 */
fun normalizeQuarter(deg: Int): Int {
    val n = ((deg % 360) + 360) % 360
    return ((n + 45) / 90 % 4) * 90
}

/**
 * 把"显示所需旋转角"（与 [recordOrientationHint] 同一语义）转成要写进 muxer 的旋转值。
 * [muxerCcw] 见 [ORIENTATION_MUXER_CCW]；为 true 时取 `(360−θ)%360`。
 */
fun exportOrientationHint(sourceRotationDeg: Int, muxerCcw: Boolean): Int {
    val q = normalizeQuarter(sourceRotationDeg)
    return if (muxerCcw) (360 - q) % 360 else q
}

/**
 * [exportOrientationHint] 的逆：从写进容器的值反推"显示所需旋转角"。
 * 两种约定下都是对合变换，所以在 0/90/180/270 上往返恒等：
 * `displayRotationOf(exportOrientationHint(θ,c), c) == θ`（供往返用例与排查时反推）。
 */
fun displayRotationOf(hint: Int, muxerCcw: Boolean): Int {
    val q = normalizeQuarter(hint)
    return if (muxerCcw) (360 - q) % 360 else q
}

/**
 * 外部视频容器旋转角的**双源裁决**（纯函数）。
 *
 * `MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION`（语义 = "显示所需旋转角"，相册/播放器都按它转）
 * 是权威口径；extractor 的 `KEY_ROTATION` 兜住"部分机型/容器给 0"的情况。故 [retrieverAvailable]
 * 为 true 时取其值；读不到时降级到 extractor 的值；两源都无（extractor 也未标）时上游给 0，归一化后同为 0。
 *
 * ⚠ 口径澄清（2026-10-06）：双源只决定"**读出多少度**"，它让读数口径统一、顺手兜住 extractor 给 0 的机型，
 * 但**不是** 180° 那次的修复——那次读数本就正确，问题是像素被平台多转了一次，见 [decoderFormatOf]。
 */
fun matchDecision(extractorHint: Int, retrieverRotation: Int, retrieverAvailable: Boolean): Int =
    if (retrieverAvailable) normalizeQuarter(retrieverRotation) else normalizeQuarter(extractorHint)

/**
 * 计划→行为桥（纯函数）：给定源容器的旋转角与"解码器**是否有输出面**"，返回平台是否会把这个旋转
 * **烘进解码输出的像素**（= 必须在喂给解码器前剥掉 `KEY_ROTATION`）。
 *
 * 机制：给解码器的 format 里带 `KEY_ROTATION` 且解码器**有输出面**时，平台把容器旋转作为
 * **输出缓冲变换**施加到那个面上。`SurfaceTexture.getTransformMatrix()` 随之非恒等，
 * 而 `record/ArcRepairGl.kt` 的 OES 拷贝趟（`copyOesToCur`）正是用 `stMatrix` 采样
 * ⇒ 旋转被"烤"进落盘的像素。于是像素已转过一次，`MediaMuxer.setOrientationHint` 又写同一个值
 * ⇒ **双重旋转**（2026-10-06 真机成片相对源片恰差 180° 的根因）。
 *
 * ByteBuffer 路线（`surface == null`，无输出面）旋转无处施加，像素不转 ⇒ 不改也不出错
 * （2026-10-05 真机对照：CPU 路成片与源片同向，GPU 路差 180°，与本研究一致）。
 *
 * 即：**只有"有输出面 + 非零旋转"才需要剥**；本函数是这条判断的单一真源，
 * 供运行期日志与 JVM 单测共用（恒等式测试测不出实现退化，故把分支放在这里单独钉）。
 */
fun bakedRotationNeedsStrip(rawRotationDeg: Int, surfaceOutput: Boolean): Boolean =
    surfaceOutput && normalizeQuarter(rawRotationDeg) != 0

/**
 * 纯函数：从一份 format 的键集合里挑出喂**解码器**前必须剔除的键（当前只有容器旋转角）。
 * 单独成函数是为了让"剥哪些键"这条决策能在 JVM 上被单测（`MediaFormat` 在本工程单测环境里
 * 没有实现，见 [decoderFormatOf] 的说明），而不是散落在 Android 胶水里无法验证。
 * 源里没有该键时返回空表（= 无副作用）。
 */
fun bakedRotationKeysToDrop(keys: Collection<String>): List<String> =
    keys.filter { it == MediaFormat.KEY_ROTATION }

/**
 * 喂给**解码器**的 format：保真拷贝 [src] 并剥掉容器旋转角 `KEY_ROTATION`（见 [bakedRotationNeedsStrip]）。
 *
 * 剥掉后解码像素保持原始朝向（不再被平台的缓冲变换旋转），与 `muxer.setOrientationHint` 写出的
 * `hint` 口径一致 ⇒ 双重旋转消失。**尺寸不变**（`width`/`height` 原样带过去），所以 90° 源也不会被压扁
 * ——这正是不能选"把 hint 写成 0"那条路的原因（那条路会丢掉全部方向信息）。
 *
 * 实现要点（为什么这么写）：
 * - **拷贝靠平台拷贝构造 `MediaFormat(src)`（API 29+，minSdk 29 已满足）**：它按底层键值表整表复制，
 *   含 `csd-0`/`csd-1` 等 `ByteBuffer`，不需手抄"最小必要键集"——手抄漏一个键解码器就可能建不出来
 *   （风险高于收益）。`MediaFormat` 没有其它公开深拷贝手段，这是唯一安全的保真拷贝。
 * - **只在拷贝件上删键**：本函数对 `src` 全程只读（`keys`/`containsKey`），绝不 `set*`/`removeKey`，
 *   故不污染调用方的 format（入参被污染会让调用方后续读数错乱）。
 * - `KEY_ROTATION` 在源里不存在时 [bakedRotationKeysToDrop] 返回空表，等价于原样拷贝。
 *
 * ⚠ 为什么没有 JVM 单测：`android.media.MediaFormat` 在本工程的 JVM 单测里只有 AGP 桩，
 * 任何方法都抛 `RuntimeException: Method ... not mocked`（本机实测：`setInteger`/`containsKey` 全抛），
 * 造不出可用的 `MediaFormat`；引 Robolectric 属"禁止新依赖"。所以把可测的**决策**抽成
 * [bakedRotationKeysToDrop]，并让运行期日志打出"拷贝件仍含 KEY_ROTATION / 入参仍含 KEY_ROTATION"
 * 供真机定案（见 `ArcRepairRunner` 的 `WotaArcRepair` 日志）。
 */
fun decoderFormatOf(src: MediaFormat): MediaFormat {
    val dst = MediaFormat(src)
    for (key in bakedRotationKeysToDrop(src.keys)) dst.removeKey(key)
    return dst
}

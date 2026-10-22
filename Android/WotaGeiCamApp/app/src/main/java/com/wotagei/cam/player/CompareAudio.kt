package com.wotagei.cam.player

/**
 * 对比播放的**剪辑模型（音轨部分）**：单时间线上两条视频轨（左右两窗），音轨只播其一。
 *
 * 【纯 Kotlin，无任何 Android 依赖】——JVM 单测直打，`CompareScreen` 只把这里产出的
 * 幂等命令序列喂给两台引擎，不含任何选轨/音量的判断分支。
 *
 * 【为什么不做「左右混音」】两路素材不做时基对齐地叠加会梳状滤波（相位取消），
 * 产品口径只做「环境音 / 内录」两条可选轨，二选一播放。
 *
 * 【轨序口径单一真源】容器里「环境轨 = 音频组 0、内录轨 = 音频组 1」（与 [TrackSync]、
 * 批 4 单播放页选轨同一口径）。组下标一律经 [audioGroupIndexOf] 取得，禁止各处各写
 * `setAudioTrackOverride(1, 0)` 字面量（漏改一处就是「选了没效果」）。
 */

/** 对比页两台引擎的编号（也叫两条视频轨）：0=左窗、1=右窗 */
enum class Side {
    LEFT,
    RIGHT;

    fun other(): Side = if (this == LEFT) RIGHT else LEFT
}

/** 可选音频轨种类：环境音 / 内录（仅双音轨片有内录） */
enum class AudioTrackKind { ENV, CAP }

/**
 * 轨种类 → 容器音频组下标的**唯一映射**（环境=组 0、内录=组 1）。
 * 组内 trackIndex 恒 0（每组的音轨是单条），由 [PlayerEngine.applyAudioCommand] 落定。
 */
fun audioGroupIndexOf(track: AudioTrackKind): Int = when (track) {
    AudioTrackKind.ENV -> 0
    AudioTrackKind.CAP -> 1
}

/**
 * 分屏右窗（**单引擎**）的音频计划：要不要 override、目标组下标、音量。
 *
 * 与双引擎的 [CompareAudioPlan] 的关键差异：分屏只有一台引擎，**没有"另一侧"可静音**——
 * 「只播其一」由 Media3 的 override 天然保证（引擎默认只渲染被选中的那条音轨），不需要也
 * **不许**额外压音量。若照抄对比页的 [CompareAudio.applyOrderOf]，那条 `SetVolume(mutedSide, 0f)`
 * 会把唯一那台引擎压成静音（=分屏彻底没声），所以单开这一型，不填一枚假的 mutedSide 字段。
 *
 * [overrideGroupIndex] null = 清 override 回引擎默认选择（默认即容器首条音轨=环境轨）。
 * 选环境用「清 override」而非「override 组 0」：与单播放页 PlayerScreen、对比页 [CompareAudio]
 * 同一 Media3 口径（默认 = 第一条音频组），少发一次 override 就少一次指向瞬时空组的竞态。
 */
data class SplitAudioPlan(
    val overrideGroupIndex: Int?,
    val volume: Float
)

/**
 * 分屏音频计划器：单音轨片即便请求 [AudioTrackKind.CAP] 也回落环境轨（清 override），不产生
 * 指向不存在组的 override（与 [CompareAudio.audioPlanOf] 同一兜底口径）。组下标一律经
 * [audioGroupIndexOf]——这是「环境=组 0 / 内录=组 1」的唯一映射真源，禁在各调用点写字面量。
 */
fun splitAudioPlanOf(dualAudio: Boolean, pick: AudioTrackKind): SplitAudioPlan {
    val effective = if (pick == AudioTrackKind.CAP && dualAudio) AudioTrackKind.CAP else AudioTrackKind.ENV
    return SplitAudioPlan(
        overrideGroupIndex = if (effective == AudioTrackKind.CAP) audioGroupIndexOf(AudioTrackKind.CAP) else null,
        volume = 1f
    )
}

/** 分屏音频选择的可断言行为三元组：要不要 override、目标组下标、音量（恒 1 = 没把唯一引擎误静音） */
data class SplitAudioEffect(
    val override: Boolean,
    val groupIndex: Int?,
    val volume: Float
)

/**
 * **桥函数**：把 [splitAudioPlanOf] 的计划落成一个空引擎态上的可观察行为。只测计划字段是恒等式；
 * 测桥才能钉住「选内录真的落到组 1」「选环境真的清 override」「音量真的没被误压 0」。
 */
fun splitAudioEffectOf(dualAudio: Boolean, pick: AudioTrackKind): SplitAudioEffect {
    val plan = splitAudioPlanOf(dualAudio, pick)
    return SplitAudioEffect(
        override = plan.overrideGroupIndex != null,
        groupIndex = plan.overrideGroupIndex,
        volume = plan.volume
    )
}

/**
 * 一拍音频计划：哪台引擎全量、哪台静音、要不要 override、override 的组下标。
 * [overrideGroupIndex] 为 null = 清掉 override 回引擎默认选择（默认即容器首条音轨=环境轨）。
 */
data class CompareAudioPlan(
    val fullSide: Side,
    val mutedSide: Side,
    val overrideSide: Side,
    val overrideGroupIndex: Int?
)

/**
 * 对比页音频计划器：由会话态（哪一侧 + 哪条轨）产出幂等引擎命令序列。对比页只调这里，
 * 不裸调任何选轨/音量 API（组下标一律经 [audioGroupIndexOf]）。
 */
object CompareAudio {

    /**
     * 由会话态推出音频计划。
     *
     * [sideHasDual] = 当前音频侧那一片**是不是双音轨片**（唯一真源 = [TrackSync.countAudioTracks]
     * 探测结果 ≥ 2，与 UI 的 chip 门同一位；**不许改回用引擎上报的 audioGroups.size 做行为门**——
     * 两源不一致时会「UI 能选、点了没效果」）。内录轨只有在双音轨片才可选；单音轨片即便请求
     * [AudioTrackKind.CAP] 也回落到环境轨（清 override），不产生指向不存在组的 override。
     */
    fun audioPlanOf(audioSide: Side, track: AudioTrackKind, sideHasDual: Boolean): CompareAudioPlan {
        val effective = if (track == AudioTrackKind.CAP && sideHasDual) AudioTrackKind.CAP else AudioTrackKind.ENV
        return CompareAudioPlan(
            fullSide = audioSide,
            mutedSide = audioSide.other(),
            overrideSide = audioSide,
            overrideGroupIndex = if (effective == AudioTrackKind.CAP) audioGroupIndexOf(AudioTrackKind.CAP) else null
        )
    }

    /**
     * 计划 → 引擎命令序列，**顺序固定**：
     * 1. 先静音另一侧（先把不播的那台压成 0，避免选轨/切边瞬间两侧同时出声）；
     * 2. 再对选中侧设/清 override（轨读取要等 `onTracksChanged`，但它与音量互不依赖）；
     * 3. 最后落选中侧音量（收尾保证选中侧是全量）。
     */
    fun applyOrderOf(plan: CompareAudioPlan): List<EngineCommand> = listOf(
        EngineCommand.SetVolume(plan.mutedSide, 0f),
        if (plan.overrideGroupIndex == null) {
            EngineCommand.ClearAudioOverride(plan.overrideSide)
        } else {
            EngineCommand.SetAudioOverride(plan.overrideSide, plan.overrideGroupIndex)
        },
        EngineCommand.SetVolume(plan.fullSide, 1f)
    )

    /**
     * **桥函数**：把 [audioPlanOf] 计划经 [applyOrderOf] 命令序列在一个空引擎态上跑一遍，返回
     * 最终可观察行为（左/右音量、override 目标）。这是「计划 → 行为」的桥——只测 [audioPlanOf]
     * 的字段是恒等式，测桥才能钉住「未选中侧真的静音、选中侧真的全量、override 真的落在选中侧」。
     */
    fun selectionEffectOf(audioSide: Side, track: AudioTrackKind, sideHasDual: Boolean): SelectionEffect {
        val plan = audioPlanOf(audioSide, track, sideHasDual)
        var left = 0f
        var right = 0f
        var override = OverrideTarget(plan.overrideSide, null)
        applyOrderOf(plan).forEach { cmd ->
            when (cmd) {
                is EngineCommand.SetVolume -> if (cmd.side == Side.LEFT) left = cmd.volume else right = cmd.volume
                is EngineCommand.SetAudioOverride -> override = OverrideTarget(cmd.side, cmd.groupIndex)
                is EngineCommand.ClearAudioOverride -> override = OverrideTarget(cmd.side, null)
            }
        }
        return SelectionEffect(left, right, override)
    }
}

/** 落到某台引擎的幂等命令（顺序由 [CompareAudio.applyOrderOf] 定，见其注） */
sealed interface EngineCommand {
    val side: Side

    data class SetVolume(override val side: Side, val volume: Float) : EngineCommand
    data class SetAudioOverride(override val side: Side, val groupIndex: Int) : EngineCommand
    data class ClearAudioOverride(override val side: Side) : EngineCommand
}

/** override 目标（哪台、哪个组；组为 null = 清） */
data class OverrideTarget(val side: Side, val groupIndex: Int?)

/** 音频选择的可断言效果三元组：左音量、右音量、override 目标 */
data class SelectionEffect(
    val leftVolume: Float,
    val rightVolume: Float,
    val override: OverrideTarget
)

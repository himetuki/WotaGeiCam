package com.wotagei.cam.ui.anim

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.wotagei.cam.BuildConfig
import com.wotagei.cam.R
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.WotaSpace
import com.wotagei.cam.ui.design.WotaType
import com.wotagei.cam.ui.design.wotaCard

/**
 * #73 第 1 件：**应用内的融合进度取证钩子**（唯一用途是给 #71b 抓中间态帧）。
 *
 * ## 为什么必须有
 * 这台华为测试机没有 `screenrecord` 二进制（实测 `inaccessible or not found`），
 * `adb exec-out screencap -p` 单张 464–541ms，而 FLUENT 档时长是 [com.wotagei.cam.ui.design.WotaMotion.COMMIT_MS]
 * = 750ms ⇒ 整个动画窗口只够拍 1 帧。"看得出连续收拢"的动画无法靠截屏验收，
 * 只能让应用自己把进度**钉死在指定值**（或把时长放长）再逐档截图。
 *
 * ## 为什么不是 BuildConfig.DEBUG
 * 本机不能装 debug 包：release 与 debug 是两张证书，换装必须先 `adb uninstall`，
 * 而那会清空 `wota_settings`（用户设置）与 `wota_media.db`（收藏/tag/回收站）——本项目的硬红线。
 * 所以钩子挂在**与 r13 同签名、只是 debuggable=true** 的 `debugHook` 变体上（构建侧见 `app/build.gradle.kts`），
 * 开关是那条变体专属的 [BuildConfig.MERGE_HOOK]：`release`/`debug` 两个变体恒为 false，
 * 于是 [mergeHookArgsOf] 在正式 release 里只能返回"关"，读侧也走同一条闸门，
 * 用户无论怎么点、怎么 `am start` 都进不到钩子分支。
 * ⚠ 别把这条理解成"代码被 R8 删了"：实测 release 的 mapping 里本类**仍然存在**（198 处命中），
 * 因为 `MainActivity.applyMergeHook` 与 `HudBottomZone` 都无条件引用它。真正挡住的不是裁剪，
 * 是 `MERGE_HOOK=false` 让 [mergeHookArgsOf] 恒返回关闭态、[pinnedProgress] 恒 null、
 * [timeScale] 恒 1f、徽标 `active` 恒 false 而整棵子树不组合——**入口不可达，不是代码不存在**。
 * 以后要改这条判据，验的是上面那四个恒等出口，不许拿"R8 会删掉"当理由放松任何一处。
 *
 * ## 三条纪律
 * - **不污染正常 UI**：入口只有 adb 的 intent extra（[EXTRA_MERGE_HOOK]），设置页没有一行调试项，
 *   也没有任何可被普通操作误触的手势；
 * - **不持久化**：状态只活在进程内，`force-stop` 或从桌面图标重新进入（那条 intent 没有 extra）就清零；
 * - **不改数据流形状**：进度取用仍只经 `mergeProgressOf(plan, absorbed, anim?.value)` 那一条桥，
 *   钩子只是这条桥**上游**的一个可选覆盖（[mergeProgressWithHook]），默认路径逐字不变。
 *   PLAIN 档由桥自己保证不吃钩子值（S3-1 那条用例仍在），所以"钉住 p"在无动画档里根本不生效。
 *
 * ## 生效时必须有视觉标识
 * [MergeDebugBadge]：钩子一开着就在顶栏挂一枚「调试钉住 p=0.50 · 时长 ×20」的胶囊，
 * 免得日后把钩子态当成正常渲染去截图取证。钩子关掉它就不组合（正常渲染一帧都不多画）。
 */

/** adb intent 里那枚 extra 的键；除 adb 之外没有第二个写入方 */
const val EXTRA_MERGE_HOOK = "wota_merge_hook"

/**
 * 钩子参数。[pinned] = 把融合进度钉死在该值（null = 不钉）；
 * [timeScale] = 动效时长倍率（1f = 不放长；×20 时 FLUENT 的 750ms 变 15s，连拍可覆盖中间态）。
 */
data class MergeHookArgs(
    val pinned: Float?,
    val timeScale: Float
) {
    /** 有没有在生效（决定徽标画不画、MotionSpec 要不要放长） */
    val active: Boolean get() = pinned != null || timeScale != DEFAULT.timeScale

    companion object {
        /** 关闭态：进程一起来就是这个值，且不写 prefs */
        val DEFAULT = MergeHookArgs(pinned = null, timeScale = 1f)

        /** 时长倍率上限：再大就大到 UI 别的动画也放长得不像话，而且一帧都截不完 */
        const val MAX_SCALE = 50f
    }
}

/**
 * 「adb 传的字符串 → 钩子参数」纯函数（JVM 可测）。
 *
 * 语法：`&`（或 `;`）分段的 `k=v`
 * - `pin=0.5` 钉进度；裸写一个 0..1 的数（`0.5`）等价于 `pin=0.5`，省得每次敲前缀
 * - `t=20` 时长倍率
 * - `off` 或任何认不出的写法 → 关闭态
 *
 * 夹取规则故意保守：pin 越界 / NaN、t 非正数或超过 [MergeHookArgs.MAX_SCALE] 的**那一枚参数**丢掉，
 * 另一枚照常；整串读不懂就整个关。理由是这是取证工具，宁可"没生效"也不要"半个值生效"——
 * 半个值会让人把错帧当成正常渲染。
 */
fun parseMergeHookSpec(raw: String?): MergeHookArgs {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text.equals("off", ignoreCase = true)) return MergeHookArgs.DEFAULT
    var pinned: Float? = null
    var scale = MergeHookArgs.DEFAULT.timeScale
    for (token in text.split('&', ';')) {
        val piece = token.trim()
        if (piece.isEmpty()) continue
        val key = piece.substringBefore('=', piece).trim().lowercase()
        val value = if (piece.contains('=')) piece.substringAfter('=') else null
        when {
            key == "pin" -> {
                val p = parseFloat(value)
                if (p != null && p.isIn01()) pinned = p
            }
            key == "t" -> {
                val s = parseFloat(value)
                if (s != null && s > 0f && s <= MergeHookArgs.MAX_SCALE) scale = s
            }
            // 没写键名的裸数字只认 0..1（当 pin 用），其余一律丢掉：这是取证工具，不猜用户想干什么
            value == null -> {
                val bare = parseFloat(piece)
                if (bare != null && bare.isIn01()) pinned = bare
            }
            else -> Unit
        }
    }
    return MergeHookArgs(pinned, scale)
}

/**
 * 「变体允不允许 + 原始串 → 钩子参数」——**release 不可达这条闸门的本体**（JVM 可测）。
 * [enabled] 当形参而不是直接读 [BuildConfig.MERGE_HOOK]，就是为了让用例能两头都测到：
 * 闸门只写在读侧或只写在写侧都算漏，这里两侧都过同一条函数。
 */
fun mergeHookArgsOf(enabled: Boolean, raw: String?): MergeHookArgs =
    if (enabled) parseMergeHookSpec(raw) else MergeHookArgs.DEFAULT

private fun parseFloat(text: String?): Float? = text?.trim()?.toFloatOrNull()

private fun Float.isIn01(): Boolean = !isNaN() && this >= 0f && this <= 1f

/**
 * 钩子状态：**进程内单例，不写 prefs、不进位置表**，所以杀进程 / 从桌面图标重进（无 extra）就回关闭态。
 * 用 Compose 状态包着是因为它要被绘制层读（进度）与要被组合期读（徽标、MotionSpec 的重建键）。
 */
object MergeDebugHook {

    /** 当前参数；读侧也过 [mergeHookArgsOf] 那同一道闸门，正式 release 恒为关闭态 */
    var args by mutableStateOf(MergeHookArgs.DEFAULT)
        private set

    /** 写入方只有 MainActivity 的 intent 一条路（onCreate / onNewIntent） */
    fun applySpec(raw: String?) {
        args = mergeHookArgsOf(BuildConfig.MERGE_HOOK, raw)
    }

    /** 钉住的进度；null = 不吃钩子，逐字退回动画状态的当前值 */
    val pinnedProgress: Float? get() = if (BuildConfig.MERGE_HOOK) args.pinned else null

    /** 时长倍率；关闭态恒 1f，所以 MotionSpec 与不接钩子时逐字同一份 spec */
    val timeScale: Float get() = if (BuildConfig.MERGE_HOOK) args.timeScale else 1f
}

/**
 * 钩子生效时顶栏那枚标识（见 [MergeDebugBadge] 的用法：录制页 HUD 层末尾叠加，不参与任何布局账）。
 * 关闭态直接不组合 —— 正常渲染里它一个节点都不存在，所以截图时看见它就等于看见"这张是钩子态"。
 */
@Composable
fun MergeDebugBadge(modifier: Modifier = Modifier) {
    val args = MergeDebugHook.args
    if (!args.active) return
    val label = if (args.pinned != null) {
        stringResource(R.string.merge_hook_pinned, args.pinned, args.timeScale)
    } else {
        stringResource(R.string.merge_hook_scale, args.timeScale)
    }
    Box(modifier.wotaCard(WotaShape.pill).padding(horizontal = WotaSpace.s, vertical = WotaSpace.xs)) {
        Text(
            text = label,
            style = WotaType.label,
            color = WotaColor.warn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

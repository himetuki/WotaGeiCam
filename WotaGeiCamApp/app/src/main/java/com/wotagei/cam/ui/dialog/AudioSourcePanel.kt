@file:OptIn(ExperimentalAnimationApi::class)

package com.wotagei.cam.ui.dialog

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.bt.BtSpeakerController
import com.wotagei.cam.record.CaptureState
import com.wotagei.cam.record.PlaybackCaptureController
import com.wotagei.cam.ui.anim.LocalMotion
import com.wotagei.cam.ui.design.WotaChip
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.design.WotaPillPopup
import com.wotagei.cam.ui.design.WotaType

/**
 * 音源管理弹窗（内录体系批 2）：原「蓝牙」胶囊位换成「音频」chip 后的就近面板。
 *
 * 结构：页签行 + 内容区。**页签是数据驱动的**——内容表 [audioSourceTabs] 是
 * `List<AudioTabSpec>`（key/标题/内容 composable），加新页签 = 表里加一条配置，
 * 弹窗本体的页签行与切换动画都按表渲染，不用再碰。
 *
 * 尺寸平滑自适应：`AnimatedContent` + `SizeTransform(clip = false)`，尺寸动画规格接
 * [LocalMotion.intSize]——这是 Motion.kt 例外 #2（Dock 随内容数变化宽高）登记过的同一份
 * 「容器随内容过渡」规格，两页签内容量/文案长短不同时弹窗大小跟随动画而不是硬跳。
 * 动画通道纪律（MotionHygieneTest）：不用 animateContentSize 之外的布局动画，不直调 tween。
 *
 * 开关真源（音源双开定版 2026-10-07）：左开关（内录）的选中态派生自
 * `controller.state is Active`（状态真源是 controller，见 CameraScreen 的接线）；右开关
 * （环境音）的真源是 [ambientEnabled]（WotaParams.audioEnabled，录制成片的环境音通道位）。
 * **两开关独立、可任意组合**（双轨并存，见 audioTrackPlan 桥）——旧「内录激活即环境音关」
 * 的互斥派生是批 2 单源时代的视觉遗留，已废（真机实证：面板显示环境音关、成片仍双轨）。
 * 面板只渲染状态、发意图，不自己持有会话态。
 *
 * @param anchor 无默认值必传（§69 锚点纪律）：这枚弹窗锚在触发它的那颗「音频」chip 上，
 *   漏挂锚点浮层就会按 IntRect.Zero 钉在屏幕左上角。
 */
@Composable
fun AudioSourcePanel(
    anchor: IntRect,
    capture: PlaybackCaptureController,
    recording: Boolean,
    onEnableCapture: () -> Unit,
    onLockTip: () -> Unit,
    bt: BtSpeakerController,
    ambientEnabled: Boolean,
    onToggleAmbient: () -> Unit,
    onDismiss: () -> Unit
) {
    val captureState by capture.state.observed()
    val tabs = audioSourceTabs(captureState, recording, onEnableCapture, capture, onLockTip, bt, ambientEnabled, onToggleAmbient)
    var selected by remember { mutableStateOf(tabs.first().key) }
    val motion = LocalMotion.current
    WotaPillPopup(anchor, onDismiss, title = stringResource(R.string.audio_panel_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { spec ->
                WotaChip(
                    label = spec.title,
                    selected = spec.key == selected,
                    onClick = { selected = spec.key },
                    // fill = false（2026-10-05 紧凑化）：weight 的 fill 默认 true 会把页签行
                    // 撑到弹窗最大宽（400dp），弹窗面积跟着翻倍；改内容定宽
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        // targetState 用 key 而不是 spec 对象：内容表每次重组重建，lambda 身份不_equals，
        // 按 spec 比较会每帧都触发一次假切换；按 key 比较只有真的换页签才动
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(motion.float) togetherWith fadeOut(motion.float))
                    .using(SizeTransform(clip = false) { _, _ -> motion.intSize })
            },
            label = "AudioSourcePanelTabs"
        ) { key ->
            tabs.first { it.key == key }.content()
        }
    }
}

/** 一枚页签的规格：加新页签 = 在 [audioSourceTabs] 里加一条配置（用户要求的可扩展性） */
data class AudioTabSpec(
    val key: String,
    val title: String,
    val content: @Composable () -> Unit
)

/** 音源管理的内容表：批 2 = 音源选择 + 蓝牙（原面板整块迁入）；双开定版后音源行双独立开关 */
@Composable
private fun audioSourceTabs(
    captureState: CaptureState,
    recording: Boolean,
    onEnableCapture: () -> Unit,
    capture: PlaybackCaptureController,
    onLockTip: () -> Unit,
    bt: BtSpeakerController,
    ambientEnabled: Boolean,
    onToggleAmbient: () -> Unit
): List<AudioTabSpec> {
    // 块体不是风格偏好：守卫（AudioSourceWiringGuardTest）用 bodyOf 锁这张表的表达式，
    // 表达式体让 bodyOf 失去输入、守卫假绿（KotlinSourceScanTest 钉过的坑）
    return listOf(
        AudioTabSpec(key = "source", title = stringResource(R.string.audio_tab_source)) {
            AudioSourceTab(
                captureState, recording, onEnableCapture, capture::shutdown, onLockTip,
                ambientEnabled, onToggleAmbient
            )
        },
        AudioTabSpec(key = "bt", title = stringResource(R.string.audio_tab_bluetooth)) {
            BtSpeakerPanel(bt)
        }
    )
}

/**
 * 音源选择：左右开关（左 = 捕获设备内音频、右 = 默认环境音）+ 开关下的状态提示小字。
 * **两开关独立**（音源双开定版 2026-10-07）：内录开关 toggle 捕获会话（开=授权、关=拆会话），
 * 环境音开关 toggle 环境音通道位（下次起录生效），互不弹回——四组合都合法（audioTrackPlan 桥）。
 * 录制中两边都锁（照 SizePill/LensPill 的 onLockTip 先例）：音源组合在起录那刻定格。
 */
@Composable
private fun AudioSourceTab(
    captureState: CaptureState,
    recording: Boolean,
    onEnableCapture: () -> Unit,
    onDisableCapture: () -> Unit,
    onLockTip: () -> Unit,
    ambientEnabled: Boolean,
    onToggleAmbient: () -> Unit
) {
    val active = captureState is CaptureState.Active
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AudioSourceRow(
            label = stringResource(R.string.audio_capture_label),
            checked = active,
            onClick = {
                when {
                    // 录制中音源锁定（照 SizePill/LensPill 的 onLockTip 先例）
                    recording -> onLockTip()
                    captureState is CaptureState.Authorizing -> Unit   // 系统授权框已在场
                    active -> onDisableCapture()         // 已在内录：再点=关闭（开关语义）
                    else -> onEnableCapture()            // Idle/Failed/Revoked 都可（重）授权
                }
            }
        )
        AudioSourceRow(
            label = stringResource(R.string.audio_ambient_label),
            checked = ambientEnabled,
            onClick = {
                when {
                    // 录制中音源锁定（与内录开关同一判据形态，守卫按出现次数锁两条）
                    recording -> onLockTip()
                    else -> onToggleAmbient()            // 独立 toggle 环境音通道位
                }
            }
        )
        AudioStateHint(captureState)
    }
}

/** 一行开关：整行可点（toggleable 挂行上），Switch 本体只显状态——与设置页同一套观感。
 *  行宽封顶 220dp（2026-10-05 紧凑化）：SpaceBetween 需要一段确定宽度才分得出「标签 | 开关」，
 *  但不封顶时 fillMaxWidth 会把整枚弹窗顶到 400dp 上限。 */
@Composable
private fun AudioSourceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .widthIn(max = 220.dp)
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onClick() }),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = WotaColor.textHi
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedTrackColor = WotaColor.accentActive, checkedThumbColor = WotaColor.onAccent)
        )
    }
}

/** 开关下的状态提示小字：授权中 / 失败原因 / 已撤销可重开（P3-3：撤销态必须给重新授权入口） */
@Composable
private fun AudioStateHint(state: CaptureState) {
    val text = when (state) {
        is CaptureState.Authorizing -> stringResource(R.string.audio_state_authorizing)
        is CaptureState.Active -> stringResource(R.string.audio_state_active)
        is CaptureState.Failed -> stringResource(R.string.audio_state_failed, state.reason)
        is CaptureState.Revoked -> stringResource(R.string.audio_state_revoked)
        is CaptureState.Idle -> stringResource(R.string.audio_state_idle)
    }
    Text(
        text = text,
        style = WotaType.caption,
        color = WotaColor.textLo,
        // 与开关行同一条封宽账（220dp）：状态说明在行宽内折行，不顶宽弹窗
        modifier = Modifier.widthIn(max = 220.dp)
    )
}

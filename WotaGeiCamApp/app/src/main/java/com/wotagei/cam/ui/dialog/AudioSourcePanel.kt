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
 * 开关真源：左开关（内录）的选中态派生自 `controller.state is Active`（状态真源是
 * controller，见 CameraScreen 的接线），面板只渲染状态、发意图（[onEnableCapture] 由
 * 调用方接授权 launcher），不自己持有会话态。
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
    onDismiss: () -> Unit
) {
    val captureState by capture.state.observed()
    val tabs = audioSourceTabs(captureState, recording, onEnableCapture, capture, onLockTip, bt)
    var selected by remember { mutableStateOf(tabs.first().key) }
    val motion = LocalMotion.current
    WotaPillPopup(anchor, onDismiss, title = stringResource(R.string.audio_panel_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { spec ->
                WotaChip(
                    label = spec.title,
                    selected = spec.key == selected,
                    onClick = { selected = spec.key },
                    modifier = Modifier.weight(1f)
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

/** 音源管理的内容表：批 2 = 音源选择 + 蓝牙（原面板整块迁入，零行为变化） */
@Composable
private fun audioSourceTabs(
    captureState: CaptureState,
    recording: Boolean,
    onEnableCapture: () -> Unit,
    capture: PlaybackCaptureController,
    onLockTip: () -> Unit,
    bt: BtSpeakerController
): List<AudioTabSpec> {
    // 块体不是风格偏好：守卫（AudioSourceWiringGuardTest）用 bodyOf 锁这张表的表达式，
    // 表达式体让 bodyOf 失去输入、守卫假绿（KotlinSourceScanTest 钉过的坑）
    return listOf(
        AudioTabSpec(key = "source", title = stringResource(R.string.audio_tab_source)) {
            AudioSourceTab(captureState, recording, onEnableCapture, capture::shutdown, onLockTip)
        },
        AudioTabSpec(key = "bt", title = stringResource(R.string.audio_tab_bluetooth)) {
            BtSpeakerPanel(bt)
        }
    )
}

/**
 * 音源选择：左右开关（左 = 捕获设备内音频、右 = 默认环境音）+ 开关下的状态提示小字。
 * 两边互斥：内录激活时环境音侧自动弹回，反过来也是——开关态由 [captureState] 派生，
 * 撤销链（Revoked）不需要面板做任何事就自然回到环境音侧。
 */
@Composable
private fun AudioSourceTab(
    captureState: CaptureState,
    recording: Boolean,
    onEnableCapture: () -> Unit,
    onDisableCapture: () -> Unit,
    onLockTip: () -> Unit
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
                    active -> Unit                       // 已在内录：无动作
                    captureState is CaptureState.Authorizing -> Unit   // 系统授权框已在场
                    else -> onEnableCapture()            // Idle/Failed/Revoked 都可（重）授权
                }
            }
        )
        AudioSourceRow(
            label = stringResource(R.string.audio_ambient_label),
            checked = !active,
            onClick = {
                when {
                    recording -> onLockTip()
                    active -> onDisableCapture()         // 关内录 = 拆会话回环境音
                    else -> Unit                         // 已在环境音
                }
            }
        )
        AudioStateHint(captureState)
    }
}

/** 一行开关：整行可点（toggleable 挂行上），Switch 本体只显状态——与设置页同一套观感 */
@Composable
private fun AudioSourceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
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
        color = WotaColor.textLo
    )
}

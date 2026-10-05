package com.wotagei.cam.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.R
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.theme.WotaWarn

/**
 * 录制页右侧的「音频」小控件（内录体系批 2：原「蓝牙」胶囊位换音频 chip，点击就近开音源管理弹窗）。
 *
 * **键名历史**：这颗的位置原是蓝牙音箱胶囊（`CamPill.BT`/`PillKey.BT`，07 文档 §1.2），
 * 批 2 换成音频 chip；键名保留不改——位掩码按 bit 写进 `hud_pills` 持久化，改键名等于
 * 改存量配置语义（P5 裁决）。
 *
 * **内录激活与蓝牙连接两种态并列共存**，互不覆写（批 2 裁决）：
 * - 内录激活 → 整颗高亮（accent 描边 + 图标 accent）：会话态的语义层；
 * - 蓝牙 → 既有圆点（accent=已连 / warn=未连）+ 媒体音量百分比：连接态的读数层。
 * 两个信号各占一层，内录亮不抹蓝牙点、蓝牙连不抢内录高亮，同时在场都能读出来。
 *
 * @param captureActive 设备内录会话是否激活（`PlaybackCaptureController.state is Active`）
 * @param btConnected A2DP 是否已连上（取 `BtSpeakerController.active != null`）
 * @param volumePct 媒体音量百分比，取 `BtSpeakerController.volumePercent`
 * @param modifier **必传、没有默认值**（§69 锚点纪律）：这颗就是 `PillKey.BT` 浮层的锚点，
 *   取景页要传 `anchorOf(PillKey.BT)`。之前这里没有形参可挂，全工程只有读取方没有写入方，
 *   面板永久钉在左上角而入口在右缘（§69 缺陷族）。宁可让漏挂变成编译错误。
 * @param card 是否自绘那层底板（S3-4）：单独摆 true 保持旧观感，放进竖 Dock 时由 Dock 底板承托传 false。
 */
@Composable
fun AudioChip(
    captureActive: Boolean,
    btConnected: Boolean,
    volumePct: Int,
    modifier: Modifier,
    card: Boolean = true,
    onClick: () -> Unit
) {
    val lit = captureActive || btConnected
    // 读屏描述按态拼接（P3-2）：「音频」+ 内录态 + 蓝牙态一整句交给 TalkBack；
    // 图标自己的 contentDescription 置空（装饰件），避免合并后念两遍
    val desc = listOf(
        stringResource(R.string.audio_chip_label),
        if (captureActive) stringResource(R.string.audio_chip_capture_on) else null,
        if (btConnected) stringResource(R.string.audio_chip_bt_on) else stringResource(R.string.audio_chip_bt_off)
    ).filterNotNull().joinToString(separator = "，")
    // 原蓝牙 chip 的横排单行口径（10-01 第 5 项）原样保留：图标 + 点 + 百分比收 1 档，
    // 与变焦/对焦那排横排 chip 观感统一；本批只换图标语义与内录高亮，不动布局账
    Row(
        modifier
            .semantics { contentDescription = desc }
            .then(if (card) Modifier.wotaCard(WotaShape.medium) else Modifier)
            .then(
                if (captureActive) Modifier.border(1.dp, WotaAccent, WotaShape.medium) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = if (lit) WotaAccent else WotaTextDim,
            modifier = Modifier.size(15.dp)
        )
        // 连接态点：主色 = A2DP 已连，橙色 = 未连接（蓝牙态的读数层，与内录高亮各管各的）
        Box(
            Modifier
                .size(5.dp)
                .background(if (btConnected) WotaAccent else WotaWarn, CircleShape)
        )
        Text(
            stringResource(R.string.bt_volume_value, volumePct),
            style = MonoStyle.copy(fontSize = 10.sp),
            color = if (btConnected) WotaText else WotaTextDim
        )
    }
}

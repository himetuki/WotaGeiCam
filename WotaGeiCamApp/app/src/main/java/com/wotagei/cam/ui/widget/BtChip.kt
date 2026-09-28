package com.wotagei.cam.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wotagei.cam.R
import com.wotagei.cam.bt.BtSpeakerController
import com.wotagei.cam.ui.design.WotaShape
import com.wotagei.cam.ui.design.wotaCard
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.theme.WotaWarn

/**
 * 录制页右侧的蓝牙音箱小控件（07 文档 §1.2：图标 + 连接态点 + 音量百分比），点击就近开蓝牙面板。
 *
 * @param connected A2DP 是否已连上（取 `BtSpeakerController.active != null`）
 * @param volumePct 媒体音量百分比，取 `BtSpeakerController.volumePercent`
 * @param modifier **必传、没有默认值**（审查 S2-2）：这颗就是 `PillKey.BT` 浮层的锚点，
 *   取景页要传 `anchorOf(PillKey.BT)`。之前这里没有形参可挂，全工程只有读取方没有写入方，
 *   `pillAnchors[BT]` 恒为 `IntRect.Zero` → 面板永久钉在左上角而入口在右缘（§69 缺陷族）。
 *   宁可让漏挂变成编译错误，也不留"运行时甩到原点"这种只有真机才看得见的洞。
 * @param card 是否自绘那层底板（S3-4）：单独摆 true 保持旧观感，放进竖 Dock 时由 Dock 底板承托传 false。
 */
@Composable
fun BtChip(connected: Boolean, volumePct: Int, modifier: Modifier, card: Boolean = true, onClick: () -> Unit) {
    val accent = if (connected) WotaAccent else WotaTextDim
    Column(
        modifier
            .then(if (card) Modifier.wotaCard(WotaShape.medium) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothDisabled,
            contentDescription = stringResource(
                if (connected) R.string.bt_chip_connected else R.string.bt_chip_disconnected
            ),
            tint = accent,
            modifier = Modifier.size(16.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // 连接态点：主色 = 已连上 A2DP，橙色 = 未连接
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(if (connected) WotaAccent else WotaWarn)
            )
            Text(
                stringResource(R.string.bt_volume_value, volumePct),
                style = MonoStyle.copy(fontSize = 10.sp),
                color = if (connected) WotaText else WotaTextDim
            )
        }
    }
}

/**
 * 状态自收版：自己 collect [BtSpeakerController] 的两条流，布局与上面的显式参数版完全一致。
 * 当前**零调用点**（取景页走的是显式参数版），留着是给别的页面复用时的备用入口。
 * 同样必须显式传 `modifier`：这条入口一旦有调用点又拿不到锚点，就得传 `Modifier` 并写明原因，
 * 不许再出现"有触发点、无写入方"那颗浮层（S2-2 的口径）。
 */
@Composable
fun BtChip(controller: BtSpeakerController, modifier: Modifier, onClick: () -> Unit) {
    val active by controller.active.collectAsState()
    val percent by controller.volumePercent.collectAsState()
    BtChip(connected = active != null, volumePct = percent, modifier = modifier, onClick = onClick)
}

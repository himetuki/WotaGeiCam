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
 * 录制页右侧的蓝牙音箱小控件（07 文档 §1.2：图标 + 连接态点 + 音量百分比），点击开 `BtSpeakerSheet`。
 *
 * @param connected A2DP 是否已连上（取 `BtSpeakerController.active != null`）
 * @param volumePct 媒体音量百分比，取 `BtSpeakerController.volumePercent`
 */
@Composable
fun BtChip(connected: Boolean, volumePct: Int, onClick: () -> Unit) {
    val accent = if (connected) WotaAccent else WotaTextDim
    Column(
        Modifier
            .wotaCard(WotaShape.medium)
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
 * 相机页调用形式：自己收 [BtSpeakerController] 的状态流，布局与上面的显式参数版完全一致。
 */
@Composable
fun BtChip(controller: BtSpeakerController, onClick: () -> Unit) {
    val active by controller.active.collectAsState()
    val percent by controller.volumePercent.collectAsState()
    BtChip(connected = active != null, volumePct = percent, onClick = onClick)
}

@file:OptIn(ExperimentalMaterial3Api::class)

package com.wotagei.cam.ui.dialog

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wotagei.cam.R
import com.wotagei.cam.bt.BtDevice
import com.wotagei.cam.bt.BtSpeakerController
import com.wotagei.cam.ui.design.WotaColor
import com.wotagei.cam.ui.theme.MonoStyle
import com.wotagei.cam.ui.theme.WotaAccent
import com.wotagei.cam.ui.theme.WotaDivider
import com.wotagei.cam.ui.theme.WotaSurface
import com.wotagei.cam.ui.theme.WotaText
import com.wotagei.cam.ui.theme.WotaTextDim
import com.wotagei.cam.ui.theme.WotaWarn
import kotlin.math.roundToInt

/**
 * 蓝牙音箱面板正文（需求五「录制界面小控件 + 对应弹窗」；07 文档 §1.2）。
 *
 * 结构：搜索开关 → 当前输出与电量 → 权限/蓝牙未开提示 → 设备分组列表（已配对 / 附近）
 * → 媒体音量滑杆 → 播放/上一曲/下一曲三键。标题与「收起」由外层 `WotaPillPopup` 给，
 * 所以这里从 2026-09-28 起不再自带标题，也不再是 `ModalBottomSheet`（用户要的是从右侧
 * 「蓝牙」胶囊就近弹出的小弹窗）。
 *
 * 权限申请在本组件里发起（`RequestMultiplePermissions`），回来后 [BtSpeakerController.refresh] 重算能力；
 * 「去设置」跳系统蓝牙页 —— A2DP 主动连接不可编程，所以列表里的「连接」按钮也走这条路。
 *
 * @param controller 由调用方 `remember { BtSpeakerController(context) }` 持有并在 onDispose 里 close
 */
@Composable
fun BtSpeakerPanel(controller: BtSpeakerController) {
    val context = LocalContext.current
    val devices by controller.devices.collectAsState()
    val active by controller.active.collectAsState()
    val volume by controller.mediaVolume.collectAsState()
    val battery by controller.batteryLevel.collectAsState()
    val hasPermission by controller.hasPermission.collectAsState()
    val scanning by controller.isScanning.collectAsState()
    val adapterEnabled by controller.adapterEnabled.collectAsState()
    val openSettings by controller.openSettings.collectAsState()
    val maxVolume = controller.maxVolume

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { controller.refresh() }

    LaunchedEffect(Unit) { controller.refresh() }
    LaunchedEffect(openSettings) {
        if (openSettings) {
            controller.consumeSettingsRequest()
            openBluetoothSettings(context)
        }
    }

    Column(
        // 先钳后铺（2026-10-05 紧凑化，顺序纪律见 CameraDialogs.BottomPanel 的 widthIn 注）：
        // 不钳的话 fillMaxWidth 会把整枚音源弹窗顶到 400dp 上限；240dp 够放下
        // 「设备行 + 音量滑杆 + 三枚传输键」，弹窗宽度改由各页签最宽内容决定
        Modifier
            .widthIn(max = 240.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 标题与「收起」由外层 WotaPillPopup 提供，这里只把搜索开关靠右留着
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (scanning) controller.stopScan() else controller.startScan() }) {
                Icon(
                    if (scanning) Icons.Filled.BluetoothSearching else Icons.Filled.Bluetooth,
                    contentDescription = stringResource(
                        if (scanning) R.string.bt_stop_scan else R.string.bt_start_scan
                    ),
                    tint = if (scanning) WotaAccent else WotaTextDim
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (active == null) stringResource(R.string.bt_no_active)
                else stringResource(R.string.bt_active, active?.displayLabel().orEmpty()),
                style = MaterialTheme.typography.bodyMedium,
                // 正文性信息不走 accent 小字（accent 压面板底 4.48 < 4.5）也不走 textLo（3.82）：
                // 有设备名是本行主信息给 textHi，未连接的提示降半档给 textMid，两档压面板底都过 AA 正文
                color = if (active == null) WotaColor.textMid else WotaText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            val level = battery
            if (level != null) {
                Text(
                    stringResource(R.string.bt_battery, level),
                    style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                    color = WotaTextDim
                )
            } else if (active != null) {
                // 连上了但不报电量（多数音箱不给 A2DP 通道上报），明确写「未知」而不是空着
                Text(stringResource(R.string.bt_battery_unknown), style = MaterialTheme.typography.labelSmall, color = WotaTextDim)
            }
        }

        if (!hasPermission) {
            HintCard(stringResource(R.string.perm_bluetooth_rationale))
            // 双按钮左右排间距 12vp（hw_button 响应式布局节；面板本身是气泡族、不套 ⑧ 半模态尺寸）
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { permissionLauncher.launch(controller.missingPermissions()) }) {
                    // 可点动作保语义不保 accent：小字 accent 压面板底 4.48 过不了 AA，
                    // 改 textHi（压底 4.5+ 达标）+ 下划线（链接惯例）表达「可点」
                    Text(
                        stringResource(R.string.bt_grant),
                        color = WotaText,
                        textDecoration = TextDecoration.Underline
                    )
                }
                TextButton(onClick = { openBluetoothSettings(context) }) {
                    Text(stringResource(R.string.perm_open_settings))
                }
            }
        }
        if (!adapterEnabled) {
            HintCard(stringResource(R.string.bt_adapter_off), warn = true)
        }

        val paired = devices.filter { it.bonded }
        val nearby = devices.filter { !it.bonded }
        if (paired.isEmpty() && nearby.isEmpty()) {
            // bodyMedium 是正文，textLo 只够非正文 3:1 档（压面板底 3.82），正文升 textMid
            Text(stringResource(R.string.bt_no_device), style = MaterialTheme.typography.bodyMedium, color = WotaColor.textMid)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (paired.isNotEmpty()) {
                SectionLabel(stringResource(R.string.bt_group_paired))
                paired.forEach { device ->
                    DeviceRow(device, device.address == active?.address, battery) { controller.connect(device) }
                }
            }
            if (nearby.isNotEmpty() || scanning) {
                SectionLabel(
                    if (scanning) stringResource(R.string.bt_group_nearby_scanning)
                    else stringResource(R.string.bt_group_nearby)
                )
                nearby.forEach { device ->
                    DeviceRow(device, device.address == active?.address, battery) { controller.connect(device) }
                }
            }
        }

        Text(stringResource(R.string.bt_tip_settings_only), style = MaterialTheme.typography.labelSmall, color = WotaTextDim)

        // 音量：滑杆只吃 0..1，档位换算留在这里，maxVolume 一律运行时取
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.VolumeUp, contentDescription = stringResource(R.string.bt_volume), tint = WotaTextDim)
            val fraction = if (maxVolume <= 0) 0f else (volume.toFloat() / maxVolume).coerceIn(0f, 1f)
            Slider(
                value = fraction,
                onValueChange = { controller.setVolume((it * maxVolume).roundToInt()) },
                enabled = maxVolume > 0,
                modifier = Modifier.weight(1f)
            )
            Text(
                stringResource(R.string.bt_volume_value, (fraction * 100f).roundToInt()),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelMedium.fontSize),
                color = WotaTextDim,
                modifier = Modifier.width(46.dp)
            )
        }

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.dp)
            ) {
                IconButton(onClick = { controller.prev() }) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = stringResource(R.string.bt_prev), tint = WotaText)
                }
                IconButton(onClick = { controller.playPause() }) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.bt_play_pause),
                        tint = WotaAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }
                IconButton(onClick = { controller.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = stringResource(R.string.bt_next), tint = WotaText)
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = WotaTextDim,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun HintCard(text: String, warn: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (warn) WotaWarn else WotaText,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (warn) WotaWarn.copy(alpha = 0.12f) else WotaDivider.copy(alpha = 0.24f))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

@Composable
private fun DeviceRow(device: BtDevice, isActive: Boolean, battery: Int?, onConnect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 连接态圆点：主色 = A2DP 已连，暗色 = 仅配对未连
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (device.connected) WotaAccent else WotaDivider)
        )
        Column(Modifier.weight(1f)) {
            Text(
                device.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(if (device.connected) R.string.bt_connected else R.string.bt_disconnected),
                style = MaterialTheme.typography.labelSmall,
                // 状态文案不用 accent 小字（4.48 < 4.5）：「已连接」的语义由行首 LED（accent 圆点）承载；
                // 未连接分支是 labelSmall 辅助级，留 textLo 合规
                color = if (device.connected) WotaColor.textMid else WotaTextDim
            )
        }
        if (isActive && battery != null) {
            Text(
                stringResource(R.string.bt_battery, battery),
                style = MonoStyle.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
                color = WotaTextDim
            )
        }
        if (!device.connected) {
            // 同「授予权限」：动作小字用 textHi + 下划线表达可点，不用过不了 AA 的 accent
            TextButton(onClick = onConnect) {
                Text(
                    stringResource(R.string.bt_connect),
                    color = WotaText,
                    textDecoration = TextDecoration.Underline
                )
            }
        }
    }
}

/** 无名设备退化成 MAC 地址，避免出现空白行 */
private fun BtDevice.displayLabel(): String = name.ifBlank { address }

private fun openBluetoothSettings(context: Context) {
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure { Log.w("WotaBt", "start bluetooth settings failed: ${it.message}") }
}

package com.wotagei.cam.record

import android.content.Context
import android.media.AudioManager

/** 当前输出路由是否蓝牙 A2DP（预校准只看这一条；不新增权限） */
interface AudioRouteProbe {
    fun isA2dpOutput(): Boolean
}

/**
 * 系统音频路由探测：优先读**活动路由** `isBluetoothA2dpOn`（A2DP 已连接 ≠ 当前输出正在走 A2DP），
 * 再用 `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` 里是否出现 TYPE_BLUETOOTH_A2DP 兜底
 * （部分 ROM 的废弃标志不准）。
 *
 * 两者都只读路由状态，**不需要任何权限**；整段 runCatching 包住，探测失败按"不是 A2DP"处理
 * ——预校准是增益功能，宁可跳过也不许影响起录。
 */
class AndroidAudioRouteProbe(context: Context) : AudioRouteProbe {

    private val appContext = context.applicationContext

    override fun isA2dpOutput(): Boolean {
        val am = appContext.getSystemService(AudioManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val active = runCatching { am.isBluetoothA2dpOn }.getOrDefault(false)
        if (active) return true
        // 兜底：设备列表里有已连接的 A2DP 输出（不代表当前活动路由，故排在活动判据之后）
        val types = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.toIntArray()
        }.getOrNull()
        return types != null && AudioPreflightPolicy.a2dpFromOutputTypes(types)
    }
}

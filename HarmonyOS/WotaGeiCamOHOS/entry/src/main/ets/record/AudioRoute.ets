/**
 * 当前输出路由是否「疑似蓝牙 A2DP」（预校准门控的唯一路由判据；只读路由状态，不新增权限）。
 * 直译上游 record/AudioRoute.kt 的判定面，对齐上游 e950328；本文件是本批唯一平台件。
 *
 * 【降级语义登记（对齐上游口径）】上游 AndroidAudioRouteProbe 优先读**活动路由**
 * `AudioManager.isBluetoothA2dpOn`（A2DP 已连接 ≠ 当前输出正在走 A2DP），再用设备列表
 * `getDevices(GET_DEVICES_OUTPUTS)` 里是否出现 TYPE_BLUETOOTH_A2DP 兜底（部分 ROM 的
 * 废弃标志不准）。OHOS 的 AudioRoutingManager **没有活动路由查询 API**，本侧只有
 * `getDevicesSync(OUTPUT_DEVICES_FLAG)` 的设备列表判据——即上游的**兜底**路径：
 * 语义是「设备列表含 A2DP」而非「当前活动路由正走 A2DP」。代价 = A2DP 已连接但未激活时
 * 会多跑一次预校准（无害：预校准失败路径恒 shift=0，绝不影响起录）。活动判据位以 false
 * 注入纯策略（AudioPreflightPolicy.a2dpFromOutputTypes），将来有活动路由 API 时只改这里。
 *
 * 探测异常一律按「不是 A2DP」处理——预校准是增益功能，宁可跳过也不许影响起录。
 */
import { audio } from '@kit.AudioKit';
import { AudioPreflightPolicy } from './AudioPreflightPolicy';

/** 设备列表含 BLUETOOTH_A2DP 即认为疑似 A2DP 输出（上游兜底判据；见文件头降级登记） */
export function isA2dpLikelyOn(): boolean {
  try {
    const routing: audio.AudioRoutingManager =
      audio.getAudioManager().getRoutingManager();
    const devices: audio.AudioDeviceDescriptors =
      routing.getDevicesSync(audio.DeviceFlag.OUTPUT_DEVICES_FLAG);
    let listHasA2dp: boolean = false;
    for (const d of devices) {
      if (d.deviceType === audio.DeviceType.BLUETOOTH_A2DP) {
        listHasA2dp = true;
        break;
      }
    }
    // 活动路由判据 OHOS 无对应 API，恒 false（见文件头登记）
    return AudioPreflightPolicy.a2dpFromOutputTypes(false, listHasA2dp);
  } catch (e) {
    return false;
  }
}

/**
 * 蓝牙音箱面板的数据源（上游 `bt/BtSpeakerController.kt` 的**减法版**平台面）。
 *
 * 迁移与登记（调研报告 §1）：
 * - 配对表 `connection.getPairedDevices()`、设备名 `getRemoteDeviceName`、A2DP 连接表
 *   `a2dp.createA2dpSrcProfile().getConnectedDevices()`、电量 `getRemoteDeviceBatteryInfo`
 *   （since 12）+ `on('batteryChange')` 实时刷新——**不走上游的 GATT 电池服务**（PORT_MAP
 *   原登记据此修订）；
 * - **不迁**：STREAM_MUSIC 音量写（无 setVolume 等价）、播放/上一曲/下一曲三键（avsession
 *   控制器需 system 权限）、扫描发现（startBluetoothDiscovery 需定位权限且面板价值低——
 *   减法定版：连接管理交给系统设置，本面板只读展示）；
 * - 权限：`ohos.permission.ACCESS_BLUETOOTH`（normal，只声明不弹窗）；
 * - 一切异常（蓝牙关/能力不支持 801/服务未就绪）折成 `adapterOn=false` 或空表 + 日志，
 *   面板按引导态显示，绝不崩。
 */
import { access, a2dp, connection } from '@kit.ConnectivityKit';
import { Diag } from '../diag/Diag';
import { BtEntry, BtSpeakerModel } from './BtSpeakerModel';

const TAG: string = 'WotaBt';

export class BtSpeakerController {
  /** 蓝牙开关态（读不到/异常一律按关处理，面板走「去系统设置开蓝牙」引导） */
  static adapterOn(): boolean {
    try {
      return access.getState() === access.BluetoothState.STATE_ON;
    } catch (e) {
      Diag.w(TAG, `bt state unavailable: ${e}`);
      return false;
    }
  }

  /**
   * 读一份完整面板数据：配对表 + 名字 + A2DP 连接态 + 电量（逐台查，失败按未上报）。
   * 蓝牙关/无能力 → 空表。
   */
  static async loadEntries(): Promise<Array<BtEntry>> {
    if (!BtSpeakerController.adapterOn()) {
      return [];
    }
    try {
      const pairedIds: Array<string> = connection.getPairedDevices();
      const connectedIds: Array<string> = BtSpeakerController.a2dpConnectedIds();
      const base: Array<BtEntry> = [];
      for (const id of pairedIds) {
        const name: string = BtSpeakerController.nameOf(id);
        const battery: number = await BtSpeakerController.batteryOf(id);
        base.push(new BtEntry(id, name, false, battery));
      }
      // A2DP 已连但不在配对表的：补名字与电量
      const known: Set<string> = new Set<string>(pairedIds);
      for (const id of connectedIds) {
        if (!known.has(id)) {
          const name: string = BtSpeakerController.nameOf(id);
          const battery: number = await BtSpeakerController.batteryOf(id);
          base.push(new BtEntry(id, name, true, battery));
        }
      }
      return BtSpeakerModel.mergeEntries(base, connectedIds);
    } catch (e) {
      Diag.w(TAG, `load entries failed: ${e}`);
      return [];
    }
  }

  /** A2DP 连接表（拿不到按空处理） */
  private static a2dpConnectedIds(): Array<string> {
    try {
      const profile: a2dp.A2dpSourceProfile = a2dp.createA2dpSrcProfile();
      return profile.getConnectedDevices();
    } catch (e) {
      Diag.w(TAG, `a2dp connected list failed: ${e}`);
      return [];
    }
  }

  private static nameOf(deviceId: string): string {
    try {
      const name: string = connection.getRemoteDeviceName(deviceId);
      return name === '' ? deviceId : name;
    } catch (e) {
      return deviceId;
    }
  }

  /** 电量（0..100；查不到/不带电池服务 = -1） */
  private static async batteryOf(deviceId: string): Promise<number> {
    try {
      const info: connection.BatteryInfo = await connection.getRemoteDeviceBatteryInfo(deviceId);
      return info.batteryLevel;
    } catch (e) {
      // 大多数音箱不带电池上报（上游 getBatteryLevel 反射拿不到也是 null）——不算错误
      return -1;
    }
  }

  /**
   * 电量实时刷新（面板可见期间挂、隐藏即解）。
   *
   * **平台限制（登记）**：`BatteryInfo` **不带 deviceId**（SDK 核实，字段只有
   * batteryLevel + 左右耳/仓三组——耳塞式结构），回执无法区分是哪台设备。合并策略：
   * 按「**首个已连接的 A2DP 设备**」落值——面板的主用途就是看当前输出音箱的电量；
   * 双设备同时连接时另一台的读数要等手动刷新（loadEntries 逐台直查）。
   */
  static watchBattery(onChange: (level: number) => void): void {
    try {
      connection.on('batteryChange', (info: connection.BatteryInfo): void => {
        onChange(info.batteryLevel);
      });
    } catch (e) {
      Diag.w(TAG, `watch battery failed: ${e}`);
    }
  }

  static unwatchBattery(): void {
    try {
      connection.off('batteryChange');
    } catch (e) {
      // 未注册/服务未起：解绑失败无需处理
    }
  }

  /** 面板底部的减法说明（音量/播控的平台限制，一次说清） */
  static limitationNote(): string {
    return '音量与播放控制请用音箱上的按键或系统控制中心（系统不向第三方开放这两项）。';
  }
}

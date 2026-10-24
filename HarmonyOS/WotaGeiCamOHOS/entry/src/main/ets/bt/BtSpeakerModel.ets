/**
 * 蓝牙音箱面板的**纯逻辑层**（上游 `bt/BtSpeakerController.kt` 的可测面）。
 *
 * 上游该文件的平台面（A2DP 代理反射、STREAM_MUSIC 音量读写、媒体键播控）在 OHOS 无三方
 * 等价物（调研报告 §1：音量写 AudioVolumeGroupManager 无 setVolume、播控 avsession 控制器
 * 需 system 权限）——**减法定版**：本侧面板只做「配对列表 + A2DP 连接态 + 电量」，
 * 音量/播控不迁（登记）。电量走 `connection.getRemoteDeviceBatteryInfo`（since 12），
 * 不需要上游的反射 `getBatteryLevel`/GATT 电池服务（PORT_MAP 原登记据此修订）。
 *
 * 零平台 import：合并/排序/文案全可在 test/pure 钉住。
 */

/** 面板的一条设备（未知电量 = -1，取不到名字回退 deviceId） */
export class BtEntry {
  deviceId: string;
  name: string;
  a2dpConnected: boolean;
  /** 0..100；-1 = 系统没报（很多耳机/音箱不带电池服务） */
  batteryLevel: number;

  constructor(deviceId: string, name: string, a2dpConnected: boolean, batteryLevel: number) {
    this.deviceId = deviceId;
    this.name = name;
    this.a2dpConnected = a2dpConnected;
    this.batteryLevel = batteryLevel;
  }
}

export class BtSpeakerModel {
  /**
   * 合并配对表与 A2DP 连接表（上游 bonded + connected 双源的合并语义）：
   * - A2DP 已连但不在配对表里的设备也要显示（刚连上、配对表还没刷）；
   * - 去重按 deviceId；
   * - 排序：**已连接在前，其余稳定保持输入序**——不做名字排序（中文名的字节序无意义，
   *   且配对表顺序就是系统给的稳定顺序，重排只会让条目在刷新间跳位）。
   */
  static mergeEntries(paired: Array<BtEntry>, a2dpConnectedIds: Array<string>): Array<BtEntry> {
    const byId: Map<string, BtEntry> = new Map<string, BtEntry>();
    for (const e of paired) {
      byId.set(e.deviceId, e);
    }
    const connected: Set<string> = new Set<string>(a2dpConnectedIds);
    const out: Array<BtEntry> = [];
    byId.forEach((entry: BtEntry, id: string): void => {
      out.push(new BtEntry(id, entry.name, connected.has(id), entry.batteryLevel));
    });
    // 连接表里有、配对表里没有的：补进来（name 由平台层查，这里收 deviceId 占位）
    for (const id of a2dpConnectedIds) {
      if (!byId.has(id)) {
        out.push(new BtEntry(id, id, true, -1));
      }
    }
    // 只按连接态排序：Array.sort 稳定 ⇒ 未连接组保持输入（配对表）序
    out.sort((a: BtEntry, b: BtEntry): number => {
      if (a.a2dpConnected !== b.a2dpConnected) {
        return a.a2dpConnected ? -1 : 1;
      }
      return 0;
    });
    return out;
  }

  /** 电量文案：-1 = 未上报（很多音箱没有电池服务，如实说「—」而不是编 0%） */
  static batteryText(level: number): string {
    if (level < 0 || level > 100) {
      return '电量 —';
    }
    return `电量 ${level}%`;
  }

  /** 行状态文案：已连接 / 未连接（上游 getConnectionState 的两态化） */
  static stateText(a2dpConnected: boolean): string {
    return a2dpConnected ? '已连接' : '未连接';
  }
}

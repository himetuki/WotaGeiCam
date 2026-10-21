/**
 * AutoStop 薄平台壳（判据全部复用 record/AutoStopRules.ets 与 CameraLostWatch）：
 * - 每 1s 采样相机设备态（非 CLOSE → CLOSE 才触发，首拍 CLOSE 不算）；
 * - 每 30s 检查一次电量（batterySOC，异常按 -1 = 不停）与可用空间（VideoStore.freeSpaceMb）；
 * - 一次录制至多触发一发（fired 短路），命中即停表并回调 onStop(reason)。
 *
 * 范围（登记）：本批只接 GPU 主录制路径（GlPreview/GlRecorder）；AVRecorder 路径留待主 UI 批。
 */
import { batteryInfo } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { DeviceStatus } from '../camera/DeviceStatus';
import { Diag } from '../diag/Diag';
import { AutoStopRules, CameraLostWatch } from './AutoStopRules';
import { WOTA_RECORD_TAG } from './RecordResult';
import { VideoStore } from './VideoStore';

export class AutoStopMonitor {
  private ctx: common.Context;
  private isRecording: () => boolean;
  private deviceStatus: () => DeviceStatus;
  private onStop: (reason: string) => void;

  private timer: number = -1;
  private fired: boolean = false;
  private lastCheckMs: number = 0;
  private watch: CameraLostWatch = new CameraLostWatch();
  private lastBattery: number = -1;
  private lastFreeMb: number = -1;

  constructor(ctx: common.Context, isRecording: () => boolean,
    deviceStatus: () => DeviceStatus, onStop: (reason: string) => void) {
    this.ctx = ctx;
    this.isRecording = isRecording;
    this.deviceStatus = deviceStatus;
    this.onStop = onStop;
  }

  start(): void {
    this.stop();
    this.fired = false;
    // 首个电量/存储检查在首个 1s tick 立即执行（lastCheckMs=0），其后 30s 节拍（登记口径）
    this.lastCheckMs = 0;
    this.watch = new CameraLostWatch();
    // 以当前设备态预置 lastDevice：起录窗口内相机被收回时，下一拍即可识别非 CLOSE→CLOSE
    this.watch.onSample(false, this.deviceStatus());
    this.timer = setInterval((): void => {
      this.tick();
    }, 1000);
    Diag.i(WOTA_RECORD_TAG, 'autoStop monitor started (camera 1s / battery+storage 30s)');
  }

  stop(): void {
    if (this.timer >= 0) {
      clearInterval(this.timer);
      this.timer = -1;
    }
  }

  private tick(): void {
    if (this.fired || !this.isRecording()) {
      return;
    }
    // 相机优先：丢失要快（1s 节拍）
    if (this.watch.onSample(true, this.deviceStatus())) {
      this.lastBattery = AutoStopMonitor.safeBatterySoc();
      this.lastFreeMb = VideoStore.freeSpaceMb(this.ctx);
      this.fire(AutoStopRules.REASON_CAMERA_LOST);
      return;
    }
    const now: number = Date.now();
    if (now - this.lastCheckMs < AutoStopRules.CHECK_INTERVAL_MS) {
      return;
    }
    this.lastCheckMs = now;
    this.lastBattery = AutoStopMonitor.safeBatterySoc();
    if (AutoStopRules.batteryStopNeeded(this.lastBattery)) {
      this.fire(AutoStopRules.REASON_BATTERY);
      return;
    }
    this.lastFreeMb = VideoStore.freeSpaceMb(this.ctx);
    if (AutoStopRules.storageStopNeeded(this.lastFreeMb)) {
      this.fire(AutoStopRules.REASON_STORAGE);
    }
  }

  private static safeBatterySoc(): number {
    try {
      const soc: number = batteryInfo.batterySOC;
      return Number.isFinite(soc) ? soc : -1;
    } catch (e) {
      return -1;
    }
  }

  private fire(reason: string): void {
    if (this.fired) {
      return;
    }
    this.fired = true;
    this.stop();
    Diag.i(WOTA_RECORD_TAG,
      `autoStop fired reason=${reason} battery=${this.lastBattery}% free=${this.lastFreeMb}MB`);
    this.onStop(reason);
  }
}

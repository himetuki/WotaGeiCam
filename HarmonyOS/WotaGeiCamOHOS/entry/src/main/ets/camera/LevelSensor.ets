/**
 * 水平仪 / 俯仰仪数据源——上游 camera/SensorLevel.kt 的鸿蒙移植（`@ohos.sensor` + `@ohos.vibrator`）。
 *
 * 采样源**优先 GRAVITY（融合重力，低延迟）**、无融合回退 ACCELEROMETER（只拿静态重力即可，
 * 不碰旋转矢量）；采样间隔取 GAME 档量级（约 50Hz：够跟手，又不到 FASTEST 的功耗）。
 *
 * 角度语义全部在 `core/LevelMath`（纯逻辑，可测）；本文件只做：低通、丢帧闸、
 * 发布节流、回正震动、以及**首启静默自愈**（上游真机缺陷：register 成功但 HAL 迟迟不吐样本，
 * 二次启动才正常——注册后 600ms 零样本就注销重挂，最多两次）。
 *
 * 为什么必须换算显示方向：加速度计给的是机头坐标系读数，只在竖屏时与屏幕系重合；
 * 不换算的话「横屏平举」会被读成 ±90°（上游真机实测 87.2°），水平仪整块失真。
 *
 * 生命周期：`start()`/`stop()` 与页面可见性强绑定；离开页面务必 `stop()`（传感器常开很费电）。
 */
import { sensor } from '@kit.SensorServiceKit';
import { vibrator } from '@kit.SensorServiceKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';
import { LevelConst, LevelMath } from '../core/LevelMath';

const TAG: string = 'WotaSensor';

/** 一帧读数（已换算到显示方向、已折叠） */
export class LevelReading {
  roll: number;
  pitch: number;
  isLevel: boolean;

  constructor(roll: number, pitch: number, isLevel: boolean) {
    this.roll = roll;
    this.pitch = pitch;
    this.isLevel = isLevel;
  }
}

/** 传感器读数回调（每次实际发布才触发；被节流吞掉的帧不回调） */
export type LevelListener = (reading: LevelReading) => void;

export class LevelSensor {
  /** 本机没有可用重力源计时为 false，UI 应据此隐藏仪表 */
  readonly available: boolean;
  /** 过 0° 是否震动（设置页 `level_buzz_enabled` 喂入）；关掉后两路都不震 */
  buzzEnabled: boolean = true;

  private listener: LevelListener;
  private useGravity: boolean = false;
  private registered: boolean = false;
  private displayDegrees: number = 0;
  private primed: boolean = false;
  /** 低通后的重力分量（undefined 语义 = 尚未收到首个样本；用 NaN 对齐上游） */
  private sx: number = Number.NaN;
  private sy: number = Number.NaN;
  private sz: number = Number.NaN;
  /** 当前对外读数（节流闸的比较基准） */
  private rollValue: number = 0;
  private pitchValue: number = 0;
  private levelValue: boolean = false;
  private samplesSeen: number = 0;
  private watchdogRetries: number = 0;
  private watchdogTimer: number = -1;
  /** 传感器回调（同一个函数引用：off 时能精确解绑） */
  private readonly handler: (data: sensor.GravityResponse | sensor.AccelerometerResponse) => void;

  constructor(listener: LevelListener) {
    this.listener = listener;
    let gravityOk: boolean = false;
    let accelOk: boolean = false;
    try {
      for (const s of sensor.getSensorListSync()) {
        if (s.sensorId === sensor.SensorId.GRAVITY) {
          gravityOk = true;
        }
        if (s.sensorId === sensor.SensorId.ACCELEROMETER) {
          accelOk = true;
        }
      }
    } catch (e) {
      Diag.w(TAG, 'sensor list unavailable', e as Error);
    }
    this.useGravity = gravityOk;
    this.available = gravityOk || accelOk;
    this.handler = (data: sensor.GravityResponse | sensor.AccelerometerResponse): void => {
      this.onSample(data.x, data.y, data.z);
    };
    if (!this.available) {
      Diag.w(TAG, 'no gravity/accelerometer sensor, level meter unavailable');
    }
  }

  /**
   * 喂入当前显示旋转角（0/90/180/270）。不调用时按 0 处理（等价上游「换算前的老行为」）。
   */
  setDisplayDegrees(degrees: number): void {
    this.displayDegrees = ((Math.trunc(degrees) % 360) + 360) % 360;
  }

  /** 开始采样；重复调用无副作用 */
  start(): void {
    if (!this.available || this.registered) {
      return;
    }
    this.samplesSeen = 0;
    try {
      // 约 50Hz（GAME 档）：够跟手，又不到 FASTEST 的功耗（对齐上游 SENSOR_DELAY_GAME）
      const options: sensor.Options = { interval: 'game' };
      if (this.useGravity) {
        sensor.on(sensor.SensorId.GRAVITY, this.handler as (d: sensor.GravityResponse) => void, options);
      } else {
        sensor.on(sensor.SensorId.ACCELEROMETER,
          this.handler as (d: sensor.AccelerometerResponse) => void, options);
      }
      this.registered = true;
    } catch (e) {
      Diag.w(TAG, 'sensor.on failed', e as Error);
      this.registered = false;
      return;
    }
    this.scheduleWatchdog();
  }

  /** 停止采样；下次 start 从当前姿态重新收敛，不会用旧值做低通起点 */
  stop(): void {
    this.clearWatchdog();
    // 重挂预算随会话复位：两次重试耗尽只代表「这一次注册窗口」没救活
    this.watchdogRetries = 0;
    if (!this.registered) {
      return;
    }
    this.registered = false;
    try {
      if (this.useGravity) {
        sensor.off(sensor.SensorId.GRAVITY);
      } else {
        sensor.off(sensor.SensorId.ACCELEROMETER);
      }
    } catch (e) {
      Diag.w(TAG, 'sensor.off failed', e as Error);
    }
    this.sx = Number.NaN;
    this.sy = Number.NaN;
    this.sz = Number.NaN;
    this.primed = false;
  }

  // ------------------------------------------------------------------ 采样与发布

  private onSample(x: number, y: number, z: number): void {
    // 单帧吐 NaN/Infinity 必须入口丢弃：NaN 首帧会经 primed 分支直落读数，
    // 此后所有 NaN 比较恒 false，读数会被永久钉死（上游注释登记的同类缺陷）。
    if (!Number.isFinite(x) || !Number.isFinite(y) || !Number.isFinite(z)) {
      return;
    }
    if (this.samplesSeen === 0) {
      this.watchdogRetries = 0; // 自愈成功（或本就正常）：预算归零
    }
    this.samplesSeen++;
    // 低通加在重力分量上而不是角度上：角度在 ±90 处会绕回，直接滤角度会在两端抖出跳变
    if (Number.isNaN(this.sx)) {
      this.sx = x;
      this.sy = y;
      this.sz = z;
    } else {
      const a: number = LevelConst.SMOOTH_ALPHA;
      this.sx += a * (x - this.sx);
      this.sy += a * (y - this.sy);
      this.sz += a * (z - this.sz);
    }
    const magnitude: number = Math.sqrt(this.sx * this.sx + this.sy * this.sy + this.sz * this.sz);
    // 剧烈抖动/近似自由落体时读数不可信，丢掉这一帧
    if (magnitude < LevelConst.MIN_VALID_G * LevelConst.GRAVITY) {
      return;
    }
    const planar: number = Math.sqrt(this.sx * this.sx + this.sy * this.sy);
    // 机器接近「平放」时 X-Y 投影长度趋 0，roll 数值上完全失真——保持上一次的值
    const rollValid: boolean = planar >= LevelConst.FLAT_GUARD * magnitude;
    const display: Array<number> = LevelMath.displayVector(this.sx, this.sy, this.displayDegrees);
    const pitchDeg: number = LevelMath.pitchOf(this.sx, this.sy, this.sz);
    const rollDeg: number = rollValid ? LevelMath.rollOf(display[0], display[1]) : this.rollValue;

    if (!this.primed) {
      // 首帧只落值、不震动：否则一进取景页就震一下
      this.primed = true;
      this.pitchValue = pitchDeg;
      if (rollValid) {
        this.rollValue = rollDeg;
      }
      this.levelValue = rollValid && LevelMath.isLevelOf(rollDeg);
      this.publish();
      return;
    }
    // 俯仰过 0° 与左右回正是两路独立提示，不能共用 roll 那条早退路径。
    // 回正震动挂在「落值」闸门内侧（与 roll 走 levelValue 边沿同一结构）：
    // 被节流吞掉的帧不落值，若在闸门外拿旧值当「上一状态」，慢速跨进容差带时会逐帧重触发成连震。
    if (Math.abs(pitchDeg - this.pitchValue) >= LevelConst.EMIT_STEP_DEG) {
      const pitchWasLevel: boolean = LevelMath.isLevelOf(this.pitchValue);
      this.pitchValue = pitchDeg;
      if (!pitchWasLevel && LevelMath.isLevelOf(pitchDeg)) {
        this.buzz();
      }
    }
    if (!rollValid || Math.abs(rollDeg - this.rollValue) < LevelConst.EMIT_STEP_DEG) {
      return;
    }
    this.rollValue = rollDeg;
    const level: boolean = LevelMath.isLevelOf(rollDeg);
    if (level === this.levelValue) {
      this.publish();
      return;
    }
    this.levelValue = level;
    if (level) {
      this.buzz();
    }
    this.publish();
  }

  private publish(): void {
    this.listener(new LevelReading(this.rollValue, this.pitchValue, this.levelValue));
  }

  // ------------------------------------------------------------------ 静默自愈 watchdog

  private scheduleWatchdog(): void {
    if (this.watchdogRetries >= LevelConst.WATCHDOG_MAX_RETRIES) {
      return;
    }
    this.clearWatchdog();
    this.watchdogTimer = setTimeout((): void => {
      this.watchdogTimer = -1;
      this.onWatchdog();
    }, LevelConst.WATCHDOG_DELAY_MS);
  }

  private clearWatchdog(): void {
    if (this.watchdogTimer >= 0) {
      clearTimeout(this.watchdogTimer);
      this.watchdogTimer = -1;
    }
  }

  private onWatchdog(): void {
    if (!this.registered || this.samplesSeen > 0) {
      return;
    }
    if (this.watchdogRetries >= LevelConst.WATCHDOG_MAX_RETRIES) {
      Diag.w(TAG, `sensor silent after ${this.watchdogRetries} re-register attempts, giving up`);
      return;
    }
    this.watchdogRetries++;
    Diag.w(TAG, `sensor silent after register, re-registering (retry=${this.watchdogRetries})`);
    this.registered = false;
    try {
      if (this.useGravity) {
        sensor.off(sensor.SensorId.GRAVITY);
      } else {
        sensor.off(sensor.SensorId.ACCELEROMETER);
      }
    } catch (e) {
      // 已注销或本就未注册：继续重挂
    }
    this.start();
  }

  // ------------------------------------------------------------------ 回正震动

  /** 回正瞬间单次轻震（「低成本高感知」细节）；无振动马达/无权限的机器静默跳过 */
  private buzz(): void {
    if (!this.buzzEnabled) {
      return;
    }
    const effect: vibrator.VibrateTime = { type: 'time', duration: LevelConst.BUZZ_MS };
    // usage 必填；回正提示属轻触反馈（贴近上游 DEFAULT_AMPLITUDE 单次短震的语义）
    const attr: vibrator.VibrateAttribute = { id: 0, usage: 'touch' };
    vibrator.startVibration(effect, attr).catch((e: BusinessError): void => {
      Diag.w(TAG, `vibrate failed: ${e.message}`);
    });
  }
}

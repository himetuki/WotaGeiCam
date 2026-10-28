/**
 * 取景页音量表的麦克风实时电平探针（平台件）。
 *
 * 职责边界：只读电平，不申请权限、不碰录制链。MICROPHONE 权限已随录制在 module.json5
 * 声明、由取景页申请；本模块未授权 / 被占用 / 与录制采集冲突时 createAudioCapturer
 * 失败，一律静默保持未启动态——电平表是增益功能，绝不许阻塞取景或录制。
 *
 * 采集口径：MIC 源、16kHz、单声道、S16LE；setInterval 20ms 一拍，每拍非阻塞 read
 * 640 字节（320 样本 = 20ms），RMS → dBFS → (db + 60) / 60 钳 [0,1]，回调 100ms 节流。
 *
 * 自愈：read 返回 0 / 异常只记日志（2s 节流）不退出循环；stop 幂等，且与在途
 * create/start 竞争时按「先置未启动态、落地后立刻 teardown」收口，不留野采集器。
 */
import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';

const TAG: string = 'WotaAudio';
/** 单拍读块：16kHz × 单声道 × 2 字节 × 20ms = 640B（320 样本） */
const READ_BYTES: number = 640;
/** 读拍间隔（与读块对齐，一拍一读，非阻塞 read 不占 UI 线程） */
const TICK_MS: number = 20;
/** 回调节流：UI 电平表 10Hz 足够，避免每拍刷一次状态 */
const EMIT_INTERVAL_MS: number = 100;
/** 错误日志节流：采集被占/拔麦时 read 每拍都可能失败，不许刷爆日志 */
const ERROR_LOG_INTERVAL_MS: number = 2000;
/** 电平映射窗：-60dBFS → 0，0dBFS → 1 */
const LEVEL_RANGE_DB: number = 60;

export class AudioLevelProbe {
  /** 当前回调；start 在运行中重复调用只替换它（幂等） */
  private static cb: ((level: number) => void) | undefined = undefined;
  /** 已拿到并已启动的采集器；undefined = 未启动态 */
  private static capturer: audio.AudioCapturer | undefined = undefined;
  /** createAudioCapturer 在途（防重复建路；stop 不打断，落地后按 cb 是否存在收口） */
  private static creating: boolean = false;
  private static timer: number = -1;
  /** 读在途旗标：上一拍非阻塞 read 未落地时跳过本拍，防 promise 堆积 */
  private static reading: boolean = false;
  private static lastEmitMs: number = 0;
  private static lastErrorLogMs: number = 0;

  /**
   * 启动电平探针（幂等）：未运行则建 MIC 采集器并起 20ms 读拍；已运行只替换回调。
   * 建路/启动失败全吞（只记 W 级日志）——未授权或与录制采集冲突时保持未启动态，
   * 下次 start 会再试。
   */
  static start(cb: (level: number) => void): void {
    AudioLevelProbe.cb = cb;
    if (AudioLevelProbe.capturer !== undefined || AudioLevelProbe.creating) {
      return;
    }
    AudioLevelProbe.creating = true;
    try {
      audio.createAudioCapturer(AudioLevelProbe.options())
        .then((cap: audio.AudioCapturer): void => {
          AudioLevelProbe.creating = false;
          AudioLevelProbe.onCreated(cap);
        })
        .catch((e: BusinessError): void => {
          AudioLevelProbe.creating = false;
          Diag.w(TAG, `createAudioCapturer failed: ${e.message}`);
        });
    } catch (e) {
      AudioLevelProbe.creating = false;
      Diag.w(TAG, `createAudioCapturer threw: ${e}`);
    }
  }

  /** 停止并释放（幂等）：清定时器、置未启动态，采集器 stop → release */
  static stop(): void {
    AudioLevelProbe.cb = undefined;
    if (AudioLevelProbe.timer >= 0) {
      clearInterval(AudioLevelProbe.timer);
      AudioLevelProbe.timer = -1;
    }
    AudioLevelProbe.reading = false;
    const cap: audio.AudioCapturer | undefined = AudioLevelProbe.capturer;
    AudioLevelProbe.capturer = undefined;
    if (cap !== undefined) {
      AudioLevelProbe.teardown(cap);
    }
  }

  /** 采集器参数：MIC / 16kHz / 单声道 / S16LE（取景页电平表口径） */
  private static options(): audio.AudioCapturerOptions {
    return {
      streamInfo: {
        samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_16000,
        channels: audio.AudioChannel.CHANNEL_1,
        sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
        encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
      },
      capturerInfo: {
        source: audio.SourceType.SOURCE_TYPE_MIC,
        capturerFlags: 0
      }
    };
  }

  /** create 落地：stop 已先到（cb 没了）则直接 teardown；否则启动并起读拍 */
  private static onCreated(cap: audio.AudioCapturer): void {
    if (AudioLevelProbe.cb === undefined) {
      AudioLevelProbe.teardown(cap);
      return;
    }
    AudioLevelProbe.capturer = cap;
    try {
      cap.start()
        .then((): void => {
          // stop 在启动在途时已收口（capturer 被清空并 teardown），这里不再碰
          if (AudioLevelProbe.capturer !== cap) {
            return;
          }
          AudioLevelProbe.startTimer();
        })
        .catch((e: BusinessError): void => {
          const owned: boolean = AudioLevelProbe.capturer === cap;
          if (owned) {
            AudioLevelProbe.capturer = undefined;
          }
          Diag.w(TAG, `capturer.start failed: ${e.message}`);
          if (owned) {
            AudioLevelProbe.teardown(cap);
          }
        });
    } catch (e) {
      AudioLevelProbe.capturer = undefined;
      Diag.w(TAG, `capturer.start threw: ${e}`);
      AudioLevelProbe.teardown(cap);
    }
  }

  private static startTimer(): void {
    if (AudioLevelProbe.timer >= 0) {
      return;
    }
    AudioLevelProbe.lastEmitMs = 0;
    AudioLevelProbe.timer = setInterval((): void => {
      AudioLevelProbe.tick();
    }, TICK_MS);
  }

  /**
   * 一拍：非阻塞 read 一次。读在途时跳过本拍（防堆积）；返回 0 字节/异常都只落
   * reading=false 等下一拍，绝不 clearInterval 退出。
   */
  private static tick(): void {
    const cap: audio.AudioCapturer | undefined = AudioLevelProbe.capturer;
    if (cap === undefined || AudioLevelProbe.reading) {
      return;
    }
    AudioLevelProbe.reading = true;
    try {
      cap.read(READ_BYTES, false)
        .then((buf: ArrayBuffer): void => {
          // stop 后到达的数据丢弃；旧代结果也不许复位新采集器的在途旗标
          if (AudioLevelProbe.capturer !== cap) {
            return;
          }
          AudioLevelProbe.reading = false;
          AudioLevelProbe.onBuffer(buf);
        })
        .catch((e: BusinessError): void => {
          if (AudioLevelProbe.capturer !== cap) {
            return;
          }
          AudioLevelProbe.reading = false;
          AudioLevelProbe.logReadError(e);
        });
    } catch (e) {
      AudioLevelProbe.reading = false;
      AudioLevelProbe.logReadError(e as BusinessError);
    }
  }

  /** 640B → 320 个 S16 样本 → RMS → dBFS → 0..1；100ms 节流后交回调 */
  private static onBuffer(buf: ArrayBuffer): void {
    const count: number = Math.floor(buf.byteLength / 2);
    if (count <= 0) {
      return; // 本拍无数据：下一拍继续
    }
    let sum: number = 0;
    try {
      const view: Int16Array = new Int16Array(buf, 0, count);
      for (let i = 0; i < count; i++) {
        const v: number = view[i] / 32768;
        sum += v * v;
      }
    } catch (e) {
      return; // 读块异常：按无数据处理，循环不断
    }
    const rms: number = Math.sqrt(sum / count);
    let level: number = 0;
    if (rms > 0) {
      const db: number = 20 * Math.log10(rms);
      level = (db + LEVEL_RANGE_DB) / LEVEL_RANGE_DB;
    }
    if (!(level > 0)) {
      level = 0; // 含 rms=0 的 -Infinity 与潜在 NaN
    } else if (level > 1) {
      level = 1;
    }
    const now: number = Date.now();
    if (now - AudioLevelProbe.lastEmitMs < EMIT_INTERVAL_MS) {
      return;
    }
    AudioLevelProbe.lastEmitMs = now;
    const cb: ((level: number) => void) | undefined = AudioLevelProbe.cb;
    if (cb === undefined) {
      return;
    }
    try {
      cb(level);
    } catch (e) {
      Diag.w(TAG, `level callback threw: ${e}`);
    }
  }

  /** read 失败日志：2s 节流（真机长按录制抢麦时别把 hilog 冲掉） */
  private static logReadError(e: BusinessError): void {
    const now: number = Date.now();
    if (now - AudioLevelProbe.lastErrorLogMs < ERROR_LOG_INTERVAL_MS) {
      return;
    }
    AudioLevelProbe.lastErrorLogMs = now;
    Diag.w(TAG, `capturer.read failed: ${e.message}`);
  }

  /** stop → release；stop 失败（未在跑/已停）也必须 release，全部吞 */
  private static teardown(cap: audio.AudioCapturer): void {
    try {
      cap.stop()
        .then((): void => {
          AudioLevelProbe.releaseQuiet(cap);
        })
        .catch((_e: BusinessError): void => {
          AudioLevelProbe.releaseQuiet(cap);
        });
    } catch (e) {
      AudioLevelProbe.releaseQuiet(cap);
    }
  }

  private static releaseQuiet(cap: audio.AudioCapturer): void {
    try {
      cap.release().catch((_e: BusinessError): void => {
        // 已释放/句柄失效：吞
      });
    } catch (e) {
      // release 同步抛：吞（采集器已不可用）
    }
  }
}

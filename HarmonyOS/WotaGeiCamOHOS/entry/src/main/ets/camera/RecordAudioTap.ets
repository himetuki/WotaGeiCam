/**
 * 录制音轨麦克风采集（平台件）——上游 AudioFeeder 的 OHOS 面（2026-11-02）：
 * AudioCapturer(MIC) 48kHz 立体声 S16LE → 20ms 一拍读块 → NAPI pushAudioPcm →
 * native AAC 编码 + muxer 音轨（codec_recorder 的 PushAudioPcm）。
 *
 * 边界：只做采集与喂入；编码/封装全在 native。启动失败/未授权静默（音量表同姿态）——
 * 音轨是增益功能，绝不阻塞录制（视频照录，无音轨）。stop 幂等。
 */
import { audio } from '@kit.AudioKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { Diag } from '../diag/Diag';

/** native 喂入口（**动态加载**：GlLibBridge 静态 import 会把 libentry.so 拖进纯测闭包，
 *  与 ClipExporter「UI 层不得静态 import 桥」同一 load-order 纪律） */
type PushFn = (pcm: ArrayBuffer) => string;
let pushFn: PushFn | undefined = undefined;

const TAG: string = 'WotaRecordAudio';
/** 单拍读块：48kHz × 立体声 × 2B × 20ms = 3840B */
const READ_BYTES: number = 3840;
const TICK_MS: number = 20;

export class RecordAudioTap {
  private static capturer: audio.AudioCapturer | undefined = undefined;
  private static timer: number = -1;
  private static reading: boolean = false;
  private static enabled: boolean = false;

  private static options(): audio.AudioCapturerOptions {
    return {
      streamInfo: {
        samplingRate: audio.AudioSamplingRate.SAMPLE_RATE_48000,
        channels: audio.AudioChannel.CHANNEL_2,
        sampleFormat: audio.AudioSampleFormat.SAMPLE_FORMAT_S16LE,
        encodingType: audio.AudioEncodingType.ENCODING_TYPE_RAW
      },
      capturerInfo: {
        source: audio.SourceType.SOURCE_TYPE_MIC,
        capturerFlags: 0
      }
    };
  }

  /** 起采集并把 PCM 喂进 native 音轨（幂等；失败静默只记 W，不阻塞录制） */
  static start(): void {
    if (RecordAudioTap.enabled || RecordAudioTap.capturer !== undefined) {
      return;
    }
    RecordAudioTap.enabled = true;
    if (pushFn === undefined) {
      import('./GlLibBridge').then((m): void => {
        pushFn = m.pushAudioPcm;
      }).catch((e: Object): void => {
        Diag.w(TAG, `record audio bridge load failed: ${e}`);
      });
    }
    audio.createAudioCapturer(RecordAudioTap.options()).then((cap: audio.AudioCapturer): void => {
      if (!RecordAudioTap.enabled) {
        cap.release().catch((): void => {
        });
        return;
      }
      RecordAudioTap.capturer = cap;
      cap.start().then((): void => {
        RecordAudioTap.timer = setInterval((): void => {
          RecordAudioTap.readOnce();
        }, TICK_MS);
      }).catch((e: BusinessError): void => {
        Diag.w(TAG, `record audio start failed: ${e.code} ${e.message}`);
        RecordAudioTap.teardown();
      });
    }).catch((e: BusinessError): void => {
      Diag.w(TAG, `record audio create failed: ${e.code} ${e.message}`);
      RecordAudioTap.enabled = false;
    });
  }

  private static readOnce(): void {
    const cap: audio.AudioCapturer | undefined = RecordAudioTap.capturer;
    if (cap === undefined || RecordAudioTap.reading || !RecordAudioTap.enabled) {
      return;
    }
    RecordAudioTap.reading = true;
    cap.read(READ_BYTES, false).then((buf: ArrayBuffer): void => {
      RecordAudioTap.reading = false;
      const len: number = buf.byteLength;
      if (len > 0) {
        const fn: PushFn | undefined = pushFn;
        if (fn === undefined) {
          return; // 桥未就绪（首个 tick 前动态加载未落地）：丢一拍 <20ms，不致命
        }
        try {
          fn(buf);
        } catch (e) {
          Diag.w(TAG, `record audio push failed: ${e}`);
        }
      }
    }).catch((e: BusinessError): void => {
      RecordAudioTap.reading = false;
      Diag.w(TAG, `record audio read failed: ${e.code} ${e.message}`);
    });
  }

  /** 停采集（幂等；与起路竞争也收口，不压录制收尾） */
  static stop(): void {
    RecordAudioTap.enabled = false;
    RecordAudioTap.teardown();
  }

  private static teardown(): void {
    if (RecordAudioTap.timer >= 0) {
      clearInterval(RecordAudioTap.timer);
      RecordAudioTap.timer = -1;
    }
    const cap: audio.AudioCapturer | undefined = RecordAudioTap.capturer;
    RecordAudioTap.capturer = undefined;
    if (cap !== undefined) {
      cap.stop().catch((): void => {
      });
      cap.release().catch((): void => {
      });
    }
  }
}

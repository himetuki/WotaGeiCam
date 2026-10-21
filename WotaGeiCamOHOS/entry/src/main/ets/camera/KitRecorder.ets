/**
 * 最小录制引擎（AVRecorder 面输入 + VideoSession）——上游 MrRecorder（MediaRecorder 面输入）
 * 的 OHOS 等价物最小版（对齐上游 38d712a）。
 *
 * 平台差异（登记，执行口径钉死）：
 * - 会话切换：录制用独立 VideoSession(NORMAL_VIDEO)；开始前调用方先 PreviewEngine.stop()，
 *   停录后调用方重启预览（不做同会话双模式热切）；
 * - 音频：AUDIO_SOURCE_TYPE_MIC + AAC（BitratePolicy.AUDIO_BITRATE）；真值来源 = 权限允许 &&
 *   参数总线 audioEnabled——任一为假即 video-only（profile/config 不含任何 audio 字段）；
 * - 参数下发：VideoSession 无 ManualExposure/ManualIso/OIS，录制会话不做参数下发（登记）；
 * - 分段（3.5GiB 轮转）/AutoStop 巡检/FrameLedger 接线/MediaStore 入库/HEVC 重试/
 *   .drops.json 均留白（随录制后续批）；
 * - 输出契约：**AVRecorderConfig.url 用 `fd://<fd>`**（d.ts 明确 fd://xx；fileIo.openSync
 *   WRITE_ONLY|CREATE|TRUNC 建文件取 fd，fd 保留到 AVRecorder reset/release 之后才关闭）；
 *   不再使用裸路径/file:// 退路；
 * - 起录失败：统一 failStart（先释放资源并关 fd，再删已创建/半写的输出文件，不留幽灵件）；
 *   判废顺序：先 MIN_KEEP_MS（短段一律 discard + TOO_SHORT，含 engineErr 短段）；唯一例外
 *   是 av.stop 失败（STOP_FAILED，保留文件 triage，登记）；
 * - 重入保护：starting/stopping 标志，异步进行中拒绝再次进入（页面按钮同侧防重复）；
 * - 留白登记：orientationHint 恒 0 且未接 AVRecorderConfig.rotation（显示旋转源随 HUD 批）、
 *   sampleRate 恒 48k（未做设备支持探测）、mirrored/useGpu 恒 false。
 */
import { camera } from '@kit.CameraKit';
import { media } from '@kit.MediaKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { Diag } from '../diag/Diag';
import { Size } from '../core/Constants';
import { WotaParams } from '../core/WotaParams';
import { pickDirectPreviewSize, sizeCloseTo, VideoSizeCandidates } from '../core/VideoSizePicker';
import { BitratePolicy } from '../record/BitratePolicy';
import { SeriesIds } from '../record/SeriesIds';
import { VideoStore } from '../record/VideoStore';
import { RecordProfile, CODEC_H264, MIN_KEEP_MS } from '../record/RecordProfile';
import { RecordError, RecordResult, WOTA_RECORD_TAG } from '../record/RecordResult';
import { enumerateKitLenses, findDeviceById, pickDefaultLens } from './KitLens';

/**
 * VideoProfile 选档：优先「同尺寸（±容差）且帧率覆盖目标」；无覆盖取同尺寸 max 最大档；
 * 尺寸无匹配退全表并登记（调用方日志）。返回 undefined = 无任何视频档。
 */
function pickVideoProfile(profiles: Array<camera.VideoProfile>, want: Size, wantFps: number):
camera.VideoProfile | undefined {
  if (profiles.length === 0) {
    return undefined;
  }
  const sameSize: Array<camera.VideoProfile> = profiles.filter((p: camera.VideoProfile): boolean =>
    sizeCloseTo(new Size(p.size.width, p.size.height), want, VideoSizeCandidates.TOLERANCE_PX));
  const pool: Array<camera.VideoProfile> = sameSize.length > 0 ? sameSize : profiles;
  const covering: Array<camera.VideoProfile> = pool.filter((p: camera.VideoProfile): boolean =>
    p.frameRateRange !== undefined && p.frameRateRange.min <= wantFps && wantFps <= p.frameRateRange.max);
  const tier: Array<camera.VideoProfile> = covering.length > 0 ? covering : pool;
  let best: camera.VideoProfile = tier[0];
  for (const p of tier) {
    if (p.frameRateRange !== undefined && best.frameRateRange !== undefined &&
      p.frameRateRange.max > best.frameRateRange.max) {
      best = p;
    }
  }
  return best;
}

/**
 * 本地最小接口（仅 prepare 一项）：SDK 的 AVRecorder.prepare 在 since-19 的 JSDoc 副本里
 * @permission 后多了一句 "This permission is required only if audio recording is involved."，
 * ets-loader 的 @permission 检查会把该句逐词当权限名解析（api_check_permission.validPermission
 * 以最后一个词判有效），即使 module.json5 已声明 MICROPHONE 也恒报告警——属 SDK 文档缺陷的
 * 固有误报。经本接口引用同一实例方法可让调用点脱离该 JSDoc，语义零变化。
 */
interface AvPrepareLike {
  prepare(config: media.AVRecorderConfig): Promise<void>;
}

/** 帧率钳到所选视频档的合法域（无精确档设备按范围端点取） */
function clampFps(want: number, range: camera.FrameRateRange): number {
  if (range === undefined) {
    return want > 0 ? want : 30;
  }
  const min: number = range.min > 0 ? range.min : 1;
  const max: number = range.max >= min ? range.max : min;
  return Math.min(max, Math.max(min, Math.round(want > 0 ? want : min)));
}

/** 预览档：与录像档同比例、面积不超录像档的最大预览档（core 规则）；无匹配退首档 */
function previewProfileFor(profiles: Array<camera.Profile>, videoSize: Size): camera.Profile | undefined {
  if (profiles.length === 0) {
    return undefined;
  }
  const candidates: Array<Size> = profiles.map((p: camera.Profile): Size => new Size(p.size.width, p.size.height));
  const chosen: Size | undefined = pickDirectPreviewSize(candidates, videoSize, 0, 0);
  if (chosen !== undefined) {
    const hit: camera.Profile | undefined = profiles.find((p: camera.Profile): boolean =>
      sizeCloseTo(new Size(p.size.width, p.size.height), chosen, VideoSizeCandidates.TOLERANCE_PX));
    if (hit !== undefined) {
      return hit;
    }
  }
  return profiles[0];
}

/**
 * 录制引擎：一次 start = 一套 VideoSession（input + previewOutput + videoOutput）+ AVRecorder；
 * stop 先停 AVRecorder 再逐项清理相机侧资源（顺序与建链相反），收尾不抛。
 */
export class RecordingEngine {
  private av: media.AVRecorder | undefined = undefined;
  private session: camera.VideoSession | undefined = undefined;
  private previewOut: camera.PreviewOutput | undefined = undefined;
  private videoOut: camera.VideoOutput | undefined = undefined;
  private input: camera.CameraInput | undefined = undefined;
  private path: string = '';
  /** 输出文件 fd（fd:// 契约）：openSync 取得，AVRecorder reset/release 后才关闭 */
  private outFd: number = -1;
  private startedAtMs: number = 0;
  private seriesId: number = 0;
  private profile: RecordProfile | undefined = undefined;
  private engineError: string | undefined = undefined;
  /** 重入保护：start/stop 异步进行中拒绝再次进入（页面按钮同侧防重复） */
  private starting: boolean = false;
  private stopping: boolean = false;

  /** 是否处于录制态（页面按钮状态以此为准） */
  isRecording(): boolean {
    return this.session !== undefined || this.av !== undefined;
  }

  /**
   * 起录。返回人读摘要（页面直接显示）：成功以 "recording " 开头；
   * 失败返回机器码/`码:载荷`（LOW_STORAGE:mb / NO_OUTPUT / PREPARE_FAILED / START_FAILED / ENGINE_ERROR:…），
   * 并已在内部回收全部已建资源**且删除已创建/半写的输出文件**（failStart）。
   */
  async start(ctx: common.Context, surfaceId: string, params: WotaParams, audioAllowed: boolean): Promise<string> {
    if (this.starting || this.stopping) {
      return RecordError.BAD_STATE;
    }
    if (this.isRecording()) {
      return RecordError.BAD_STATE;
    }
    if (surfaceId === '') {
      return `${RecordError.NO_OUTPUT}:no-surface`;
    }
    this.starting = true;
    try {
      const low: string | undefined = VideoStore.checkFreeSpace(ctx);
      if (low !== undefined) {
        return low;
      }
      const seriesId: number = SeriesIds.nextSeriesId(Date.now());
      const mgr: camera.CameraManager = camera.getCameraManager(ctx);
      const slots = enumerateKitLenses(mgr);
      const slot = pickDefaultLens(slots);
      if (slot === undefined) {
        return `${RecordError.NO_OUTPUT}:no-camera`;
      }
      const device: camera.CameraDevice | undefined = findDeviceById(mgr, slot.logicId);
      if (device === undefined) {
        return `${RecordError.NO_OUTPUT}:device-gone`;
      }

      // 视频档：会话能力按 NORMAL_VIDEO 采集（录制面专属）
      const capability: camera.CameraOutputCapability =
        mgr.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_VIDEO);
      const videoProfiles: Array<camera.VideoProfile> = capability.videoProfiles ?? [];
      const vp: camera.VideoProfile | undefined =
        pickVideoProfile(videoProfiles, params.size.value, params.fps.value.value);
      if (vp === undefined) {
        return `${RecordError.NO_OUTPUT}:no-video-profile`;
      }
      const w: number = vp.size.width;
      const h: number = vp.size.height;
      const fps: number = clampFps(params.fps.value.value, vp.frameRateRange);
      const bitrate: number = BitratePolicy.resolve(params.bitrate.value, w, h, fps, false);
      // 音频真值来源：权限允许 && 参数总线 audioEnabled（任一为假 → video-only）
      const audioOn: boolean = audioAllowed && params.audioEnabled.value;
      const profile: RecordProfile = new RecordProfile({
        width: w,
        height: h,
        fps: fps,
        captureRate: 0,
        bitrate: params.bitrate.value,
        codec: CODEC_H264,
        sampleRate: 48_000,
        channels: 2,
        audioBitrate: BitratePolicy.AUDIO_BITRATE,
        audioEnabled: audioOn,
        orientationHint: 0,
        mirrored: false,
        useGpu: false
      });

      const av: media.AVRecorder = await media.createAVRecorder();
      this.av = av;
      av.on('error', (err: BusinessError): void => {
        this.engineError = `${RecordError.ENGINE_ERROR}:${err.code}:${err.message}`;
        Diag.e(WOTA_RECORD_TAG, `av error: ${err.code} ${err.message}`);
      });
      av.on('stateChange', (state: media.AVRecorderState, reason: media.StateChangeReason): void => {
        Diag.i(WOTA_RECORD_TAG, `av state=${state} reason=${reason}`);
      });

      const name: string = VideoStore.displayName();
      const path: string = VideoStore.pathFor(ctx, name);
      this.path = path;
      this.seriesId = seriesId;
      this.profile = profile;

      // fd:// 契约（d.ts：AVRecorderConfig.url = fd://xx）：先建文件拿 fd，AVRecorder 按 fd 写；
      // fd 保留到 reset/release 之后才关闭（releaseAll 收尾）。不再使用裸路径/file:// 退路。
      const outFile: fileIo.File = fileIo.openSync(path,
        fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      this.outFd = outFile.fd;

      let prepared: boolean = false;
      const prep: AvPrepareLike = av as AvPrepareLike;
      try {
        await prep.prepare(this.buildConfig(`fd://${this.outFd}`, profile, audioOn));
        prepared = true;
      } catch (e1) {
        Diag.e(WOTA_RECORD_TAG, `prepare failed: ${e1}`);
      }
      if (!prepared) {
        await this.failStart();
        return RecordError.PREPARE_FAILED;
      }

      const videoSurfaceId: string = await av.getInputSurface();
      const previewProfile: camera.Profile | undefined =
        previewProfileFor(capability.previewProfiles ?? [], new Size(w, h));
      if (previewProfile === undefined) {
        await this.failStart();
        return `${RecordError.NO_OUTPUT}:no-preview-profile`;
      }

      const input: camera.CameraInput = mgr.createCameraInput(device);
      await input.open();
      this.input = input;
      const session: camera.VideoSession = mgr.createSession<camera.VideoSession>(camera.SceneMode.NORMAL_VIDEO);
      this.session = session;
      session.on('error', (err: BusinessError): void => {
        this.engineError = `${RecordError.ENGINE_ERROR}:session:${err.code}:${err.message}`;
        Diag.e(WOTA_RECORD_TAG, `session error: ${err.code} ${err.message}`);
      });
      session.beginConfig();
      session.addInput(input);
      const previewOut: camera.PreviewOutput = mgr.createPreviewOutput(previewProfile, surfaceId);
      const videoOut: camera.VideoOutput = mgr.createVideoOutput(vp, videoSurfaceId);
      this.previewOut = previewOut;
      this.videoOut = videoOut;
      session.addOutput(previewOut);
      session.addOutput(videoOut);
      await session.commitConfig();
      await session.start();
      try {
        await av.start();
      } catch (eStart) {
        Diag.e(WOTA_RECORD_TAG, `av.start failed: ${eStart}`);
        await this.failStart();
        return RecordError.START_FAILED;
      }

      this.startedAtMs = Date.now();
      Diag.i(WOTA_RECORD_TAG,
        `started series=${seriesId} ${w}x${h}@${fps} bitrate=${bitrate} audio=${audioOn}`);
      return `recording series=${seriesId} ${w}x${h}@${fps} bitrate=${bitrate} audio=${audioOn}`;
    } catch (e) {
      Diag.e(WOTA_RECORD_TAG, `start failed: ${e}`);
      await this.failStart();
      return `${RecordError.ENGINE_ERROR}:${e}`;
    } finally {
      this.starting = false;
    }
  }

  /**
   * 停录并返回结果（无录制中 / 已在停录中 → BAD_STATE）。顺序：av.stop → av.reset/release →
   * session.stop/release → videoOut/previewOut.release → input.close → 关闭输出 fd（与建链相反），
   * 逐项 try/catch。判废顺序：**先 MIN_KEEP_MS**（短段一律 discard + TOO_SHORT，含 engineErr 短段，
   * 对齐上游 segmentKeepDecision：时长不足即弃）；唯一例外是 av.stop 失败（STOP_FAILED）——
   * 文件可能未封口，保留路径供 triage（登记）。
   */
  async stop(): Promise<RecordResult> {
    const seriesId: number = this.seriesId;
    if (this.stopping) {
      return RecordResult.fail(RecordError.BAD_STATE, seriesId);
    }
    if (!this.isRecording()) {
      return RecordResult.fail(RecordError.BAD_STATE, seriesId);
    }
    this.stopping = true;
    try {
      const path: string = this.path;
      const durationMs: number = this.startedAtMs > 0 ? Date.now() - this.startedAtMs : 0;
      const engineErr: string | undefined = this.engineError;
      let stopFailed: boolean = false;
      try {
        if (this.av !== undefined) {
          await this.av.stop();
        }
      } catch (e) {
        stopFailed = true;
        Diag.e(WOTA_RECORD_TAG, `av.stop failed: ${e}`);
      }
      await this.releaseAll();

      const bytes: number = path !== '' ? VideoStore.sizeOf(path) : -1;
      const outPath: string | null = path === '' ? null : path;
      if (stopFailed) {
        // 唯一登记例外：文件可能未封口，保留路径供排查（不改名不删除）
        Diag.w(WOTA_RECORD_TAG, `stop failed, keep file for triage: ${path}`);
        return new RecordResult(outPath, bytes, durationMs, RecordError.STOP_FAILED, seriesId);
      }
      if (durationMs < MIN_KEEP_MS) {
        // 短段判废先于错误码（engineErr 短段同样按 TOO_SHORT，对齐上游 segmentKeepDecision）
        if (path !== '') {
          VideoStore.discard(path);
        }
        Diag.i(WOTA_RECORD_TAG, `discard too short: ${durationMs}ms ${path}`);
        return RecordResult.fail(RecordError.TOO_SHORT, seriesId);
      }
      if (engineErr !== undefined) {
        return new RecordResult(outPath, bytes, durationMs, engineErr, seriesId);
      }
      Diag.i(WOTA_RECORD_TAG, `stopped series=${seriesId} bytes=${bytes} duration=${durationMs}ms`);
      return new RecordResult(outPath, bytes, durationMs, null, seriesId);
    } finally {
      this.stopping = false;
    }
  }

  /** 起录失败统一回收：先释放资源并关 fd，再删已创建/半写的输出文件（不留 0 字节幽灵件） */
  private async failStart(): Promise<void> {
    const path: string = this.path;
    await this.releaseAll();
    if (path !== '') {
      VideoStore.discard(path);
    }
  }

  /** 逐项回收全部资源（起录失败与停录共用）；任何一步失败不阻塞后续清理 */
  private async releaseAll(): Promise<void> {
    try {
      if (this.av !== undefined) {
        await this.av.reset();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `av.reset: ${e}`);
    }
    try {
      if (this.av !== undefined) {
        await this.av.release();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `av.release: ${e}`);
    }
    try {
      if (this.session !== undefined) {
        await this.session.stop();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `session.stop: ${e}`);
    }
    try {
      if (this.session !== undefined) {
        await this.session.release();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `session.release: ${e}`);
    }
    try {
      if (this.videoOut !== undefined) {
        await this.videoOut.release();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `videoOut.release: ${e}`);
    }
    try {
      if (this.previewOut !== undefined) {
        await this.previewOut.release();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `previewOut.release: ${e}`);
    }
    try {
      if (this.input !== undefined) {
        await this.input.close();
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `input.close: ${e}`);
    }
    // fd:// 契约收尾：AVRecorder 已 reset/release（不再写），此时才关输出 fd
    try {
      if (this.outFd >= 0) {
        fileIo.closeSync(this.outFd);
      }
    } catch (e) {
      Diag.w(WOTA_RECORD_TAG, `fd close: ${e}`);
    }
    this.av = undefined;
    this.session = undefined;
    this.videoOut = undefined;
    this.previewOut = undefined;
    this.input = undefined;
    this.outFd = -1;
    this.path = '';
    this.startedAtMs = 0;
    this.seriesId = 0;
    this.profile = undefined;
    this.engineError = undefined;
  }

  /** AVRecorder 配置：video-only 时不含任何 audio 字段（mic 拒绝 / 参数总线静音的共用实现） */
  private buildConfig(url: string, p: RecordProfile, audioOn: boolean): media.AVRecorderConfig {
    const mp: media.AVRecorderProfile = {
      videoCodec: media.CodecMimeType.VIDEO_AVC,
      videoBitrate: p.effectiveBitrate(),
      videoFrameWidth: p.width,
      videoFrameHeight: p.height,
      videoFrameRate: p.fps,
      fileFormat: media.ContainerFormatType.CFT_MPEG_4
    };
    if (audioOn) {
      mp.audioCodec = media.CodecMimeType.AUDIO_AAC;
      mp.audioBitrate = p.audioBitrate;
      mp.audioSampleRate = p.sampleRate;
      mp.audioChannels = p.channels;
    }
    const config: media.AVRecorderConfig = {
      videoSourceType: media.VideoSourceType.VIDEO_SOURCE_TYPE_SURFACE_YUV,
      profile: mp,
      url: url
    };
    if (audioOn) {
      config.audioSourceType = media.AudioSourceType.AUDIO_SOURCE_TYPE_MIC;
    }
    return config;
  }
}

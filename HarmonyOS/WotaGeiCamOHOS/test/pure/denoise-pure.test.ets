/**
 * 降噪参数模型与负载分档（对齐上游 DenoiseTest 真值口径，8646b05/316516f）。
 *
 * 平台裁定（文件头注与 PORT_MAP 登记）：音频降噪 OHOS 采集链无等价物（setAudioEffectMode
 * 是输出端）；HAL 降噪键 Camera2 专属不移植；GLSL 双边随真机批。本批交付纯函数层。
 */
import {
  DENOISE_FULL_KERNEL_PIXEL_RATE, DENOISE_LEVEL_FULL, DENOISE_LEVEL_LITE, DENOISE_LEVEL_OFF,
  AudioDenoise, VideoDenoise, audioDenoiseFromKey, audioDenoiseKey, audioDenoiseNeedsCodec,
  denoiseKernelLite, effectiveVideoDenoise, videoDenoiseActive, videoDenoiseFromKey,
  videoDenoiseKey
} from '../../entry/src/main/ets/core/Denoise';

test('实际生效档_GPU双边在DIRECT退HAL快速_其余原样', () => {
  assertEq(VideoDenoise.HAL_FAST, effectiveVideoDenoise(VideoDenoise.GPU_BILATERAL, false),
    'DIRECT 下 GPU 双边退 HAL 快速（返回实际档，UI 显示已退回）');
  assertEq(VideoDenoise.GPU_BILATERAL, effectiveVideoDenoise(VideoDenoise.GPU_BILATERAL, true));
  assertEq(VideoDenoise.OFF, effectiveVideoDenoise(VideoDenoise.OFF, false), 'OFF 恒原样');
  assertEq(VideoDenoise.HAL_HIGH_QUALITY,
    effectiveVideoDenoise(VideoDenoise.HAL_HIGH_QUALITY, false));
});

test('生效中判据_退档仍算有降噪', () => {
  // 退回 HAL 快速档仍然算有降噪——否则胶囊选中态跟着渲染模式乱闪
  assertTrue(videoDenoiseActive(VideoDenoise.GPU_BILATERAL, false));
  assertTrue(videoDenoiseActive(VideoDenoise.GPU_BILATERAL, true));
  assertTrue(videoDenoiseActive(VideoDenoise.HAL_FAST, true));
  assertFalse(videoDenoiseActive(VideoDenoise.OFF, true));
});

test('音频降噪非OFF需要Codec引擎', () => {
  assertTrue(audioDenoiseNeedsCodec(AudioDenoise.SYSTEM));
  assertFalse(audioDenoiseNeedsCodec(AudioDenoise.OFF));
});

test('负载分档_超阈值用轻量核', () => {
  assertFalse(denoiseKernelLite(1920, 1080, 30), '1080p30 = 62M 留完整核');
  assertFalse(denoiseKernelLite(1920, 1080, 60), '1080p60 = 124M 留完整核');
  assertFalse(denoiseKernelLite(2560, 1440, 30), '1440p30 = 110.6M < 150M 留完整核');
  assertTrue(denoiseKernelLite(3840, 2160, 30), '4K30 = 248.8M 轻量');
  assertTrue(denoiseKernelLite(1920, 1080, 120), '1080p120 轻量');
  assertFalse(denoiseKernelLite(0, 1080, 30), '信息不全留完整核');
  assertFalse(denoiseKernelLite(1920, 1080, 0));
  assertEq(150_000_000, DENOISE_FULL_KERNEL_PIXEL_RATE, '与 Glow 分档同一魔数（单一真源）');
});

test('档位编号常量三处共用', () => {
  assertEq(0, DENOISE_LEVEL_OFF);
  assertEq(1, DENOISE_LEVEL_FULL);
  assertEq(2, DENOISE_LEVEL_LITE);
});

test('持久化键往返_坏值回OFF', () => {
  assertEq('off', videoDenoiseKey(VideoDenoise.OFF));
  assertEq('hal_fast', videoDenoiseKey(VideoDenoise.HAL_FAST));
  assertEq('hal_hq', videoDenoiseKey(VideoDenoise.HAL_HIGH_QUALITY));
  assertEq('gpu_bilateral', videoDenoiseKey(VideoDenoise.GPU_BILATERAL));
  assertEq(VideoDenoise.GPU_BILATERAL, videoDenoiseFromKey('gpu_bilateral'));
  assertEq(VideoDenoise.OFF, videoDenoiseFromKey('garbage'));
  assertEq(VideoDenoise.OFF, videoDenoiseFromKey(undefined));
  assertEq('system', audioDenoiseKey(AudioDenoise.SYSTEM));
  assertEq(AudioDenoise.SYSTEM, audioDenoiseFromKey('system'));
  assertEq(AudioDenoise.OFF, audioDenoiseFromKey('garbage'));
});

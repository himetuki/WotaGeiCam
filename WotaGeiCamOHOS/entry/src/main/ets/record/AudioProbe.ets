/**
 * 音频采集参数算式与档位体检（直译上游 record/AudioFeeder.kt 的 AudioProbe，对齐上游 38d712a）。
 *
 * 纯逻辑（不 import 平台 Kit）：帧宽/读块/整缓冲/分贝/档位不符短因。上游 minBufferSize 由
 * AudioRecord.getMinBufferSize 提供（平台面留 T27），本文件只落可测算式；三处消费点
 * （环境路建路、内录路建路、泵读块）共用同一份算式，禁止各写一份。
 *
 * T26 边界：AVScreenCapture/AudioCapturer 平台建路与回读（captureParamMismatch 的真实调用点）
 * 留 T27；本文件只提供被调用的纯判据。
 */

/** 单次读块上限：约 40ms（48k 立体声 8KB），保证 stop 几十毫秒内生效 */
export const CHUNK_MAX: number = 8192;

/** 一帧 PCM16 字节数：max(channels, 1) × 2（0/负按单声道兜底） */
export function frameBytes(channels: number): number {
  return Math.max(channels, 1) * 2;
}

/**
 * 单次读块字节数：半个最小缓冲为基准、下限 16 帧、上限 CHUNK_MAX、向下对齐到整帧。
 * 泵读块与内录建路共用这一份。
 *
 * 负 minBufferSize 口径（登记）：半缓冲 `floor(min/2)` 为负时会被 16 帧下限掩蔽
 * （结果 = 16 帧对齐值），与上游 `max(minBufferSize/2, frame*16)` 的 Kotlin Int 除零向
 * 截断一致；平台错误码（-1 等）因此不会把读块算成负数，但也不会被这里报错——建路前
 * 的可用性判定仍由平台层负责（T27）。
 */
export function chunkBytes(minBufferSize: number, channels: number): number {
  const frame: number = frameBytes(channels);
  const raw: number = Math.min(Math.max(Math.floor(minBufferSize / 2), frame * 16), CHUNK_MAX);
  return Math.floor(raw / frame) * frame;
}

/** 采集器构造的整缓冲字节数 = max(minBufferSize, chunkBytes(...))（环境/内录两路共用口径） */
export function recordBufferBytes(minBufferSize: number, channels: number): number {
  return Math.max(minBufferSize, chunkBytes(minBufferSize, channels));
}

/** 振幅 → 分贝：20 × log10(amp / 0.1)；amp ≤ 0 给下限 -160（UI 直接显示） */
export function dbOf(amplitude: number): number {
  if (!Number.isFinite(amplitude) || amplitude <= 0) {
    return -160;
  }
  return 20 * Math.log10(amplitude / 0.1);
}

/**
 * 内录档位体检判据（纯函数）：请求档与回读的实际档逐项相等 → undefined（照常放行）；
 * 任一不等（含实际读出 0 = 系统未按档配置）→ 返回中文短因（含请求与实际）。
 * 短因自身不带括号（外层文案已带一对），句式用冒号 + 逗号分段。
 */
export function captureParamMismatch(
  reqRate: number,
  reqChannels: number,
  actualRate: number,
  actualChannels: number
): string | undefined {
  if (reqRate === actualRate && reqChannels === actualChannels) {
    return undefined;
  }
  return `本机内录不支持该档：请求 ${reqRate}Hz/${reqChannels}ch，系统改为 ${actualRate}Hz/${actualChannels}ch`;
}

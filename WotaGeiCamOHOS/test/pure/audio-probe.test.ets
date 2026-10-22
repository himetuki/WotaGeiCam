/**
 * 音频参数算式与档位体检（record/AudioProbe.ets）单测——对拍上游
 * app/src/test/java/com/wotagei/cam/record/CaptureParamAuditTest.kt 的纯函数面（对齐上游 38d712a）。
 *
 * 3 条：档位体检全表（逐项相等才放行 + 短因三维度） / chunk/buffer 手写字面量 / dbOf 字面量。
 */
import {
  CHUNK_MAX, captureParamMismatch, chunkBytes, dbOf, frameBytes, recordBufferBytes
} from '../../entry/src/main/ets/record/AudioProbe';

function assertClose(expected: number, actual: number, tol: number, msg: string): void {
  assertTrue(Math.abs(expected - actual) <= tol, `${msg}: expected ${expected}±${tol} got ${actual}`);
}

test('captureParamMismatch全表 逐项相等才放行 不等必含请求与实际', (): void => {
  const reqRates: Array<number> = [44100, 48000];
  const reqChs: Array<number> = [1, 2];
  const actRates: Array<number> = [0, 44100, 48000];
  const actChs: Array<number> = [0, 1, 2];
  for (const reqRate of reqRates) {
    for (const reqCh of reqChs) {
      for (const actRate of actRates) {
        for (const actCh of actChs) {
          const pass: boolean = reqRate === actRate && reqCh === actCh;
          const got: string | undefined = captureParamMismatch(reqRate, reqCh, actRate, actCh);
          const tag: string = `req=${reqRate}/${reqCh} act=${actRate}/${actCh}`;
          if (pass) {
            assertNull(got, `逐项相等必须放行（${tag}）`);
          } else {
            assertTrue(got !== undefined, `任一不等必须判不通过（${tag}）`);
            const text: string = got ?? '';
            assertTrue(text.indexOf(`${reqRate}Hz/${reqCh}ch`) >= 0, `短因含请求档（${tag}）：${text}`);
            assertTrue(text.indexOf(`${actRate}Hz/${actCh}ch`) >= 0, `短因含实际档（${tag}）：${text}`);
          }
        }
      }
    }
  }
  // 边界显式条目：成功路径（全等）与「读回 0 = 系统未按档配置」必须各走一边
  assertNull(captureParamMismatch(48000, 2, 48000, 2), '全等放行');
  assertNull(captureParamMismatch(0, 0, 0, 0), '0/0 全等同样放行（判据只比相等）');
  assertTrue(captureParamMismatch(48000, 2, 0, 0) !== undefined, '实际 0/0 而请求 48k/2ch 必须不通过');
  // 单维度不符各一条：rate 不符 / ch 不符 / 双不符（文案三维度）
  const rateOnly: string = captureParamMismatch(48000, 2, 44100, 2) ?? '';
  assertTrue(rateOnly.indexOf('44100Hz') >= 0, 'rate 不符给实际采样率');
  const chOnly: string = captureParamMismatch(48000, 2, 48000, 1) ?? '';
  assertTrue(chOnly.indexOf('1ch') >= 0, 'ch 不符给实际声道');
  const both: string = captureParamMismatch(48000, 2, 44100, 1) ?? '';
  assertTrue(both.indexOf('请求 48000Hz/2ch') >= 0, '双不符含请求档（与用户拍板示例同形）');
  assertTrue(both.indexOf('系统改为 44100Hz/1ch') >= 0, '双不符含实际档');
});

test('chunk与buffer手写字面量', (): void => {
  assertEq(8192, CHUNK_MAX, '读块上限 8KB');
  assertEq(2, frameBytes(1), '单声道帧 2 字节');
  assertEq(4, frameBytes(2), '立体声帧 4 字节');
  assertEq(2, frameBytes(0), '声道 0 按单声道兜底');
  assertEq(32, chunkBytes(0, 1), '空最小缓冲：下限 16 帧 × 2B');
  assertEq(64, chunkBytes(0, 2), '空最小缓冲：下限 16 帧 × 4B');
  assertEq(64, chunkBytes(64, 2), '32B 半缓冲低于下限取 64');
  assertEq(2048, chunkBytes(4096, 2), '4096 半缓冲');
  assertEq(4096, chunkBytes(8192, 2), '8192 半缓冲');
  assertEq(4096, recordBufferBytes(4096, 2), '整缓冲取 minBuffer 与 chunk 大者');
  assertEq(8192, chunkBytes(1048576, 2), '超大 minBuffer 被 CHUNK_MAX 封顶');
  assertEq(1048576, recordBufferBytes(1048576, 2), '整缓冲保留 minBuffer 原值');
  // 审查修复 P3：补足上游网格的取整/奇偶/多声道/负值面
  assertEq(4094, chunkBytes(8191, 1), '8191 半缓冲向下对齐到偶帧（4095→4094）');
  assertEq(64, chunkBytes(100, 2), '100 半缓冲低于 16 帧下限');
  // channels=3：帧 6 字节；1000 半缓冲 500 → floor(500/6)*6 = 498（非帧倍数被截掉）
  assertEq(498, chunkBytes(1000, 3), '三声道帧对齐');
  // 负 minBuffer（平台错误码）被 16 帧下限掩蔽 → 64，不产出负读块（口径见源注释）
  assertEq(64, chunkBytes(-100, 2), '负 minBuffer 被下限掩蔽');
  assertEq(64, recordBufferBytes(-100, 2), '整缓冲同样不采负值');
});

test('dbOf字面量', (): void => {
  // 手写期望：-160 下限 / -7dB / 1dB / 2dB / 10dB / 80dB（±0.001 容差）
  assertEq(-160, dbOf(0), 'amp=0 给下限');
  assertEq(-160, dbOf(-5), '负振幅给下限');
  assertClose(-7, dbOf(0.04467), 0.001, 'amp=0.04467 → -7dB');
  assertClose(1, dbOf(0.1122), 0.001, 'amp=0.1122 → 1dB');
  assertClose(2, dbOf(0.1259), 0.001, 'amp=0.1259 → 2dB');
  assertClose(10, dbOf(0.3162), 0.001, 'amp=0.3162 → 10dB');
  assertClose(80, dbOf(1000), 0.001, 'amp=1000 → 80dB');
});

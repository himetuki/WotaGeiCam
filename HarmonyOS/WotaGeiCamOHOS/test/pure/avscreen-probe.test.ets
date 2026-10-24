/**
 * T27-1 平台探针（P27 批）：结果模型全表——native 状态串契约的解析与三条取证结论的判读。
 * 契约串见 cpp/avscreen_probe.cpp（ok/failed/cancelled/running 四形）。
 */
import {
  AvScreenProbeInfo, AvScreenProbeModel, AvScreenProbeVerdict, AvSourceCounters
} from '../../entry/src/main/ets/record/AvScreenProbeModel';
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/ui/HudSpec.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

// ------------------------------------------------------------------ 解析全表

test('解析_运行态与idle返回undefined_轮询继续', () => {
  assertNull(AvScreenProbeModel.parse('idle'));
  assertNull(AvScreenProbeModel.parse('running:0'));
  assertNull(AvScreenProbeModel.parse('running:1234'));
});

test('解析_取消与失败形态', () => {
  const cancelled: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse('cancelled');
  assertTrue(cancelled !== undefined);
  assertEq(AvScreenProbeVerdict.CANCELLED, cancelled!.verdict);
  // Init/Start 失败（隐私拒绝路径）：BLOCKED——失败形态本身就是取证结论
  const blocked: AvScreenProbeInfo | undefined =
    AvScreenProbeModel.parse('failed:init:2:err=4400101');
  assertTrue(blocked !== undefined);
  assertEq(AvScreenProbeVerdict.BLOCKED, blocked!.verdict);
  assertEq('failed:init:2:err=4400101', blocked!.raw, '原文保留（真机日志对表用）');
  // 桥接失败/未知串：FAILED
  const fail: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse('garbage');
  assertTrue(fail !== undefined);
  assertEq(AvScreenProbeVerdict.FAILED, fail!.verdict);
});

test('解析_双源同采判定', () => {
  const info: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse(
    'ok:inner=52/98304:mic=52/98304:states=0:err=0:elapsedMs=2530');
  assertTrue(info !== undefined);
  assertEq(AvScreenProbeVerdict.DUAL_SOURCE, info!.verdict, '两类都出数据 → 双源 ✓（T27-3 可行）');
  assertEq(52, info!.inner.callbacks);
  assertEq(98304, info!.inner.bytes);
  assertEq(52, info!.mic.callbacks);
  assertTrue(info!.inner.hasData && info!.mic.hasData);
  assertEq(2530, info!.elapsedMs);
  assertEq('0', info!.states.join(','), 'OH_SCREEN_CAPTURE_STATE_STARTED=0 原样保留');
  assertEq('双源 ✓ 内录 52 次/98304B · 麦克风 52 次/98304B（2530ms）', info!.summary());
});

test('解析_单源与静音窗口', () => {
  const innerOnly: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse(
    'ok:inner=40/76800:mic=0/0:states=0:err=0:elapsedMs=2500');
  assertEq(AvScreenProbeVerdict.SINGLE_SOURCE, innerOnly!.verdict, '仅内录');
  assertTrue(innerOnly!.summary().startsWith('仅内录'));

  const micOnly: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse(
    'ok:inner=0/0:mic=30/57600:states=0:err=0:elapsedMs=2500');
  assertEq(AvScreenProbeVerdict.SINGLE_SOURCE, micOnly!.verdict, '仅麦克风');
  assertTrue(micOnly!.summary().startsWith('仅麦克风'), '读数要说明是哪一侧');

  const silent: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse(
    'ok:inner=0/0:mic=0/0:states=-:err=0:elapsedMs=2520');
  assertEq(AvScreenProbeVerdict.SILENT_WINDOW, silent!.verdict, '窗口内无回调不下结论');
  assertTrue(silent!.summary().indexOf('带声源') >= 0, '读数要给出复测指引');
  assertEq(0, silent!.states.length, 'states=- 解析为空数组');
});

test('解析_多状态序列与坏段容错', () => {
  const info: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse(
    'ok:inner=1/1024:mic=1/1024:states=0,2,4:err=4400102:elapsedMs=2600');
  assertEq('0,2,4', info!.states.join(','), '多状态逗号分隔原样保留');
  assertEq(4400102, info!.lastError, 'onError 最近一次原样保留（不解读）');
  // 坏段（无 = / 非数字）不炸：缺字段按 0，仍可判读
  const partial: AvScreenProbeInfo | undefined = AvScreenProbeModel.parse('ok:inner=1/1024');
  assertEq(AvScreenProbeVerdict.SINGLE_SOURCE, partial!.verdict, 'mic 缺段按 0/0');
  assertEq(0, partial!.elapsedMs);
});

// ------------------------------------------------------------------ 接线守卫

test('接线_native三口与页面按钮', () => {
  const cpp: string = sourceText('entry/src/main/cpp/napi_init.cpp');
  assertTrue(cpp.indexOf('"startAvScreenProbe"') >= 0 &&
    cpp.indexOf('"getAvScreenProbeStatus"') >= 0 &&
    cpp.indexOf('"cancelAvScreenProbe"') >= 0, 'napi 三口注册');
  const probe: string = sourceText('entry/src/main/cpp/avscreen_probe.cpp');
  assertTrue(probe.indexOf('OH_AVScreenCapture_SetMicrophoneEnabled(capture, true)') >= 0,
    '双源取证：内录源 + 麦克风总开关');
  assertTrue(probe.indexOf('OH_ALL_PLAYBACK') >= 0, '内录源 = ALL_PLAYBACK（含其它应用播放）');
  assertTrue(probe.indexOf('OH_AVScreenCapture_ReleaseAudioBuffer') >= 0,
    '音频缓冲必须取还（不还会卡死回调）');
  assertTrue(probe.indexOf('OH_AVScreenCapture_Release(capture)') >= 0, 'Release 收口');

  const index: string = sourceText('entry/src/main/ets/pages/Index.ets');
  assertTrue(index.indexOf('runAvScreenProbe()') >= 0, '开发者页有探针按钮（默认隐藏入口可达）');
  assertTrue(index.indexOf('cancelAvScreenProbe();') >= 0, '页面销毁取消兜底');

  const cmake: string = readFileSync(resolve(srcRoot(), 'entry/src/main/cpp/CMakeLists.txt'), 'utf8');
  assertTrue(cmake.indexOf('avscreen_probe.cpp') >= 0 &&
    cmake.indexOf('libnative_avscreen_capture.so') >= 0,
    'CMake 源文件与链接库登记（库名=sysroot 实名 libnative_avscreen_capture.so，编译期纠正过一次）');

  const model: string = sourceText('entry/src/main/ets/record/AvScreenProbeModel.ets');
  assertTrue(model.indexOf('@ohos') < 0 && model.indexOf('@kit') < 0,
    '结果模型零平台 import（纯逻辑全测）');
});

/**
 * NAPI 声明守卫（v0.0.10-r02 CI 构建失败修复批，2026-10-25）：
 * cpp/types/libentry/index.d.ts 是手写声明，与 napi_init.cpp 的 exports 描述符表
 * 漂移后 ArkTS 侧 CI 编译报「属性不存在 / 参数个数不符」（本地增量构建不复现，
 * CI 全量检查是裁判）。本守卫把两边的名字集合作机器比对 + 钉尾参可选写法，
 * 防下次 native 加口忘更声明。
 */
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

const NAPI_CPP = 'entry/src/main/cpp/napi_init.cpp';
const DECL_DTS = 'entry/src/main/cpp/types/libentry/index.d.ts';

/** native exports 表的全部属性名（`"name", nullptr, FnName` 描述符行） */
function nativeNames(): Array<string> {
  const src: string = readFileSync(resolve(srcRoot(), NAPI_CPP), 'utf8');
  const names: Array<string> = [];
  for (const m of src.matchAll(/"([A-Za-z][A-Za-z0-9]*)", *nullptr, *[A-Z][A-Za-z]*/g)) {
    names.push(m[1]);
  }
  return names;
}

test('声明全覆盖_native导出表逐名对账', () => {
  const dts: string = readFileSync(resolve(srcRoot(), DECL_DTS), 'utf8');
  const missing: Array<string> = [];
  for (const name of nativeNames()) {
    if (dts.indexOf(`export const ${name}:`) < 0) {
      missing.push(name);
    }
  }
  assertEq(missing.join(','), '', 'index.d.ts 必须声明 napi_init exports 表的每一个名字');
});

test('声明无多余_native没有的导出名', () => {
  const dts: string = readFileSync(resolve(srcRoot(), DECL_DTS), 'utf8');
  const declared: Array<string> = [];
  for (const m of dts.matchAll(/export const ([A-Za-z][A-Za-z0-9]*):/g)) {
    declared.push(m[1]);
  }
  const native: Array<string> = nativeNames();
  const extra: Array<string> = declared.filter((n: string): boolean => native.indexOf(n) < 0);
  assertEq(extra.join(','), '', 'index.d.ts 不得声明 native 未导出的名字（幽灵声明）');
});

test('尾参可选_与native兼容口径逐字', () => {
  const dts: string = readFileSync(resolve(srcRoot(), DECL_DTS), 'utf8');
  // native：startClipExport 6 必需 + 第 7 位可选；startArcRepair 12 必需 + 第 13 位可选；
  // startAvScreenProbe 0/1 参（缺省 2500）
  assertTrue(dts.indexOf('audioShiftUs?: number') >= 0, 'startClipExport 尾参必须可选');
  assertTrue(dts.indexOf('capOffsetMs?: number') >= 0, 'startArcRepair 尾参必须可选');
  assertTrue(dts.indexOf('durationMs?: number') >= 0, 'startAvScreenProbe 参必须可选');
});

test('桥调用点_元数在声明域内', () => {
  const bridge: string = readFileSync(
    resolve(srcRoot(), 'entry/src/main/ets/camera/GlLibBridge.ets'), 'utf8');
  // 桥传 6 参给 startClipExport（第 7 位缺省）；12 参给 startArcRepair（第 13 位缺省）——
  // 可选尾参声明下两者均合法；桥必须带三探针口的转发面
  assertTrue(bridge.indexOf('glLib.startClipExport(inFd, inSize, outFd, startMs, endMs, audioOrdinal)') >= 0,
    '剪辑导出桥 6 参调用');
  assertTrue(bridge.indexOf('durationMs)') >= 0 && bridge.indexOf('glLib.startAvScreenProbe') >= 0,
    '探针桥转发面存在');
});

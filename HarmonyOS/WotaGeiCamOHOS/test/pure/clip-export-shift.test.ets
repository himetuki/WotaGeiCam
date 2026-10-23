/**
 * 剪辑导出的同步平移（对齐上游 e950328 的 ClipExportShiftTest.kt）：
 * - 纯判据：只有内录轨吃 `−offsetMs`，环境轨恒 0；平移后负 PTS 丢弃、不 clip 到 0；
 *   出点也吃平移后的时刻（预扫与写出必须同口径）。
 * - 接线守卫：ArkTS 门面收 shift 并传 native、native 的预扫与拷贝循环都吃 `job.audioShiftUs`、
 *   视频轨不被音轨偏移污染、播放页从同步快照算 shift、光弧修复把 capOffsetMs 交给 native。
 *
 * 移位真身在 C++（clip_exporter.cpp / arc_repair.cpp），pure-test 只能读源码钉结构——
 * 与上游 KotlinSourceScan 的接线臂同一目的（防「参数加了但某条链没接」静默失效）。
 */
import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { TrackChoice, audioShiftUs, keepSample, remuxAudioSampleWritable } from
  '../../entry/src/main/ets/player/ClipRule';

/** 从转译产物目录向上找仓库根（含 entry/src/main/ets/player/ClipRule.ets 的祖先目录） */
function srcRoot(): string {
  let dir: string = __dirname;
  for (let i = 0; i < 6; i++) {
    if (existsSync(resolve(dir, 'entry/src/main/ets/player/ClipRule.ets'))) {
      return dir;
    }
    dir = resolve(dir, '..');
  }
  throw new Error('source root not found from ' + __dirname);
}

/** 去注释（块注释 + 整行行注释）：让接线扫描只看代码，注释里的关键词不算数 */
function codeOnly(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^[ \t]*\/\/.*$/gm, ' ');
}

function sourceText(rel: string): string {
  return codeOnly(readFileSync(resolve(srcRoot(), rel), 'utf8'));
}

// ------------------------------------------------------------------ 纯判据（上游同表）

test('内录轨按负偏移平移_环境轨恒零', () => {
  assertEq(-120000, audioShiftUs(TrackChoice.CAP, 120), 'CAP +120ms → -120000µs（向前拉）');
  assertEq(120000, audioShiftUs(TrackChoice.CAP, -120), 'CAP -120ms → +120000µs（延后）');
  assertEq(0, audioShiftUs(TrackChoice.CAP, 0), 'CAP 零偏移不平移');
  assertEq(0, audioShiftUs(TrackChoice.ENV, 120), 'ENV 恒不平移');
  assertEq(0, audioShiftUs(TrackChoice.ENV, -999), 'ENV 负偏移也不平移');
  assertEq(0, audioShiftUs(TrackChoice.NO_AUDIO, 120), '无音轨无可平移');
});

test('平移后负PTS不可写_不clip到零', () => {
  // 源样本早于入点 100ms：环境轨（shift=0）仍为负 → 丢弃
  assertFalse(remuxAudioSampleWritable(-100000, 0, undefined));
  // 内录晚响 120ms → shift=+120ms，同一枚样本被拉回正区间 → 可写
  assertTrue(remuxAudioSampleWritable(-100000, 120000, undefined));
  // 负向平移（内录早响）会把 50ms 样本推到负 → 丢弃，且不允许钳到 0
  assertFalse(remuxAudioSampleWritable(50000, -120000, undefined));
  // 出点也必须吃平移后的时刻：raw 在区间内、延后后越界 → 不写（预扫/写出同口径）
  assertFalse(remuxAudioSampleWritable(990000, 120000, 1000000));
  assertTrue(remuxAudioSampleWritable(880000, 120000, 1000000));
  // 边界 0 是可写的（keepSample 口径 rel>=0）
  assertTrue(keepSample(0), '边界 0 可写');
  assertFalse(keepSample(-1), '负值丢弃');
});

test('预扫写死0与正确平移的行为差异', () => {
  // 内录早 120ms → shift=+120ms：一枚早于入点 100ms 的样本在正确平移下可写
  assertTrue(remuxAudioSampleWritable(-100000, 120000, undefined));
  // 同一个预扫若写死 shift=0（缺陷形态）会判不可写——"建了轨却写不出样本"的来源
  assertFalse(remuxAudioSampleWritable(-100000, 0, undefined));
  // 出点同口径：raw 990ms 在 1000ms 出点内，但延后 120ms 后越界 → 正确实现不写
  assertFalse(remuxAudioSampleWritable(990000, 120000, 1000000));
  assertTrue(remuxAudioSampleWritable(990000, 0, 1000000));
});

// ------------------------------------------------------------------ 接线守卫

test('接线_ArkTS门面收shift并传native_播放页从快照算shift', () => {
  const exporter: string = sourceText('entry/src/main/ets/player/ClipExporter.ets');
  assertTrue(exporter.indexOf('audioShiftUs: number') >= 0,
    'ClipExporter.exportClip 必须收音频平移参数');
  assertTrue(exporter.indexOf('audioOrdinal < 0 ? 0 : Math.trunc(audioShiftUs)') >= 0,
    '无音轨时平移必须归零（不得把平移用到无音频的语义上）');

  const player: string = sourceText('entry/src/main/ets/pages/Player.ets');
  assertTrue(player.indexOf('audioShiftUs(this.exportTrack') >= 0,
    '播放页必须用 ClipRule.audioShiftUs 按选中轨算平移（不许各处手写符号）');
  assertTrue(player.indexOf('SyncPrefsStore.read(ctx, clip.path)') >= 0,
    '播放页必须从 prefs 读同步快照（导出读数与平移共用同一枚偏移）');
  assertTrue(player.indexOf('原片分享不重写容器') >= 0,
    '导出弹层必须写明「原片分享不重写容器」的诚实边界（上游 player_track_sync_note）');
});

test('接线_native预扫与拷贝循环同吃audioShiftUs_视频不污染', () => {
  const cpp: string = sourceText('entry/src/main/cpp/clip_exporter.cpp');
  assertTrue(cpp.indexOf('job.audioShiftUs') >= 0, 'native 必须持有 audioShiftUs');
  assertTrue(cpp.indexOf('attr.pts - basePtsUs + job.audioShiftUs') >= 0,
    '预扫必须吃平移后的时刻（写死 0 会让平移后的内录轨整条被判不可写）');
  assertTrue(cpp.indexOf('const int64_t audioRel = rel + job.audioShiftUs;') >= 0,
    '拷贝循环必须用 audioRel（封轨/负值/单调桥同口径）');
  assertTrue(cpp.indexOf('MonotonicPts(audioLastPts, audioRel)') >= 0,
    '音频单调桥必须吃平移后的时刻');
  // 视频轨：只减公共基准，绝不加 shift（否则画面时间轴被音轨偏移带歪）
  assertFalse(cpp.indexOf('attr.pts - basePtsUs + job.audioShiftUs;\n            if (rel > endRelUs)') >= 0,
    '视频分支不得出现 audioShiftUs');
  assertTrue(cpp.indexOf('const int64_t rel = attr.pts - basePtsUs;') >= 0,
    '视频 PTS 仍只减公共基准');
});

test('接线_光弧修复把capOffsetMs交给native且只作用第二条lane', () => {
  const runner: string = sourceText('entry/src/main/ets/record/ArcRepairRunner.ets');
  assertTrue(runner.indexOf('capOffsetMs: number') >= 0, 'ArcRepairRunner.run 必须收 capOffsetMs');
  assertTrue(runner.indexOf('Math.trunc(capOffsetMs)') >= 0, 'capOffsetMs 必须传进 native startArcRepair');

  const cpp: string = sourceText('entry/src/main/cpp/arc_repair.cpp');
  assertTrue(cpp.indexOf('job.capOffsetMs') >= 0, 'native 必须持有 capOffsetMs');
  assertTrue(cpp.indexOf('audioTrackIndices.size() >= 2 && ai == 1') >= 0,
    '平移只准作用于「确有第二轨」的内录 lane（单轨绝不猜）');
  assertTrue(cpp.indexOf('norm += laneShiftUs') >= 0, '拷贝循环必须叠加 laneShiftUs');
  assertTrue(cpp.indexOf('probeAttr.pts - probeVideoPtsUs + laneShiftUs >= 0') >= 0,
    '预检必须按平移后的时刻判可写（防零样本轨崩 muxer）');
});

/**
 * 对比播放剪辑模型（player/CompareAudio.ets）的纯函数单测——对拍上游
 * app/src/test/java/com/wotagei/cam/player/CompareAudioTest.kt 与 SplitAudioPickTest.kt
 * 的裁剪面（对齐上游 38d712a）。
 *
 * 16 条 = 对位上游 14 个测试方法（两条命令顺序臂合成一条用例，断言逐条保留）+ 3 条分屏臂
 * （分屏部分覆盖上游 SplitAudioPickTest 的计划/桥矩阵）。
 *
 * 三类断言：
 * - audioPlanOf 全表（Side × 轨种类 × 双轨可用）；
 * - applyOrderOf 的**命令顺序**（先静音另一侧 → 再设/清 override → 最后落音量）；
 * - 桥函数 selectionEffectOf / splitAudioEffectOf：计划经命令跑一遍后的可观察行为，
 *   期望值全部手写字面量，不调被测实现反推。
 *
 * 分屏臂的红线：分屏只有一台引擎，**没有另一侧可静音**；照抄对比页的 applyOrderOf 会把
 * 唯一引擎压成 0（分屏彻底没声），故 splitAudioPlanOf 的音量恒 1。
 */
import {
  AudioTrackKind, CompareAudioPlan, EngineCommand, EngineCommandKind, OverrideTarget, SelectionEffect,
  Side, applyOrderOf, audioGroupIndexOf, audioPlanOf, selectionEffectOf, splitAudioEffectOf,
  splitAudioPlanOf
} from '../../entry/src/main/ets/player/CompareAudio';

test('组下标映射 环境0内录1', (): void => {
  assertEq(0, audioGroupIndexOf(AudioTrackKind.ENV), '环境=组 0');
  assertEq(1, audioGroupIndexOf(AudioTrackKind.CAP), '内录=组 1');
});

test('计划 左片环境音 右侧静音且清override', (): void => {
  const p: CompareAudioPlan = audioPlanOf(Side.LEFT, AudioTrackKind.ENV, true);
  assertEq(Side.LEFT, p.fullSide, '全量侧左');
  assertEq(Side.RIGHT, p.mutedSide, '静音侧右');
  assertEq(Side.LEFT, p.overrideSide, 'override 落左');
  assertNull(p.overrideGroupIndex, '清 override');
});

test('计划 左片内录 左override组1', (): void => {
  const p: CompareAudioPlan = audioPlanOf(Side.LEFT, AudioTrackKind.CAP, true);
  assertEq(Side.LEFT, p.fullSide, '全量侧左');
  assertEq(Side.RIGHT, p.mutedSide, '静音侧右');
  assertEq(Side.LEFT, p.overrideSide, 'override 落左');
  assertEq(1, p.overrideGroupIndex ?? -1, '组 1');
});

test('计划 右片环境音 左侧静音且清override', (): void => {
  const p: CompareAudioPlan = audioPlanOf(Side.RIGHT, AudioTrackKind.ENV, true);
  assertEq(Side.RIGHT, p.fullSide, '全量侧右');
  assertEq(Side.LEFT, p.mutedSide, '静音侧左');
  assertEq(Side.RIGHT, p.overrideSide, 'override 落右');
  assertNull(p.overrideGroupIndex, '清 override');
});

test('计划 右片内录 右override组1', (): void => {
  const p: CompareAudioPlan = audioPlanOf(Side.RIGHT, AudioTrackKind.CAP, true);
  assertEq(Side.RIGHT, p.fullSide, '全量侧右');
  assertEq(Side.LEFT, p.mutedSide, '静音侧左');
  assertEq(Side.RIGHT, p.overrideSide, 'override 落右');
  assertEq(1, p.overrideGroupIndex ?? -1, '组 1');
});

test('计划 单音轨片请求内录 回落环境轨清override', (): void => {
  const left: CompareAudioPlan = audioPlanOf(Side.LEFT, AudioTrackKind.CAP, false);
  assertNull(left.overrideGroupIndex, '左单音轨回落清 override');
  assertEq(Side.LEFT, left.overrideSide, 'override 仍落左');
  const right: CompareAudioPlan = audioPlanOf(Side.RIGHT, AudioTrackKind.CAP, false);
  assertNull(right.overrideGroupIndex, '右单音轨回落清 override');
  assertEq(Side.RIGHT, right.overrideSide, 'override 仍落右');
});

test('命令顺序 先静音另一侧 再设或清override 最后落音量', (): void => {
  // 内录臂：设 override 组 1
  const cmds: Array<EngineCommand> =
    applyOrderOf(audioPlanOf(Side.LEFT, AudioTrackKind.CAP, true));
  assertEq(3, cmds.length, '内录臂三条命令');
  assertEq(EngineCommandKind.SET_VOLUME, cmds[0].kind, '内录臂第 1 条设音量');
  assertEq(Side.RIGHT, cmds[0].side, '内录臂第 1 条作用右（静音）');
  assertEq(0, cmds[0].volume, '内录臂第 1 条音量 0');
  assertEq(EngineCommandKind.SET_AUDIO_OVERRIDE, cmds[1].kind, '内录臂第 2 条设 override');
  assertEq(Side.LEFT, cmds[1].side, '内录臂第 2 条作用左');
  assertEq(1, cmds[1].groupIndex ?? -1, '内录臂第 2 条组 1');
  assertEq(EngineCommandKind.SET_VOLUME, cmds[2].kind, '内录臂第 3 条设音量');
  assertEq(Side.LEFT, cmds[2].side, '内录臂第 3 条作用左');
  assertEq(1, cmds[2].volume, '内录臂第 3 条音量 1');
  // 环境臂：第二条是清 override
  const envCmds: Array<EngineCommand> =
    applyOrderOf(audioPlanOf(Side.RIGHT, AudioTrackKind.ENV, true));
  assertEq(3, envCmds.length, '环境臂三条命令');
  assertEq(EngineCommandKind.SET_VOLUME, envCmds[0].kind, '环境臂第 1 条设音量');
  assertEq(Side.LEFT, envCmds[0].side, '环境臂第 1 条作用左（静音）');
  assertEq(0, envCmds[0].volume, '环境臂第 1 条音量 0');
  assertEq(EngineCommandKind.CLEAR_AUDIO_OVERRIDE, envCmds[1].kind, '环境臂第 2 条清 override');
  assertEq(Side.RIGHT, envCmds[1].side, '环境臂第 2 条作用右');
  assertEq(EngineCommandKind.SET_VOLUME, envCmds[2].kind, '环境臂第 3 条设音量');
  assertEq(Side.RIGHT, envCmds[2].side, '环境臂第 3 条作用右');
  assertEq(1, envCmds[2].volume, '环境臂第 3 条音量 1');
});

test('桥 左片环境音 左1右0清左override', (): void => {
  const e: SelectionEffect = selectionEffectOf(Side.LEFT, AudioTrackKind.ENV, true);
  assertEq(1, e.leftVolume, '左音量 1');
  assertEq(0, e.rightVolume, '右音量 0');
  assertEq(Side.LEFT, e.override.side, 'override 落左');
  assertNull(e.override.groupIndex, '清 override');
});

test('桥 左片内录 左1右0左override组1', (): void => {
  const e: SelectionEffect = selectionEffectOf(Side.LEFT, AudioTrackKind.CAP, true);
  assertEq(1, e.leftVolume, '左音量 1');
  assertEq(0, e.rightVolume, '右音量 0');
  assertEq(Side.LEFT, e.override.side, 'override 落左');
  assertEq(1, e.override.groupIndex ?? -1, '组 1');
});

test('桥 左片请求内录但单音轨 回落清左override', (): void => {
  const e: SelectionEffect = selectionEffectOf(Side.LEFT, AudioTrackKind.CAP, false);
  assertEq(1, e.leftVolume, '左音量 1');
  assertEq(0, e.rightVolume, '右音量 0');
  assertEq(Side.LEFT, e.override.side, 'override 落左');
  assertNull(e.override.groupIndex, '回落清 override');
});

test('桥 右片环境音 左0右1清右override', (): void => {
  const e: SelectionEffect = selectionEffectOf(Side.RIGHT, AudioTrackKind.ENV, true);
  assertEq(0, e.leftVolume, '左音量 0');
  assertEq(1, e.rightVolume, '右音量 1');
  assertEq(Side.RIGHT, e.override.side, 'override 落右');
  assertNull(e.override.groupIndex, '清 override');
});

test('桥 右片内录 左0右1右override组1', (): void => {
  const e: SelectionEffect = selectionEffectOf(Side.RIGHT, AudioTrackKind.CAP, true);
  assertEq(0, e.leftVolume, '左音量 0');
  assertEq(1, e.rightVolume, '右音量 1');
  assertEq(Side.RIGHT, e.override.side, 'override 落右');
  assertEq(1, e.override.groupIndex ?? -1, '组 1');
});

test('桥 右片请求内录但单音轨 回落清右override', (): void => {
  const e: SelectionEffect = selectionEffectOf(Side.RIGHT, AudioTrackKind.CAP, false);
  assertEq(0, e.leftVolume, '左音量 0');
  assertEq(1, e.rightVolume, '右音量 1');
  assertEq(Side.RIGHT, e.override.side, 'override 落右');
  assertNull(e.override.groupIndex, '回落清 override');
});

test('分屏臂 CAP加单音轨回落 不产生指向不存在组的override', (): void => {
  // 单音轨片即便请求内录也回落环境轨（不产生指向不存在组的 override，与 audioPlanOf 同口径）
  const plan = splitAudioPlanOf(false, AudioTrackKind.CAP);
  assertNull(plan.overrideGroupIndex, '回落清 override');
  assertEq(1, plan.volume, '音量恒 1');
  const effect = splitAudioEffectOf(false, AudioTrackKind.CAP);
  assertFalse(effect.override, '不要 override');
  assertNull(effect.groupIndex, '组下标空');
  assertEq(1, effect.volume, '音量恒 1（不误静音）');
});

test('分屏臂 音量恒1不误静音 双音轨选内录', (): void => {
  // 分屏只有一台引擎：必须 override 组 1 但**不许**压音量（照抄对比页会 0 音量）
  const plan = splitAudioPlanOf(true, AudioTrackKind.CAP);
  assertEq(1, plan.overrideGroupIndex ?? -1, 'override 组 1');
  assertEq(1, plan.volume, '音量恒 1');
  const effect = splitAudioEffectOf(true, AudioTrackKind.CAP);
  assertTrue(effect.override, '要 override');
  assertEq(1, effect.groupIndex ?? -1, '组 1');
  assertEq(1, effect.volume, '音量恒 1（唯一引擎不被压 0）');
});

test('分屏臂 选环境清override且音量1', (): void => {
  const plan = splitAudioPlanOf(true, AudioTrackKind.ENV);
  assertNull(plan.overrideGroupIndex, '选环境清 override');
  assertEq(1, plan.volume, '音量恒 1');
  const effect = splitAudioEffectOf(true, AudioTrackKind.ENV);
  assertFalse(effect.override, '不要 override');
  assertNull(effect.groupIndex, '组下标空');
  assertEq(1, effect.volume, '音量恒 1');
});

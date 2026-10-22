/**
 * 对比播放的剪辑模型（音轨部分），直译上游 player/CompareAudio.kt（对齐上游 38d712a）。
 *
 * 单时间线上两条视频轨（左右两窗），音轨只播其一：
 * - 两路素材不做时基对齐地叠加会梳状滤波（相位取消），产品口径只做「环境音 / 内录」二选一；
 * - **轨序口径单一真源**：容器里「环境轨 = 音频组 0、内录轨 = 音频组 1」，组下标一律经
 *   audioGroupIndexOf 取得，禁止在各调用点写 setAudioOverride(1, 0) 字面量。
 *
 * 平台差异 / 本批边界：
 * - Kotlin enum class Side.other() → 字符串 enum + 自由函数 otherSide；
 * - Kotlin sealed interface EngineCommand → 带 kind 判别字段的普通类 + 静态工厂
 *   （setVolume / setAudioOverride / clearAudioOverride），消费方按 kind 分支；
 * - Float → number（音量 0/1 整数语义，无精度问题）；
 * - 纯逻辑（不 import 平台 Kit）；页面接线（Compare.ets）留待 T25。
 */

/** 对比页两台引擎的编号（也叫两条视频轨）：0=左窗、1=右窗 */
export enum Side {
  LEFT = 'LEFT',
  RIGHT = 'RIGHT'
}

/** Side.other() 的等价物（ArkTS enum 不能挂方法） */
export function otherSide(side: Side): Side {
  return side === Side.LEFT ? Side.RIGHT : Side.LEFT;
}

/** 可选音频轨种类：环境音 / 内录（仅双音轨片有内录） */
export enum AudioTrackKind {
  ENV = 'ENV',
  CAP = 'CAP'
}

/**
 * 轨种类 → 容器音频组下标的**唯一映射**（环境=组 0、内录=组 1）。
 * 组内 trackIndex 恒 0（每组的音轨是单条），由引擎 applyAudioCommand 落定。
 */
export function audioGroupIndexOf(track: AudioTrackKind): number {
  return track === AudioTrackKind.CAP ? 1 : 0;
}

/**
 * 分屏右窗（**单引擎**）的音频计划：要不要 override、目标组下标、音量。
 *
 * 与双引擎 CompareAudioPlan 的关键差异：分屏只有一台引擎，**没有"另一侧"可静音**——
 * 「只播其一」由 override 天然保证，不需要也不许额外压音量。若照抄 applyOrderOf，
 * 那条 SetVolume(mutedSide, 0) 会把唯一那台引擎压成静音（=分屏彻底没声）。
 *
 * overrideGroupIndex 为 undefined = 清 override 回引擎默认选择（默认即容器首条音轨=环境轨）。
 */
export class SplitAudioPlan {
  readonly overrideGroupIndex: number | undefined;
  readonly volume: number;

  constructor(overrideGroupIndex: number | undefined, volume: number) {
    this.overrideGroupIndex = overrideGroupIndex;
    this.volume = volume;
  }
}

/**
 * 分屏音频计划器：单音轨片即便请求 CAP 也回落环境轨（清 override），不产生指向不存在组的
 * override（与 audioPlanOf 同一兜底口径）。组下标一律经 audioGroupIndexOf。
 */
export function splitAudioPlanOf(dualAudio: boolean, pick: AudioTrackKind): SplitAudioPlan {
  const effective: AudioTrackKind =
    pick === AudioTrackKind.CAP && dualAudio ? AudioTrackKind.CAP : AudioTrackKind.ENV;
  return new SplitAudioPlan(
    effective === AudioTrackKind.CAP ? audioGroupIndexOf(AudioTrackKind.CAP) : undefined,
    1
  );
}

/** 分屏音频选择的可断言行为三元组：要不要 override、目标组下标、音量（恒 1 = 没把唯一引擎误静音） */
export class SplitAudioEffect {
  readonly override: boolean;
  readonly groupIndex: number | undefined;
  readonly volume: number;

  constructor(override: boolean, groupIndex: number | undefined, volume: number) {
    this.override = override;
    this.groupIndex = groupIndex;
    this.volume = volume;
  }
}

/** 桥函数：把 splitAudioPlanOf 的计划落成一个空引擎态上的可观察行为 */
export function splitAudioEffectOf(dualAudio: boolean, pick: AudioTrackKind): SplitAudioEffect {
  const plan: SplitAudioPlan = splitAudioPlanOf(dualAudio, pick);
  return new SplitAudioEffect(plan.overrideGroupIndex !== undefined, plan.overrideGroupIndex, plan.volume);
}

/**
 * 一拍音频计划：哪台引擎全量、哪台静音、要不要 override、override 的组下标。
 * overrideGroupIndex 为 undefined = 清掉 override 回引擎默认选择（默认即容器首条音轨=环境轨）。
 */
export class CompareAudioPlan {
  readonly fullSide: Side;
  readonly mutedSide: Side;
  readonly overrideSide: Side;
  readonly overrideGroupIndex: number | undefined;

  constructor(fullSide: Side, mutedSide: Side, overrideSide: Side, overrideGroupIndex: number | undefined) {
    this.fullSide = fullSide;
    this.mutedSide = mutedSide;
    this.overrideSide = overrideSide;
    this.overrideGroupIndex = overrideGroupIndex;
  }
}

/** 引擎命令种类（Kotlin sealed 子类的判别字段） */
export enum EngineCommandKind {
  SET_VOLUME = 'SET_VOLUME',
  SET_AUDIO_OVERRIDE = 'SET_AUDIO_OVERRIDE',
  CLEAR_AUDIO_OVERRIDE = 'CLEAR_AUDIO_OVERRIDE'
}

/**
 * 落到某台引擎的幂等命令（顺序由 applyOrderOf 定，见其注）。
 * 三型共用一张字段表：volume 仅 SET_VOLUME 有效，groupIndex 仅 SET_AUDIO_OVERRIDE 有效。
 */
export class EngineCommand {
  readonly kind: EngineCommandKind;
  readonly side: Side;
  readonly volume: number;
  readonly groupIndex: number | undefined;

  private constructor(kind: EngineCommandKind, side: Side, volume: number, groupIndex: number | undefined) {
    this.kind = kind;
    this.side = side;
    this.volume = volume;
    this.groupIndex = groupIndex;
  }

  static setVolume(side: Side, volume: number): EngineCommand {
    return new EngineCommand(EngineCommandKind.SET_VOLUME, side, volume, undefined);
  }

  static setAudioOverride(side: Side, groupIndex: number): EngineCommand {
    return new EngineCommand(EngineCommandKind.SET_AUDIO_OVERRIDE, side, 0, groupIndex);
  }

  static clearAudioOverride(side: Side): EngineCommand {
    return new EngineCommand(EngineCommandKind.CLEAR_AUDIO_OVERRIDE, side, 0, undefined);
  }
}

/** override 目标（哪台、哪个组；组为 undefined = 清） */
export class OverrideTarget {
  readonly side: Side;
  readonly groupIndex: number | undefined;

  constructor(side: Side, groupIndex: number | undefined) {
    this.side = side;
    this.groupIndex = groupIndex;
  }
}

/** 音频选择的可断言效果三元组：左音量、右音量、override 目标 */
export class SelectionEffect {
  readonly leftVolume: number;
  readonly rightVolume: number;
  readonly override: OverrideTarget;

  constructor(leftVolume: number, rightVolume: number, override: OverrideTarget) {
    this.leftVolume = leftVolume;
    this.rightVolume = rightVolume;
    this.override = override;
  }
}

/**
 * 由会话态推出音频计划。
 *
 * sideHasDual = 当前音频侧那一片**是不是双音轨片**（唯一真源 = 轨数探测结果 ≥2，与 UI chip
 * 门同一位；不许改回用引擎上报的 audioGroups.size 做行为门——两源不一致时会「UI 能选、点了没
 * 效果」）。内录轨只有在双音轨片才可选；单音轨片即便请求 CAP 也回落环境轨（清 override）。
 */
export function audioPlanOf(audioSide: Side, track: AudioTrackKind, sideHasDual: boolean): CompareAudioPlan {
  const effective: AudioTrackKind =
    track === AudioTrackKind.CAP && sideHasDual ? AudioTrackKind.CAP : AudioTrackKind.ENV;
  return new CompareAudioPlan(
    audioSide,
    otherSide(audioSide),
    audioSide,
    effective === AudioTrackKind.CAP ? audioGroupIndexOf(AudioTrackKind.CAP) : undefined
  );
}

/**
 * 计划 → 引擎命令序列，**顺序固定**：
 * 1. 先静音另一侧（先把不播的那台压成 0，避免选轨/切边瞬间两侧同时出声）；
 * 2. 再对选中侧设/清 override（轨读取要等 onTracksChanged，但它与音量互不依赖）；
 * 3. 最后落选中侧音量（收尾保证选中侧是全量）。
 */
export function applyOrderOf(plan: CompareAudioPlan): Array<EngineCommand> {
  const cmds: Array<EngineCommand> = [];
  cmds.push(EngineCommand.setVolume(plan.mutedSide, 0));
  if (plan.overrideGroupIndex === undefined) {
    cmds.push(EngineCommand.clearAudioOverride(plan.overrideSide));
  } else {
    cmds.push(EngineCommand.setAudioOverride(plan.overrideSide, plan.overrideGroupIndex));
  }
  cmds.push(EngineCommand.setVolume(plan.fullSide, 1));
  return cmds;
}

/**
 * 桥函数：把 audioPlanOf 计划经 applyOrderOf 命令序列在一个空引擎态上跑一遍，返回最终可观察
 * 行为（左/右音量、override 目标）。只测 audioPlanOf 的字段是恒等式，测桥才能钉住
 * 「未选中侧真的静音、选中侧真的全量、override 真的落在选中侧」。
 */
export function selectionEffectOf(
  audioSide: Side,
  track: AudioTrackKind,
  sideHasDual: boolean
): SelectionEffect {
  const plan: CompareAudioPlan = audioPlanOf(audioSide, track, sideHasDual);
  let left: number = 0;
  let right: number = 0;
  let override: OverrideTarget = new OverrideTarget(plan.overrideSide, undefined);
  const cmds: Array<EngineCommand> = applyOrderOf(plan);
  for (const cmd of cmds) {
    if (cmd.kind === EngineCommandKind.SET_VOLUME) {
      if (cmd.side === Side.LEFT) {
        left = cmd.volume;
      } else {
        right = cmd.volume;
      }
    } else if (cmd.kind === EngineCommandKind.SET_AUDIO_OVERRIDE) {
      override = new OverrideTarget(cmd.side, cmd.groupIndex);
    } else {
      override = new OverrideTarget(cmd.side, undefined);
    }
  }
  return new SelectionEffect(left, right, override);
}

/**
 * 直译自上游 core/LensSlot.kt（harmony 分支）。
 * 平台差异：enumerateLenses/discoverCameraIds（CameraManager 绑定）不在本文件，
 * 由 OHOS 相机适配层从 CameraKit 的设备管理器枚举后构造 LensSlot；
 * 纯函数 assignRearTiers / shouldHideLogicalHead 与 JVM 单测同口径，全部保留。
 */
import { Diag } from '../diag/Diag';
import { WotaTag } from './Constants';
import { CameraAbility } from './CameraAbility';

/** 产品语义的镜头档位；AUTO 是「由 App 自行选档」的伪档位，不出现在枚举表里 */
export enum LensType {
  SUPER_WIDE,
  WIDE,
  TELEPHOTO,
  SUPER_TELEPHOTO,
  AUTO,
  FRONT
}

/** 一个可打开的镜头。ability 按「这颗镜头自己」的能力收，切镜头即整套换挡 */
export class LensSlot {
  type: LensType;
  logicId: string;
  physicalId: string | undefined;
  eqFocal: number;
  ability: CameraAbility;

  constructor(type: LensType, logicId: string, physicalId: string | undefined, eqFocal: number, ability: CameraAbility) {
    this.type = type;
    this.logicId = logicId;
    this.physicalId = physicalId;
    this.eqFocal = eqFocal;
    this.ability = ability;
  }

  /**
   * 槽位唯一键。档位会重复（多颗同焦段后摄、多颗前摄），只有键能唯一指认一颗镜头，
   * 所以「当前镜头 / 切镜头」一律按 key 走，type 只作展示与兜底。
   */
  get key(): string {
    return this.physicalId === undefined ? this.logicId : `${this.logicId}#${this.physicalId}`;
  }
}

/** 枚举名（对齐 Kotlin enum.name 的日志口径） */
export function lensTypeName(t: LensType): string {
  switch (t) {
    case LensType.SUPER_WIDE: return 'SUPER_WIDE';
    case LensType.WIDE: return 'WIDE';
    case LensType.TELEPHOTO: return 'TELEPHOTO';
    case LensType.SUPER_TELEPHOTO: return 'SUPER_TELEPHOTO';
    case LensType.AUTO: return 'AUTO';
    case LensType.FRONT: return 'FRONT';
    default: return '';
  }
}

/**
 * 纯函数：逻辑多摄头是否该从档位表里隐藏。
 *
 * 只有一种情形能隐藏：它确实是逻辑头、physicalCameraIds 读到了非空集合、且所有子头都已作为
 * 独立镜头暴露（hiddenPhysicals 为空）。其余情形——尤其 physicalCameraIds 为空（读取失败、
 * HAL 不报）——必须保留逻辑头本身，否则这颗摄像头会整颗消失。
 */
export function shouldHideLogicalHead(
  logical: boolean,
  physicalIds: Set<string>,
  hiddenPhysicals: Array<string>
): boolean {
  return logical && physicalIds.size > 0 && hiddenPhysicals.length === 0;
}

/**
 * 纯函数：后摄颗数 → 档位分配（表），按「物理焦距升序」逐位对应，返回表长度恒等于颗数；
 * ≥4 时多出的镜头并入超长焦档。焦段数值本身不参与（避免写死机型焦段）。
 */
export function assignRearTiers(count: number): Array<LensType> {
  if (count <= 0) {
    return [];
  }
  if (count === 1) {
    return [LensType.WIDE];
  }
  if (count === 2) {
    return [LensType.SUPER_WIDE, LensType.WIDE];
  }
  if (count === 3) {
    return [LensType.SUPER_WIDE, LensType.WIDE, LensType.TELEPHOTO];
  }
  const tiers: Array<LensType> = [LensType.SUPER_WIDE, LensType.WIDE, LensType.TELEPHOTO];
  for (let i = 3; i < count; i++) {
    tiers.push(LensType.SUPER_TELEPHOTO);
  }
  return tiers;
}

/** M1 门禁：按 WotaLens 过滤打印镜头列表 + 每档能力范围 */
export function dumpLenses(slots: Array<LensSlot>): void {
  Diag.i(WotaTag.LENS, `lenses total=${slots.length}`);
  for (const slot of slots) {
    Diag.i(WotaTag.LENS, `lens ${slot.type} key=${slot.key} eq=${slot.eqFocal}mm` +
      ` videoSizes=${slot.ability.videoSizes.length} previewSizes=${slot.ability.previewSizes.length}`);
    slot.ability.dump(slot.key);
  }
}

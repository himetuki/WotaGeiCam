/**
 * 镜头枚举的 Kit 版（探测版适配层）——对应上游 core/LensSlot.kt 的
 * enumerateLenses(CameraManager) / discoverCameraIds(CameraManager)（对齐上游 38d712a）。
 *
 * 平台差异（登记）：
 * - 上游 discoverCameraIds 除 cameraIdList 外还「按数字 id 探隐藏镜头」（某 ROM 取证专用手段）；
 *   OHOS 无对应物，不移植——getSupportedCameras() 即全集。语义损失：理论上有 HAL 隐藏头探不到，
 *   由 shouldHideLogicalHead 的「读不到子头保留逻辑头」分支兜底，不会丢摄像头；
 * - OHOS 的 constituentCameraDevices 直接给出子设备完整 CameraDevice 对象，
 *   比上游按 physicalId 再查 characteristics 更强（一次拿全，无二次查询失败面）；
 * - 子头能力用 mgr.getSupportedOutputCapability(child) 采集（上游等价路径是子头自己的
 *   CameraCharacteristics）；逻辑头全部子头已独立暴露时同样让位（shouldHideLogicalHead 原语义）。
 * 归档排序、assignRearTiers、前摄 FRONT、dumpLenses 全部复用 core/LensSlot.ets 既有导出，不重写。
 */
import { camera } from '@kit.CameraKit';
import { LensSlot, LensType, shouldHideLogicalHead, assignRearTiers } from '../core/LensSlot';
import { CameraAbility } from '../core/CameraAbility';
import { abilityFromKit, eqFocalMmOfKit, focalMmOfKit } from './KitAbility';

/** 上游 private inline tryOrNullLens 的模块级等价：单颗镜头读取失败不拖垮整轮枚举 */
function tryLens<T>(block: () => T): T | undefined {
  try {
    return block();
  } catch (e) {
    return undefined;
  }
}

/** 能力查询失败时的空能力表（字段齐全的 typed literal；abilityFromKit 各字段另有 fallback） */
const EMPTY_CAPABILITY: camera.CameraOutputCapability = {
  previewProfiles: [],
  photoProfiles: [],
  videoProfiles: [],
  supportedMetadataObjectTypes: []
};

/**
 * 可打开的镜头 id 全集。上游 = cameraIdList ∪ 数字 id 探测；OHOS = getSupportedCameras().cameraId
 * （隐藏数字 id 探测不移植，见头注释登记）。
 */
export function discoverKitCameraIds(mgr: camera.CameraManager): Array<string> {
  const devices: Array<camera.CameraDevice> =
    tryLens((): Array<camera.CameraDevice> => mgr.getSupportedCameras()) ?? [];
  return devices.map((d: camera.CameraDevice): string => d.cameraId);
}

/**
 * 默认档：归档结果里第一个 WIDE（多后摄按焦距归档后广角稳定在 WIDE）；无 WIDE 则第一个非前摄。
 * 预览引擎（KitPreview）与录制引擎（KitRecorder）共用，不写死任何 cameraId。
 */
export function pickDefaultLens(slots: Array<LensSlot>): LensSlot | undefined {
  if (slots.length === 0) {
    return undefined;
  }
  const wide: LensSlot | undefined = slots.find((s: LensSlot): boolean => s.type === LensType.WIDE);
  if (wide !== undefined) {
    return wide;
  }
  const nonFront: LensSlot | undefined = slots.find((s: LensSlot): boolean => s.type !== LensType.FRONT);
  return nonFront ?? slots[0];
}

/**
 * 按 cameraId 找回设备对象：先匹配 getSupportedCameras 本体，再匹配逻辑设备的子设备。
 * 镜头枚举后会话/输出要用 device 本体开输入（LensSlot 只存 id 不存设备引用），
 * KitProbe 取能力与 KitPreview/KitRecorder 开会话共用这一份查找，不复制遍历。
 */
export function findDeviceById(mgr: camera.CameraManager, id: string): camera.CameraDevice | undefined {
  let devices: Array<camera.CameraDevice> = [];
  try {
    devices = mgr.getSupportedCameras();
  } catch (e) {
    return undefined;
  }
  for (const device of devices) {
    if (device.cameraId === id) {
      return device;
    }
    // @SuppressWarnings compatibility
    const children: Array<camera.CameraDevice> = device.constituentCameraDevices ?? [];
    for (const child of children) {
      if (child.cameraId === id) {
        return child;
      }
    }
  }
  return undefined;
}

/** 一颗待归档的镜头（上游 private data class LensEntry 同口径：物理焦距只用来排序，不落档位名） */
class LensEntry {
  logicId: string;
  focalMm: number;
  eqFocal: number;
  ability: CameraAbility;
  front: boolean;

  constructor(logicId: string, focalMm: number, eqFocal: number, ability: CameraAbility, front: boolean) {
    this.logicId = logicId;
    this.focalMm = focalMm;
    this.eqFocal = eqFocal;
    this.ability = ability;
    this.front = front;
  }

  /** 未知焦距（HAL 不报 lensFocalLength）排最后，避免抢掉广角档（上游 focalOrder 语义） */
  get focalOrder(): number {
    return this.focalMm > 0 ? this.focalMm : Number.MAX_VALUE;
  }

  /** 等效焦距的次级排序键（上游 eqOrder 语义） */
  get eqOrder(): number {
    return this.eqFocal > 0 ? this.eqFocal : Number.MAX_VALUE;
  }
}

/** 上游 toSlot 语义：等效焦距缺失时用物理焦距顶上（都缺就是 0，档位仍由整组相对顺序决定） */
function entryToSlot(e: LensEntry, type: LensType): LensSlot {
  const eq: number = e.eqFocal > 0 ? e.eqFocal : e.focalMm;
  return new LensSlot(type, e.logicId, undefined, eq, e.ability);
}

/** 单颗设备的能力采集：getSupportedOutputCapability 失败/为空时用空表（各字段闭集兜底） */
function abilityOfDevice(mgr: camera.CameraManager, device: camera.CameraDevice): CameraAbility {
  // 用 mode 版（无 mode 版自 API 11 废弃）；预览/照片链路按 NORMAL_PHOTO 采集。
  // **2026-10-28 逻辑漏洞修复**：NORMAL_PHOTO 表的 videoProfiles 常为空（模拟器实证
  // fpsRanges= 空）⇒ decideFps 全档灰 = 帧率弹层死锁。视频档能力必须以 NORMAL_VIDEO
  // 会话表为准，这里两表合并（预览/照片用 PHOTO，videoProfiles 并上 VIDEO）。
  const photoCap: camera.CameraOutputCapability | undefined =
    tryLens((): camera.CameraOutputCapability =>
      mgr.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_PHOTO));
  const videoCap: camera.CameraOutputCapability | undefined =
    tryLens((): camera.CameraOutputCapability =>
      mgr.getSupportedOutputCapability(device, camera.SceneMode.NORMAL_VIDEO));
  const base: camera.CameraOutputCapability = photoCap ?? EMPTY_CAPABILITY;
  const merged: camera.CameraOutputCapability = {
    previewProfiles: base.previewProfiles ?? [],
    photoProfiles: base.photoProfiles ?? [],
    videoProfiles: (base.videoProfiles ?? []).concat(videoCap?.videoProfiles ?? []),
    supportedMetadataObjectTypes: []
  };
  return abilityFromKit(device.cameraId, device, merged);
}

/**
 * 枚举镜头（上游 enumerateLenses 四步语义不变）：
 * 1. 可打开集合 = getSupportedCameras；
 * 2. 逻辑多摄（isLogicalCamera）按 constituentCameraDevices 展开：子头已单独列出不重复，
 *    未暴露的子头各自补成一颗镜头；子头全部已暴露时逻辑头让位（shouldHideLogicalHead 原语义），
 *    读不到子头时保留逻辑头本身；
 * 3. 后摄按物理焦距升序整组归档（多颗同焦段也各自成档），前摄统一 FRONT、按 key 区分；
 * 4. 能力按「这颗镜头自己」的输出能力收，切镜头整套换挡。
 */
export function enumerateKitLenses(mgr: camera.CameraManager): Array<LensSlot> {
  const devices: Array<camera.CameraDevice> =
    tryLens((): Array<camera.CameraDevice> => mgr.getSupportedCameras()) ?? [];
  const openable: Set<string> = new Set<string>(
    devices.map((d: camera.CameraDevice): string => d.cameraId));
  const entries: Array<LensEntry> = [];

  for (const device of devices) {
    const front: boolean = device.cameraPosition === camera.CameraPosition.CAMERA_POSITION_FRONT;
    // @SuppressWarnings compatibility
    const logical: boolean = device.isLogicalCamera === true;
    // @SuppressWarnings compatibility
    const children: Array<camera.CameraDevice> =
      logical ? (device.constituentCameraDevices ?? []) : [];
    const childIds: Set<string> = new Set<string>(
      children.map((c: camera.CameraDevice): string => c.cameraId));
    // 未被 getSupportedCameras 单独暴露的子头才补成镜头（上游 hiddenPhysicals 口径）
    const hiddenPhysicals: Array<camera.CameraDevice> =
      children.filter((c: camera.CameraDevice): boolean => !openable.has(c.cameraId));
    const hiddenIds: Array<string> = hiddenPhysicals.map((c: camera.CameraDevice): string => c.cameraId);
    if (shouldHideLogicalHead(logical, childIds, hiddenIds)) {
      continue;
    }

    for (const child of hiddenPhysicals) {
      entries.push(new LensEntry(
        child.cameraId, focalMmOfKit(child), eqFocalMmOfKit(child), abilityOfDevice(mgr, child), front));
    }
    if (logical) {
      continue;
    }
    entries.push(new LensEntry(
      device.cameraId, focalMmOfKit(device), eqFocalMmOfKit(device), abilityOfDevice(mgr, device), front));
  }

  const rears: Array<LensEntry> = entries
    .filter((e: LensEntry): boolean => !e.front)
    .slice()
    .sort((a: LensEntry, b: LensEntry): number => {
      if (a.focalOrder !== b.focalOrder) {
        return a.focalOrder - b.focalOrder;
      }
      return a.eqOrder - b.eqOrder;
    });
  const tiers: Array<LensType> = assignRearTiers(rears.length);
  const fronts: Array<LensEntry> = entries.filter((e: LensEntry): boolean => e.front);

  const slots: Array<LensSlot> = [];
  rears.forEach((e: LensEntry, index: number): void => {
    // tiers 长度恒等于 rears 颗数；越界兜底取末位（上游 tiers.getOrElse { tiers.last() }）
    const tier: LensType = index < tiers.length ? tiers[index] : tiers[tiers.length - 1];
    slots.push(entryToSlot(e, tier));
  });
  for (const e of fronts) {
    slots.push(entryToSlot(e, LensType.FRONT));
  }
  return slots;
}

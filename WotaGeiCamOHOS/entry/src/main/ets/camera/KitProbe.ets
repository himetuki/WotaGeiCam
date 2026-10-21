/**
 * 探测入口（探测版适配层）：getCameraManager → 镜头枚举 → 能力/档位表 dump（hilog 走 Diag）
 * + 返回给页面的人读摘要串。对应上游在 CameraController 初始化期的镜头/能力采集
 * （对齐上游 38d712a）；本批不打开相机会话、不做预览。
 *
 * 预期行为（登记）：本批尚未注册 CAMERA 权限——运行时 getCameraManager /
 * getSupportedCameras 抛权限错误走 catch 是预期路径，页面兜底显示错误摘要；
 * 权限注册与申请流随预览批补。dump 文案零新写：镜头/能力/档位表一律调
 * core 直译版 dumpLenses / dumpVideoSizeTable / CameraAbility.dump。
 */
import { camera } from '@kit.CameraKit';
import { common } from '@kit.AbilityKit';
import { Diag } from '../diag/Diag';
import { WotaTag } from '../core/Constants';
import { LensSlot, dumpLenses } from '../core/LensSlot';
import { VideoSizeTable, dumpVideoSizeTable } from '../core/VideoSizePicker';
import { enumerateKitLenses } from './KitLens';
import { videoSizeTableFromKit } from './VideoSizeKit';

/** 按 cameraId 找回该镜头的输出能力表：先匹配设备本体，再匹配逻辑设备的子设备 */
function capabilityForSlotId(mgr: camera.CameraManager, id: string): camera.CameraOutputCapability | undefined {
  let devices: Array<camera.CameraDevice> = [];
  try {
    devices = mgr.getSupportedCameras();
  } catch (e) {
    return undefined;
  }
  for (const device of devices) {
    if (device.cameraId === id) {
      return outputCapabilityOf(mgr, device);
    }
    const children: Array<camera.CameraDevice> = device.constituentCameraDevices ?? [];
    for (const child of children) {
      if (child.cameraId === id) {
        return outputCapabilityOf(mgr, child);
      }
    }
  }
  return undefined;
}

function outputCapabilityOf(mgr: camera.CameraManager, device: camera.CameraDevice):
camera.CameraOutputCapability | undefined {
  try {
    return mgr.getSupportedOutputCapability(device);
  } catch (e) {
    return undefined;
  }
}

/**
 * 一键探测并落 hilog（WotaLens / WotaAbility / WotaVideoSize 过滤），返回页面可显示的摘要串：
 * 镜头数、各镜头 key/等效焦距/视频表数、首镜头档位表概况。
 */
export function probeAndDump(ctx: common.Context): string {
  try {
    const mgr: camera.CameraManager = camera.getCameraManager(ctx);
    const slots: Array<LensSlot> = enumerateKitLenses(mgr);
    dumpLenses(slots);

    let summary: string = `lenses total=${slots.length}`;
    for (const slot of slots) {
      summary += `\nslot ${slot.key} eq=${slot.eqFocal}mm` +
        ` videoSizes=${slot.ability.videoSizes.length} previewSizes=${slot.ability.previewSizes.length}`;
    }

    if (slots.length > 0) {
      const first: LensSlot = slots[0];
      const capability: camera.CameraOutputCapability | undefined = capabilityForSlotId(mgr, first.logicId);
      if (capability !== undefined) {
        const table: VideoSizeTable = videoSizeTableFromKit(first.ability, capability);
        dumpVideoSizeTable(table);
        const maxSize: string = table.maxSize === undefined
          ? 'none' : `${table.maxSize.width}x${table.maxSize.height}`;
        const minSize: string = table.minSize === undefined
          ? 'none' : `${table.minSize.width}x${table.minSize.height}`;
        summary += `\nsizeTable options=${table.options.length} supported=${table.supported.length}` +
          ` max=${maxSize} min=${minSize} fixedFps=${table.fixedFps.join(',')}`;
      }
    }
    return summary;
  } catch (e) {
    // 无 CAMERA 权限/相机服务不可用时走这里（预期，见头注释登记）
    Diag.e(WotaTag.ABILITY, `probe failed: ${e}`);
    return `probe failed: ${e}`;
  }
}

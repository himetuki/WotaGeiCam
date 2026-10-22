/**
 * CAMERA 权限申请门（预览批）：requestPermissionsFromUser 一次弹框、Promise 汇总判定。
 * 对应上游 AndroidManifest 的 CAMERA 声明 + 运行时 ActivityCompat.requestPermissions
 * 的最小等价（对齐上游 38d712a）。
 * 登记：永久拒绝（弹框不再出现）时的「去设置」特殊引导随 HUD 批；本批拒绝即返回 false，
 * 页面兜底提示、可重点重试（系统授权策略自行决定是否再弹）。
 */
import { abilityAccessCtrl, common, Permissions, PermissionRequestResult } from '@kit.AbilityKit';

/** 一次申请的结果拆分：camera/mic 各自授权态（录制批：mic 拒绝退视频-only） */
export class RecordPermResult {
  camera: boolean;
  mic: boolean;

  constructor(camera: boolean, mic: boolean) {
    this.camera = camera;
    this.mic = mic;
  }
}

/**
 * 申请录制双权限（CAMERA + MICROPHONE）：一次弹框、按 permissions/authResults 逐项判定
 * （authResults 全部 = 0 为授予）；异常 → 全 false（页面兜底）。
 */
export async function requestRecordPermissions(ctx: common.Context): Promise<RecordPermResult> {
  try {
    const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
    const perms: Array<Permissions> = ['ohos.permission.CAMERA', 'ohos.permission.MICROPHONE'];
    const result: PermissionRequestResult =
      await atManager.requestPermissionsFromUser(ctx, perms);
    let camera: boolean = false;
    let mic: boolean = false;
    const n: number = Math.min(result.permissions.length, result.authResults.length);
    for (let i = 0; i < n; i++) {
      const granted: boolean = result.authResults[i] === 0;
      if (result.permissions[i] === 'ohos.permission.CAMERA') {
        camera = granted;
      } else if (result.permissions[i] === 'ohos.permission.MICROPHONE') {
        mic = granted;
      }
    }
    return new RecordPermResult(camera, mic);
  } catch (e) {
    return new RecordPermResult(false, false);
  }
}

/**
 * 申请 CAMERA 权限：授权（authResults 全部 = 0，PERMISSION_GRANTED）→ true；
 * 拒绝/取消/异常 → false（不抛出，页面按 false 兜底显示）。
 */
export async function requestCameraPermission(ctx: common.Context): Promise<boolean> {
  try {
    const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
    const perms: Array<Permissions> = ['ohos.permission.CAMERA'];
    const result: PermissionRequestResult =
      await atManager.requestPermissionsFromUser(ctx, perms);
    return result.authResults.length > 0 &&
      result.authResults.every((s: number): boolean => s === 0);
  } catch (e) {
    return false;
  }
}

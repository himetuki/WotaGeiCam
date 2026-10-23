/**
 * 更新包落地与安装引导——上游 update/UpdateInstaller.kt 的鸿蒙对应物。
 *
 * **平台事实（决定了本文件形态）**：HarmonyOS NEXT 不向三方应用开放静默安装/拉起系统安装器的
 * 接口（`INSTALL_BUNDLE` 属系统级权限），所以只能：
 * 1. 下载完成后做**结构核对**（PK 魔数 + 非空 + 体积与探测一致；与上游「包名/签名核对」同位）；
 * 2. 经系统文件选择器把包**另存到用户可见目录**（DocumentViewPicker.save），用户在文件管理里
 *    点按安装——这一步由系统安装器接管，签名/profile 校验仍归系统；
 * 3. 同时提供「打开 Release 页」作为兜底入口。
 *
 * 与上游的差异登记：① 无「同包名 + 同签名证书集合」的程序化核对（OHOS 无读包体签名信息的
 * 公开 API），补偿是有密码学意义的 SHA-256（段 1 官方说明）与系统安装器的 profile 校验；
 * ② 不改 FileProvider/Intent，改走 picker 保存 + 浏览器兜底。
 */
import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';
import { picker } from '@kit.CoreFileKit';
import { Diag } from '../diag/Diag';

const TAG: string = 'WotaUpdate';

/** 保存结果：ok=false 时 message 可直接上屏 */
export class SaveResult {
  ok: boolean;
  uri: string;
  message: string;

  constructor(ok: boolean, uri: string, message: string) {
    this.ok = ok;
    this.uri = uri;
    this.message = message;
  }
}

export class UpdateInstaller {
  /** HAP 扩展名（picker 的默认后缀与另存文件名都用它） */
  static readonly PACKAGE_SUFFIX: string = '.hap';

  /**
   * 交付前的最后一道结构核对（与下载完成时的校验同位，防中间态被换）：
   * 文件存在、非空、首两字节 `PK`。
   */
  static verifyPackage(path: string): boolean {
    let fd: number = -1;
    try {
      const stat: fileIo.Stat = fileIo.statSync(path);
      if (stat.size < 2) {
        return false;
      }
      fd = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY).fd;
      const buf: ArrayBuffer = new ArrayBuffer(2);
      const n: number = fileIo.readSync(fd, buf);
      if (n !== 2) {
        return false;
      }
      const b: Uint8Array = new Uint8Array(buf);
      return b[0] === 0x50 && b[1] === 0x4b;
    } catch (e) {
      Diag.w(TAG, `更新包结构核对失败：${path}`, e as Error);
      return false;
    } finally {
      if (fd >= 0) {
        try {
          fileIo.closeSync(fd);
        } catch (e) {
          // 只读句柄关闭失败不影响判据
        }
      }
    }
  }

  /**
   * 经系统文件选择器另存 HAP（用户选目录，文件落进可见存储，可在文件管理里点按安装）。
   * 用户取消选择 → ok=false + message '已取消保存'（不是错误，UI 按中性提示）。
   */
  static async saveAs(ctx: common.Context, sourcePath: string, fileName: string): Promise<SaveResult> {
    if (!UpdateInstaller.verifyPackage(sourcePath)) {
      return new SaveResult(false, '', '更新包已损坏，请重新下载');
    }
    try {
      const docPicker: picker.DocumentViewPicker = new picker.DocumentViewPicker(ctx);
      const options: picker.DocumentSaveOptions = {
        newFileNames: [fileName],
        fileSuffixChoices: [UpdateInstaller.PACKAGE_SUFFIX]
      };
      const uris: Array<string> = await docPicker.save(options);
      if (uris.length === 0) {
        return new SaveResult(false, '', '已取消保存');
      }
      const destUri: string = uris[0];
      UpdateInstaller.copyToUri(sourcePath, destUri);
      Diag.i(TAG, `更新包已另存：${destUri}`);
      return new SaveResult(true, destUri, '已保存，请在文件管理中打开安装');
    } catch (e) {
      Diag.e(TAG, `另存更新包失败`, e as Error);
      return new SaveResult(false, '', '保存失败，请稍后重试');
    }
  }

  /** 打开 Release 页（浏览器）——没有安装器可拉起时的兜底入口 */
  static async openReleasePage(ctx: common.Context, url: string): Promise<boolean> {
    try {
      // startAbility 定义在 UIAbilityContext 上（页面侧拿到的就是它；声明类型是更宽的 Context）
      await (ctx as common.UIAbilityContext).startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: url
      });
      return true;
    } catch (e) {
      Diag.w(TAG, `打开 Release 页失败：${url}`, e as Error);
      return false;
    }
  }

  /** 复制到 picker 返回的目标 uri（64KiB 分块，避免整包进内存） */
  private static copyToUri(sourcePath: string, destUri: string): void {
    let srcFd: number = -1;
    let destFd: number = -1;
    try {
      srcFd = fileIo.openSync(sourcePath, fileIo.OpenMode.READ_ONLY).fd;
      destFd = fileIo.openSync(destUri,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC).fd;
      const buf: ArrayBuffer = new ArrayBuffer(64 * 1024);
      while (true) {
        const n: number = fileIo.readSync(srcFd, buf);
        if (n <= 0) {
          break;
        }
        fileIo.writeSync(destFd, n === buf.byteLength ? buf : buf.slice(0, n));
      }
    } catch (e) {
      // 抛给 saveAs 的 catch 统一上屏（复制失败 = 保存失败）
      Diag.e(TAG, `复制更新包到目标 uri 失败：${destUri}`, e as Error);
      throw new Error('copy package failed');
    } finally {
      if (destFd >= 0) {
        try {
          fileIo.closeSync(destFd);
        } catch (e) {
          // 关闭失败不掩盖主流程异常
        }
      }
      if (srcFd >= 0) {
        try {
          fileIo.closeSync(srcFd);
        } catch (e) {
          // 同上
        }
      }
    }
  }
}

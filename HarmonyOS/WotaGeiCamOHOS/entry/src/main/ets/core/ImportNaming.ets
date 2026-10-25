/**
 * 从系统相册导入的命名纯逻辑（ALB 批，2026-10-25）。
 *
 * 上游 `MediaScope.AllVideos` 是**浏览全机视频**的 scope（Enumerating MediaStore，需要
 * `READ_MEDIA_VIDEO` 一类读权限，并对别人的片做收藏/回收站/删除）。本侧一贯姿态是**零媒体
 * 权限**（MS 批红线用例钉住「零新增权限」），且沙箱模型下管理别人的文件必须逐次弹系统授权框
 * ——正是上游 StorageAccess 那条线要规避的东西。故本批取等价且合规的形态：
 * **系统相册选择器（临时授权读，零权限）→ 拷贝进应用库**，导入后就是本应用的正常成片
 * （可改名/标签/系列/分享，全部既有能力自动可用）。
 *
 * 本文件只管**命名与去重**（可 full test）：源文件名合法化 + 扩展名补齐 + 撞名时按
 * `name (2).mp4` 递增，直到库内无同名为止。
 */
import { RenameSpec } from './RenameSpec';

export class ImportNaming {
  /** 撞名递增上限（超过即放弃，避免病态输入把循环拖长） */
  static readonly MAX_TRIES: number = 200;

  /** 导入件默认扩展名（选择器过滤 VIDEO，源名缺后缀时兜底） */
  static readonly DEFAULT_EXT: string = 'mp4';

  /** 源文件名取自 URI 尾段：去掉查询串与目录，做一次百分号解码（失败原样） */
  static fileNameFromUri(uri: string): string {
    let s: string = uri;
    const q: number = s.indexOf('?');
    if (q >= 0) {
      s = s.substring(0, q);
    }
    const hash: number = s.indexOf('#');
    if (hash >= 0) {
      s = s.substring(0, hash);
    }
    const slash: number = s.lastIndexOf('/');
    if (slash >= 0) {
      s = s.substring(slash + 1);
    }
    try {
      return decodeURIComponent(s);
    } catch (e) {
      return s;
    }
  }

  /**
   * 目标名：源名合法化（走 RenameSpec 同一道闸：拒 `/`、`\`、`.`、`..`、控制字符）+
   * 缺扩展名补 `.mp4`；源名整体不可用（空/非法）时返回空串，由调用方回退到时间戳名。
   */
  static preferredName(sourceUri: string): string {
    const raw: string = ImportNaming.fileNameFromUri(sourceUri);
    const clean: string = RenameSpec.sanitize(raw);
    if (clean === '') {
      return '';
    }
    const ext: string = RenameSpec.extOf(clean);
    return ext === '' ? `${clean}.${ImportNaming.DEFAULT_EXT}` : clean;
  }

  /** 兜底名：`IMP_yyyyMMdd_HHmmss.mp4`（源名不可用/撞名超限时用；纯函数，时刻由调用方传入） */
  static stampName(epochMs: number): string {
    const d: Date = new Date(epochMs);
    const two = (v: number): string => (v < 10 ? `0${v}` : `${v}`);
    return `IMP_${d.getFullYear()}${two(d.getMonth() + 1)}${two(d.getDate())}_` +
      `${two(d.getHours())}${two(d.getMinutes())}${two(d.getSeconds())}.${ImportNaming.DEFAULT_EXT}`;
  }

  /** 撞名递增：`a.mp4` → `a (2).mp4` → `a (3).mp4`（基名取 stem，扩展名保留） */
  static withOrdinal(name: string, ordinal: number): string {
    if (ordinal <= 1) {
      return name;
    }
    const stem: string = RenameSpec.stemOf(name);
    const ext: string = RenameSpec.extOf(name);
    const tail: string = ext === '' ? '' : `.${ext}`;
    return `${stem} (${ordinal})${tail}`;
  }

  /**
   * 取库内首个不撞名的名字。`taken` 传库内**现有文件名**集合（同目录大小写不敏感比较：
   * 部分文件系统大小写不敏感，`A.mp4` 与 `a.mp4` 会互相覆盖）。
   * 返回空串 = 连递增上限都用完了（调用方报失败，不硬写覆盖）。
   */
  static uniqueName(preferred: string, taken: Array<string>): string {
    if (preferred === '') {
      return '';
    }
    const lower: Set<string> = new Set<string>();
    for (const t of taken) {
      lower.add(t.toLowerCase());
    }
    for (let i = 1; i <= ImportNaming.MAX_TRIES; i++) {
      const candidate: string = ImportNaming.withOrdinal(preferred, i);
      if (!lower.has(candidate.toLowerCase())) {
        return candidate;
      }
    }
    return '';
  }
}
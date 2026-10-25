/**
 * 媒体改名的纯逻辑（RN 批，2026-10-25；上游 `MediaActions.rename` + `ensureSuffix` 的移植）。
 *
 * 与上游的**根因差异**（决定了本批不是照抄）：上游改名走 `MediaStore` 的 `DISPLAY_NAME`，
 * 底层文件连带改名，而 MediaStore 的 `_ID` **不随名字变**——所有按 ID 记账的表天然不受影响。
 * 本侧是沙箱文件系统 + 「ID = 相对路径 32 位 FNV-1a」（`MediaRepo.MediaIds`），改名即换 ID，
 * 于是改名必须成套迁移：磁盘文件 + `.drops.json` sidecar + `media_tag`/`trash_item`/
 * `series_part` 三表 + `TrackSync` 路径键。本文件只出**纯算式**（新名合法化与新路径），
 * 迁移编排在 `MediaRepo.rename`。
 *
 * 上游两条硬规矩照搬：① 空名直接拒；② **后缀按旧名补齐**（用户只输「我的片子」时补 `.mp4`，
 * 输入已带同名后缀则不重复补）。另加本侧必守的一条：**名里不许带路径分隔符或 `.`/`..`**
 * （上游靠 MediaStore 消毒，我们直接拼路径，用户输入必须自己拦——上游 f941799 正是为
 * sidecar 拼路径补了同一道闸）。
 */
export class RenameSpec {
  /** 名字长度上限（文件系统常见上限 255 字节，留路径余量取 100） */
  static readonly MAX_NAME_LEN: number = 100;

  /** 基名（去扩展名）；无扩展名时原样返回 */
  static stemOf(name: string): string {
    const dot: number = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  /** 扩展名（不含点）；无扩展名返回空串 */
  static extOf(name: string): string {
    const dot: number = name.lastIndexOf('.');
    return dot > 0 ? name.substring(dot + 1) : '';
  }

  /**
   * 合法化用户输入 → 可直接用的文件名；不合法返回空串（调用方给提示、不动作）。
   * 拒绝：空/全空白、含路径分隔符（`/` `\`）、等于 `.` 或 `..`、含控制字符、超长。
   */
  static sanitize(raw: string): string {
    const trimmed: string = raw.trim();
    if (trimmed === '' || trimmed === '.' || trimmed === '..') {
      return '';
    }
    if (trimmed.length > RenameSpec.MAX_NAME_LEN) {
      return '';
    }
    for (const ch of trimmed.split('')) {
      if (ch === '/' || ch === '\\') {
        return '';
      }
      const code: number = ch.charCodeAt(0);
      if (code < 0x20 || code === 0x7f) {
        return '';
      }
    }
    return trimmed;
  }

  /**
   * 补齐后缀（上游 `ensureSuffix` 逐字）：旧名有扩展名、而新名没带同名后缀时补上。
   * 大小写不敏感（`.MP4` 视为已带）；旧名无扩展名则原样返回。
   */
  static ensureSuffix(name: string, oldName: string): string {
    const ext: string = RenameSpec.extOf(oldName);
    if (ext === '') {
      return name;
    }
    const lower: string = name.toLowerCase();
    const suffix: string = ('.' + ext).toLowerCase();
    if (lower.length >= suffix.length && lower.substring(lower.length - suffix.length) === suffix) {
      return name;
    }
    return name + '.' + ext;
  }

  /**
   * 一次到位：合法化 + 补后缀；返回空串表示应当拒绝这次改名。
   * 与旧名**逐字相同**时也返回空串（无变化 → 调用方不动作，免一次无谓的重命名与迁移）。
   */
  static resolve(raw: string, oldName: string): string {
    const clean: string = RenameSpec.sanitize(raw);
    if (clean === '') {
      return '';
    }
    const withExt: string = RenameSpec.ensureSuffix(clean, oldName);
    return withExt === oldName ? '' : withExt;
  }

  /** 同目录新路径（目录部分逐字保留） */
  static newPathOf(oldPath: string, fileName: string): string {
    const slash: number = oldPath.lastIndexOf('/');
    return slash < 0 ? fileName : oldPath.substring(0, slash + 1) + fileName;
  }

  /** 同目录新相对路径（相对路径只做同样替换，不碰目录结构） */
  static newRelativePathOf(oldRelativePath: string, fileName: string): string {
    return RenameSpec.newPathOf(oldRelativePath, fileName);
  }
}
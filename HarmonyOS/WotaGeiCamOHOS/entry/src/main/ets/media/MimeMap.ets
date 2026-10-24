/**
 * 扩展名 → MIME / UTD 映射（上游 media/Share.kt 的 `mimeOf` 手写表直译）。
 *
 * 上游先查系统 `MimeTypeMap` 再落手写表；OHOS 没有全局 MIME 查询服务，**只保留手写表**
 * （未知扩展名兜底 `video/*` / `general.video`，与上游兜底同义）——登记为平台差异。
 *
 * UTD（Uniform Type Descriptor）是 systemShare 面板的类型语言：`utd` 字段吃 UTD ID 串
 * （本机 SDK `@ohos.data.uniformTypeDescriptor` 枚举核实：`MPEG4 = 'general.mpeg-4'`、
 * `VIDEO = 'general.video'`；**没有** `general.mp4` 这个 id）。
 *
 * 零平台 import：映射表可在 test/pure 里全表钉住。
 */

/** 文件名 → 小写扩展名（无点/尾点 = 空串；对齐上游 `substringAfterLast('.', "")`） */
export function extOf(name: string): string {
  const dot: number = name.lastIndexOf('.');
  if (dot < 0 || dot === name.length - 1) {
    return '';
  }
  return name.substring(dot + 1).toLowerCase();
}

/**
 * MIME：上游手写表逐条照搬。本仓产物实际只有 mp4，其余档位是「外部导入再分享」的兜底。
 */
export function mimeOf(name: string): string {
  const ext: string = extOf(name);
  if (ext === 'mp4' || ext === 'm4v') {
    return 'video/mp4';
  }
  if (ext === 'mov') {
    return 'video/quicktime';
  }
  if (ext === '3gp') {
    return 'video/3gpp';
  }
  if (ext === 'mkv') {
    return 'video/x-matroska';
  }
  if (ext === 'webm') {
    return 'video/webm';
  }
  return 'video/*';
}

/**
 * UTD ID：systemShare 面板按它筛「能接收的应用」。mp4 一族给精确的 `general.mpeg-4`，
 * 其余（含未知）给宽的 `general.video`——宁可多给几个候选应用，不因类型过窄把面板筛空。
 */
export function utdOf(name: string): string {
  const ext: string = extOf(name);
  if (ext === 'mp4' || ext === 'm4v') {
    return 'general.mpeg-4';
  }
  return 'general.video';
}

/**
 * 文件名 → 存相册用的标题（去扩展名）：`PhotoCreationConfig.title` 语义是资产名，
 * 系统会按 `extension` 另行拼后缀，标题里再带 `.mp4` 会得到 `xxx.mp4.mp4`。
 */
export function titleOf(name: string): string {
  const dot: number = name.lastIndexOf('.');
  return dot > 0 ? name.substring(0, dot) : name;
}

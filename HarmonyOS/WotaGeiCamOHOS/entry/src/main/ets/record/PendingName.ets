/**
 * 直译自上游 record/PendingName.kt（harmony 分支）。
 * 平台差异：
 * - Kotlin Regex.replace（全局替换）→ RegExp 带 g 标志（ArkTS 禁正则字面量，故 new RegExp；
 *   String.replace 对全局正则从头替换全部命中，语义同 Kotlin）；
 * - 上游 cur 取名用的是**单参** substringAfterLast('/')（缺分隔符时默认值=整串，其后仍可
 *   走到裸文件名返回）；本侧 lastIndexOf('/') < 0 直接判 null——**有意分歧**，两侧真实
 *   调用域（文件系统路径恒含 '/'）不可达该分支，故行为等价。上游 dir 用的才是显式传空串
 *   的 substringBeforeLast('/', "")（缺分隔符 → ""，本侧 slash<0 已提前返回，不可达），
 *   slash 为 0 时 dir 为空串、返回裸 realName，与上游一致。
 */

/**
 * MediaStore pending 临时名清洗（2026-10-07 命名 race 修复）。
 *
 * pending 记录在 MediaProvider 里带 `.pending-<id>-<原名>` 临时段，commit（IS_PENDING→0）后
 * provider 才异步把它复原成原名。commit 后改名侧若在复原完成前查到的名字还带着临时段，
 * 直接拼前缀就会把它烙进最终成片名（真机三连实证：
 * `30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4`）。
 *
 * stripPendingJunk 把该形态的段剥掉（含多个段也全剥）；正则钉住「`.pending-` + 纯数字 + `-`」
 * 形态——名字里恰好含 "pending" 字样但不是这个形态的正常名字（`my.pending-file.mp4`、
 * `pending-abc-x.mp4`）不受影响。真人名字恰长成 `x.pending-123-y.mp4` 的形态与临时名
 * 无法区分，按临时名剥（provider 自己也是这么认的），记为已知边界。
 *
 * commit 后改名的所有出口（成片前缀改名、sidecar 跟名）一律走 commitRenameTarget，
 * 禁止散落各写一份清洗规则。
 */
export class PendingName {
  /** MediaProvider 的 pending 临时段：`.pending-<纯数字>-`（id 为纯数字，见类注）；g = Kotlin Regex.replace 全局替换语义 */
  private static readonly PENDING_SEG: RegExp = new RegExp('\\.pending-\\d+-', 'g');

  /** 剥掉名字里所有 pending 临时段；无临时段时原样返回 */
  static stripPendingJunk(displayName: string): string {
    return displayName.replace(PendingName.PENDING_SEG, '');
  }

  /** commit 后改名的单一出口：前缀 + 剥净临时段的名字（fetchedName 取到临时名也不怕） */
  static commitRenameTarget(prefix: string, fetchedName: string): string {
    return prefix + PendingName.stripPendingJunk(fetchedName);
  }

  /**
   * sidecar 归位用的成片路径（2026-10-08 幽灵 sidecar 修复）。
   *
   * 为什么需要它：sidecar 是录制收尾时按当时的成片文件名落的，而 commit 后 provider 把
   * `.pending-<id>-` 复原成片名是异步的。原先"搬 sidecar"这一步被嵌在"加前缀改名"分支里
   * （条件是源实测帧率可用），于是不加前缀的会话整块跳过 ⇒ sidecar 永久留在 pending 名下
   * （真机每会话一条 `.pending-<id>-….drops.json` 幽灵，成片从此找不到自己的位次档案）。
   * 归位与"是否加前缀"无关，所以抽成这座桥、在所有改名动作之后无条件走一次。
   *
   * 入参与返回都是成片路径（不是 sidecar 路径）：真正的搬动交给 ArcDropLog.renameFor，
   * 它按成片路径自己拼扩展名——把带 `.drops.json` 的 sidecar 名喂进去会拼成
   * `….mp4.drops.drops.json`（真机 r20 首轮实测踩过）。
   *
   * 名字口径与成片改名同一套 stripPendingJunk：目标名取回查名剥净（回查名可能仍带临时段）；
   * 回查失败退回"当前名剥净"，与成片自己的回查失败兜底同规则，两边名字永不分叉。
   * 比较用物理当前名：文件真叫 `.pending-9-VID_1.mp4` 时必须搬，哪怕剥净后与目标同名。
   *
   * @param videoPath 成片当前路径（录制期 sidecar 落盘用的那条）
   * @param queriedName 回查到的成片 DISPLAY_NAME（null/空 = 回查失败）
   * @return 成片应处的路径；null = 无需搬动（物理名已一致或名字无法确定）
   */
  static sidecarConvergeTarget(videoPath: string, queriedName: string | null): string | null {
    const slash: number = videoPath.lastIndexOf('/');
    if (slash < 0) {
      // 有意分歧：上游单参 substringAfterLast('/') 缺分隔符返回整串（其后可走到裸文件名返回），
      // 本侧判 null。文件系统路径恒含 '/'，两侧均不可达该分支，行为等价（见文件头平台差异）
      return null;
    }
    const cur: string = videoPath.substring(slash + 1);
    const curClean: string = PendingName.stripPendingJunk(cur);
    if (curClean.length === 0) {
      return null;
    }
    let realName: string = curClean;
    if (queriedName !== null) {
      const queried: string = PendingName.stripPendingJunk(queriedName);
      if (queried.length > 0) {
        realName = queried;
      }
    }
    if (realName === cur) {
      return null;
    }
    const dir: string = videoPath.substring(0, slash);
    return dir.length === 0 ? realName : `${dir}/${realName}`;
  }
}

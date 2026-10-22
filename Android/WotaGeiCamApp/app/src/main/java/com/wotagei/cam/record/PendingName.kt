package com.wotagei.cam.record

/**
 * MediaStore pending 临时名清洗（2026-10-07 命名 race 修复）。
 *
 * pending 记录在 MediaProvider 里带 `.pending-<id>-<原名>` 临时段，commit（IS_PENDING→0）后
 * provider 才**异步**把它复原成原名。commit 后改名侧若在复原完成前查到的名字还带着临时段，
 * 直接拼前缀就会把它烙进最终成片名（真机 WIKO 三连实证：
 * `30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4`）。
 *
 * [stripPendingJunk] 把该形态的段剥掉（含多个段也全剥）；正则钉住「`.pending-` + **纯数字** + `-`」
 * 形态——名字里恰好含 "pending" 字样但不是这个形态的正常名字（`my.pending-file.mp4`、
 * `pending-abc-x.mp4`）不受影响。真人名字恰长成 `x.pending-123-y.mp4` 的形态与临时名无法区分，
 * 按临时名剥（provider 自己也是这么认的），记为已知边界。
 *
 * commit 后改名的所有出口（成片前缀改名、sidecar 跟名）一律走 [commitRenameTarget]，
 * 禁止散落各写一份清洗规则。
 */
object PendingName {

    /** MediaProvider 的 pending 临时段：`.pending-<纯数字>-`（id 为纯数字，见类注） */
    private val PENDING_SEG = Regex("""\.pending-\d+-""")

    /** 剥掉名字里所有 pending 临时段；无临时段时原样返回 */
    fun stripPendingJunk(displayName: String): String = PENDING_SEG.replace(displayName, "")

    /** commit 后改名的单一出口：前缀 + 剥净临时段的名字（fetchedName 取到临时名也不怕） */
    fun commitRenameTarget(prefix: String, fetchedName: String): String =
        prefix + stripPendingJunk(fetchedName)

    /**
     * sidecar 归位用的**成片路径**（2026-10-08 幽灵 sidecar 修复）。
     *
     * 为什么需要它：sidecar 是录制收尾时按**当时的成片文件名**落的，而 commit 后 provider 把
     * `.pending-<id>-` 复原成片名是**异步**的。原先"搬 sidecar"这一步被嵌在"加前缀改名"分支里
     * （条件是源实测帧率可用），于是**不加前缀的会话整块跳过** ⇒ sidecar 永久留在 pending 名下
     * （真机每会话一条 `.pending-<id>-….drops.json` 幽灵，成片从此找不到自己的位次档案）。
     * 归位与"是否加前缀"无关，所以抽成这座桥、在**所有改名动作之后**无条件走一次。
     *
     * **入参与返回都是成片路径**（不是 sidecar 路径）：真正的搬动交给 [ArcDropLog.renameFor]，
     * 它按成片路径自己拼扩展名——把带 `.drops.json` 的 sidecar 名喂进去会拼成
     * `….mp4.drops.drops.json`（真机 r20 首轮实测踩过）。
     *
     * 名字口径与成片改名同一套 [stripPendingJunk]：目标名取回查名剥净（回查名可能仍带临时段）；
     * 回查失败退回"当前名剥净"，与成片自己的回查失败兜底同规则，两边名字永不分叉。
     * 比较用**物理当前名**：文件真叫 `.pending-9-VID_1.mp4` 时必须搬，哪怕剥净后与目标同名。
     *
     * @param videoPath 成片当前路径（录制期 sidecar 落盘用的那条）
     * @param queriedName 回查到的成片 DISPLAY_NAME（null/空 = 回查失败）
     * @return 成片应处的路径；**null = 无需搬动**（物理名已一致或名字无法确定）
     */
    fun sidecarConvergeTarget(videoPath: String, queriedName: String?): String? {
        val cur = videoPath.substringAfterLast('/')
        if (cur.isEmpty()) return null
        val curClean = stripPendingJunk(cur)
        if (curClean.isEmpty()) return null
        val realName = queriedName?.let(::stripPendingJunk)?.takeIf { it.isNotEmpty() } ?: curClean
        if (realName == cur) return null
        val dir = videoPath.substringBeforeLast('/', "")
        return if (dir.isEmpty()) realName else "$dir/$realName"
    }
}

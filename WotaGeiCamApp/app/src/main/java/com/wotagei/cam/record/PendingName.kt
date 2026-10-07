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
}

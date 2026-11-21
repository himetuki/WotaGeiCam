package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4「导出链 pending 行救援」的数据红线守卫。
 *
 * 背景：commit 全败的成片行保留下来等救援（[StalePendingSweep.rescuePendingRows]）。救援的
 * 数据红线：**「文件在盘」的任何东西都不许删**——只有封口完整性通过才 update 清 IS_PENDING；
 * 唯一删行分支是 [RowFix.DELETE_ROW]（文件确认缺失 + 卷可见 + 按 URI 复核行身份一致）；
 * 不可观测只标记。年龄门槛 [sweepAgeOk] 与单轮上限 [StalePendingSweep.SWEEP_MAX_INDEX]
 * 必须真的在救援体内（否则在飞的导出会被误碰）。
 *
 * 2026-11-19（Y22）：旧实现在 `rescuePendingRows` 内用 `!f.exists()` 分流，现改为逐行
 * [StalePendingSweep.fixRow] + 处置出口 [StalePendingSweep.performFix]；守卫按
 * `RowFix.DELETE_ROW` / `RowFix.RESCUE_ROW` 两个分支切片，删行只许出现在前者、update 只许
 * 出现在后者。
 */
class PendingRowRescueGuardTest {

    private fun sweepBody(name: String): String = KotlinSourceScan.flatten(
        KotlinSourceScan.bodyOf(
            KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("record/StalePendingSweep.kt")),
            name
        )
    )

    /** 「删行只在确认缺失分支、救援只 update、门槛上限在体内」的判定本体 */
    private fun holds(rescue: String, perform: String): Boolean {
        if (!rescue.contains("SWEEP_MAX_INDEX") || !rescue.contains("fixRow(") || !rescue.contains("performFix(")) return false
        // 救援体内不得直接删/改行：所有动作都必须走统一处置出口
        if (rescue.contains("deleteRow(") || rescue.contains("unpendRow(")) return false
        val delete = perform.indexOf("RowFix.DELETE_ROW ->")
        val rescueBranch = perform.indexOf("RowFix.RESCUE_ROW ->")
        val mark = perform.indexOf("RowFix.MARK_UNOBSERVABLE ->")
        val keep = perform.indexOf("RowFix.KEEP ->")
        if (delete < 0 || rescueBranch <= delete || mark <= rescueBranch || keep <= mark) return false
        val deleteBranch = perform.substring(delete, rescueBranch)
        val rescueOnly = perform.substring(rescueBranch, mark)
        val markBranch = perform.substring(mark, keep)
        // 删行只许出现在 DELETE_ROW 分支
        if (!deleteBranch.contains("deleteRow(")) return false
        if (rescueOnly.contains("deleteRow(") || markBranch.contains("deleteRow(")) return false
        // 救援只许 update 清 pending（且只清具体卷条目 URI 那一条，见 SweepRowIdentityGuardTest）
        if (!rescueOnly.contains("unpendRow(app, row)")) return false
        if (deleteBranch.contains("unpendRow(")) return false
        // 不可观测必须留标记日志
        if (!markBranch.contains("Log.w(")) return false
        return true
    }

    @Test
    fun `救援只update不删_删行只在确认缺失分支`() {
        assertTrue(
            "rescuePendingRows 数据红线：文件在盘永不删（救援=unpendRow 的 update、不可观测=Log.w），" +
                "唯一删行分支在 DELETE_ROW，且年龄门槛/单轮上限必须真的在体内",
            holds(sweepBody("rescuePendingRows"), sweepBody("performFix"))
        )
    }

    @Test
    fun `突变_救援分支的update换成delete必红`() {
        val raw = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("record/StalePendingSweep.kt"))
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", holds(sweepBody("rescuePendingRows"), sweepBody("performFix")))
        // 突变：把救援动作（update 清 IS_PENDING）换成删行——MediaStore 删行连带删文件，
        // 等于把"救援"变成"销毁成片"（文本级突变不编译，只验尺子会红）
        val mutant = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(
                raw.replace(
                    Regex.fromLiteral("unpendRow(app, row)"),
                    "deleteRow(app, row)"
                ),
                "performFix"
            )
        )
        assertFalse(
            "救援分支把 update 换成 delete 后守卫必须报红（真偏离：删行连带删掉文件在盘的成片）",
            holds(sweepBody("rescuePendingRows"), mutant)
        )
    }

    @Test
    fun `源码钉扎_文件缺失判定必须过provider与目录双检`() {
        val observe = sweepBody("observeFile")
        assertTrue("stat 之外必须用 provider 打开复核（FUSE 路径不可见时 stat 会撒谎）", observe.contains("openAssetFileDescriptor("))
        assertTrue("必须区分 FileNotFoundException（可能缺失）与其他异常（不可观测）", observe.contains("FileNotFoundException"))
        assertTrue("缺失只能在父目录可读列举后确认", observe.contains(".list()"))
        assertTrue("路径未知/父目录不可读必须落到 UNOBSERVABLE", observe.contains("RowFileState.UNOBSERVABLE"))
    }

    @Test
    fun `源码钉扎_封口完整性必须先算box账目`() {
        val probe = sweepBody("extractable")
        assertTrue("探测必须先验容器尾段账目和 moov，再查询轨道", probe.indexOf("MoovSeal.complete(") in 0 until probe.indexOf("MediaExtractor()"))
        assertTrue("仍须有视频轨", probe.contains("startsWith(") && probe.contains("KEY_MIME"))
    }
}

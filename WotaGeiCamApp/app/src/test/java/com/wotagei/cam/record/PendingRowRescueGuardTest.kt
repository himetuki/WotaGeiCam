package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #4「导出链 pending 行救援」的数据红线守卫。
 *
 * 背景：commit 全败的成片行保留下来等救援（[StalePendingSweep.rescuePendingRows]）。救援的
 * 数据红线：**「文件在盘」的任何东西都不许删**——文件在盘且 MediaExtractor 打得开 → 只
 * update 清 IS_PENDING；打不开 → Log.w 留原状；唯一删行分支在「文件确认缺失」（MediaStore
 * 删行连带删文件，文件还在时删行 = 丢成片）。年龄门槛 SWEEP_STALE_MS 与单轮上限
 * SWEEP_MAX_INDEX 必须真的在救援体内（否则在飞的导出会被误碰）。
 *
 * 切片法：missing 锚点（`if (f == null || !f.exists())`）到 rescue 锚点（`} else if (extractable(f))`）
 * 之间是「文件缺失」分支，其后是救援/留原状两支——逐段判定 deleteRow 只许出现在前一支。
 */
class PendingRowRescueGuardTest {

    private fun rescueBody(raw: String): String = KotlinSourceScan.flatten(
        KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(raw), "rescuePendingRows")
    )

    /** 「删行只许在文件缺失分支、救援只许 update、门槛上限在体内」的判定本体 */
    private fun holds(body: String): Boolean {
        if (!body.contains("SWEEP_STALE_MS") || !body.contains("SWEEP_MAX_INDEX")) return false
        val missing = body.indexOf("if (f == null || !f.exists())")
        val rescue = body.indexOf("} else if (extractable(f))")
        if (missing < 0 || rescue <= missing) return false
        val missingBranch = body.substring(missing, rescue)
        val tail = body.substring(rescue)
        // 删行只许出现在「文件缺失」分支内（体内其余位置出现即红）
        val total = KotlinSourceScan.occurrences(body, "deleteRow").size
        val inMissing = KotlinSourceScan.occurrences(missingBranch, "deleteRow").size
        if (inMissing < 1 || inMissing != total) return false
        // 文件在盘的两支（救援/打不开）只许 update 与留原状，不许删
        if (!tail.contains("unpendRow(app, row.id)")) return false
        if (tail.contains("deleteRow")) return false
        return tail.contains("Log.w(")
    }

    @Test
    fun `救援只update不删_删行只在文件缺失分支`() {
        assertTrue(
            "rescuePendingRows 数据红线：文件在盘永不删（救援=unpendRow 的 update、打不开=Log.w 留原状），" +
                "唯一删行分支在文件确认缺失时，且年龄门槛/单轮上限必须真的在体内",
            holds(rescueBody(KotlinSourceScan.mainSourceText("record/StalePendingSweep.kt")))
        )
    }

    @Test
    fun `突变_救援分支的update换成delete必红`() {
        val raw = KotlinSourceScan.mainSourceText("record/StalePendingSweep.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义", holds(rescueBody(raw)))
        // 突变：把救援动作（update 清 IS_PENDING）换成删行——MediaStore 删行连带删文件，
        // 等于把"救援"变成"销毁成片"（文本级突变不编译，只验尺子会红）
        val mutant = rescueBody(
            raw.replace(
                Regex.fromLiteral("unpendRow(app, row.id)"),
                "deleteRow(app, row.id)"
            )
        )
        assertFalse(
            "救援分支把 update 换成 delete 后守卫必须报红（真偏离：删行连带删掉文件在盘的成片）",
            holds(mutant)
        )
    }
}

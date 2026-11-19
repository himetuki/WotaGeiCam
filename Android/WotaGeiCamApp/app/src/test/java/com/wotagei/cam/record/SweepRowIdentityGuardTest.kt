package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y22（2026-11-20 收口）：清扫/复核/打开/救援四路必须共用**同一枚"具体卷条目 URI"**行身份。
 *
 * 旧实现用 `ContentUris.withAppendedId(EXTERNAL_CONTENT_URI, id)` 做 provider 打开、按 URI 复核、
 * 删行、清 IS_PENDING——聚合集合上跨卷 `_ID` 可以重号，四个动作都可能落到另一卷的同号行上
 * （删行连带删文件 = 删别人的成片；清 pending = 改别人的行状态）。按名字查行（`DISPLAY_NAME`
 * + `RELATIVE_PATH`）同样不带卷，SD 卡同路径同名文件会被当成本卷的行改名。
 *
 * 这是一枚**接线尺**：[StalePendingSweep.rowItemUri] 的拼法是唯一出口，判据本体在
 * [concreteRowVolume] / [ownerAllowed] / [rowFixDecision]（真值表见 `SweepRowPolicyTest`）。
 * 每个判据都配一条**突变**：把好实现换成旧写法，尺子必须当场报红——否则"绿"只是没测到。
 */
class SweepRowIdentityGuardTest {

    private val sweep: String =
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("record/StalePendingSweep.kt"))

    /**
     * 取一个函数的**声明到下一个成员声明之间**的文本。表达式体（`= runCatching {`）没有花括号体，
     * `KotlinSourceScan.bodyOf` 结构化地拒绝这种输入，所以这里按成员边界切片（输入已遮蔽，
     * 注释里的括号/关键字不会带偏）。切不出段落就抛——守卫不能在没有输入时算通过。
     */
    private fun section(src: String, name: String): String {
        val marker = "private fun $name("
        val start = src.indexOf(marker)
        if (start < 0) throw AssertionError("源码里找不到 $marker——守卫失去输入，不许算通过")
        val rest = src.substring(start)
        val end = SECTION_END.map { rest.indexOf(it) }.filter { it > 0 }.minOrNull() ?: rest.length
        return KotlinSourceScan.flatten(rest.substring(0, end))
    }

    /**
     * 行身份尺（好实现必须全绿；任一突变必须让它红）：
     * 1. `rowItemUri` 只许 `具体卷集合 + withAppendedId`，不许出现聚合集合；
     * 2. 打开（provider）/ 按 URI 复核 / 删 / 救援四路都只经 `rowItemUri`；
     * 3. 行查询（库修补、pending 救援、改名有无行）只落在主卷集合；
     * 4. 判据装配把 owner 与具体卷**真的**喂进纯判据；
     * 5. owner 投影只在 API 30+ 拼（API 29 上加未知列会让整个查询抛掉）。
     */
    private fun holds(src: String): Boolean {
        val item = section(src, "rowItemUri")
        if (!item.contains("concreteRowVolume(")) return false
        if (!item.contains("getContentUri(") || !item.contains("withAppendedId(")) return false
        if (item.contains("EXTERNAL_CONTENT_URI")) return false
        // 四路动作只许拿 rowItemUri 拼出的 URI，且不许再出现聚合集合的点操作
        for (fn in listOf("observeFile", "queryRowByUri", "deleteRow", "unpendRow")) {
            val b = section(src, fn)
            if (!b.contains("rowItemUri(")) return false
            if (b.contains("EXTERNAL_CONTENT_URI")) return false
        }
        // 行查询 + 按名字查/改名只落在主卷集合
        for (fn in listOf("queryRows", "queryPendingRows", "rowExists", "renameRow")) {
            val b = section(src, fn)
            if (!b.contains("PRIMARY_VIDEO_URI")) return false
            if (b.contains("EXTERNAL_CONTENT_URI")) return false
        }
        // 判据装配：owner 闸门与具体卷都必须真的进纯判据
        val fix = section(src, "fixRow")
        if (!fix.contains("rowFixDecision(")) return false
        if (!fix.contains("ownerOurs = ownerAllowed(")) return false
        if (!fix.contains("volumeVisible(concreteRowVolume(")) return false
        // owner 投影：API 30+ 才加列，且按列名取（不靠固定下标）
        if (!src.contains("BASE_ROW_COLUMNS + COL_OWNER")) return false
        if (!src.contains("Build.VERSION_CODES.R")) return false
        if (!section(src, "readRow").contains("getColumnIndex(COL_OWNER)")) return false
        // 行快照必须带卷（具体行 URI 的唯一来源）
        if (!src.contains("MediaStore.MediaColumns.VOLUME_NAME")) return false
        return true
    }

    @Test
    fun `源码钉扎_四路动作共用具体卷条目URI_行查询只落主卷`() {
        assertTrue(
            "清扫/复核/打开/救援必须共用 rowItemUri（具体卷 + _ID），行查询只许主卷集合",
            holds(sweep)
        )
    }

    @Test
    fun `突变_四路动作退回聚合URI加id必红`() {
        assertTrue("良品先真的绿，突变的红才有意义", holds(sweep))
        // 突变：把具体卷条目 URI 换回"聚合集合 + _ID"——跨卷重号时打开/删/救援全打错行
        val mutant = sweep.replace(
            "rowItemUri(row)",
            "ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, row.id)"
        )
        assertFalse("聚合 URI + id 的旧写法必须让尺子报红", holds(mutant))
    }

    @Test
    fun `突变_行查询退回聚合集合同样必红`() {
        val mutant = sweep.replace("PRIMARY_VIDEO_URI", "MediaStore.Video.Media.EXTERNAL_CONTENT_URI")
        assertFalse(
            "按 RELATIVE_PATH/名字在聚合集合上查行 = 错卷风险（Y22 原病），必须报红",
            holds(mutant)
        )
    }

    @Test
    fun `突变_owner闸门喂成常量必红`() {
        val mutant = sweep.replaceFirst(
            "ownerOurs = ownerAllowed(row.owner, app.packageName)",
            "ownerOurs = true"
        )
        assertFalse("owner 闸门形同虚设（常量 true）必须报红", holds(mutant))
    }

    @Test
    fun `突变_卷判断退回裸卷名必红`() {
        val mutant = sweep.replaceFirst(
            "volumeVisible(concreteRowVolume(row.volume), volumes)",
            "volumeVisible(row.volume, volumes)"
        )
        assertFalse("合成卷 external 也当可用卷（拼回聚合集合）必须报红", holds(mutant))
    }

    @Test
    fun `突变_owner投影退到API29必红`() {
        val mutant = sweep.replaceFirst("Build.VERSION_CODES.R", "Build.VERSION_CODES.Q")
        assertFalse(
            "API 29 上查询 owner 未知列会整条查询抛掉（库修补退化成全目录重扫），必须报红",
            holds(mutant)
        )
    }

    private companion object {
        /** 成员边界（4 空格缩进的新声明 / 下一段 KDoc / object 收尾） */
        private val SECTION_END = listOf(
            "\n    private fun ",
            "\n    internal fun ",
            "\n    fun ",
            "\n    val ",
            "\n    var ",
            "\n    @",
            "\n    /**",
            "\n}"
        )
    }
}

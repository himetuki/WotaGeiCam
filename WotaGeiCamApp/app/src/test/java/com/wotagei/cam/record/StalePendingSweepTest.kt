package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启维护清扫的**判断表**与接线守卫（2026-10-08）。
 *
 * 真机遗留形态（清扫前的实际盘点）：①10 条名字带 `.pending-<id>-` 的**有效成片**（命名 race 时代产物，
 * `is_pending=0`、在相册里就带着烂名显示）；②5 条 `.pending-<id>-….drops.json` 无主档案（对应成片
 * 已按干净名提交）；③2 条档案与成片成对带烂名。清扫要做的是"成片去 pending 段、无主档案删掉"，
 * 而红线是**成片绝不删**。
 */
class StalePendingSweepTest {

    private val fresh = StalePendingSweep.SWEEP_STALE_MS - 1

    /** 明确"够老"（一天前）——门槛是 1 小时，用例必须真的越过它，否则测到的是年龄门槛而不是目标分支 */
    private val old = 24L * 60L * 60L * 1000L

    // ---- ① 纯函数表：sweepPlan ----

    @Test
    fun `非 pending 形态一律不动`() {
        val p = sweepPlan("VID_20261008_172951_262.mp4", ageMs = old, cleanTwinExists = false, clipName = null)
        assertEquals(SweepAction.LEAVE, p.action)
        assertEquals(null, p.to)
        // 名字里恰好含 "pending" 但不是临时名形态（正则只认 .pending-<纯数字>-）
        assertEquals(SweepAction.LEAVE, sweepPlan("my.pending-file.mp4", old, false, null).action)
        assertEquals(SweepAction.LEAVE, sweepPlan(".pending-abc-VID_1.mp4", old, false, null).action)
    }

    @Test
    fun `比门槛新的条目不动（可能正在录的那一段）`() {
        val p = sweepPlan(".pending-9-VID_1.mp4", ageMs = fresh, cleanTwinExists = false, clipName = null)
        assertEquals(SweepAction.LEAVE, p.action)
        // 门槛这一刻刚好放行（>= 门槛）
        val ok = sweepPlan(".pending-9-VID_1.mp4", StalePendingSweep.SWEEP_STALE_MS, false, null)
        assertEquals(SweepAction.RENAME_CLIP, ok.action)
    }

    @Test
    fun `成片去 pending 段`() {
        val p = sweepPlan(
            "30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4",
            ageMs = old, cleanTwinExists = false, clipName = null
        )
        assertEquals(SweepAction.RENAME_CLIP, p.action)
        assertEquals("30fto24f_VID_20261007_215822_240.mp4", p.to)
    }

    @Test
    fun `成片同内容双份时只记录不动手（成片永不删红线）`() {
        val p = sweepPlan(".pending-9-VID_1.mp4", ageMs = old, cleanTwinExists = true, clipName = null)
        assertEquals("剥段后同名成片已在 ⇒ 绝不删成片（宁可留一条难看名字）", SweepAction.LEAVE, p.action)
        assertEquals(null, p.to)
    }

    @Test
    fun `无主档案删掉`() {
        val p = sweepPlan(".pending-1792056591-VID_20261008_172951_262.drops.json", old, false, null)
        assertEquals(SweepAction.DELETE_ARCHIVE, p.action)
        assertEquals(null, p.to)
    }

    @Test
    fun `档案对应成片在时归位到成片真实名旁`() {
        // 真机第②类实际形态：成片已按干净名提交，档案还挂着 pending 名，而且**档案名里少了 `.mp4` 那一段**
        // （落盘时路径就少扩展名）⇒ 目标必须取"成片真实名 + 扩展名"，否则 App 永远读不到这份档案
        val p = sweepPlan(
            ".pending-1792056591-VID_20261008_172951_262.drops.json",
            ageMs = old, cleanTwinExists = false, clipName = "VID_20261008_172951_262.mp4"
        )
        assertEquals(SweepAction.RENAME_ARCHIVE, p.action)
        assertEquals("VID_20261008_172951_262.mp4.drops.json", p.to)
    }

    @Test
    fun `档案同名双份时删重复的那条（纯档案无数据损失）`() {
        val p = sweepPlan(
            ".pending-9-VID_1.mp4.drops.json", old, cleanTwinExists = true, clipName = "VID_1.mp4"
        )
        assertEquals(SweepAction.DELETE_ARCHIVE, p.action)
    }

    @Test
    fun `档案与成片成对带烂名时各自归位`() {
        // 真机第③类：成片与档案都带 pending 名。清扫是**两趟**（先成片后档案）——成片先改名落地，
        // 档案那一趟才找得到它，所以这里按"两趟之后"的输入断言。
        val clip = sweepPlan(
            "30fto24f_.pending-1791985960-VID_20261007_215240_909.mp4",
            old, cleanTwinExists = false, clipName = null
        )
        assertEquals(SweepAction.RENAME_CLIP, clip.action)
        assertEquals("30fto24f_VID_20261007_215240_909.mp4", clip.to)
        val archive = sweepPlan(
            "30fto24f_.pending-1791985960-VID_20261007_215240_909.drops.json",
            old, cleanTwinExists = false, clipName = "30fto24f_VID_20261007_215240_909.mp4"
        )
        assertEquals(SweepAction.RENAME_ARCHIVE, archive.action)
        assertEquals("30fto24f_VID_20261007_215240_909.mp4.drops.json", archive.to)
    }

    @Test
    fun `空名字与纯段名不放行`() {
        assertEquals(SweepAction.LEAVE, sweepPlan("", old, false, null).action)
        // 剥段后为空 ⇒ 没有合法目标名，不动手
        assertEquals(SweepAction.LEAVE, sweepPlan(".pending-9-", old, false, null).action)
    }

    // ---- ② 接线与安全边界守卫 ----

    @Test
    fun `清扫必须进程内只跑一次且只在冷启接线`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        assertTrue("必须用 CAS 做进程内一次性", sweep.contains("compareAndSet(false, true)"))
        assertTrue("必须有年龄门槛常量", sweep.contains("SWEEP_STALE_MS"))
        val activity = codeOnly(mainSourceText("ui/MainActivity.kt"))
        val create = bodyOf(activity, "onCreate")
        assertTrue("冷启必须接线清扫", create.contains("StalePendingSweep.runOnce("))
        assertTrue("只在真冷启跑（配置变更重建不再扫）", create.contains("savedInstanceState == null"))
        assertTrue("必须后台线程跑（会查/改 MediaStore 与文件系统）", create.contains("Thread("))
    }

    @Test
    fun `清扫不许删成片`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        // 唯一的 delete 调用点必须在 DELETE_ARCHIVE 分支里；成片分支（RENAME_CLIP）不许出现 delete
        val renameClip = sweep.substringAfter("SweepAction.RENAME_CLIP ->")
            .substringBefore("SweepAction.LEAVE")
        assertFalse("成片分支里不许有 delete（成片永不删红线）", renameClip.contains("delete("))
        val deleteBranch = sweep.substringAfter("SweepAction.DELETE_ARCHIVE ->")
        assertTrue("删除只允许出现在档案分支", deleteBranch.substringBefore("SweepAction.RENAME_ARCHIVE").contains("delete("))
    }

    @Test
    fun `目录内精确匹配必须叠 RELATIVE_PATH`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        assertTrue("改名必须走 provider（有行的文件自己 rename 会让行指空）", sweep.contains("MediaStore.Video.Media.EXTERNAL_CONTENT_URI"))
        assertTrue("DISPLAY_NAME 不唯一，必须叠 RELATIVE_PATH", sweep.contains("MediaStore.MediaColumns.RELATIVE_PATH"))
        assertTrue("顺带兜一次 IS_PENDING=0（历史 insert 在飞残留可能停在 pending 态）", sweep.contains("MediaStore.MediaColumns.IS_PENDING"))
        assertTrue("侧车随成片一起搬（复用成片改名的单一出口）", sweep.contains("ArcDropLog.renameFor("))
    }

    /**
     * **防坏行红线**（2026-10-08 真机踩过）：清扫第一版"provider 失败就回退 fs rename"，结果 10 条成片
     * 的文件被改名、行里的 `_data` 还指着旧名 ⇒ 相册里 10 条点开即坏的行。有行的文件**只能**让
     * provider 改名，改不动就不动。
     */
    @Test
    fun `有行的成片只许 provider 改名`() {
        // 注意：apply 是**表达式体**（`= runCatching {`），KotlinSourceScan.bodyOf 只认花括号体 ⇒
        // 这里按标记切片（切片到下一个 `private fun` 为止），不靠 bodyOf
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        val clip = sweep.substringAfter("private fun apply(").substringBefore("\n    private fun ").substringAfter("RENAME_CLIP ->")
        assertTrue("成片分支必须先判'有没有行'", clip.contains("rowExists("))
        assertTrue("有行时必须走 provider 改名", clip.contains("renameRow("))
        assertTrue("provider 改名失败必须就地放弃（不许回退 fs rename）", clip.contains("return@runCatching false"))
        assertTrue("判据次序：rowExists 必须早于 fs rename", clip.indexOf("rowExists(") < clip.indexOf("f.renameTo("))
    }

    /** 库一致性修补：删"行在文件不在"的坏行 + 给"有文件没行"的成片补一次系统扫描入库 */
    @Test
    fun `库一致性双向修补`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        val rec = bodyOf(sweep, "reconcileLibrary")
        assertTrue("必须删坏行", rec.contains("deleteRow("))
        assertTrue("必须补入库", rec.contains("indexFile("))
        assertTrue("补入库只认我们自己的成片名（别人的文件一个都不碰）", rec.contains("isOurClipName("))
        assertTrue("必须有单轮上限（收敛于多轮，别让冷启扫全库）", sweep.contains("SWEEP_MAX_INDEX"))
        assertTrue("补入库走系统扫描 API（文件已在磁盘、行还没有的正解）", sweep.contains("MediaScannerConnection.scanFile("))
    }
}

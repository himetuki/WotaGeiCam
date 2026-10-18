package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冷启维护清扫的**判断表**与接线守卫（2026-10-08；Q6 判据归一 2026-10-16）。
 *
 * 真机遗留形态（清扫前的实际盘点）：①10 条名字带 `.pending-<id>-` 的**有效成片**（命名 race 时代产物，
 * `is_pending=0`、在相册里就带着烂名显示）；②5 条 `.pending-<id>-….drops.json` 无主档案（对应成片
 * 已按干净名提交）；③2 条档案与成片成对带烂名。清扫要做的是"成片去 pending 段、无主档案删掉"，
 * 而红线是**成片绝不删**。
 *
 * Q6 判据归一：成片改名之前必须先探容器可播（复用导出链救援的 `extractable()` 同一把尺）——
 * 录制中被杀/断电留下的半截 MP4（无 moov）旧行为会被"洗白"成相册里点开即坏的条目。本文件用
 * 全表枚举钉住"**不可播/未探的成片在任何组合下都到不了 RENAME_CLIP**"，并配接线守卫钉住
 * 探测发生在改名之前、且只落在成片趟。
 */
class StalePendingSweepTest {

    private val fresh = StalePendingSweep.SWEEP_STALE_MS - 1

    /** 明确"够老"（一天前）——门槛是 1 小时，用例必须真的越过它，否则测到的是年龄门槛而不是目标分支 */
    private val old = 24L * 60L * 60L * 1000L

    // ---- ① 纯函数表：sweepPlan ----

    @Test
    fun `非 pending 形态一律不动`() {
        val p = sweepPlan("VID_20261008_172951_262.mp4", ageMs = old, cleanTwinExists = false, clipName = null, clipPlayable = true)
        assertEquals(SweepAction.LEAVE, p.action)
        assertEquals(null, p.to)
        // 名字里恰好含 "pending" 但不是临时名形态（正则只认 .pending-<纯数字>-）
        assertEquals(SweepAction.LEAVE, sweepPlan("my.pending-file.mp4", old, false, null, true).action)
        assertEquals(SweepAction.LEAVE, sweepPlan(".pending-abc-VID_1.mp4", old, false, null, true).action)
    }

    @Test
    fun `比门槛新的条目不动（可能正在录的那一段）`() {
        val p = sweepPlan(".pending-9-VID_1.mp4", ageMs = fresh, cleanTwinExists = false, clipName = null, clipPlayable = true)
        assertEquals(SweepAction.LEAVE, p.action)
        // 门槛这一刻刚好放行（>= 门槛）
        val ok = sweepPlan(".pending-9-VID_1.mp4", StalePendingSweep.SWEEP_STALE_MS, false, null, true)
        assertEquals(SweepAction.RENAME_CLIP, ok.action)
    }

    @Test
    fun `成片去 pending 段`() {
        val p = sweepPlan(
            "30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4",
            ageMs = old, cleanTwinExists = false, clipName = null, clipPlayable = true
        )
        assertEquals(SweepAction.RENAME_CLIP, p.action)
        assertEquals("30fto24f_VID_20261007_215822_240.mp4", p.to)
    }

    @Test
    fun `成片同内容双份时只记录不动手（成片永不删红线）`() {
        val p = sweepPlan(".pending-9-VID_1.mp4", ageMs = old, cleanTwinExists = true, clipName = null, clipPlayable = true)
        assertEquals("剥段后同名成片已在 ⇒ 绝不删成片（宁可留一条难看名字）", SweepAction.LEAVE, p.action)
        assertEquals(null, p.to)
    }

    @Test
    fun `无主档案删掉`() {
        val p = sweepPlan(".pending-1792056591-VID_20261008_172951_262.drops.json", old, false, null, null)
        assertEquals(SweepAction.DELETE_ARCHIVE, p.action)
        assertEquals(null, p.to)
    }

    @Test
    fun `档案对应成片在时归位到成片真实名旁`() {
        // 真机第②类实际形态：成片已按干净名提交，档案还挂着 pending 名，而且**档案名里少了 `.mp4` 那一段**
        // （落盘时路径就少扩展名）⇒ 目标必须取"成片真实名 + 扩展名"，否则 App 永远读不到这份档案
        val p = sweepPlan(
            ".pending-1792056591-VID_20261008_172951_262.drops.json",
            ageMs = old, cleanTwinExists = false, clipName = "VID_20261008_172951_262.mp4", clipPlayable = null
        )
        assertEquals(SweepAction.RENAME_ARCHIVE, p.action)
        assertEquals("VID_20261008_172951_262.mp4.drops.json", p.to)
    }

    @Test
    fun `档案同名双份时删重复的那条（纯档案无数据损失）`() {
        val p = sweepPlan(
            ".pending-9-VID_1.mp4.drops.json", old, cleanTwinExists = true, clipName = "VID_1.mp4", clipPlayable = null
        )
        assertEquals(SweepAction.DELETE_ARCHIVE, p.action)
    }

    @Test
    fun `档案与成片成对带烂名时各自归位`() {
        // 真机第③类：成片与档案都带 pending 名。清扫是**两趟**（先成片后档案）——成片先改名落地，
        // 档案那一趟才找得到它，所以这里按"两趟之后"的输入断言。
        val clip = sweepPlan(
            "30fto24f_.pending-1791985960-VID_20261007_215240_909.mp4",
            old, cleanTwinExists = false, clipName = null, clipPlayable = true
        )
        assertEquals(SweepAction.RENAME_CLIP, clip.action)
        assertEquals("30fto24f_VID_20261007_215240_909.mp4", clip.to)
        val archive = sweepPlan(
            "30fto24f_.pending-1791985960-VID_20261007_215240_909.drops.json",
            old, cleanTwinExists = false, clipName = "30fto24f_VID_20261007_215240_909.mp4", clipPlayable = null
        )
        assertEquals(SweepAction.RENAME_ARCHIVE, archive.action)
        assertEquals("30fto24f_VID_20261007_215240_909.mp4.drops.json", archive.to)
    }

    @Test
    fun `空名字与纯段名不放行`() {
        assertEquals(SweepAction.LEAVE, sweepPlan("", old, false, null, true).action)
        // 剥段后为空 ⇒ 没有合法目标名，不动手
        assertEquals(SweepAction.LEAVE, sweepPlan(".pending-9-", old, false, null, true).action)
    }

    // ---- Q6：成片趟的容器可播判据（判据归一，复用导出链救援的 extractable()） ----

    @Test
    fun `不可播的成片一动不动_连名字都不动`() {
        // 无同名 + 不可播：旧行为会在这格 RENAME_CLIP（把半截 MP4 洗白成相册可见坏条目）
        assertEquals(
            "不可播 × 无同名 ⇒ LEAVE（不 provider 改名、不 fs 改名、不碰 IS_PENDING）",
            SweepAction.LEAVE,
            sweepPlan(".pending-9-VID_1.mp4", old, cleanTwinExists = false, clipName = null, clipPlayable = false).action
        )
        // 有同名 + 不可播：红线依旧 ⇒ LEAVE（与旧行为一致，理由叠加）
        assertEquals(
            "不可播 × 有同名 ⇒ LEAVE（红线不变）",
            SweepAction.LEAVE,
            sweepPlan(".pending-9-VID_1.mp4", old, cleanTwinExists = true, clipName = null, clipPlayable = false).action
        )
        // 未探（null，如调用侧漏了探测）也绝不放行：RENAME_CLIP 只认"探过且可播"的正向确认
        assertEquals(
            "未探 ⇒ LEAVE（正向确认，漏探不许改名）",
            SweepAction.LEAVE,
            sweepPlan(".pending-9-VID_1.mp4", old, cleanTwinExists = false, clipName = null, clipPlayable = null).action
        )
        // 可播 × 无同名 ⇒ 一切照旧（对照组：防止"一刀切全 LEAVE"这种假绿）
        val ok = sweepPlan(".pending-9-VID_1.mp4", old, cleanTwinExists = false, clipName = null, clipPlayable = true)
        assertEquals("可播 × 无同名 ⇒ RENAME_CLIP（旧行为不变）", SweepAction.RENAME_CLIP, ok.action)
        assertEquals("该格改名目标照旧", "VID_1.mp4", ok.to)
    }

    @Test
    fun `不可播的成片在任何组合下都不可能到达 RENAME_CLIP`() {
        val names = listOf(
            ".pending-9-VID_1.mp4",
            "30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4",
            ".pending-1792056591-VID_20261008_172951_262.drops.json", // 档案形态（本就不产 RENAME_CLIP）
            "VID_20261008_172951_262.mp4", // 非 pending 形态
            ".pending-9-" // 剥段后为空
        )
        val ages = listOf(0L, fresh, StalePendingSweep.SWEEP_STALE_MS, old)
        val twins = listOf(false, true)
        val clipNames = listOf(null, "VID_1.mp4")
        val playables = listOf<Boolean?>(true, false, null)
        var clipped = 0
        for (n in names) for (a in ages) for (t in twins) for (c in clipNames) for (p in playables) {
            val plan = sweepPlan(n, a, t, c, p)
            if (p == true) {
                if (plan.action == SweepAction.RENAME_CLIP) clipped++
            } else {
                assertNotEquals(
                    "不可播/未探的条目到达 RENAME_CLIP：name=$n age=$a twin=$t clip=$c playable=$p",
                    SweepAction.RENAME_CLIP, plan.action
                )
            }
        }
        // 反向对照：全表里必须仍存在可到达 RENAME_CLIP 的格（否则"全 LEAVE"的突变体也能骗过上面）。
        // 计数 = 2 个成片形态 × 2 个放行年龄档（门槛本身与更老）× 1 个无同名 × 2 个 clipName 取值
        // （成片分支不读 clipName，两种取值都放行）= 8；成片形态的其余组合（新年龄/有同名/不可播/未探）恒 LEAVE
        assertEquals("可播格必须仍能改名（表不许退化成全 LEAVE）", 8, clipped)
    }

    @Test
    fun `档案趟不吃可播判据_三种探测值下处置一致`() {
        // 探测只属于成片趟：档案（JSON）不是媒体容器，关于它的处置不许随 clipPlayable 漂移
        for (p in listOf<Boolean?>(true, false, null)) {
            assertEquals(
                "档案 × 对应成片在 ⇒ RENAME_ARCHIVE（与 clipPlayable=$p 无关）",
                SweepAction.RENAME_ARCHIVE,
                sweepPlan(".pending-9-VID_1.mp4.drops.json", old, false, "VID_1.mp4", p).action
            )
            assertEquals(
                "档案 × 无主 ⇒ DELETE_ARCHIVE（与 clipPlayable=$p 无关）",
                SweepAction.DELETE_ARCHIVE,
                sweepPlan(".pending-9-VID_1.drops.json", old, false, null, p).action
            )
        }
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

    // ---- Q6 接线守卫与"残片不会被换个方式洗白"的复核 ----

    /**
     * Q6 接线守卫的判定本体（良品与突变体共用同一把尺）：成片趟必须探、探测在改名之前、探测结果
     * 喂进计划；档案趟一律不探。切片法：`for (f in clips)` 到 `for (f in archives)` 之间是成片趟，
     * 其后到 `reconcileLibrary` 调用之间是档案趟。
     */
    private fun probeWiringHolds(raw: String): Boolean {
        val sweep = codeOnly(raw)
        val run = bodyOf(sweep, "runOnce")
        val clipsLoop = run.substringAfter("for (f in clips)", "").substringBefore("for (f in archives)", "")
        val archivesLoop = run.substringAfter("for (f in archives)", "").substringBefore("reconcileLibrary", "")
        if (clipsLoop.isEmpty() || archivesLoop.isEmpty()) return false
        // ① 成片趟必须探容器可播（extractable() 单一判据），且发生在改名动作（apply）之前
        if (!clipsLoop.contains("extractable(")) return false
        if (clipsLoop.indexOf("extractable(") >= clipsLoop.indexOf("apply(")) return false
        // ② 探测结果必须喂进计划（漏喂 = 判据悬空，计划永远拿不到可播性）
        if (!clipsLoop.contains("clipPlayable = playable")) return false
        // ③ 探测只属于成片趟：档案趟不许出现 extractable，且显式声明未探
        if (archivesLoop.contains("extractable(")) return false
        if (!archivesLoop.contains("clipPlayable = null")) return false
        // ④ 不可播的残片必须留据（i 级日志，含条目名与大小）
        if (!clipsLoop.contains("Log.i(TAG,")) return false
        return clipsLoop.contains("f.length()")
    }

    @Test
    fun `成片趟先探可播再改名_档案趟不探`() {
        val raw = mainSourceText("record/StalePendingSweep.kt")
        assertTrue(
            "成片趟探测（改名之前、结果喂进计划）+ 档案趟不探 + 残片留据，缺一即红",
            probeWiringHolds(raw)
        )
        // 突变自证 1：探测被摘（计划拿不到可播性 ⇒ 半截 MP4 照旧被改名洗白）
        val m1 = raw.replace(
            "val playable = if (ageMs >= SWEEP_STALE_MS) extractable(f) else null",
            "val playable: Boolean? = true"
        )
        assertFalse("摘掉探测后尺子必须红", probeWiringHolds(m1))
        // 突变自证 2：探测挪到改名**之后**（现场已被动过才发现打不开）——只动成片趟那一处
        val m2 = raw.replace(
            "val playable = if (ageMs >= SWEEP_STALE_MS) extractable(f) else null",
            "val playable: Boolean? = true"
        )
            .replace(
                "if (apply(app, dir, f, plan)) acted++\n        }\n        for (f in archives) {",
                "if (apply(app, dir, f, plan)) acted++\n            extractable(f)\n        }\n        for (f in archives) {"
            )
        assertFalse("探测挪到改名之后尺子必须红", probeWiringHolds(m2))
        // 突变自证 3：档案趟也去探（判据漂到档案上）
        val m3 = raw.replace("clipPlayable = null", "clipPlayable = extractable(f)")
        assertFalse("档案趟引入探测后尺子必须红", probeWiringHolds(m3))
    }

    /**
     * Q6 的另一半（复核）：不可播的残片保持 marker 名 ⇒ 第三趟（补入库）的 `isOurClipName` 必须
     * 认不出它——否则改名虽然没做，登记照旧发生，等于换个方式把残片洗成可见条目。两种真机
     * marker 形态（裸前缀 / 带转换前缀）逐个复核对，并用干净名做反向对照（补入库通道本身
     * 不许被这轮改动掐掉）。
     */
    @Test
    fun `marker名残片不会被补入库趟扫到_isOurClipName复核`() {
        assertFalse(
            "裸前缀 marker 形态认不出（substringBefore(VID_) 带 .pending-<数字>- 段）",
            StalePendingSweep.isOurClipName(".pending-1791986302-VID_20261007_215822_240.mp4")
        )
        assertFalse(
            "带转换前缀的真机形态也认不出（真机第①③类的那 10 条就是它）",
            StalePendingSweep.isOurClipName("30fto24f_.pending-1791986302-VID_20261007_215822_240.mp4")
        )
        // 反向对照：干净名照旧认（这轮改动没掐掉补入库通道本身）
        assertTrue(StalePendingSweep.isOurClipName("VID_20261007_215822_240.mp4"))
        assertTrue(StalePendingSweep.isOurClipName("30fto24f_VID_20261007_215822_240.mp4"))
    }

    // ---- 档案归位的覆盖/误删红线（2026-10-18） ----

    /**
     * 档案"重复件"判据：必须对**真实归位目标**（`成片名 + .drops.json`）判，不能只看剥段后的
     * 档案名。真机幽灵档案 `…-VID_x.drops.json` 少了 `.mp4` 段，归位目标是
     * `VID_x.mp4.drops.json`；旧判据查的是 `VID_x.drops.json`，目标已存在时查不到 ⇒
     * `renameTo` 直接覆盖，有效档案被旧幽灵件顶掉。
     */
    @Test
    fun `档案重复判据必须看真实归位目标`() {
        assertTrue(
            "目标名已在目录里 ⇒ 重复（删待归位件，绝不覆盖目标）",
            archiveTwinExists(
                archiveName = ".pending-9-VID_1.drops.json",
                targetName = "VID_1.mp4.drops.json",
                dirNames = setOf("VID_1.mp4.drops.json", ".pending-9-VID_1.drops.json")
            )
        )
        assertTrue(
            "剥段后的同名条目已在 ⇒ 也是重复（旧判据覆盖的情形）",
            archiveTwinExists(
                archiveName = ".pending-9-VID_1.drops.json",
                targetName = null,
                dirNames = setOf("VID_1.drops.json", ".pending-9-VID_1.drops.json")
            )
        )
        assertFalse(
            "目录里既没有目标也没有剥段同名条目 ⇒ 不是重复，应该归位",
            archiveTwinExists(
                archiveName = ".pending-9-VID_1.drops.json",
                targetName = "VID_1.mp4.drops.json",
                dirNames = setOf(".pending-9-VID_1.drops.json", ".pending-9-VID_1.mp4")
            )
        )
        assertFalse(
            "只有自己这一条（目标名等于自己）⇒ 不算重复",
            archiveTwinExists(
                archiveName = "VID_1.mp4.drops.json",
                targetName = "VID_1.mp4.drops.json",
                dirNames = setOf("VID_1.mp4.drops.json")
            )
        )
    }

    /**
     * 成片趟 provider 改名失败时成片仍带 pending 名：档案趟必须还能找到它，否则会把仍有效的
     * sidecar 当无主档案删掉（旧 `clipFor` 只找干净名与 `名.mp4`）。
     */
    @Test
    fun `成片仍带pending名时档案仍能找到它`() {
        val dir = java.nio.file.Files.createTempDirectory("wota-sweep").toFile()
        try {
            java.io.File(dir, ".pending-9-VID_1.mp4").writeText("x")
            assertEquals(
                "档案必须挂回 pending 名成片旁（`.pending-9-VID_1.mp4.drops.json`）",
                ".pending-9-VID_1.mp4",
                StalePendingSweep.clipFor(".pending-9-VID_1.drops.json", dir)
            )
            // 反向对照：没有成片时仍是 null（无主档案照旧可删）
            assertEquals(null, StalePendingSweep.clipFor(".pending-9-VID_2.drops.json", dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `档案归位目标已存在时不得覆盖`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        val archive = sweep.substringAfter("SweepAction.RENAME_ARCHIVE ->").substringBefore("SweepAction.RENAME_CLIP ->")
        assertTrue("归位前必须判目标是否已存在", archive.contains("if (to.exists())"))
        assertTrue(
            "目标已存在必须原地放弃（不许 renameTo 覆盖）",
            archive.indexOf("if (to.exists())") < archive.indexOf("f.renameTo(to)")
        )
        // 接线：档案趟必须把真实目标名喂进重复判据（喂剥段名 = 回到旧缺陷）
        val runOnce = flatten(bodyOf(sweep, "runOnce"))
        assertTrue(
            "档案趟重复判据必须吃 targetName（成片名 + .drops.json）",
            runOnce.contains("archiveTwinExists(f.name, clipName?.let { it + ArcDropLog.EXTENSION }")
        )
    }
}

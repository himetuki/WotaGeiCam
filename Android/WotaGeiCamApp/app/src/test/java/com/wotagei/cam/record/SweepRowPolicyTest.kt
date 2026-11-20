package com.wotagei.cam.record

import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y22 冷启清扫判据的**桥测**：三值文件状态、删行前的卷/URI 复核、缺尾段容器不晋升、
 * owner 闸门与"具体卷"门。
 *
 * 旧实现把 `!File(path).exists()` 当"文件缺失"并删行：FUSE 路径不可见、卷离线、过期 DATA
 * 都会让 stat 回 false，删行 = MediaStore 连带删掉还在盘上的成片。现在纯函数
 * [rowFixDecision] 给出真值表，[mp4TopLevelComplete] 用合成 box 流证明"缺尾段"可判，
 * 接线守卫钉住 Android 侧只喂事实、不做第二套判断。
 *
 * 2026-11-20 增量：行身份必须钉在**具体卷 + _ID** 上（聚合集合跨卷重号），owner 明确是
 * 别的包的成片行一律不碰。这两条在 [rowFixDecision] / [concreteRowVolume] /
 * [ownerAllowed] 上全表，接线钉扎见 `SweepRowIdentityGuardTest`。
 */
class SweepRowPolicyTest {

    private val old = StalePendingSweep.SWEEP_STALE_MS + 1
    private val us = "com.wotagei.cam"

    // ---- 文件状态三值 ----

    @Test
    fun `路径不可见是独立状态_只标记不删行`() {
        // FUSE 不可见 / 卷离线 / provider 异常都归 UNOBSERVABLE：即使卷与行复核都"看起来可以"，也不许删
        assertEquals(
            RowFix.MARK_UNOBSERVABLE,
            rowFixDecision(
                pending = false, state = RowFileState.UNOBSERVABLE, ageOk = true,
                volumeMounted = true, rowMatches = true, playable = true, ownerOurs = true
            )
        )
        assertEquals(
            RowFix.MARK_UNOBSERVABLE,
            rowFixDecision(
                pending = true, state = RowFileState.UNOBSERVABLE, ageOk = true,
                volumeMounted = true, rowMatches = true, playable = true, ownerOurs = true
            )
        )
    }

    @Test
    fun `确认缺失也要卷可见且行身份复核一致才删`() {
        assertEquals(
            "确认缺失 + 卷可见 + 行仍是同一行：坏行可删",
            RowFix.DELETE_ROW,
            rowFixDecision(false, RowFileState.ABSENT, true, true, true, playable = false, ownerOurs = true)
        )
        assertEquals(
            "卷查询不到 ⇒ 不可观测（卷可能只是暂时离线）",
            RowFix.MARK_UNOBSERVABLE,
            rowFixDecision(false, RowFileState.ABSENT, true, false, true, playable = false, ownerOurs = true)
        )
        assertEquals(
            "按 URI 复核不上（行被替换/路径漂移）⇒ 不可观测",
            RowFix.MARK_UNOBSERVABLE,
            rowFixDecision(false, RowFileState.ABSENT, true, true, false, playable = false, ownerOurs = true)
        )
    }

    @Test
    fun `文件在盘永不删_pending完整才晋升`() {
        assertEquals(
            "完整 + 有视频轨：pending 行只清 IS_PENDING",
            RowFix.RESCUE_ROW,
            rowFixDecision(true, RowFileState.PRESENT, true, true, true, playable = true, ownerOurs = true)
        )
        assertEquals(
            "即使文件完整，复核发现行已变更也不许晋升",
            RowFix.MARK_UNOBSERVABLE,
            rowFixDecision(true, RowFileState.PRESENT, true, true, false, playable = true, ownerOurs = true)
        )
        assertEquals(
            "缺尾段/无视频轨：不许晋升（相册里点开即坏的条目）",
            RowFix.KEEP,
            rowFixDecision(true, RowFileState.PRESENT, true, true, true, playable = false, ownerOurs = true)
        )
        assertEquals(
            "已发布的行不需要处置",
            RowFix.KEEP,
            rowFixDecision(false, RowFileState.PRESENT, true, true, true, playable = true, ownerOurs = true)
        )
        assertEquals(
            "卷不可见时不碰",
            RowFix.KEEP,
            rowFixDecision(true, RowFileState.PRESENT, true, false, true, playable = true, ownerOurs = true)
        )
    }

    @Test
    fun `他人的行一律不碰_删与救援都不许`() {
        // owner 明确是别的包：确认缺失也不许删（删行连带删文件 = 删别人的成片）
        assertEquals(
            "他人行 + 文件确认缺失 + 其它条件全绿 ⇒ 仍不删",
            RowFix.KEEP,
            rowFixDecision(false, RowFileState.ABSENT, true, true, true, playable = false, ownerOurs = false)
        )
        // 他人行 + pending + 完整：也不许替它清 IS_PENDING（改别人的行状态同样是动别人的数据）
        assertEquals(
            "他人行 + pending 完整 ⇒ 不救援",
            RowFix.KEEP,
            rowFixDecision(true, RowFileState.PRESENT, true, true, true, playable = true, ownerOurs = false)
        )
        assertEquals(
            "他人行不可观测 ⇒ 连标记都省（不是我们的行，不记录我们在管它）",
            RowFix.KEEP,
            rowFixDecision(true, RowFileState.UNOBSERVABLE, true, true, true, playable = true, ownerOurs = false)
        )
    }

    @Test
    fun `年龄门槛_封口标记只缩短等待不跳过完整性`() {
        assertEquals("普通遗留一分钟不碰", RowFix.KEEP, rowFixDecision(true, RowFileState.PRESENT, false, true, true, playable = true, ownerOurs = true))
        val tenMin = 10L * 60L * 1000L
        assertFalse("无标记的 pending 行 10 分钟仍不够（在飞导出是分钟级）", sweepAgeOk(tenMin, sealed = false))
        assertTrue("带封口标记 5 分钟即可进救援", sweepAgeOk(tenMin, sealed = true))
        assertFalse("封口标记也不能在 5 分钟内动手", sweepAgeOk(4L * 60L * 1000L, sealed = true))
        assertTrue("普通遗留满 1 小时", sweepAgeOk(StalePendingSweep.SWEEP_STALE_MS, sealed = false))
    }

    @Test
    fun `卷查询失败一律按不可见`() {
        assertFalse("卷名未知", volumeVisible(null, setOf("external_primary")))
        assertFalse("卷枚举查询失败", volumeVisible("external_primary", null))
        assertFalse("卷不在可用集合", volumeVisible("1234-5678", setOf("external_primary")))
        assertTrue(volumeVisible("external_primary", setOf("external_primary")))
    }

    @Test
    fun `具体卷名_合成卷与读不到都不许钉行URI`() {
        // 合成卷 external = 跨所有卷的聚合集合：拿它 + _ID 拼条目就是"聚合 URI + id"（Y22 原病）
        assertNull("合成卷 external 不是具体卷", concreteRowVolume("external"))
        assertNull("读不到卷名", concreteRowVolume(null))
        assertNull("空卷名", concreteRowVolume(""))
        assertNull("空白卷名", concreteRowVolume("   "))
        assertEquals("主卷是真卷名（不是合成卷）", "external_primary", concreteRowVolume("external_primary"))
        assertEquals("可移动卷按 UUID 认", "1234-5678", concreteRowVolume("1234-5678"))
        assertEquals("首尾空白不算身份差异", "1234-5678", concreteRowVolume(" 1234-5678 "))
    }

    @Test
    fun `owner闸门_读不到不拒_别的包拒绝`() {
        assertTrue("本系统读不到 owner 列（API 29）⇒ 不据此拒绝", ownerAllowed(null, us))
        assertTrue("空串按读不到处理", ownerAllowed("", us))
        assertTrue("我们自己的行", ownerAllowed(us, us))
        assertFalse("别的包的行", ownerAllowed("com.example.other", us))
    }

    @Test
    fun `新旧Provider条件同时核对路径日期尺寸与pending`() {
        val name = "artist's VID_1.mp4"
        val path = "/storage/emulated/0/Movies/Wota/$name"
        val rel = "Movies/Wota/"
        val common = "_display_name = ? AND _data = ? AND relative_path = ? AND date_added = ? AND " +
            "_size = ? AND date_modified = ? AND is_pending = ?"
        val args = listOf(name, path, rel, "10", "250", "20", "1")
        val q = sweepWriteSelection(name, path, rel, 10, 250, 20, true, null, null, hasGeneration = false)
        assertEquals(SweepWriteSelection(common, args), q)
        val r = sweepWriteSelection(name, path, rel, 10, 250, 20, true, us, 30, hasGeneration = true)
        assertEquals(
            SweepWriteSelection(common + " AND owner_package_name = ? AND generation_modified = ? AND is_trashed = ?",
                args + listOf(us, "30", "0")),
            r
        )
    }

    @Test
    fun `可空元数据须核对null_关键身份缺失拒写`() {
        val match = sweepWriteSelection("VID_1.mp4", "/Movies/VID_1.mp4", "Movies/",
            10, null, null, false, null, 30, hasGeneration = true)
        assertEquals(
            SweepWriteSelection(
                "_display_name = ? AND _data = ? AND relative_path = ? AND date_added = ? AND " +
                    "_size IS NULL AND date_modified IS NULL AND is_pending = ? AND " +
                    "owner_package_name IS NULL AND generation_modified = ? AND is_trashed = ?",
                listOf("VID_1.mp4", "/Movies/VID_1.mp4", "Movies/", "10", "0", "30", "0")
            ),
            match
        )
        assertNull(sweepWriteSelection(null, "/Movies/VID_1.mp4", "Movies/", 10, 25, 20, true, us, 30, true))
        assertNull(sweepWriteSelection("VID_1.mp4", null, "Movies/", 10, 25, 20, true, us, 30, true))
        assertNull(sweepWriteSelection("VID_1.mp4", "/Movies/VID_1.mp4", "", 10, 25, 20, true, us, 30, true))
        assertNull(sweepWriteSelection("VID_1.mp4", "/Movies/VID_1.mp4", "Movies/", 0, 25, 20, true, us, 30, true))
        assertNull(sweepWriteSelection("VID_1.mp4", "/Movies/VID_1.mp4", "Movies/", 10, 25, 20, true, us, null, true))
    }

    // ---- 缺尾段容器 ----

    @Test
    fun `声明长度越过EOF的MP4判缺失尾段`() {
        val ftyp = box("ftyp", 16)
        val mdatFull = box("mdat", 32)
        val complete = ftyp + mdatFull
        assertTrue("完整 box 账目", mp4TopLevelComplete(complete.size.toLong(), reader(complete)))
        // 截掉 mdat 尾部：声明长度仍在头里，但文件不够长
        val truncated = complete.copyOf(complete.size - 8)
        assertFalse("缺尾段必须判不完整", mp4TopLevelComplete(truncated.size.toLong(), reader(truncated)))
        // 头本身被截断
        val cutHeader = ftyp.copyOf(ftyp.size - 3)
        assertFalse("box 头截断必须判不完整", mp4TopLevelComplete(cutHeader.size.toLong(), reader(cutHeader)))
    }

    @Test
    fun `box为零长与文件尾零填充都算完整`() {
        val ftyp = box("ftyp", 8)
        val openEnded = byteArrayOf(0, 0, 0, 0) + "mdat".encodeToByteArray() + ByteArray(10)
        val withOpenEnded = ftyp + openEnded
        assertTrue("size=0 = 延伸到文件尾，本身合法", mp4TopLevelComplete(withOpenEnded.size.toLong(), reader(withOpenEnded)))
        val padded = ftyp + ByteArray(3) // 1..7 字节零填充合法
        assertTrue("零填充不算缺尾段", mp4TopLevelComplete(padded.size.toLong(), reader(padded)))
        val garbageTail = ftyp + byteArrayOf(1, 2, 3)
        assertFalse("非零残留 = 头被截断", mp4TopLevelComplete(garbageTail.size.toLong(), reader(garbageTail)))
    }

    @Test
    fun `64位box长度按largesize核对`() {
        val large = ByteArray(24)
        writeU32(large, 0, 1L)
        "mdat".encodeToByteArray().copyInto(large, 4)
        writeU64(large, 8, 24L)
        assertTrue("largesize 与实际长度一致", mp4TopLevelComplete(large.size.toLong(), reader(large)))
        val short = large.copyOf(20)
        assertFalse("largesize 越过 EOF 必须判不完整", mp4TopLevelComplete(short.size.toLong(), reader(short)))
        val beyond = large.copyOf()
        writeU64(beyond, 8, 100L)
        assertFalse("largesize 越过 EOF 必须判不完整", mp4TopLevelComplete(beyond.size.toLong(), reader(beyond)))
        val liar = large.copyOf()
        writeU64(liar, 8, 12L)
        assertFalse("largesize 小于头长非法", mp4TopLevelComplete(liar.size.toLong(), reader(liar)))
    }

    // ---- 接线守卫 ----

    @Test
    fun `源码钉扎_库修补与救援共用同一处置出口`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        val rec = bodyOf(sweep, "reconcileLibrary")
        val rescue = bodyOf(sweep, "rescuePendingRows")
        val fix = bodyOf(sweep, "fixRow")
        assertTrue("库修补逐行走 fixRow/performFix", rec.contains("fixRow(") && rec.contains("performFix("))
        assertTrue("救援逐行走 fixRow/performFix", rescue.contains("fixRow(") && rescue.contains("performFix("))
        assertTrue("单行判据必须调用纯函数", fix.contains("rowFixDecision("))
        assertTrue("删行前必须按 URI 复核", fix.contains("queryRowByUri("))
        assertTrue("卷可用性必须来自真实卷集合", fix.contains("volumeVisible("))
        assertTrue("救援完整性必须走统一探测", fix.contains("extractable("))
    }

    @Test
    fun `源码钉扎_删除趟有上限且三值状态来自单口探测`() {
        val sweep = codeOnly(mainSourceText("record/StalePendingSweep.kt"))
        val rec = bodyOf(sweep, "reconcileLibrary")
        val deleteLoop = rec.substringAfter("for (row in rows)").substringBefore("val byName")
        assertTrue("删行趟必须先查预算再生效", deleteLoop.contains("fixed >= SWEEP_MAX_INDEX"))
        assertFalse("删行趟不得绕过处置出口直接 deleteRow", deleteLoop.contains("deleteRow("))
        val observe = bodyOf(sweep, "observeFile")
        assertTrue("provider 打开作为 stat 的独立复核", observe.contains("openAssetFileDescriptor("))
        assertTrue("目录列举确认缺失", observe.contains("parent.list()"))
        assertTrue("其余异常一律不可观测", observe.contains("RowFileState.UNOBSERVABLE"))
        val probe = bodyOf(sweep, "extractable")
        assertTrue("探测先验完整封口（缺尾段不晋升）", probe.contains("MoovSeal.complete("))
    }

    // ---- 合成 MP4 ----

    private fun box(type: String, size: Int): ByteArray {
        val b = ByteArray(size)
        writeU32(b, 0, size.toLong())
        type.encodeToByteArray().copyInto(b, 4)
        return b
    }

    private fun writeU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun writeU64(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) b[at + i] = (v ushr (56 - 8 * i)).toByte()
    }

    private fun reader(bytes: ByteArray): (Long, Int) -> ByteArray? = { offset, count ->
        if (offset < 0L || count <= 0 || offset + count > bytes.size) {
            null
        } else {
            bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
    }
}

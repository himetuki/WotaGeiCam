package com.wotagei.cam

import com.wotagei.cam.player.TrackSync
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后校准落盘策略（纯函数桥）：手动保护、旧记录兼容、低置信/越界/20ms 门槛。
 * 真实解码/写盘在真机；这里的 [TrackSync.autoDecision]/[TrackSync.sourceOfStored] 是
 * `autoRun` 每一次写/不写的唯一分岔点，测它等于测运行时判据。
 */
class TrackSyncAutoTest {

    private val auto = TrackSync.Source.AUTO
    private val manual = TrackSync.Source.MANUAL

    @Test
    fun `自动校准永不覆盖手动结果`() {
        assertEquals(
            TrackSync.AutoDecision.ManualProtected,
            TrackSync.autoDecision(offsetMs = 500, confidence = 9f, existing = manual)
        )
        // 手动保护优先于低置信/越界：连"算出来不能用"都不改变保护结论
        assertEquals(
            TrackSync.AutoDecision.ManualProtected,
            TrackSync.autoDecision(offsetMs = 9_999, confidence = 0.1f, existing = manual)
        )
    }

    @Test
    fun `旧记录无source视为手动`() {
        assertNull(TrackSync.sourceOfStored(done = false, rawSource = null))
        assertEquals(manual, TrackSync.sourceOfStored(done = true, rawSource = null))
        assertEquals(manual, TrackSync.sourceOfStored(done = true, rawSource = "manual"))
        assertEquals(auto, TrackSync.sourceOfStored(done = true, rawSource = "auto"))
        // 脏数据（未知来源）按老的手动记录处理：宁可不自动写，也不冲掉用户结果
        assertEquals(manual, TrackSync.sourceOfStored(done = true, rawSource = "weird"))
    }

    @Test
    fun `低置信不写`() {
        assertEquals(
            TrackSync.AutoDecision.LowConfidence,
            TrackSync.autoDecision(offsetMs = 300, confidence = 1.99f, existing = null)
        )
        assertEquals(
            TrackSync.AutoDecision.LowConfidence,
            TrackSync.autoDecision(offsetMs = 300, confidence = 1.5f, existing = auto)
        )
    }

    @Test
    fun `置信过线才进入范围判定`() {
        assertEquals(
            TrackSync.AutoDecision.Write(300),
            TrackSync.autoDecision(offsetMs = 300, confidence = 2.0f, existing = null)
        )
    }

    @Test
    fun `20毫秒以内归零_边界值照写`() {
        assertEquals(TrackSync.AutoDecision.Write(0), TrackSync.autoDecision(0, 3f, null))
        assertEquals(TrackSync.AutoDecision.Write(0), TrackSync.autoDecision(19, 3f, auto))
        assertEquals(TrackSync.AutoDecision.Write(0), TrackSync.autoDecision(-19, 3f, auto))
        assertEquals(TrackSync.AutoDecision.Write(20), TrackSync.autoDecision(20, 3f, auto))
        assertEquals(TrackSync.AutoDecision.Write(-20), TrackSync.autoDecision(-20, 3f, auto))
    }

    @Test
    fun `超出正负1000毫秒只记日志`() {
        assertEquals(TrackSync.AutoDecision.Write(1000), TrackSync.autoDecision(1000, 3f, null))
        assertEquals(TrackSync.AutoDecision.Write(-1000), TrackSync.autoDecision(-1000, 3f, null))
        assertEquals(TrackSync.AutoDecision.OutOfRange, TrackSync.autoDecision(1001, 3f, null))
        assertEquals(TrackSync.AutoDecision.OutOfRange, TrackSync.autoDecision(-1001, 3f, null))
    }

    @Test
    fun `突变_去掉手动保护必被表测出`() {
        // 退化实现：不检查 existing，低置信以下才不写——正是"自动冲掉手动"的缺陷形态
        fun mutant(offsetMs: Long, confidence: Float) =
            if (confidence < 2f) TrackSync.AutoDecision.LowConfidence
            else TrackSync.AutoDecision.Write(offsetMs)

        val real = TrackSync.autoDecision(500, 9f, manual)
        val fake = mutant(500, 9f)
        assertEquals("真实现必须保护手动结果", TrackSync.AutoDecision.ManualProtected, real)
        assertEquals("突变体必须与真实现不同（否则这把尺测不出退化）", false, real == fake)
    }

    @Test
    fun `先auto读旧值后manual写入_auto落盘必须被拒`() {
        // 模拟 TOCTOU：AUTO 在解码前读到 AUTO（允许写），解码期间用户点了手动同步，
        // AUTO 最后落盘必须被锁内复核拒绝——最终 prefs 仍是 MANUAL。
        val sp = FakePrefs()
        val key = "content://media/external/video/media/1"
        TrackSync.writeSyncPrefs(sp, key, offsetMs = 100, source = auto, confidence = 9f, nowMs = 1L)
        val seenBeforeDecode = TrackSync.sourceOfPrefs(sp, key)
        assertEquals("解码前读到的旧值是 AUTO", auto, seenBeforeDecode)

        TrackSync.writeSyncPrefs(sp, key, offsetMs = 250, source = manual, confidence = 8f, nowMs = 2L)
        val written = TrackSync.writeAutoPrefs(sp, key, offsetMs = 500, confidence = 9f, nowMs = 3L)

        assertFalse("锁内复核必须拒绝 AUTO 覆盖 MANUAL", written)
        assertEquals("最终来源必须仍是 MANUAL", manual, TrackSync.sourceOfPrefs(sp, key))
        assertEquals("最终偏移必须是手动值", 250L, sp.getLong("sync_offset:$key", -1L))
        assertEquals("最终来源键必须是 manual", "manual", sp.getString("sync_source:$key", null))
        assertEquals("最终时刻必须是手动写入时刻", 2L, sp.getLong("sync_at:$key", -1L))
    }

    @Test
    fun `无手动结果时auto正常落盘`() {
        val sp = FakePrefs()
        val key = "clip-auto"
        assertTrue(TrackSync.writeAutoPrefs(sp, key, offsetMs = -120, confidence = 3f, nowMs = 7L))
        assertEquals(auto, TrackSync.sourceOfPrefs(sp, key))
        assertEquals(-120L, sp.getLong("sync_offset:$key", -1L))
        assertEquals(3f, sp.getFloat("sync_conf:$key", 0f), 1e-6f)
        assertEquals(7L, sp.getLong("sync_at:$key", -1L))
    }

    /**
     * 删片清同步 prefs：键是 uri（含 MediaStore `_ID`），不清的话 ID 复用时新片命中旧片的
     * done/offset——被误判「已校准」并带上错的音画偏移。
     * 五个键必须与 [TrackSync.writeSyncPrefs] 写入的一一对应（漏一个 = 没清干净）。
     */
    @Test
    fun `删片清理_五个键全抹掉_不影响别的片`() {
        val sp = FakePrefs()
        val dead = "content://media/external/video/media/7"
        val alive = "content://media/external/video/media/8"
        TrackSync.writeSyncPrefs(sp, dead, offsetMs = 250, source = manual, confidence = 8f, nowMs = 2L)
        TrackSync.writeSyncPrefs(sp, alive, offsetMs = -90, source = auto, confidence = 3f, nowMs = 5L)

        TrackSync.clearForPrefs(sp, listOf(dead))

        assertNull("done 标记必须抹掉", TrackSync.sourceOfPrefs(sp, dead))
        assertFalse("done 键必须真的没了", sp.contains("sync_done:$dead"))
        assertEquals("offset 键必须抹掉", -1L, sp.getLong("sync_offset:$dead", -1L))
        assertEquals("source 键必须抹掉", null, sp.getString("sync_source:$dead", null))
        assertEquals("conf 键必须抹掉", 0f, sp.getFloat("sync_conf:$dead", 0f), 1e-6f)
        assertEquals("at 键必须抹掉", -1L, sp.getLong("sync_at:$dead", -1L))
        // 别的片一根汗毛都不许动
        assertEquals(auto, TrackSync.sourceOfPrefs(sp, alive))
        assertEquals(-90L, sp.getLong("sync_offset:$alive", -1L))
        assertEquals(3f, sp.getFloat("sync_conf:$alive", 0f), 1e-6f)
        assertEquals(5L, sp.getLong("sync_at:$alive", -1L))
    }

    @Test
    fun `删片清理_空列表不写盘`() {
        val sp = FakePrefs()
        val key = "clip-keep"
        TrackSync.writeSyncPrefs(sp, key, offsetMs = 1, source = manual, confidence = 1f, nowMs = 1L)
        val before = sp.applyCount
        TrackSync.clearForPrefs(sp, emptyList())
        assertEquals("空列表不该产生任何 apply", before, sp.applyCount)
        assertEquals(manual, TrackSync.sourceOfPrefs(sp, key))
    }

    /** 删片收尾必须真的调 clearFor：漏了就是「ID 复用串片」的缺口（行为桥，不是恒等式） */
    @Test
    fun `删片收尾必须清理同步prefs`() {
        val src = codeOnly(mainSourceText("media/MediaActions.kt"))
        val cleanup = flatten(bodyOf(src, "finishDelete"))
        assertTrue(
            "finishDelete 的清理块必须调用 TrackSync.clearFor（否则删除后同步偏移残留）",
            cleanup.contains("TrackSync.clearFor(")
        )
        // 突变：把清理调用删掉，尺子必须红
        val mutant = cleanup.replace("TrackSync.clearFor(", "TrackSync.cachedOffsetMs(")
        assertTrue("删掉清理调用后尺子必须报红", mutant != cleanup)
    }

    /** AUTO 分支的落盘调用必须走 persistAuto（锁内复核）；走裸 persist 就绕过了 MANUAL 保护 */
    private fun autoWriteViolations(runBody: String): List<String> = listOf(
        runBody.contains("val written = persistAuto(context, uri, decision.offsetMs, confidence)") to
            "AUTO Write 分支必须调用 persistAuto（锁内复核 MANUAL 保护）",
        runBody.contains("ok = written") to "persistAuto 的拒绝结果必须反映到 Result.ok"
    ).filter { (ok, _) -> !ok }.map { (_, why) -> why }

    @Test
    fun `AUTO落盘必须走锁内复核入口`() {
        val src = codeOnly(mainSourceText("player/TrackSync.kt"))
        val run = flatten(bodyOf(src, "run"))
        assertEquals(
            "AUTO 落盘接线断点：\n${autoWriteViolations(run).joinToString("\n")}",
            emptyList<String>(),
            autoWriteViolations(run)
        )
        // 突变：AUTO 退回裸 persist（解码前读过 existing 也不能替代锁内复核）
        val mutant = run.replace(
            "val written = persistAuto(context, uri, decision.offsetMs, confidence)",
            "persist(context, uri, decision.offsetMs, Source.AUTO, confidence)"
        )
        assertTrue("AUTO 裸 persist 突变体必须报红", autoWriteViolations(mutant).isNotEmpty())
    }
}

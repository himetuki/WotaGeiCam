package com.wotagei.cam.media

import com.wotopei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotopei.cam.source.KotlinSourceScan.codeOnly
import com.wotopei.cam.source.KotlinSourceScan.flatten
import com.wotopei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制收尾落库的**接线与红线守卫**（#5 Step 4b）。
 *
 * [SeriesStoreTest] 钉纯逻辑真值表；这里钉的是「纯逻辑之外、只有源码能看见」的四件事：
 * 1. **接线**：`CameraScreen` 的 `result.collect` 里必须调 `SeriesStore.record(...)`，
 *    且必须传 `result.parts` 与 `result.seriesId`——传错成 `emptyList()` 或首段 uri 的话，
 *    归组永远建不起来，而真值表全绿。
 * 2. **0 归属门槛**：`seriesId == 0L` 直接返回（不是所有录制路径都有系列归属）。
 * 3. **抗取消**：整段 `NonCancellable + Dispatchers.IO`——调用点在组合作用域的 `collect` 里，
 *    不抗取消就是"用户在写库途中离场 ⇒ 归组静默丢失"，且丢得毫无痕迹。
 * 4. **异常不许冒进 UI**：`runCatching` + `Log.e`，写库失败只是"归组没记上"，不许把录制页带崩。
 */
class SeriesStoreGuardTest {

    private fun masked(rel: String) = codeOnly(mainSourceText(rel))

    private fun body(rel: String, fn: String) = flatten(bodyOf(masked(rel), fn))

    /** `CameraScreen` 的 collect 体：`runner.result.collect` 到 `runner.clearResult()` 之间 */
    private fun collectBlock(src: String): String {
        val at = src.indexOf("runner.result.collect")
        if (at < 0) throw AssertionError("找不到 runner.result.collect —— collect 被挪走/改名，本守卫失去输入")
        return flatten(src.substring(at, src.indexOf("runner.clearResult()", at)))
    }

    @Test
    fun `录制成功分支必须调用SeriesStore_record并带上parts与seriesId`() {
        val collect = collectBlock(masked("ui/CameraScreen.kt"))
        assertTrue("result.ok 分支必须调 SeriesStore.record（不调 = 分段永远归不了组）", collect.contains("SeriesStore.record("))
        assertTrue("必须传 result.parts（传首段 uri 或 emptyList 都建不起组）", collect.contains("result.parts"))
        assertTrue("必须传 result.seriesId", collect.contains("result.seriesId"))
        assertTrue("必须传本会话起始时刻（runner.sessionStartedAtMs）", collect.contains("runner.sessionStartedAtMs"))
        // 取消不许被 runCatching 吞掉（吞掉后协程会在已取消的作用域里继续 collect）
        assertTrue("CancellationException 必须原样抛回", collect.contains("catch (ce: CancellationException)") && collect.contains("throw ce"))
    }

    @Test
    fun `record的门槛与抗取消与异常吞掉三样都在`() {
        val rec = body("media/SeriesStore.kt", "record")
        assertTrue("seriesId 为 0 直接返回（不是所有录制路径都有系列归属）", rec.contains("if (seriesId == 0L) return"))
        assertTrue("纯函数算出空（不足两段）直接返回", rec.contains("if (rows.isEmpty()) return"))
        assertTrue("必须 upsert 系列头（MediaSeries）", rec.contains("upsertSeries("))
        assertTrue("必须逐段 upsert 成员（SeriesPart）", rec.contains("upsertPart("))
        assertTrue("必须抗取消（组合作用域的 collect 里离场会漏记）", rec.contains("NonCancellable + Dispatchers.IO"))
        assertTrue("写库异常必须吞掉", rec.contains("runCatching"))
        assertTrue("写库失败必须留日志", rec.contains("Log.e("))
    }

    // ------------------------------------------------------------------ 突变自证

    /**
     * 突变自证：把三条判据逐条破坏，每次都必须**真的**翻红，且互不代偿。
     *
     * 每个突变都先断言「它确实改动了源码」——没改动的突变体在这条自证里等于什么都没证明
     * （本仓踩过的语义空操作突变坑）。
     */
    @Test
    fun `突变自证：摘掉接线或门槛必红`() {
        val screenRaw = mainSourceText("ui/CameraScreen.kt")
        val storeRaw = mainSourceText("media/SeriesStore.kt")

        // ① 接线：把 result.parts 换成空列表（归组建不起来，真值表却全绿）
        val noParts = screenRaw.replace("result.seriesId, runner.sessionStartedAtMs, result.parts", "result.seriesId, runner.sessionStartedAtMs, emptyList()")
        assertTrue("突变体①必须真的改了源码", noParts != screenRaw)
        assertFalse("传 emptyList 时接线判据必须翻假", collectBlock(codeOnly(noParts)).contains("result.parts"))

        // ② 抗取消：把 NonCancellable 摘掉（离场即漏记，且毫无痕迹）
        val noNc = storeRaw.replace("NonCancellable + Dispatchers.IO", "Dispatchers.IO")
        assertTrue("突变体②必须真的改了源码", noNc != storeRaw)
        assertFalse(
            "摘掉 NonCancellable 后抗取消判据必须翻假",
            flatten(bodyOf(codeOnly(noNc), "record")).contains("NonCancellable + Dispatchers.IO")
        )

        // ③ 0 归属门槛
        val noZero = storeRaw.replace("if (seriesId == 0L) return", "")
        assertTrue("突变体③必须真的改了源码", noZero != storeRaw)
        assertFalse(
            "摘掉 0 归属门槛后门槛判据必须翻假",
            flatten(bodyOf(codeOnly(noZero), "record")).contains("if (seriesId == 0L) return")
        )

        // ④ 三条互不代偿：摘掉一条，其余两条仍为真（否则"必红"可能只是某一条恒红）
        assertTrue(
            "摘掉 NonCancellable 不得牵连 0 归属门槛",
            flatten(bodyOf(codeOnly(noNc), "record")).contains("if (seriesId == 0L) return")
        )
        assertTrue(
            "摘掉 0 归属门槛不得牵连接线",
            collectBlock(codeOnly(noZero)).contains("SeriesStore.record(")
        )
    }

    /**
     * 纯函数那道「有效段 < 2 ⇒ 返回空」的门槛也钉一道源码锁（行为在 `SeriesStoreTest`）。
     *
     * 行为用例与源码锁各管一半：`SeriesStoreTest` 证明**现在是对的**，这条锁证明**它写在
     * 对的地方**——门槛被挪回 `record` 里时行为用例仍全绿（`record` 要 Room，测不了），
     * 只有这条会红。
     */
    @Test
    fun `纯函数的成系列门槛必须是2且写在纯函数里`() {
        val store = masked("media/SeriesStore.kt")
        assertTrue("门槛常量必须存在且是 2", store.contains("MIN_SERIES_PARTS = 2"))
        assertTrue(
            "门槛必须落在纯函数里（挪回 record 里就 JVM 测不到，改成 <1 也不会红）",
            flatten(bodyOf(store, "seriesPartsToRecord")).contains("rows.size < MIN_SERIES_PARTS")
        )

        // 突变自证：把 < MIN_SERIES_PARTS 改成 < 1，判据必须翻红
        val raw = mainSourceText("media/SeriesStore.kt")
        val mutant = raw.replace("rows.size < MIN_SERIES_PARTS", "rows.size < 1")
        assertTrue("突变体必须真的改了源码（没改动 = 这条自证什么也证明不了）", mutant != raw)
        assertFalse(
            "门槛被改成 < 1 后判据必须翻假",
            flatten(bodyOf(codeOnly(mutant), "seriesPartsToRecord")).contains("rows.size < MIN_SERIES_PARTS")
        )
    }
}

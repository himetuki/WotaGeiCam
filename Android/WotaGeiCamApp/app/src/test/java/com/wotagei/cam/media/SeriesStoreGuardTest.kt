package com.wotagei.cam.media

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 录制收尾落库的**接线与红线守卫**（#5 Step 4b；Y21 迁移到会话所有者）。
 *
 * [SeriesStoreTest] 钉纯逻辑真值表；这里钉的是「纯逻辑之外、只有源码能看见」的五件事：
 * 1. **接线搬家**：`CameraScreen` 的 `result.collect` 不再内联 `SeriesStore.record`，而是把
 *    `result.parts / result.seriesId / runner.sessionStartedAtMs` 交给进程级所有者
 *    `TakeSettlement.settle(...)`；页面退场只取消 `await`，不取消落库。
 * 2. **0 归属门槛**：`seriesId == 0L` 直接返回（不是所有录制路径都有系列归属）。
 * 3. **事务化**：系列头与成员行必须在同一个 `db.withTransaction` 里写（Y21）。
 * 4. **抗取消**：整段 `NonCancellable + Dispatchers.IO`——调用方作用域被取消也不许写一半。
 * 5. **异常不许冒进 UI**：`runCatching` + `Log.e`，写库失败只是"归组没记上"。
 */
class SeriesStoreGuardTest {

    private fun masked(rel: String) = codeOnly(mainSourceText(rel))

    private fun body(rel: String, fn: String) = flatten(bodyOf(masked(rel), fn))

    /**
     * 取函数体：**花括号体**走 [bodyOf]；**表达式体**（`): Int = Foo.bar(...)`）按签名括号配平截取。
     *
     * 为什么不能一律 [bodyOf]：生产后端 `writeSeries` 是单表达式转调（`= SeriesStore.record(...)`），
     * `bodyOf` 只认 `{`，遇到表达式体会抛"找不到花括号体"——把**合法实现**判成守卫失效。
     * 也不能退化成固定窗口文本：截取必须从签名右括号后的 `=` 起、按 `()[]{}` 配平到表达式结束；
     * 函数被改名（一个 `fun fn(` 都找不到）仍然拿不到输入，保留原守卫"不许沉默通过"的语义。
     *
     * 同名声明可能有多处（接口声明 + 实现），只认**有体**的那一处。
     */
    private fun bodyOrExpr(rel: String, fn: String): String {
        val src = masked(rel)
        var at = src.indexOf("fun $fn(")
        if (at < 0) throw AssertionError("找不到 fun $fn( —— 函数被改名/挪走，守卫失去输入")
        while (at >= 0) {
            // 参数表右括号（配平嵌套调用与默认值里的括号）
            var i = src.indexOf('(', at)
            var depth = 0
            while (i < src.length) {
                when (src[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            var j = i + 1
            // 跳过返回类型（`): Int = ...` / `): Int { ... }`）；行尾仍未遇到 = / { = 只有声明
            while (j < src.length && src[j] != '{' && src[j] != '=' && src[j] != '\n') j++
            if (j < src.length && src[j] == '{') return flatten(bodyOf(src.substring(at), fn))
            if (j < src.length && src[j] == '=') {
                // 表达式体：从 '=' 起按括号配平；深度归零后的行尾即结束
                var k = j + 1
                var d = 0
                while (k < src.length) {
                    when (src[k]) {
                        '(', '[', '{' -> d++
                        ')', ']', '}' -> d--
                    }
                    k++
                    if (d == 0 && k < src.length && src[k] == '\n') break
                }
                val expr = src.substring(j, k)
                if (expr.isBlank()) throw AssertionError("fun $fn 的表达式体截取为空——守卫失去输入")
                return flatten(expr)
            }
            at = src.indexOf("fun $fn(", at + 1)
        }
        throw AssertionError("fun $fn 只有声明没有函数体 —— 实现被挪走，守卫失去输入")
    }

    /** `CameraScreen` 的 collect 体：`runner.result.collect` 到 `runner.clearResult()` 之间 */
    private fun collectBlock(src: String): String {
        val at = src.indexOf("runner.result.collect")
        if (at < 0) throw AssertionError("找不到 runner.result.collect —— collect 被挪走/改名，本守卫失去输入")
        return flatten(src.substring(at, src.indexOf("runner.clearResult()", at)))
    }

    @Test
    fun `录制收尾必须交给会话所有者且带齐会话身份`() {
        val screen = masked("ui/CameraScreen.kt")
        val stop = bodyOf(screen, "stopInternal")
        assertTrue("停录登记必须调 RecordSession.record（唯一结算入口）", stop.contains("RecordSession.record("))
        assertTrue("必须带引擎终态结果（parts/seriesId 在 out 上）", stop.contains("result = out"))
        assertTrue("必须带本会话系列身份", stop.contains("seriesId = sessionSeriesId"))
        assertTrue("必须带本会话起始时刻", stop.contains("startedAtMs = sessionStartedAtMs"))
        assertTrue("必须带停录那一刻的练习武装快照", stop.contains("practiceArmed = practiceArmedAtStop"))
        assertFalse(
            "页面 collect 不得再自行结算（同一键两处发起会重复交付）",
            collectBlock(screen).contains("TakeSettlement.settle(")
        )
        assertFalse(
            "UI 不许内联 SeriesStore.record（归组已搬到进程级所有者）",
            stop.contains("SeriesStore.record(")
        )
    }
    @Test
    fun `所有者里必须真的调SeriesStore_record并带齐参数`() {
        val owner = bodyOrExpr("record/TakeSettlement.kt", "writeSeries")
        assertTrue("生产后端必须调 SeriesStore.record", owner.contains("SeriesStore.record("))
        assertTrue("必须传 seriesId", owner.contains("seriesId"))
        assertTrue("必须传会话起始时刻", owner.contains("startedAtMs"))
        assertTrue("必须把段映射成 SeriesPartInput", owner.contains("SeriesPartInput("))
    }

    @Test
    fun `record的门槛与抗取消与异常吞掉三样都在`() {
        val rec = body("media/SeriesStore.kt", "record")
        assertTrue("seriesId 为 0 直接返回（不是所有录制路径都有系列归属）", rec.contains("if (seriesId == 0L) return"))
        assertTrue("纯函数算出空（不足两段）直接返回", rec.contains("if (rows.isEmpty()) return"))
        assertTrue("必须 upsert 系列头（MediaSeries）", rec.contains("upsertSeries("))
        assertTrue("必须逐段 upsert 成员（SeriesPart）", rec.contains("upsertPart("))
        assertTrue("系列头与成员行必须同一事务（Y21）", rec.contains("db.withTransaction"))
        assertTrue("必须抗取消（调用方作用域被取消也不许写一半）", rec.contains("NonCancellable + Dispatchers.IO"))
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

        // ① 接线：丢掉会话起始时刻（结算键退化成 0，迟到结论全被拆成另一场）
        val noStart = screenRaw.replace("startedAtMs = sessionStartedAtMs", "startedAtMs = 0L")
        assertTrue("突变体①必须真的改了源码", noStart != screenRaw)
        assertFalse(
            "丢掉会话起始时刻后接线判据必须翻假",
            bodyOf(codeOnly(noStart), "stopInternal").contains("startedAtMs = sessionStartedAtMs")
        )

        // ② 抗取消：把 NonCancellable 摘掉（取消即写一半，且毫无痕迹）
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
            "摘掉 0 归属门槛不得牵连接线（两个判据的输入互不相干）",
            bodyOf(codeOnly(screenRaw), "stopInternal").contains("RecordSession.record(")
        )
    }

    @Test
    fun `突变自证：事务被拆开后守卫必红`() {
        val storeRaw = mainSourceText("media/SeriesStore.kt")
        val noTx = storeRaw.replace("db.withTransaction {", "run {")
        assertTrue("突变体必须真的改了源码", noTx != storeRaw)
        assertFalse(
            "拆掉事务后系列头/成员行不再原子（Y21 回潮）",
            flatten(bodyOf(codeOnly(noTx), "record")).contains("db.withTransaction")
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
        assertTrue(
            "未发布段过滤必须落在纯函数里（Y21：committed=false 不许进系列）",
            flatten(bodyOf(store, "seriesPartsToRecord")).contains("if (!p.committed)")
        )

        // 突变自证 1：把 < MIN_SERIES_PARTS 改成 < 1，判据必须翻红
        val raw = mainSourceText("media/SeriesStore.kt")
        val mutant = raw.replace("rows.size < MIN_SERIES_PARTS", "rows.size < 1")
        assertTrue("突变体必须真的改了源码（没改动 = 这条自证什么也证明不了）", mutant != raw)
        assertFalse(
            "门槛被改成 < 1 后判据必须翻假",
            flatten(bodyOf(codeOnly(mutant), "seriesPartsToRecord")).contains("rows.size < MIN_SERIES_PARTS")
        )
        // 突变自证 2：摘掉未发布过滤，判据必须翻红
        val noCommitted = raw.replace("if (!p.committed) return@mapNotNull null", "")
        assertTrue("突变体必须真的改了源码", noCommitted != raw)
        assertFalse(
            "摘掉未发布过滤后判据必须翻假",
            flatten(bodyOf(codeOnly(noCommitted), "seriesPartsToRecord")).contains("if (!p.committed)")
        )
    }
}

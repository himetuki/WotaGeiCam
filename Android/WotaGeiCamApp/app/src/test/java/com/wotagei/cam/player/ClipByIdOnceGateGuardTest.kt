package com.wotagei.cam.player

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #7 散点反查一次性出口的源码结构守卫。
 *
 * 背景：关闸期 `MediaRepo.clipById` 的闸门分支**零发射**，散点的单次反查（对比页选右片 /
 * 练习回程 / 历史面板 / 录制页练习参考片与手选片）用 `.first()` 收流会在零发射流上挂起
 * 整个关闸期（约 1.15s）。修复 = 生产侧新增一次性出口 [MediaRepo.clipByIdOnce]（不受
 * 闸门约束，闸门只护列表/播放页两条流），5 处消费点全部改走它。
 *
 * 为什么要结构守卫：把某个消费点悄悄改回 `clipById(id).first()`，纯看代码审查极易漏
 * （形态几乎一样）；给 `clipByIdOnce` 塞回闸门引用则会让修复整条作废。本文件钉三件事：
 * 1. `clipByIdOnce` 体内直接走 `queryById` 且**不引用 refreshGate**；
 * 2. 消费点逐一断言调 `clipByIdOnce(`，切片内不得残留 `.first()`/`.firstOrNull()` 回退形态；
 * 3. 每条断言配突变自证（文本级突变不编译，只验尺子会红）——期望值逐个手推复核过：
 *    CompareContent 1 处（练习回程；2026-10-26 起手选右片改走 `clipByUri(uri)`，不再按 id 反查）、
 *    CompareHistorySheet 1 处（历史面板的库内条目；外来条目走 clipByUri）、
 *    SplitComparePlayer 1 处（练习参考片自动挂载；手选片同上改走 clipByUri）。
 *
 * 与既有 `PlayerClipMissingGuardTest` 互不遮蔽：它钉 `clipById` 本体的 null 直发契约，
 * 本文件钉 `clipByIdOnce` 与消费点接线，两者各管各的。
 */
class ClipByIdOnceGateGuardTest {

    /** 「一次性反查且无收流回退」的判定本体（良品与突变体走同一把尺） */
    private fun onceOnlyHolds(raw: String, rel: String, funName: String, expected: Int): Boolean {
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(raw), funName)
        )
        // 注意 occurrences 的词边界检查的是 needle 两端的**标识符字符**：needle 以 "(" 结尾时
        // 后面跟的实参首字母永远算标识符字符，永不命中——所以 needle 不带括号
        return KotlinSourceScan.occurrences(body, "clipByIdOnce").size == expected &&
            !body.contains(".first(") &&
            !body.contains(".firstOrNull(")
    }

    private fun mainText(rel: String): String = KotlinSourceScan.mainSourceText(rel)

    @Test
    fun `clipByIdOnce是一次性查询_体内不带闸门`() {
        val raw = mainText("media/MediaRepo.kt")
        val body = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(raw), "clipByIdOnce")
        )
        assertTrue(
            "clipByIdOnce 必须直接走 queryById（一次查完即返回）",
            body.contains("queryById(")
        )
        assertFalse(
            "clipByIdOnce 体内不得引用 refreshGate（闸门只护列表/播放页两条流；塞回闸门 = " +
                "散点反查重新在零发射流上挂起整个关闸期）",
            body.contains("refreshGate")
        )
        // 突变自证：塞回闸门引用（文本级突变不编译，只验尺子会红）
        val mutant = raw.replace(
            "val clip = queryById(id)",
            "val clip = refreshGate.value.let { queryById(id) }"
        )
        val mutantBody = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(KotlinSourceScan.codeOnly(mutant), "clipByIdOnce")
        )
        assertTrue("突变体必须真的塞进了闸门引用（替换不中 = 尺子没咬合）", mutantBody.contains("refreshGate"))
        val gateFree = mutantBody.contains("queryById(") && !mutantBody.contains("refreshGate")
        assertFalse("塞回闸门引用后守卫必须报红", gateFree)
    }

    @Test
    fun `对比页主体反查都走一次性查询`() {
        val raw = mainText("player/CompareScreen.kt")
        assertTrue(
            "CompareContent（练习回程一处；手选右片走 clipByUri）必须走 clipByIdOnce，且切片内不得残留 .first()/.firstOrNull()",
            onceOnlyHolds(raw, "player/CompareScreen.kt", "CompareContent", expected = 1)
        )
    }

    @Test
    fun `历史面板反查走一次性查询`() {
        val raw = mainText("player/CompareScreen.kt")
        assertTrue(
            "CompareHistorySheet 必须走 clipByIdOnce（面板逐条反查，关闸期 .first() 会逐条挂起）",
            onceOnlyHolds(raw, "player/CompareScreen.kt", "CompareHistorySheet", expected = 1)
        )
    }

    @Test
    fun `录制页对比播放反查都走一次性查询`() {
        val raw = mainText("ui/CameraScreen.kt")
        assertTrue(
            "SplitComparePlayer（练习参考片自动挂载一处；手选片走 clipByUri）必须走 clipByIdOnce",
            onceOnlyHolds(raw, "ui/CameraScreen.kt", "SplitComparePlayer", expected = 1)
        )
    }

    @Test
    fun `突变_消费点改回first收流必红`() {
        val raw = mainText("player/CompareScreen.kt")
        assertTrue("良品必须先真的绿，否则突变体的红没有意义",
            onceOnlyHolds(raw, "player/CompareScreen.kt", "CompareContent", expected = 1))
        // 突变：某一点改回 clipById(id).first()（文本级突变不编译，只验尺子会红）
        val mutant = raw.replace(
            Regex.fromLiteral("repo.clipByIdOnce(id)"),
            "repo.clipById(id).first()"
        )
        assertFalse(
            "消费点改回 .first() 后守卫必须报红（真偏离：零发射流上挂起整个关闸期）",
            onceOnlyHolds(mutant, "player/CompareScreen.kt", "CompareContent", expected = 1)
        )
    }
}

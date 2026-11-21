package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 霜板**逐槽可见性**的源码级守卫（Y14，2026-11-19 评审）——
 * 与 [FrostPlateCoverageTest]（数据行为）互补，这里钉"谁在什么时候把什么发布给 UI"：
 *
 * 缺陷史：整页只有一个 `platesDrawn` 布尔。只要任一块板画成功它就翻真，而完全落在信箱黑边上的板
 * 被 `frostUvInto` 跳过（与画面无交集 ⇒ 一块都没画）——UI 却按整页 live 把那块板的纯色 fill 也撤了，
 * 结果是"透明描边框"。修法三条：
 * 1. GL 把**原始槽位**的覆盖位掩码回报给 [com.wotagei.cam.camera.FrostCardTable]（压实下标不行）；
 * 2. 掩码只在窗口 **swap 成功之后**才发布（画了但没上屏的帧不许让 UI 撤底色）；
 * 3. UI 逐板判让位：本槽没有板就保留本地纯色底，注册只看开关意图（不回到 live 启动死锁）。
 *
 * 全部按"真身 + 突变体同吃一把尺"写，判据是吃源码文本的纯函数。
 */
class FrostPlateCoverageGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }
    private val chain by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/FrostBlurChain.kt")) }
    private val table by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/FrostBlur.kt")) }
    private val hud by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/HudLayer.kt")) }
    private val widgets by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/design/Widgets.kt")) }
    private val liquid by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/anim/LiquidMerge.kt")) }

    private fun body(src: String, name: String): String =
        KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, name))

    // ---------------------------------------------------------------- 1. GL 侧发布时机

    /** 真实源码必须满足：失败分支撤报、成功分支（`else` 内）才发布本帧掩码。 */
    private fun publishesOnlyAfterSwap(src: String): Boolean {
        val b = body(src, "finishWindowFrame")
        val swapAt = b.indexOf("if (!swap(window))")
        val elseAt = b.indexOf("} else {", swapAt)
        if (swapAt < 0 || elseAt <= swapAt) return false
        // "唯一"口径：发布调用一次都不许出现在 swap 之前。只按 swapAt 之后找会漏掉
        // "额外插一条提前发布"的突变（两处并存时后面那条仍满足顺序），判据就白摆。
        if ("reportPlatesCovered" in b.substring(0, swapAt)) return false
        val failAt = b.indexOf("FrostCardTable.reportPlatesCovered(0)", swapAt)
        val okAt = b.indexOf("FrostCardTable.reportPlatesCovered(frostFrameCovered)", swapAt)
        return failAt in (swapAt + 1) until elseAt && okAt > elseAt
    }

    @Test
    fun `逐槽覆盖位只在swap成功之后发布`() {
        assertTrue("发布位置必须满足：失败撤报、成功才发布本帧掩码", publishesOnlyAfterSwap(engine))
        val b = body(engine, "finishWindowFrame")
        assertTrue("画板必须发生在 swap 之前（#84 顺序红线）", b.indexOf("drawFrostPlates()") in 0 until b.indexOf("if (!swap(window))"))
        assertTrue(
            "发布之后才允许记 swap 心跳（先发布再记心跳会把顺序写反而不自知）",
            b.indexOf("lastWindowSwapNs = System.nanoTime()") > b.indexOf("FrostCardTable.reportPlatesCovered(frostFrameCovered)")
        )
        // 尺要能红：把成功发布挪到 swap 之前 ⇒ 判据必须翻红
        val mutant = engine.replace(
            "drawFrostPlates()\n        if (!swap(window)) {",
            "drawFrostPlates()\n        FrostCardTable.reportPlatesCovered(frostFrameCovered)\n        if (!swap(window)) {"
        )
        assertFalse("swap 之前发布的突变必须被看见", publishesOnlyAfterSwap(mutant))
    }

    /** 真实源码必须满足：画板函数只记账、按原始槽位回报、不自己发布。 */
    private fun drawsIntoFrameMaskOnly(src: String): Boolean {
        val b = body(src, "drawFrostPlates")
        return b.contains("tryReadInto(frostTableCopy, frostTableSlots)") &&
            b.contains("cardSlots = frostTableSlots") &&
            b.contains("frostFrameCovered = covered") &&
            b.contains("Integer.bitCount(covered)") &&
            !b.contains("reportPlatesCovered") &&
            !b.contains("reportPlatesDrawn")
    }

    @Test
    fun `画板只记本帧掩码不发布`() {
        assertTrue("drawFrostPlates 必须按原始槽位回报且只记账", drawsIntoFrameMaskOnly(engine))
        // 掩码是 GL 线程独占（帧内写、swap 后读），不许加 @Volatile 掩盖帧内多次读
        assertTrue(engine.contains("private val frostTableSlots = IntArray(FrostCardTable.SLOT_CAPACITY)"))
        assertTrue(engine.contains("private var frostFrameCovered = 0"))
        assertFalse(
            "frostFrameCovered 是 GL 线程独占，不许 @Volatile",
            Regex("@Volatile\\s*private var frostFrameCovered").containsMatchIn(engine)
        )
        assertTrue("引擎销毁必须清本帧掩码", body(engine, "releaseGl").contains("frostFrameCovered = 0"))
        // 尺要能红：让 drawFrostPlates 自己发布 ⇒ 判据翻红
        val mutant = engine.replace(
            "frostFrameCovered = covered",
            "frostFrameCovered = covered FrostCardTable.reportPlatesCovered(covered)"
        )
        assertFalse("画板函数内发布覆盖位的突变必须被看见", drawsIntoFrameMaskOnly(mutant))
    }

    @Test
    fun `板pass把覆盖记到原始槽位而不是压实下标`() {
        val plate = chain.substringAfter("internal class FrostPlatePass")
        assertTrue("cardSlots 必须进参数（压实块下标对本次读表有效，不是槽位号）", plate.contains("cardSlots: IntArray"))
        assertTrue("返回值必须是位掩码", plate.contains("): Int {"))
        assertTrue(
            "必须按原始槽位建 bit",
            plate.contains("val slot = if (i < cardSlots.size) cardSlots[i] else -1") &&
                plate.contains("coveredMask = coveredMask or (1 shl slot)")
        )
        assertTrue("非法槽位号必须被挡掉", plate.contains("if (slot in 0 until FrostCardTable.SLOT_CAPACITY)"))
        assertTrue("GL 报错的帧必须回报 0 位（画的没上屏）", plate.contains("if (errors > 0) return 0"))
        assertTrue("正常路径回报掩码", plate.contains("return coveredMask"))
        // 尺要能红：改回压实下标 i ⇒ 判据翻红
        val mutant = plate.replace("cardSlots[i] else -1", "i else -1")
        assertFalse("按压实下标回报的突变必须被看见", mutant.contains("cardSlots[i] else -1"))
    }

    @Test
    fun `表的掩码API拒绝负数且整页撤报连槽位一起清`() {
        val b = body(table, "reportPlatesCovered")
        assertTrue("负数掩码当场当 0（-1 and SLOT_MASK 会点亮所有槽）", b.contains("if (mask <= 0) 0 else mask and SLOT_MASK"))
        assertTrue("整页 live 由掩码派生", b.contains("val drawn = next != 0") && b.contains("platesDrawn = drawn"))
        val clear = body(table, "reportPlatesDrawn")
        assertTrue("整页撤报必须连逐槽位一起清", clear.contains("coveredSlots = 0"))
        // isPlateCovered 是表达式体函数（bodyOf 只认花括号体），按扁平文本锁整条判据
        assertTrue(
            "读方逐槽判据必须挡越界",
            KotlinSourceScan.flatten(table).contains(
                "fun isPlateCovered(slot: Int): Boolean = slot in 0 until SLOT_CAPACITY && (coveredSlots and (1 shl slot)) != 0"
            )
        )
    }

    // ---------------------------------------------------------------- 2. UI 侧逐板让位

    /** 真实源码必须满足：注册看意图、让位只看本槽、未覆盖时保留本地底。 */
    private fun fillYieldsOnlyWhenCovered(src: String): Boolean {
        val card = body(src, "Modifier.wotaHudCard")
        return card.contains("!HudFrost.intent") &&
            !card.contains("!HudFrost.live") &&
            card.contains("val slot = rememberFrostSlot(HudFrost.intent)") &&
            card.contains("if (!HudFrost.isCovered(slot))") &&
            card.contains("return this.wotaCard(shape).then(hudFrostRectRegistrar(frost, slot))") &&
            card.contains("return edged.then(hudFrostRectRegistrar(frost, slot))")
    }

    @Test
    fun `HUD卡片只在本槽真贴出板时让位`() {
        assertTrue("wotaHudCard 必须逐槽判让位、未覆盖保留本地底", fillYieldsOnlyWhenCovered(widgets))
        // 尺要能红：把逐槽判据摘成"永远让位" ⇒ 判据翻红
        val mutant = widgets.replace("!HudFrost.isCovered(slot)", "false")
        assertFalse("摘掉逐槽判据的突变必须被看见", fillYieldsOnlyWhenCovered(mutant))
        // 同一枚槽必须既用于画 fill 的判据、又用于写矩形（两处各领一枚会把判据绑到别的板身上）
        assertTrue(
            "rememberFrostSlot 必须同时被判据与注册链使用",
            KotlinSourceScan.occurrences(body(widgets, "Modifier.wotaHudCard"), "slot").size >= 4
        )
    }

    @Test
    fun `底栏两条自绘路径也逐槽判让位`() {
        val dock = body(hud, "HudDockZone")
        assertTrue("竖 Dock 必须领同一枚槽并逐槽判", dock.contains("val frostSlot = rememberFrostSlot(HudFrost.intent)"))
        assertTrue(dock.contains("val frostCovered = HudFrost.isCovered(frostSlot)"))
        assertTrue("fill 让位判据必须用 frostCovered", dock.contains("if (frostCovered && frostVisible && !overlaysOn)"))
        assertTrue("注册链必须消费同一枚槽", dock.contains("hudFrostDockRegistrar(plateRadiusPx, frostVisible, frostSlot)"))

        val shell = body(liquid, "Modifier.wotaDockShell")
        assertTrue("底栏壳必须逐槽判", shell.contains("val frostCovered = HudFrost.isCovered(frostSlot)"))
        assertTrue("fill 条件必须用 frostCovered", shell.contains("if (!frostCovered || overlaysOn) {"))
        assertFalse("底栏壳不许再读整页 live 判 fill", shell.contains("!live || overlaysOn"))
    }

    @Test
    fun `覆盖位轮询只在变化时写state`() {
        assertTrue(
            "逐槽位必须是 Compose state（否则逐板让位不会重组）",
            hud.contains("var coveredMask by mutableIntStateOf(0)")
        )
        val poll = body(hud, "refreshAvailability")
        assertTrue("轮询必须读表的掩码快照", poll.contains("FrostCardTable.coveredSlotMask()"))
        assertTrue("只在真变化时写 state", poll.contains("if (mask != coveredMask)") && poll.contains("coveredMask = mask"))
        assertTrue("关掉/撤宿主必须清零", body(hud, "detach").contains("coveredMask = 0"))
        // isCovered 是表达式体函数（bodyOf 只认花括号体），按扁平文本锁整条判据
        val flatHud = KotlinSourceScan.flatten(hud)
        assertTrue(
            "isCovered 必须挡越界槽并查逐槽位",
            flatHud.contains(
                "fun isCovered(slot: Int): Boolean = slot in 0 until FrostCardTable.SLOT_CAPACITY && " +
                    "(coveredMask and (1 shl slot)) != 0"
            )
        )
        // 与表侧判据同一条口径（两处各写一份"bit s = 槽 s"就会分叉）
        assertTrue(
            "UI 与表侧的槽位上限必须是同一常量",
            KotlinSourceScan.flatten(table).contains("slot in 0 until SLOT_CAPACITY")
        )
    }
}

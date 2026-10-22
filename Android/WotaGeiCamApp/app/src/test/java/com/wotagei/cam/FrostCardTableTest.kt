package com.wotagei.cam

import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.camera.FrostCardTable.CARD_ALPHA
import com.wotagei.cam.camera.FrostCardTable.CARD_BOTTOM
import com.wotagei.cam.camera.FrostCardTable.CARD_LEFT
import com.wotagei.cam.camera.FrostCardTable.CARD_PRESENT
import com.wotagei.cam.camera.FrostCardTable.CARD_RADIUS
import com.wotagei.cam.camera.FrostCardTable.CARD_RIGHT
import com.wotagei.cam.camera.FrostCardTable.CARD_TOP
import com.wotagei.cam.camera.FrostCardTable.HEADER_CARD_SPACE
import com.wotagei.cam.camera.FrostCardTable.HEADER_FLOATS
import com.wotagei.cam.camera.FrostCardTable.HEADER_ROOT_HEIGHT
import com.wotagei.cam.camera.FrostCardTable.HEADER_ROOT_LEFT
import com.wotagei.cam.camera.FrostCardTable.HEADER_ROOT_TOP
import com.wotagei.cam.camera.FrostCardTable.HEADER_ROOT_WIDTH
import com.wotagei.cam.camera.FrostCardTable.HEADER_TINT_BLUE
import com.wotagei.cam.camera.FrostCardTable.HEADER_TINT_GREEN
import com.wotagei.cam.camera.FrostCardTable.HEADER_TINT_RED
import com.wotagei.cam.camera.FrostCardTable.HEADER_UI_ENABLED
import com.wotagei.cam.camera.FrostCardTable.SLOT_CAPACITY
import com.wotagei.cam.camera.FrostCardTable.SLOT_FLOATS
import com.wotagei.cam.camera.FrostCardTable.TABLE_FLOATS
import com.wotagei.cam.camera.frostUvInto
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [FrostCardTable] 的行为测试（#84 步骤 2 · A2 混合路线里 `ui/` 与 `camera/` 之间**唯一**的接口）。
 *
 * A2 下 `ui/` 交给 GL 的只有这张表：一枚表头 + 至多 [SLOT_CAPACITY] 块卡片矩形。它错了不会崩、
 * 不会报错，只会让屏幕上的板**贴错位置、贴慢一帧、或多贴一块已经不存在的板**——全是只能上机、
 * 且很难归因的故障。所以这里把五类账逐条钉住：
 * 1. **槽位账**：发满必须给 -1（满了不许静默复用别人的格子，注册点拿它判"保持旧观感"）、
 *    还回来的槽必须可复用、且**还槽必须清掉在场位**（不清就是给 GL 留一块幽灵板）、
 *    迟到/重复的释放与写入一律按在场位（租约）拒绝；
 * 2. **退化账**：四边反序/零面积的写入必须当"这帧没有这块板"，与 [frostUvInto] 的退化口径同一条；
 * 3. **布局账**：[FrostCardTable.tryReadInto] 读到的必须是「表头 9 个 float + **压实后**的卡片块」。
 *    压实偏移算错，GL 就会拿第 3 格的矩形去画第 1 块板，而 JVM 之外没人知道；表头/卡片的字段偏移
 *    由 [FrostCardTable.HEADER_ROOT_LEFT] 那一组常量说了算，所以另有一条"常量值 = 线上排布"的冻结用例；
 * 4. **并发账**：GL 线程读、UI 线程写，读方一次拿到的**每一格**都必须出自同一次写入事务
 *    （双缓冲 + 一次引用交换 + 回收握手的全部意义就在这条，见表类的「不撕裂」一节）；
 * 5. **线程契约账**：写方只能是一枚线程，换了线程就停用整张表（不许静默继续）。
 *
 * ⚠ 这张表是 `object` 单例，用例之间必然共享状态（尤其那枚"首写方线程"登记），所以 [setUp] / [tearDown]
 * 走 [FrostCardTable.resetForTests] 把它恢复成刚 init 的样子；用例一律**不假设**自己拿到的是几号槽
 * （只假设"两两不同"和"在 0 until 容量内"）。
 */
class FrostCardTableTest {

    private val out = FloatArray(TABLE_FLOATS)

    /** 表头九项各写一个互不相同的值：读出来逐位对得上，才算"偏移常量与写入顺序没分叉" */
    private fun header(uiEnabled: Boolean = true, cardSpace: Boolean = true) = FrostCardTable.writeHeader(
        rootLeftPx = 11f, rootTopPx = 22f, rootWidthPx = 33f, rootHeightPx = 44f,
        tintRed = 0.5f, tintGreen = 0.25f, tintBlue = 0.125f, uiEnabled = uiEnabled,
        cardSpace = cardSpace
    )

    private fun slotOffset(slot: Int) = HEADER_FLOATS + slot * SLOT_FLOATS

    @Before
    fun setUp() {
        FrostCardTable.resetForTests()
        header()
        out.fill(Float.NaN)
    }

    @After
    fun tearDown() {
        // 清账（含首写方线程登记）：本类里有用例把写方跑在专用线程上，不清就会绊倒下一条
        FrostCardTable.resetForTests()
    }

    // ------------------------------------------------------------- 1. 槽位账

    @Test
    fun `槽位发满之后必须返回负一而不是静默复用`() {
        val taken = ArrayList<Int>()
        repeat(SLOT_CAPACITY) { taken += FrostCardTable.acquireSlot() }
        assertEquals("发出去的槽位必须互不相同：$taken", SLOT_CAPACITY, taken.toSet().size)
        assertTrue("槽位必须落在 0 until $SLOT_CAPACITY：$taken", taken.all { it in 0 until SLOT_CAPACITY })
        assertEquals(SLOT_CAPACITY, FrostCardTable.usedSlotCount())
        // 满了返回 -1 是注册点"保持旧观感、不画半块板"的判据本体
        assertEquals("满了必须给 -1", -1, FrostCardTable.acquireSlot())
        assertEquals("溢出那次取用不许偷偷占掉别人的格子", SLOT_CAPACITY, FrostCardTable.usedSlotCount())
    }

    @Test
    fun `还槽既归还编号也清掉在场位`() {
        val taken = ArrayList<Int>()
        repeat(SLOT_CAPACITY) { taken += FrostCardTable.acquireSlot() }
        val recycled = taken[2]
        // 先给这格写一块板（在场位=1），再还槽：还槽必须顺手清掉在场位，否则 GL 会画一块已经不存在的板
        assertTrue(FrostCardTable.writeCard(recycled, 100f, 200f, 180f, 240f, 14f, 0.3f))
        assertEquals("写入后必须在场", 1, FrostCardTable.tryReadInto(out))
        FrostCardTable.releaseSlot(recycled)
        assertEquals("还槽必须把占用数降下来", SLOT_CAPACITY - 1, FrostCardTable.usedSlotCount())
        assertEquals("还槽后清空在场位：GL 不该再看见这块板", 0, FrostCardTable.tryReadInto(out))
        assertEquals("空闲栈是后进先出：刚还的那格必须能被再拿到", recycled, FrostCardTable.acquireSlot())
    }

    @Test
    fun `越界槽位的读写都礼貌拒绝`() {
        // 注册点手上可能握着 -1（没抢到槽），这些调用每帧都会发生，绝不能抛
        assertFalse(FrostCardTable.writeCard(-1, 0f, 0f, 10f, 10f, 0f, 1f))
        assertFalse(FrostCardTable.writeCard(SLOT_CAPACITY, 0f, 0f, 10f, 10f, 0f, 1f))
        FrostCardTable.releaseSlot(-1)
        FrostCardTable.releaseSlot(SLOT_CAPACITY)
        FrostCardTable.releaseSlot(9999)
        assertEquals("非法槽位不该改变表的内容", 0, FrostCardTable.tryReadInto(out))
    }

    @Test
    fun `重复释放不许把同一格塞回空闲栈两次`() {
        // 判重一律看在场位（租约），不看栈内容：同一份租约的第二次释放到达时这一位已经是 false ⇒ 整条忽略
        val taken = ArrayList<Int>()
        repeat(SLOT_CAPACITY) { taken += FrostCardTable.acquireSlot() }
        val victim = taken[3]
        FrostCardTable.releaseSlot(victim)
        FrostCardTable.releaseSlot(victim)
        FrostCardTable.releaseSlot(victim)
        assertEquals("三次释放只该收回一次占用", SLOT_CAPACITY - 1, FrostCardTable.usedSlotCount())
        // 把其余格子也收回后重发：必须还是那 8 枚互不相同的格子，第 9 次拿不到
        taken.filterNot { it == victim }.forEach { FrostCardTable.releaseSlot(it) }
        assertEquals("全部收回后占用数必须归零", 0, FrostCardTable.usedSlotCount())
        val again = ArrayList<Int>()
        repeat(SLOT_CAPACITY) { again += FrostCardTable.acquireSlot() }
        assertEquals("空闲栈里被重复塞过一次的格子会在这里露馅", SLOT_CAPACITY, again.toSet().size)
        assertEquals(-1, FrostCardTable.acquireSlot())
    }

    @Test
    fun `还回去的格子不许被迟到写入画成幽灵板`() {
        // 现场形状：AnimatedContent 那类节点的布局回调可能排在它的 onDispose 之后，
        // 于是"节点已经离开组合、但还在往自己那一格里写矩形" ⇒ GL 画一块哪儿都不存在的板
        val slot = FrostCardTable.acquireSlot()
        assertTrue(FrostCardTable.writeCard(slot, 10f, 20f, 120f, 80f, 14f, 0.4f))
        assertEquals(1, FrostCardTable.tryReadInto(out))
        FrostCardTable.releaseSlot(slot)
        assertEquals("还槽后这格不再算占用", 0, FrostCardTable.usedSlotCount())
        assertFalse(
            "格子不在自己手里时写入必须被拒绝（在场位判，不看栈内容）",
            FrostCardTable.writeCard(slot, 10f, 20f, 120f, 80f, 14f, 0.4f)
        )
        assertEquals("被拒绝的迟到写入一个字节都不许写进表", 0, FrostCardTable.tryReadInto(out))
        // 别人（或自己重新）抢到同一格以后，写入必须立刻恢复有效——这条拒绝只认在场位，不是把这格判死
        val reacquired = FrostCardTable.acquireSlot()
        assertTrue(FrostCardTable.writeCard(reacquired, 10f, 20f, 120f, 80f, 14f, 0.4f))
        assertEquals(1, FrostCardTable.tryReadInto(out))
    }

    // ------------------------------------------------------------- 2. 退化账

    @Test
    fun `反序或零面积的矩形等于这帧没有这块板`() {
        val slot = FrostCardTable.acquireSlot()
        assertTrue(FrostCardTable.writeCard(slot, 10f, 20f, 120f, 80f, 14f, 0.4f))
        assertEquals("正常矩形必须在场", 1, FrostCardTable.tryReadInto(out))
        // 右缘收到左缘（拖拽中途会出现）：必须从表里消失，而不是留一块 0 宽的板。
        // "在场位被清 0"这件事的可观测口径就是计数从 1 掉回 0（压实读法根本不会把它端出去）。
        assertFalse("零宽写入必须返回 false", FrostCardTable.writeCard(slot, 10f, 20f, 10f, 80f, 14f, 0.4f))
        assertEquals(0, FrostCardTable.tryReadInto(out))
        assertTrue(FrostCardTable.writeCard(slot, 10f, 20f, 120f, 80f, 14f, 0.4f))
        assertEquals(1, FrostCardTable.tryReadInto(out))
        // 反序（right < left）同口径
        assertFalse("反序矩形必须返回 false", FrostCardTable.writeCard(slot, 120f, 80f, 10f, 20f, 14f, 0.4f))
        assertEquals(0, FrostCardTable.tryReadInto(out))
        // 零高同口径
        assertFalse(FrostCardTable.writeCard(slot, 10f, 20f, 120f, 20f, 14f, 0.4f))
        assertEquals(0, FrostCardTable.tryReadInto(out))
        // 退回正常值要能再回来（不许一次退化就把这格永久判死）
        assertTrue(FrostCardTable.writeCard(slot, 10f, 20f, 120f, 80f, 14f, 0.4f))
        assertEquals(1, FrostCardTable.tryReadInto(out))
    }

    @Test
    fun `底板色的不透明度夹进闭区间`() {
        // alpha 是"透光 = 1 − alpha"的另一面：越界会让板全透（看不见背板）或压成实心（挡住底层内容），
        // docs/plan/14 §二 那笔 WCAG 对比度账直接作废
        val hi = FrostCardTable.acquireSlot()
        val lo = FrostCardTable.acquireSlot()
        assertTrue(FrostCardTable.writeCard(hi, 0f, 0f, 10f, 10f, 0f, 1.7f))
        assertTrue(FrostCardTable.writeCard(lo, 20f, 20f, 30f, 30f, 0f, -0.3f))
        val copy = FloatArray(TABLE_FLOATS)
        assertEquals(2, FrostCardTable.tryReadInto(copy))
        // 两块按槽号升序压实，不假设谁在前 ⇒ 用集合比
        val alphas = setOf(copy[HEADER_FLOATS + CARD_ALPHA], copy[HEADER_FLOATS + SLOT_FLOATS + CARD_ALPHA])
        assertEquals("1.7 必须夹成 1、-0.3 必须夹成 0", setOf(1f, 0f), alphas)
    }

    // ------------------------------------------------------------- 3. 布局账

    @Test
    fun `读出来的是表头加压实后的卡片块`() {
        val slots = ArrayList<Int>()
        repeat(3) { slots += FrostCardTable.acquireSlot() }
        val sorted = slots.sorted()
        val lowSlot = sorted[0]
        val midSlot = sorted[1]
        val highSlot = sorted[2]
        // 中间那格只抢槽不写矩形 ⇒ 它必须"不在表里"，而不是在表里占一个空位
        assertTrue(FrostCardTable.writeCard(lowSlot, 101f, 102f, 199f, 160f, 14f, 0.35f))
        assertTrue(FrostCardTable.writeCard(highSlot, 201f, 202f, 299f, 260f, -1f, 0.65f))
        out.fill(0f)
        assertEquals(2, FrostCardTable.tryReadInto(out))
        // 表头恒在 out[0 until 9]
        assertEquals(11f, out[0], 0f)
        assertEquals(22f, out[1], 0f)
        assertEquals(33f, out[2], 0f)
        assertEquals(44f, out[3], 0f)
        assertEquals(0.5f, out[4], 0f)
        assertEquals(0.25f, out[5], 0f)
        assertEquals(0.125f, out[6], 0f)
        assertEquals(1f, out[7], 0f)
        // 卡片坐标系位（0=窗口系 / 1=视图局部系）：默认 header() 写 true ⇒ 1f；
        // 这一格错了读方就会拿"根矩形反推的原点"去配已经是视图局部的卡片，板整体错一圈
        assertEquals(1f, out[8], 0f)
        // 压实：第 0 块紧跟表头，第 1 块在 HEADER+SLOT_FLOATS 处（**不是**按槽位下标摆）
        assertEquals(101f, out[HEADER_FLOATS + CARD_LEFT], 0f)
        assertEquals(102f, out[HEADER_FLOATS + CARD_TOP], 0f)
        assertEquals(199f, out[HEADER_FLOATS + CARD_RIGHT], 0f)
        assertEquals(160f, out[HEADER_FLOATS + CARD_BOTTOM], 0f)
        assertEquals(14f, out[HEADER_FLOATS + CARD_RADIUS], 0f)
        assertEquals(0.35f, out[HEADER_FLOATS + CARD_ALPHA], 0f)
        assertEquals(1f, out[HEADER_FLOATS + CARD_PRESENT], 0f)
        val secondBase = HEADER_FLOATS + SLOT_FLOATS
        assertEquals(201f, out[secondBase + CARD_LEFT], 0f)
        assertEquals(299f, out[secondBase + CARD_RIGHT], 0f)
        assertEquals(-1f, out[secondBase + CARD_RADIUS], 0f) // 负数=短边一半那一档，表里原样存、GL 侧现场夹
        assertEquals(1f, out[secondBase + CARD_PRESENT], 0f)
        // 反向证据（与上面两条压实位断言配对，别写成由定义推出的恒等式）：
        // 中位那格只抢了槽没写矩形 ⇒ 若读方按"槽位下标摆"，压实位 $secondBase 上就该是它的空格子，
        // 于是 highSlot 那块（left=201）就得以整体后移一格、上面那两条就会红。
        // 这里刻意不去断言 out[slotOffset(midSlot)]——槽号由空闲栈顺序决定，槽位偏移完全可能
        // 正好落在压实区里（三枚槽 0/1/2 时 slotOffset(1) == secondBase），那条断言是假断言。
        assertEquals("第 3 块位置必须是空的（没写矩形的那格 $midSlot 不该在表里占一块）", 0f, out[HEADER_FLOATS + 2 * SLOT_FLOATS], 0f)
        // 卡片块之后不许有多余数据：读到的 float 数恰好 = 表头 + 2 块
        assertTrue(
            "压实后的两块必须就是写过矩形的那两块（$lowSlot/$highSlot），中间那格没资格占位",
            setOf(out[HEADER_FLOATS + CARD_LEFT], out[secondBase + CARD_LEFT]) == setOf(101f, 201f)
        )
    }

    @Test
    fun `偏移常量与线上排布必须还互相对得上`() {
        // 上面那条用例是拿**裸下标**比期望值的（那才是"线上排布"的冻结基线），本条把这些下标本身钉死：
        // 画板那侧（FrostPlatePass.draw）现在一律用 HEADER_*/CARD_* 取数，常量一旦被顺手重排，
        // "写方用常量写、读方用常量读"就变成一条由定义推出的恒等式、什么都测不出来。
        // 两条用例合起来才把"常量 == 老排布"钉成一条真断言。
        assertEquals(0, HEADER_ROOT_LEFT)
        assertEquals(1, HEADER_ROOT_TOP)
        assertEquals(2, HEADER_ROOT_WIDTH)
        assertEquals(3, HEADER_ROOT_HEIGHT)
        assertEquals(4, HEADER_TINT_RED)
        assertEquals(5, HEADER_TINT_GREEN)
        assertEquals(6, HEADER_TINT_BLUE)
        assertEquals(7, HEADER_UI_ENABLED)
        assertEquals(8, HEADER_CARD_SPACE)
        assertEquals(HEADER_FLOATS, HEADER_CARD_SPACE + 1)
        assertEquals(0, CARD_LEFT)
        assertEquals(6, CARD_PRESENT)
        assertEquals(SLOT_FLOATS, CARD_PRESENT + 1)
        assertEquals(TABLE_FLOATS, HEADER_FLOATS + SLOT_CAPACITY * SLOT_FLOATS)
        repeat(SLOT_CAPACITY) { assertEquals(HEADER_FLOATS + it * SLOT_FLOATS, FrostCardTable.cardBase(it)) }
    }

    @Test
    fun `副本数组太短一律返回负一且一个字节都不写`() {
        val slot = FrostCardTable.acquireSlot()
        FrostCardTable.writeCard(slot, 5f, 6f, 70f, 80f, 8f, 0.5f)
        val tooShort = FloatArray(TABLE_FLOATS - 1)
        tooShort.fill(-9f)
        assertEquals("太短的副本必须拒绝，不能越界写", -1, FrostCardTable.tryReadInto(tooShort))
        assertEquals("拒绝写入时一个字节都不许动", 0, tooShort.count { it != -9f })
        assertEquals("刚好够长必须能读", 1, FrostCardTable.tryReadInto(FloatArray(TABLE_FLOATS)))
    }

    @Test
    fun `压实顺序按槽号升序且计数跟着在场格数走`() {
        // 每格写一个能认出身份的 left = 1000 + 槽号 ⇒ 读回来的序列本身就把"谁在场、按什么顺序"说清了
        val slots = ArrayList<Int>()
        repeat(4) { slots += FrostCardTable.acquireSlot() }
        slots.forEach { FrostCardTable.writeCard(it, 1000f + it, 0f, 1050f + it, 50f, 0f, 0.5f) }
        val copy = FloatArray(TABLE_FLOATS)
        assertEquals(4, FrostCardTable.tryReadInto(copy))
        val readLefts = { n: Int -> (0 until n).map { copy[HEADER_FLOATS + it * SLOT_FLOATS + CARD_LEFT] } }
        assertEquals("必须按槽号升序端出来", slots.sorted().map { 1000f + it }, readLefts(4))
        FrostCardTable.releaseSlot(slots[1])
        assertEquals("还槽后计数必须立刻掉下来（GL 不该再画那块板）", 3, FrostCardTable.tryReadInto(copy))
        assertEquals(
            "掉的那一块必须就是刚还的槽，其余三块依次补上前移",
            slots.sorted().filterNot { it == slots[1] }.map { 1000f + it },
            readLefts(3)
        )
    }

    // ------------------------------------------------------------- 开关与回报

    @Test
    fun `表头那一位开关就是 GL 每帧问的那一位`() {
        header(uiEnabled = false)
        assertFalse(FrostCardTable.hasUiEnabled())
        val off = FloatArray(TABLE_FLOATS)
        FrostCardTable.tryReadInto(off)
        assertEquals("表头那一位必须与 hasUiEnabled 同源", 0f, off[7], 0f)
        header(uiEnabled = true)
        assertTrue(FrostCardTable.hasUiEnabled())
        // setUiEnabled 只改那一位，其余字段照抄（设置页翻转要立刻可见，不该等一次布局）
        FrostCardTable.setUiEnabled(false)
        assertFalse(FrostCardTable.hasUiEnabled())
        val copy = FloatArray(TABLE_FLOATS)
        assertEquals(0, FrostCardTable.tryReadInto(copy))
        assertEquals(0f, copy[7], 0f)
        assertEquals("表头其余字段必须原样保留", 33f, copy[2], 0f)
        assertEquals("表头其余字段必须原样保留", 0.125f, copy[6], 0f)
        FrostCardTable.setUiEnabled(true)
        assertTrue(FrostCardTable.hasUiEnabled())
    }

    @Test
    fun `GL 的贴板回报能被 UI 读到并且可以反复翻转`() {
        // 这条是"关掉毛玻璃要能完整回到旧观感"的可证部分：GL 撤报 false，UI 下一帧就把纯色 fill 画回来
        assertFalse(FrostCardTable.isPlatesDrawn())
        FrostCardTable.reportPlatesDrawn(true)
        assertTrue(FrostCardTable.isPlatesDrawn())
        FrostCardTable.reportPlatesDrawn(true)
        assertTrue(FrostCardTable.isPlatesDrawn())
        FrostCardTable.reportPlatesDrawn(false)
        assertFalse("撤报必须生效：卡住 true 就让 UI 永远不画旧 fill", FrostCardTable.isPlatesDrawn())
        FrostCardTable.reportPlatesDrawn(true)
        assertTrue("再报一次要能再翻回来（#85 对照实验要反复翻转）", FrostCardTable.isPlatesDrawn())
    }

    // ------------------------------------------------------------- 4. 并发账

    /** 数值上限：gen × 100 必须留在 2^23 = 8388608 以下，理由见用例注释 */
    private val genLimit = 40_000

    /** 每一代写几块板：与录制页同时在场的注册板数同量级（见 FrostCardTable.SLOT_CAPACITY 那笔手算） */
    private val platesPerGen = 5

    /**
     * 双缓冲 + 一次 volatile 引用交换的全部意义：GL 线程读到的**每一格**都必须出自同一次写入事务。
     *
     * 形状与生产一致：写方先刷一次表头、再逐格写自己那一块板（一格一次事务），读方每帧整表拷贝。
     * 自证编码（全是整数，块内差值恒等）：第 gen 代第 s 格写
     * `left = gen*100 + s`、`top = left + 10`、`right = left + 20`、`bottom = left + 30`、
     * `radius = left + 40`、`alpha = 0.5`、`present = 1`；表头写 `rootLeft = gen*100 + 1`、
     * `rootTop = rootLeft + 1`、宽高恒 720×1600、底色恒 0.5。于是"块内四档差值 10/20/30/40"
     * 与"表头差 1 + 宽高色恒等"里任何一条不成立，就是读到了**跨代拼出来的半张卡**。
     *
     * ⚠ 上一版序号锁就是在这里被测红的（满载压测 3000 次成功读里 7 次 `radius − left = −60` 而不是 40），
     * 判据形态这次一个字都没换：把发布方式改坏——撤掉引用交换、让写方就地改那块已经发出去的数组，
     * 或者撤掉 FrostCardTable 里护住缓冲回收的那把锁——本用例必须再红一次。
     *
     * ⚠ 判据一律用**块内差值**、且 gen 上限压在 [genLimit]（⇒ 数值 ≤ 4,000,007 < 2^23）：
     * Float32 只有 24 位尾数，越过 2^23 之后奇数会被舍成偶数，届时 `right − left` 算出来是 4 或 6，
     * 报的「撕裂」是浮点精度而不是被测代码。本用例第一版就是这么踩到的（gen 跑到 12 万，
     * 3000 次成功读里报出 42 次假撕裂），所以把上限与判据形态一起写死在这里。
     *
     * ⚠ **写方必须是一枚专用线程，而且「抢槽 + 每一笔写」都在它上面跑**：表类的前提①（单写方）现在有
     * 代码在执行，测试线程只要先写过一笔就成了登记在册的首写方，写方线程一到就被判违规并停用整张表
     * ⇒ 读方从此只读到空表（那是本用例自己违反契约，不是被测代码错）。所以这里第一件事是
     * [FrostCardTable.resetForTests]（清掉 setUp 那笔登记），第二件事是让写方线程自己发布第 0 代、
     * 用闩告诉读方「表里已经有自证编码了」。前提①被违反会怎样，另有用例守着（见第 5 节）。
     *
     * 另：这里**不**要求"所有卡片同代"。表头与每一格各是一次事务，写方逐格刷新时读到
     * "新表头 + 旧格子"是设计允许的一帧延迟（引用交换保证的是不撕裂，不是同一次布局）；
     * 但「每格自己内部必须整块同代」是硬要求——画板拿的就是那一块里的 left/radius 组合。
     */
    @Test
    fun `GL 线程读不到半张卡`() {
        FrostCardTable.resetForTests()   // 清掉 setUp 在测试线程上登记的首写方
        val stop = AtomicBoolean(false)
        val maxGenSeen = AtomicInteger(0)
        val tornFirst = AtomicReference<String?>(null)
        val tornCount = AtomicInteger(0)
        val writerFailure = AtomicReference<Throwable?>(null)
        val ready = CountDownLatch(1)
        val writer = Thread {
            try {
                val slots = IntArray(platesPerGen) { FrostCardTable.acquireSlot() }
                assertTrue("写方抢槽必须成功：${slots.toList()}", slots.all { it >= 0 })
                var gen = 0
                while (!stop.get() && gen <= genLimit) {
                    writeGen(gen, slots)
                    if (gen == 0) ready.countDown()
                    maxGenSeen.set(gen)
                    gen++
                }
            } catch (t: Throwable) {
                writerFailure.set(t)
            } finally {
                ready.countDown()   // 写方起跑失败也不许把读方钉死在 await 上
            }
        }.apply { isDaemon = true; name = "frost-table-writer"; start() }

        assertTrue("写方线程没能发布第 0 代", ready.await(5L, TimeUnit.SECONDS))
        writerFailure.get()?.let { throw AssertionError("写方线程抛了：$it") }

        val copy = FloatArray(TABLE_FLOATS)
        var successfulReads = 0
        var emptyReads = 0
        var attempts = 0
        val gensSeen = HashSet<Int>()
        try {
            // 按「读到的代数 + 读成功次数」收口而不是按尝试次数：只按尝试次数的话，
            // 成功样本量就成了机器速度的函数（慢机器上可能一辈子只读到一两代）
            while ((successfulReads < 20_000 || gensSeen.size < 150) && attempts < 30_000_000) {
                attempts++
                val n = FrostCardTable.tryReadInto(copy)
                assertTrue(
                    "换双缓冲之后读表不再有失败分支（-1 只剩「副本数组太短」一种成因，而副本是定长预分配的）：$n",
                    n >= 0
                )
                successfulReads++
                if (n == 0) emptyReads++
                assertTrue("返回的卡片数不可能超过容量：$n", n in 0..SLOT_CAPACITY)
                assertEquals("写方每一代都把 $platesPerGen 块板写满，读到别的数说明压实或在场位错了：$n", platesPerGen, n)
                // 表头：rootTop 恒比 rootLeft 大 1，宽高与三色恒为常量 ⇒ 半次表头写入必然对不上
                if (copy[HEADER_ROOT_TOP] - copy[HEADER_ROOT_LEFT] != 1f) noteTorn(tornFirst, tornCount, "表头 rootTop−rootLeft = ${copy[HEADER_ROOT_TOP] - copy[HEADER_ROOT_LEFT]}（应为 1）")
                if (copy[HEADER_ROOT_WIDTH] != 720f || copy[HEADER_ROOT_HEIGHT] != 1600f) noteTorn(tornFirst, tornCount, "表头宽高变了：${copy[HEADER_ROOT_WIDTH]} × ${copy[HEADER_ROOT_HEIGHT]}")
                if (copy[HEADER_TINT_RED] != 0.5f || copy[HEADER_TINT_GREEN] != 0.5f || copy[HEADER_TINT_BLUE] != 0.5f) noteTorn(tornFirst, tornCount, "表头底色变了：${copy[HEADER_TINT_RED]},${copy[HEADER_TINT_GREEN]},${copy[HEADER_TINT_BLUE]}")
                if (copy[HEADER_UI_ENABLED] != 1f) noteTorn(tornFirst, tornCount, "表头开关位不是 1：${copy[HEADER_UI_ENABLED]}（整张表被停用才会清 0）")
                for (i in 0 until n) {
                    val base = HEADER_FLOATS + i * SLOT_FLOATS
                    val left = copy[base + CARD_LEFT]
                    // 块内四档差值：任何一格被撕成两代，差值必然跳出 10/20/30/40
                    if (copy[base + CARD_TOP] - left != 10f) noteTorn(tornFirst, tornCount, "卡片[$i] top−left = ${copy[base + CARD_TOP] - left}（应为 10，left=$left）")
                    if (copy[base + CARD_RIGHT] - left != 20f) noteTorn(tornFirst, tornCount, "卡片[$i] right−left = ${copy[base + CARD_RIGHT] - left}（应为 20，left=$left）")
                    if (copy[base + CARD_BOTTOM] - left != 30f) noteTorn(tornFirst, tornCount, "卡片[$i] bottom−left = ${copy[base + CARD_BOTTOM] - left}（应为 30，left=$left）")
                    if (copy[base + CARD_RADIUS] - left != 40f) noteTorn(tornFirst, tornCount, "卡片[$i] radius−left = ${copy[base + CARD_RADIUS] - left}（应为 40，left=$left）")
                    if (copy[base + CARD_ALPHA] != 0.5f) noteTorn(tornFirst, tornCount, "卡片[$i] 的 alpha 被撕：${copy[base + CARD_ALPHA]}")
                    if (copy[base + CARD_PRESENT] != 1f) noteTorn(tornFirst, tornCount, "卡片[$i] 在场位不是 1：${copy[base + CARD_PRESENT]}")
                    // left = gen*100 + s ⇒ 用 Int 除法取代号，不再碰 Float 除法
                    gensSeen.add(left.toInt() / 100)
                }
            }
        } finally {
            // 断言失败也必须把写方收干净：单例表被后台线程继续改写的话，后面每一条用例都会被污染
            stop.set(true)
            writer.join(5_000L)
        }

        // 活性：证明这次真的读写并发过，而不是"没人写、读方当然不撕裂"的空转绿
        assertTrue("写入方只跑到第 ${maxGenSeen.get()} 代，撞车窗口没打开", maxGenSeen.get() >= 20)
        assertTrue("成功读表只有 $successfulReads 次（尝试 $attempts 次），样本太少", successfulReads >= 1_000)
        assertTrue("只读到 ${gensSeen.size} 代快照，读方八成全程没和写方重叠", gensSeen.size >= 8)
        assertEquals(
            "读到空表 $emptyReads 次：整张表被停用（多半是本用例自己违反了单写方前提）才会这样",
            0,
            emptyReads
        )
        assertEquals("读到撕裂组合 ${tornCount.get()} 次，首次：${tornFirst.get()}", 0, tornCount.get())
    }

    /** 写方一整代：表头一次事务 + 每格一次事务（与注册点"一格一次事务"的口径一致） */
    private fun writeGen(gen: Int, slots: IntArray) {
        val rootLeft = gen * 100f + 1f
        FrostCardTable.writeHeader(
            rootLeftPx = rootLeft,
            rootTopPx = rootLeft + 1f,
            rootWidthPx = 720f,
            rootHeightPx = 1600f,
            tintRed = 0.5f, tintGreen = 0.5f, tintBlue = 0.5f,
            uiEnabled = true,
            cardSpace = true
        )
        // 一次表头事务 + 逐格卡片事务 = 注册点（`ui/design/hudFrostRectRegistrar`）真实形状：
        // 先 refreshHeader()，再 writeCard 自己那一格。
        for (s in slots.indices) {
            val left = gen * 100f + s
            FrostCardTable.writeCard(
                slot = slots[s],
                leftPx = left,
                topPx = left + 10f,
                rightPx = left + 20f,
                bottomPx = left + 30f,
                radiusPx = left + 40f,
                alpha = 0.5f
            )
        }
    }

    private fun noteTorn(first: AtomicReference<String?>, counter: AtomicInteger, reason: String) {
        counter.incrementAndGet()
        first.compareAndSet(null, reason)
    }

    // ------------------------------------------------------------- 5. 线程契约账

    @Test
    fun `同一条线程反复写不该停用整张表`() {
        FrostCardTable.resetForTests()
        val slot = FrostCardTable.acquireSlot()
        assertTrue(slot >= 0)
        repeat(500) {
            header(uiEnabled = it % 2 == 0)
            assertTrue(FrostCardTable.writeCard(slot, 10f, 20f, 120f, 80f, 14f, 0.4f))
        }
        assertFalse("单写方是本表的契约，同一枚线程写不该触发停用", FrostCardTable.isDisabledForDiagnostics())
        header(uiEnabled = true)   // 上面那圈把开关位翻成了交替值，这里回到 true 再问那一位
        assertEquals(1, FrostCardTable.tryReadInto(out))
        assertTrue(FrostCardTable.hasUiEnabled())
    }

    @Test
    fun `换了线程写表必须停用整张表而不是静默继续`() {
        // 前提①从注释变成代码。上一版的形状是"两枚线程同写 ⇒ 实测 332/3000 次读到半张表"，
        // 双缓冲把那个具体的洞堵上了，但"表体是两块可复用数组在换手"仍然只在单写方下成立。
        // 违规的处理口径与画板/离屏链同族：记原因 + 停用 + 退化成"没有毛玻璃"，不抛
        //（为一个观感特性把主线程写崩是不可接受的，但静默继续更不可接受）。
        FrostCardTable.resetForTests()
        val pinnedWriter = AtomicReference(-1L)
        val secondWriter = AtomicReference(-1L)
        Thread {
            pinnedWriter.set(Thread.currentThread().id)
            header()
            val s = FrostCardTable.acquireSlot()
            assertTrue(FrostCardTable.writeCard(s, 10f, 20f, 120f, 80f, 14f, 0.4f))
        }.apply { start(); join(5_000L) }
        assertFalse("首写方不该触发停用", FrostCardTable.isDisabledForDiagnostics())
        assertEquals(1, FrostCardTable.tryReadInto(out))

        Thread {
            secondWriter.set(Thread.currentThread().id)
            FrostCardTable.setUiEnabled(false)   // 换一枚线程来写：哪怕只改开关那一位也算
        }.apply { start(); join(5_000L) }

        assertTrue(
            "第二枚写方线程（tid=${secondWriter.get()}）到达后必须停用整张表；首写方 tid=${pinnedWriter.get()}、" +
                "登记值 ${FrostCardTable.writerThreadIdForDiagnostics()}",
            FrostCardTable.isDisabledForDiagnostics()
        )
        assertNotNull("停用必须留下原因，不许静默", FrostCardTable.disabledReasonForDiagnostics())
        val copy = FloatArray(TABLE_FLOATS)
        assertEquals("停用后读方只能看到空表（一块板都不该再画）", 0, FrostCardTable.tryReadInto(copy))
        assertFalse(
            "停用后开关意图必须是 false（GL 因此撤快照、UI 把旧的纯色 fill 画回来）",
            FrostCardTable.hasUiEnabled()
        )
        assertEquals("停用后不再发槽", -1, FrostCardTable.acquireSlot())
        assertFalse("停用后写卡片一律拒绝", FrostCardTable.writeCard(0, 1f, 1f, 9f, 9f, 0f, 0.5f))
        FrostCardTable.writeHeader(1f, 2f, 720f, 1600f, 0f, 0f, 0f, true, true)
        assertFalse("停用后写表头也拒绝（开关不许自己又翻回 true）", FrostCardTable.hasUiEnabled())
        assertEquals("占用数必须停在停用那一刻的账上，不许继续涨", 1, FrostCardTable.usedSlotCount())
    }

    @Test
    fun `画板读表不许再用裸下标`() {
        // #84 步骤 2 的那处隐患：FrostPlatePass.draw 读卡片块用 CARD_* 常量、读表头却用 table[0]…table[6]，
        // 表头字段一增，裸下标就整体错位一格（拿到"根高"当底色用），且一声不响。
        // 画板要 GL 上下文，JVM 里跑不起来（本工程没开 returnDefaultValues），所以这条按源码扫：
        // 常量值由上面那条冻结用例钉住，"读表只用常量"由这条钉住。
        val src = KotlinSourceScan.mainSourceText("camera/FrostBlurChain.kt")
        val masked = KotlinSourceScan.codeOnly(src)
        // 正向锚点（缺了锚点的否定断言就是空转）：表头与卡片两侧都必须真的在按常量取数
        assertTrue("画板已经不按 HEADER_* 常量读表头了（锚点丢失）", masked.contains("table[FrostCardTable.HEADER_ROOT_LEFT]"))
        assertTrue("画板已经不按 CARD_* 常量读卡片了（锚点丢失）", masked.contains("table[base + FrostCardTable.CARD_LEFT]"))
        // 卡片坐标系位必须按常量读：写死一边（恒 0 或恒 1）就会拿错口径的原点去画板，板整体错一圈
        assertTrue(
            "画板必须按 table[FrostCardTable.HEADER_CARD_SPACE] 区分卡片坐标系（写死就退化成旧缺陷）",
            masked.contains("table[FrostCardTable.HEADER_CARD_SPACE]")
        )
        val raw = Regex("table\\[\\s*\\d").findAll(masked).map { it.value }.toList()
        assertTrue("画板里出现了裸下标读表（表头一加字段就静默错位）：$raw", raw.isEmpty())
    }

    @Test
    fun `测试专用的清账入口不许出现在生产代码里`() {
        // resetForTests 会把「首写方线程」的登记清掉，等于绕过前提①——它只能活在单测里。
        // 这条守卫让那个后门一旦被接进 main 就变红，而不是靠人记得。
        val root = KotlinSourceScan.mainSourceFile("camera/FrostBlur.kt").parentFile.parentFile
        val ktFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("一个 main 源码文件都没扫到，守卫在空转（$ktFiles.size）", ktFiles.size > 20)
        val hits = ktFiles.filter { KotlinSourceScan.codeOnly(it.readText(Charsets.UTF_8)).contains(".resetForTests(") }
        assertTrue("main 里出现了 resetForTests 调用：${hits.map { it.name }}", hits.isEmpty())
    }
}

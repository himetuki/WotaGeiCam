package com.wotagei.cam

import com.wotagei.cam.camera.FrostCardTable
import com.wotagei.cam.camera.FrostCardTable.CARD_ALPHA
import com.wotagei.cam.camera.FrostCardTable.CARD_BOTTOM
import com.wotagei.cam.camera.FrostCardTable.CARD_LEFT
import com.wotagei.cam.camera.FrostCardTable.CARD_PRESENT
import com.wotagei.cam.camera.FrostCardTable.CARD_RADIUS
import com.wotagei.cam.camera.FrostCardTable.CARD_RIGHT
import com.wotagei.cam.camera.FrostCardTable.CARD_TOP
import com.wotagei.cam.camera.FrostCardTable.HEADER_FLOATS
import com.wotagei.cam.camera.FrostCardTable.SLOT_CAPACITY
import com.wotagei.cam.camera.FrostCardTable.SLOT_FLOATS
import com.wotagei.cam.camera.FrostCardTable.TABLE_FLOATS
import com.wotagei.cam.camera.frostUvInto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * [FrostCardTable] 的行为测试（#84 步骤 2 · A2 混合路线里 `ui/` 与 `camera/` 之间**唯一**的接口）。
 *
 * A2 下 `ui/` 交给 GL 的只有这张表：一枚表头 + 至多 [SLOT_CAPACITY] 块卡片矩形。它错了不会崩、
 * 不会报错，只会让屏幕上的板**贴错位置、贴慢一帧、或多贴一块已经不存在的板**——全是只能上机、
 * 且很难归因的故障。所以这里把四类账逐条钉住：
 * 1. **槽位账**：发满必须给 -1（满了不许静默复用别人的格子，注册点拿它判"保持旧观感"）、
 *    还回来的槽必须可复用、且**还槽必须清掉在场位**（不清就是给 GL 留一块幽灵板）；
 * 2. **退化账**：四边反序/零面积的写入必须当"这帧没有这块板"，与 [frostUvInto] 的退化口径同一条；
 * 3. **布局账**：[FrostCardTable.tryReadInto] 读到的必须是「表头 8 个 float + **压实后**的卡片块」。
 *    压实偏移算错，GL 就会拿第 3 格的矩形去画第 1 块板，而 JVM 之外没人知道；
 * 4. **并发账**：GL 线程读、UI 线程写，读方一次拿到的**每一格**都必须出自同一次写入事务
 *    （序号锁的全部意义就在这条，见表类的「不撕裂」一节）。
 *
 * ⚠ 这张表是 `object` 单例，用例之间必然共享状态，所以 [setUp] / [tearDown] 把所有槽位收回空闲态、
 * 清掉在场位、撤回报；用例一律**不假设**自己拿到的是几号槽（只假设"两两不同"和"在 0 until 容量内"）。
 */
class FrostCardTableTest {

    private val out = FloatArray(TABLE_FLOATS)

    /** 表头八项各写一个互不相同的值：读出来逐位对得上，才算"偏移常量与写入顺序没分叉" */
    private fun header(uiEnabled: Boolean = true) = FrostCardTable.writeHeader(
        rootLeftPx = 11f, rootTopPx = 22f, rootWidthPx = 33f, rootHeightPx = 44f,
        tintRed = 0.5f, tintGreen = 0.25f, tintBlue = 0.125f, uiEnabled = uiEnabled
    )

    private fun slotOffset(slot: Int) = HEADER_FLOATS + slot * SLOT_FLOATS

    @Before
    fun setUp() {
        for (slot in 0 until SLOT_CAPACITY) FrostCardTable.releaseSlot(slot)
        FrostCardTable.reportPlatesDrawn(false)
        header()
        out.fill(Float.NaN)
    }

    @After
    fun tearDown() {
        for (slot in 0 until SLOT_CAPACITY) FrostCardTable.releaseSlot(slot)
        FrostCardTable.reportPlatesDrawn(false)
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
        // 表头恒在 out[0 until 8]
        assertEquals(11f, out[0], 0f)
        assertEquals(22f, out[1], 0f)
        assertEquals(33f, out[2], 0f)
        assertEquals(44f, out[3], 0f)
        assertEquals(0.5f, out[4], 0f)
        assertEquals(0.25f, out[5], 0f)
        assertEquals(0.125f, out[6], 0f)
        assertEquals(1f, out[7], 0f)
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
        // 反向证据：若按"槽位下标摆"，highSlot 的原地偏移上一定会留数据
        val highSlotOffset = slotOffset(highSlot)
        assertTrue("三枚互不相同的槽位里最大的那个必然不等于压实位：$highSlot", highSlotOffset != secondBase)
        assertEquals("第 2 块必须压到偏移 $secondBase，而不是留在槽位偏移 $highSlotOffset", 0f, out[highSlotOffset], 0f)
        // 被跳过的那格既不该出现在压实结果里，也不该在原地留一块在场板
        assertEquals(0f, out[slotOffset(midSlot) + CARD_PRESENT], 0f)
        // 卡片块之后不许有多余数据：读到的 float 数恰好 = 表头 + 2 块
        assertEquals(0f, out[HEADER_FLOATS + 2 * SLOT_FLOATS], 0f)
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

    /**
     * 序号锁的全部意义：GL 线程读到的**每一格**都必须出自同一次写入事务。
     *
     * 自证编码（全是整数，块内差值恒等）：第 gen 代第 s 格写
     * `left = gen*100 + s`、`top = left + 10`、`right = left + 20`、`bottom = left + 30`、
     * `radius = left + 40`、`alpha = 0.5`、`present = 1`；表头写 `rootLeft = gen*100 + 1`、
     * `rootTop = rootLeft + 1`、宽高恒 720×1600、底色恒 0.5。于是"块内四档差值 10/20/30/40"
     * 与"表头差 1 + 宽高色恒等"里任何一条不成立，就是读到了半张卡。
     *
     * ⚠ 判据一律用**块内差值**、且 gen 上限压在 [genLimit]（⇒ 数值 ≤ 4,000,007 < 2^23）：
     * Float32 只有 24 位尾数，越过 2^23 之后奇数会被舍成偶数，届时 `right − left` 算出来是 4 或 6，
     * 报的"撕裂"是浮点精度而不是被测代码。本用例第一版就是这么踩到的（gen 跑到 12 万，
     * 3000 次成功读里报出 42 次假撕裂），所以把上限与判据形态一起写死在这里。
     *
     * ⚠ **只许一枚写方线程**：表类注释写明写方是 `ui/` 主线程独占。这里先试过两枚，读方立刻
     * 稳定读到半张表头（332/3000 次）——因为 `beginWrite` 的 `epoch + 1` 是读-改-写而不是 CAS，
     * 两枚线程并发写时先写完的那枚会把 epoch 翻成偶数、而另一枚还在写 ⇒ 序号锁当场失效。
     * 那不是被测代码越界运行出的 bug，而是用例违反了契约；契约本身在 main 里只是
     * "谁在什么线程上写"的一张表、没写成硬约束，这条已回传给上游。
     *
     * 另：这里**不**要求"所有卡片同代"。表头与每一格各是一次事务，写方逐格刷新时读到
     * "新表头 + 旧格子"是设计允许的一帧延迟（表类注释：epoch 保证的是不撕裂，不是同一次布局）。
     */
    @Test
    fun `GL 线程读不到半张卡`() {
        val stop = AtomicBoolean(false)
        val maxGenSeen = AtomicInteger(0)
        val tornFirst = AtomicReference<String?>(null)
        val tornCount = AtomicInteger(0)
        // 先把表拉进"第 0 代"：写方还没起跑时读到的必须是同一套自证编码，
        // 否则 setUp 那枚普通表头会被差值判据当成撕裂（那是测试自己的错，不是被测代码的错）
        writeGen(0)
        val writer = Thread {
            var gen = 1
            while (!stop.get() && gen <= genLimit) {
                writeGen(gen)
                maxGenSeen.set(gen)
                gen++
            }
        }.apply { isDaemon = true; start() }

        val copy = FloatArray(TABLE_FLOATS)
        var successfulReads = 0
        var rejectedReads = 0
        var attempts = 0
        val gensSeen = HashSet<Int>()
        try {
            // 按"读成功次数"收口而不是按尝试次数：写方全速跑时相当一部分尝试会被序号锁挡掉
            // （返回值 -1 = 设计要的"慢一帧不脏"），只按尝试次数的话成功样本量就成了机器速度的函数
            while ((successfulReads < 2_000 || rejectedReads < 20) && attempts < 3_000_000) {
                attempts++
                val n = FrostCardTable.tryReadInto(copy)
                if (n < 0) {
                    // 撞车退路：-1 = 沿用上一帧副本，本来就是设计要的"慢一帧不脏"
                    rejectedReads++
                    continue
                }
                successfulReads++
                assertTrue("返回的卡片数不可能超过容量：$n", n in 0..SLOT_CAPACITY)
                // 表头：rootTop 恒比 rootLeft 大 1，宽高与三色恒为常量 ⇒ 半次表头写入必然对不上
                if (copy[1] - copy[0] != 1f) noteTorn(tornFirst, tornCount, "表头 rootTop−rootLeft = ${copy[1] - copy[0]}（应为 1）")
                if (copy[2] != 720f || copy[3] != 1600f) noteTorn(tornFirst, tornCount, "表头宽高变了：${copy[2]} × ${copy[3]}")
                if (copy[4] != 0.5f || copy[5] != 0.5f || copy[6] != 0.5f) noteTorn(tornFirst, tornCount, "表头底色变了：${copy[4]},${copy[5]},${copy[6]}")
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
                    // left = gen*100 + slot ⇒ 用 Int 除法取代号，不再碰 Float 除法
                    gensSeen.add(left.toInt() / 100)
                }
            }
        } finally {
            // 断言失败也必须把写方收干净：单例表被后台线程继续改写的话，后面每一条用例都会被污染
            stop.set(true)
            writer.join(5_000L)
        }

        // 活性四条：证明这次真的读写并发过，而不是"没人写、读方当然不撕裂"的空转绿
        assertTrue("写入方只跑到第 ${maxGenSeen.get()} 代，撞车窗口没打开", maxGenSeen.get() >= 20)
        assertTrue("成功读表只有 $successfulReads 次（尝试 $attempts 次），样本太少", successfulReads >= 1_000)
        assertTrue("只读到 ${gensSeen.size} 代快照，读方八成全程没和写方重叠", gensSeen.size >= 5)
        // 一次都没撞车 = 序号锁的重试分支从未执行，那本用例只测到"没撕"、没测到"撞了会重读"
        assertTrue("重试 $rejectedReads 次 / 尝试 $attempts 次：读方全程没撞上写方，重试分支没被执行过", rejectedReads >= 1)
        assertEquals("读到撕裂组合 ${tornCount.get()} 次，首次：${tornFirst.get()}", 0, tornCount.get())
    }

    /** 写方一整代：表头一次事务 + 每格一次事务（与注册点"一格一次事务"的口径一致） */
    private fun writeGen(gen: Int) {
        val rootLeft = gen * 100f + 1f
        FrostCardTable.writeHeader(
            rootLeftPx = rootLeft,
            rootTopPx = rootLeft + 1f,
            rootWidthPx = 720f,
            rootHeightPx = 1600f,
            tintRed = 0.5f, tintGreen = 0.5f, tintBlue = 0.5f,
            uiEnabled = gen % 2 == 0
        )
        // 一次表头事务 + 一格卡片事务 = 注册点（`ui/design/hudFrostRectRegistrar`）真实形状：
        // 先 refreshHeader()，再 writeCard 自己那一格。压到 8 格会把撞车密度抬到一个量级以上，
        // 那时能稳定复现"混合读"（见本文件注释与 #84 步骤 2 的验收报告），已另案上报，不在这里当常驻红灯。
        val left = gen * 100f
        FrostCardTable.writeCard(
            slot = 0,
            leftPx = left,
            topPx = left + 10f,
            rightPx = left + 20f,
            bottomPx = left + 30f,
            radiusPx = left + 40f,
            alpha = 0.5f
        )
    }

    private fun noteTorn(first: AtomicReference<String?>, counter: AtomicInteger, reason: String) {
        counter.incrementAndGet()
        first.compareAndSet(null, reason)
    }
}

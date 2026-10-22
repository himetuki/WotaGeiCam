package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.core.ArcMergeDeadband
import com.wotagei.cam.record.ArcRepairMerge
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.mainSourceText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 补弧合并的**整像素选择 + 差分死区**对账（2026-10-17 缺陷修复后的唯一口径）。
 *
 * 旧前提已废除：CPU 按 YUV 逐平面取大 / GPU 按 RGB 逐通道取大，曾被解释为"同语义、不同
 * 颜色空间"，实机像素验证证明它会**造色**——极橙 (255,140,0) 与纯黑合并得到珊瑚粉
 * (255,109,158)，蓝通道凭空 +158；冰蓝变淡紫、橙叠偏蓝背景变品红。这不是口径差异，是缺陷。
 *
 * 新规则（CPU/GPU 同一条）：
 * `Δluma = luma(b) − luma(a)`；`Δluma > D` 时**整颗像素**取被抽帧 `b`，否则保持保留帧 `a`。
 * - CPU（YUV 4:2:0）：以 2×2 块为单位，用块内 4 个 Y 的最大 Δ 判定，命中则 4Y+U+V 整块
 *   一起取自 `b`；绝不 Y 逐点选、U/V 按块选（跨帧拼合会重新造色）。
 * - GPU（RGB）：片元里按 luma 差整 vec3 选择，`ARC_MERGE_FS` 的 `alpha=1.0` 是载重不变量。
 *
 * 判据遵循 AGENTS 铁律：颜色用例**不近似**，输出逐通道必须严格等于某一输入帧；并配两处
 * "尺子能红"突变（逐平面取大造色、死区改 0 破坏不叠加）。GPU 侧另有生产 shader 源扫描，
 * 把"整像素选择/luma 差/alpha=1.0/禁 max-min-mix"钉在真身上。
 */
class ArcRepairMergeParityTest {

    private data class Rgb(val r: Int, val g: Int, val b: Int)

    private data class Yuv(val y: Int, val u: Int, val v: Int)

    private data class CpuOut(val yuv: Yuv, val rgb: Rgb)

    /** 8bit 有符号 ByteArray 取无符号值 */
    private fun ByteArray.u(i: Int): Int = this[i].toInt() and 0xFF

    /** BT.601 全范围 RGB→YUV（只用于把用户给的 RGB 颜色送进 CPU 的 YUV 域） */
    private fun rgbToYuv(c: Rgb): Yuv {
        val r = c.r.toDouble()
        val g = c.g.toDouble()
        val b = c.b.toDouble()
        return Yuv(
            (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255),
            (-0.168736 * r - 0.331264 * g + 0.5 * b + 128.0).roundToInt().coerceIn(0, 255),
            (0.5 * r - 0.418688 * g - 0.081312 * b + 128.0).roundToInt().coerceIn(0, 255)
        )
    }

    /** BT.601 全范围 YUV→RGB（相对上一行同系数；珊瑚粉 (255,109,158) 的手算就靠它） */
    private fun yuvToRgb(p: Yuv): Rgb {
        val y = p.y.toDouble()
        val u = p.u - 128.0
        val v = p.v - 128.0
        return Rgb(
            (y + 1.402 * v).roundToInt().coerceIn(0, 255),
            (y - 0.344136 * u - 0.714136 * v).roundToInt().coerceIn(0, 255),
            (y + 1.772 * u).roundToInt().coerceIn(0, 255)
        )
    }

    /**
     * GPU 口径离线复刻：RGB 整像素选择 + luma 差分死区（与 `ARC_MERGE_FS` 同式）。
     * 这里 luma 用 0..255 量纲，所以直接与 D 比；shader 里两者都归一到 0..1，不等式等价。
     */
    private fun gpuSelect(a: Rgb, b: Rgb, deadband: Int): Rgb {
        val la = 0.299 * a.r + 0.587 * a.g + 0.114 * a.b
        val lb = 0.299 * b.r + 0.587 * b.g + 0.114 * b.b
        return if (lb - la > ArcMergeDeadband.clamp(deadband)) b else a
    }

    /**
     * CPU 口径**生产算子**：把一枚颜色铺满 2×2 块，调用 [ArcRepairMerge.selectInPlace]。
     * 返回选择后的 YUV 原值与还原 RGB（用来同时钉"YUV 逐通道严格相等"与"视觉效果不造色"）。
     */
    private fun cpuSelect(a: Rgb, b: Rgb, deadband: Int): CpuOut {
        val ya = rgbToYuv(a)
        val yb = rgbToYuv(b)
        val aY = ByteArray(4) { ya.y.toByte() }
        val bY = ByteArray(4) { yb.y.toByte() }
        val aU = byteArrayOf(ya.u.toByte())
        val bU = byteArrayOf(yb.u.toByte())
        val aV = byteArrayOf(ya.v.toByte())
        val bV = byteArrayOf(yb.v.toByte())
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, deadband)
        val out = Yuv(aY.u(0), aU.u(0), aV.u(0))
        return CpuOut(out, yuvToRgb(out))
    }

    /** 旧缺陷算子（突变体，非生产路径）：Y/U/V 逐平面取大。只为证明造色断言对算子敏感。 */
    private fun mutatePerPlaneMax(a: Rgb, b: Rgb): Yuv {
        val ya = rgbToYuv(a)
        val yb = rgbToYuv(b)
        return Yuv(maxOf(ya.y, yb.y), maxOf(ya.u, yb.u), maxOf(ya.v, yb.v))
    }

    /** 用户点名的五组颜色（背景 a 在前、压上来的光 b 在后） */
    private val colorCases = listOf(
        "极橙" to (Rgb(0, 0, 0) to Rgb(255, 140, 0)),
        "冰蓝" to (Rgb(0, 0, 0) to Rgb(120, 220, 255)),
        "深蓝紫" to (Rgb(0, 0, 0) to Rgb(40, 40, 220)),
        "樱粉" to (Rgb(0, 0, 0) to Rgb(255, 180, 200)),
        "橙叠偏蓝背景" to (Rgb(20, 20, 90) to Rgb(255, 140, 0))
    )

    // region ① 不造色：输出逐通道严格等于某个输入帧

    @Test
    fun `不造色_五种颜色输出严格等于某个输入像素`() {
        for ((tag, pair) in colorCases) {
            val (a, b) = pair
            val ya = rgbToYuv(a)
            val yb = rgbToYuv(b)
            val cpu = cpuSelect(a, b, ArcMergeDeadband.DEFAULT)
            assertTrue(
                "$tag CPU YUV 必须严格等于 a 或 b 的原值，实际 CPU=${cpu.yuv} a=$ya b=$yb",
                cpu.yuv == ya || cpu.yuv == yb
            )
            assertTrue(
                "$tag CPU 还原 RGB 必须严格等于同一输入帧的还原值，实际 ${cpu.rgb}",
                cpu.rgb == yuvToRgb(ya) || cpu.rgb == yuvToRgb(yb)
            )
            val gpu = gpuSelect(a, b, ArcMergeDeadband.DEFAULT)
            assertTrue("$tag GPU RGB 必须严格等于 a 或 b，实际 $gpu", gpu == a || gpu == b)
        }
    }

    @Test
    fun `不造色_D为0仍只做整像素选择`() {
        for ((tag, pair) in colorCases) {
            val (a, b) = pair
            val cpu = cpuSelect(a, b, 0)
            assertTrue("$tag D=0 CPU 仍必须落在输入帧上", cpu.yuv == rgbToYuv(a) || cpu.yuv == rgbToYuv(b))
            val gpu = gpuSelect(a, b, 0)
            assertTrue("$tag D=0 GPU 仍必须落在输入帧上", gpu == a || gpu == b)
        }
    }

    @Test
    fun `尺子能红_逐平面取大把极橙与黑并成珊瑚粉`() {
        val a = Rgb(0, 0, 0)
        val b = Rgb(255, 140, 0)
        // 生产算子：必须等于某一输入帧（不造色）
        val good = cpuSelect(a, b, ArcMergeDeadband.DEFAULT)
        assertTrue(
            "生产选择式必须严格落在输入帧上，实际 ${good.yuv} / ${good.rgb}",
            good.yuv == rgbToYuv(a) || good.yuv == rgbToYuv(b)
        )
        // 突变回旧缺陷：三平面各取大，蓝通道凭空抬高
        val mutated = mutatePerPlaneMax(a, b)
        assertFalse(
            "逐平面取大必须造出输入帧没有的 YUV，实际 $mutated",
            mutated == rgbToYuv(a) || mutated == rgbToYuv(b)
        )
        assertEquals(
            "实机报告的颜色：极橙+纯黑 → (255,109,158) 珊瑚粉",
            Rgb(255, 109, 158), yuvToRgb(mutated)
        )
        assertFalse(
            "造色断言必须对突变体变红（珊瑚粉不等于黑或橙）",
            yuvToRgb(mutated) == a || yuvToRgb(mutated) == b
        )
    }

    @Test
    fun `尺子能红_GPU逐通道取大同样造色`() {
        val bg = Rgb(20, 20, 90)
        val arc = Rgb(255, 140, 0)
        val good = gpuSelect(bg, arc, ArcMergeDeadband.DEFAULT)
        assertTrue("生产选择式必须落在输入像素上", good == bg || good == arc)
        // 突变回旧 GPU 口径：RGB 逐通道取大 → (255,140,90)，既不是背景也不是橙
        val mutated = Rgb(maxOf(bg.r, arc.r), maxOf(bg.g, arc.g), maxOf(bg.b, arc.b))
        assertEquals("逐通道取大把偏蓝背景与橙拼出原片没有的中间色", Rgb(255, 140, 90), mutated)
        assertFalse("造色断言必须对 GPU 突变体变红", mutated == bg || mutated == arc)
    }

    // endregion

    @Test
    fun `死区档位钳在0到32`() {
        assertEquals(0, ArcMergeDeadband.clamp(-7))
        assertEquals(0, ArcMergeDeadband.clamp(0))
        assertEquals(12, ArcMergeDeadband.DEFAULT)
        assertEquals(32, ArcMergeDeadband.clamp(33))
    }

    // region ② 差分死区：≤D 不叠加，>D 才取被抽帧

    @Test
    fun `死区_亮度差不超过D保持保留帧_超过D才取被抽帧`() {
        val a = Rgb(100, 100, 100)
        val below = Rgb(108, 108, 108)   // Δ=8 ≤ 12
        val boundary = Rgb(112, 112, 112) // Δ=12 == D：判据是 >，必须保持 a
        val above = Rgb(120, 120, 120)   // Δ=20 > 12

        assertTrue("Δ<D GPU 必须原样保留 a", gpuSelect(a, below, 12) == a)
        assertEquals("Δ<D CPU 必须原样保留 a 的 YUV", rgbToYuv(a), cpuSelect(a, below, 12).yuv)
        assertTrue("Δ==D 必须保持 a（> 不是 >=）", gpuSelect(a, boundary, 12) == a)
        assertEquals("Δ==D CPU 必须原样保留 a", rgbToYuv(a), cpuSelect(a, boundary, 12).yuv)
        assertTrue("Δ>D GPU 必须整颗取被抽帧 b", gpuSelect(a, above, 12) == above)
        assertEquals("Δ>D CPU 必须整块取 b 的 YUV", rgbToYuv(above), cpuSelect(a, above, 12).yuv)
    }

    @Test
    fun `尺子能红_死区改0后不叠加断言立刻变红`() {
        val a = Rgb(100, 100, 100)
        val below = Rgb(108, 108, 108)   // Δ=8
        assertTrue("真档 D=12 输出保留帧 a", gpuSelect(a, below, 12) == a)
        assertTrue("突变 D=0 后 Δ=8>0，输出变成 b", gpuSelect(a, below, 0) == below)
        assertFalse("突变体必须破掉「相同亮度不叠加」断言", gpuSelect(a, below, 0) == a)
    }

    // endregion

    // region ③ 4:2:0 2×2 块整体性：Y 与 U/V 必须来自同一帧

    @Test
    fun `CPU_块内最大Δ过门时4Y与UV整块取自被抽帧`() {
        val aY = byteArrayOf(10, 10, 10, 10)
        val aU = byteArrayOf(50)
        val aV = byteArrayOf(60)
        // 只有第一颗 Y 的 Δ=90 过门，其余 3 颗 Δ=2；块决策取最大值 ⇒ 整块 6 个样本全取 b
        val bY = byteArrayOf(100, 12, 12, 12)
        val bU = byteArrayOf(200.toByte())
        val bV = byteArrayOf(210.toByte())
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12)
        assertArrayEquals("4 个 Y 必须整块取 b（Y 逐点选会让 3 颗留在 a）", bY, aY)
        assertArrayEquals("U 必须与 Y 同取自 b（拼合即造色）", bU, aU)
        assertArrayEquals("V 必须与 Y 同取自 b（拼合即造色）", bV, aV)
    }

    @Test
    fun `CPU_块内最大Δ不超D时整块保持保留帧`() {
        val aY = byteArrayOf(10, 10, 10, 10)
        val aU = byteArrayOf(50)
        val aV = byteArrayOf(60)
        val bY = byteArrayOf(20, 21, 20, 21) // 最大 Δ=11 ≤ 12
        val bU = byteArrayOf(200.toByte())
        val bV = byteArrayOf(210.toByte())
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12)
        assertArrayEquals(byteArrayOf(10, 10, 10, 10), aY)
        assertArrayEquals(byteArrayOf(50), aU)
        assertArrayEquals(byteArrayOf(60), aV)
    }

    @Test
    fun `CPU_无符号比较_10与200必须按200更大处理`() {
        val aY = byteArrayOf(10, 10, 10, 10)
        val bY = byteArrayOf(200.toByte(), 200.toByte(), 200.toByte(), 200.toByte())
        val aU = byteArrayOf(50)
        val aV = byteArrayOf(60)
        val bU = byteArrayOf(210.toByte())
        val bV = byteArrayOf(220.toByte())
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12)
        // signed 比较下 200.toByte()=-56，Δ=-66 永远不过门；去掉 and 0xFF 必红
        assertArrayEquals("无符号 Δ=190 必须过门并整块取 b", bY, aY)
        assertArrayEquals(bU, aU)
        assertArrayEquals(bV, aV)
    }

    @Test
    fun `CPU_块内最大Δ过门_平均值不过门时仍整块取被抽帧`() {
        val aY = byteArrayOf(10, 10, 10, 10)
        val aU = byteArrayOf(50)
        val aV = byteArrayOf(60)
        // Δ 分布 (20,0,0,0)：最大 20>12 过门，平均 5≤12 不过门 ⇒ 改成平均判定必红
        val bY = byteArrayOf(30, 10, 10, 10)
        val bU = byteArrayOf(210.toByte())
        val bV = byteArrayOf(220.toByte())
        ArcRepairMerge.selectInPlace(aY, aU, aV, bY, bU, bV, 2, 2, 12)
        assertArrayEquals("块内最大值才是判据（平均会把细弧吃掉）", bY, aY)
        assertArrayEquals(bU, aU)
        assertArrayEquals(bV, aV)
    }

    /** 独立参考：按 4:2:0 的 2×2 块逐块取 4 个 Y 的无符号最大 Δ，命中则整块搬运 */
    private fun referenceBlockSelect(
        aY: ByteArray, aU: ByteArray, aV: ByteArray,
        bY: ByteArray, bU: ByteArray, bV: ByteArray,
        width: Int, height: Int, deadband: Int
    ): Triple<ByteArray, ByteArray, ByteArray> {
        val y = aY.copyOf()
        val u = aU.copyOf()
        val v = aV.copyOf()
        val cw = width / 2
        val ch = height / 2
        for (by in 0 until ch) {
            for (bx in 0 until cw) {
                val idx = intArrayOf(bx * 2 + by * 2 * width, bx * 2 + 1 + by * 2 * width,
                    bx * 2 + (by * 2 + 1) * width, bx * 2 + 1 + (by * 2 + 1) * width)
                val delta = idx.maxOf { (bY[it].toInt() and 0xFF) - (aY[it].toInt() and 0xFF) }
                if (delta > deadband) {
                    idx.forEach { y[it] = bY[it] }
                    val c = bx + by * cw
                    u[c] = bU[c]
                    v[c] = bV[c]
                }
            }
        }
        return Triple(y, u, v)
    }

    @Test
    fun `CPU_多块索引与独立参考逐块一致_4x2与2x4`() {
        // 4×2：横向 2 块（行跨度 = width），块 0 过门、块 1 不过门
        val aY42 = byteArrayOf(10, 10, 200.toByte(), 200.toByte(), 10, 10, 200.toByte(), 200.toByte())
        val bY42 = byteArrayOf(30, 30, 100, 100, 30, 30, 100, 100)
        val aU42 = byteArrayOf(50, 60)
        val bU42 = byteArrayOf(210.toByte(), 220.toByte())
        val aV42 = byteArrayOf(70, 80)
        val bV42 = byteArrayOf(230.toByte(), 240.toByte())
        val exp42 = referenceBlockSelect(aY42, aU42, aV42, bY42, bU42, bV42, 4, 2, 12)
        ArcRepairMerge.selectInPlace(aY42, aU42, aV42, bY42, bU42, bV42, 4, 2, 12)
        assertArrayEquals("4×2 的 Y 必须与逐块参考一致", exp42.first, aY42)
        assertArrayEquals("4×2 的 U 必须按块跟随决策", exp42.second, aU42)
        assertArrayEquals("4×2 的 V 必须按块跟随决策", exp42.third, aV42)

        // 2×4：纵向 2 块（块步进 = width*2），块 0 过门、块 1 不过门
        val aY24 = byteArrayOf(10, 10, 10, 10, 200.toByte(), 200.toByte(), 200.toByte(), 200.toByte())
        val bY24 = byteArrayOf(30, 30, 30, 30, 100, 100, 100, 100)
        val aU24 = byteArrayOf(50, 60)
        val bU24 = byteArrayOf(210.toByte(), 220.toByte())
        val aV24 = byteArrayOf(70, 80)
        val bV24 = byteArrayOf(230.toByte(), 240.toByte())
        val exp24 = referenceBlockSelect(aY24, aU24, aV24, bY24, bU24, bV24, 2, 4, 12)
        ArcRepairMerge.selectInPlace(aY24, aU24, aV24, bY24, bU24, bV24, 2, 4, 12)
        assertArrayEquals("2×4 的 Y 必须与逐块参考一致", exp24.first, aY24)
        assertArrayEquals("2×4 的 U 必须按块跟随决策", exp24.second, aU24)
        assertArrayEquals("2×4 的 V 必须按块跟随决策", exp24.third, aV24)
    }

    @Test
    fun `CPU_奇数宽高明确拒绝而不是静默截断`() {
        val y = ByteArray(3)
        val uv = ByteArray(1)
        assertThrows(IllegalArgumentException::class.java) {
            ArcRepairMerge.selectInPlace(y, uv, uv, y, uv, uv, 3, 1, 12)
        }
    }

    // endregion

    // region ④ GPU 真身：生产 shader 必须整像素选择 + luma 差 + alpha=1.0

    private val uaRgbSample = Regex("""vec3\s+a\s*=\s*texture2D\s*\(\s*uA\s*,\s*vUv\s*\)\s*\.rgb""")
    private val ubRgbSample = Regex("""vec3\s+b\s*=\s*texture2D\s*\(\s*uB\s*,\s*vUv\s*\)\s*\.rgb""")
    private val lumaA = Regex(
        """float\s+la\s*=\s*dot\s*\(\s*a\s*,\s*vec3\s*\(\s*0\.299\s*,\s*0\.587\s*,\s*0\.114\s*\)\s*\)"""
    )
    private val lumaB = Regex(
        """float\s+lb\s*=\s*dot\s*\(\s*b\s*,\s*vec3\s*\(\s*0\.299\s*,\s*0\.587\s*,\s*0\.114\s*\)\s*\)"""
    )
    private val selection = Regex("""\(\s*lb\s*-\s*la\s*\)\s*>\s*uDeadbandLuma\s*\?\s*b\s*:\s*a""")
    private val opaqueSelection = Regex(
        """vec4\s*\(\s*\(\s*lb\s*-\s*la\s*\)\s*>\s*uDeadbandLuma\s*\?\s*b\s*:\s*a\s*,\s*1\.0\s*\)"""
    )
    private val deadbandUniform = Regex("""uniform\s+float\s+uDeadbandLuma\s*;""")

    /** 旧路回潮 / 非整像素算子：一条都不许出现（容空白） */
    private val forbiddenMergeOps = listOf(
        Regex("""max\s*\(""") to "max(（逐通道取大/取亮）",
        Regex("""min\s*\(""") to "min(",
        Regex("""mix\s*\(""") to "mix(",
        Regex("""\*\s*0\.5""") to "* 0.5（半亮缩放）",
        Regex("""0\.5\s*\*""") to "0.5 *（半亮缩放）",
        Regex("""/\s*2(\.0)?\s*""") to "/2（半亮缩放）"
    )

    /**
     * `Shaders.ARC_MERGE_FS` 的生产字符串判据：两个输入 `.rgb` 采样、双 luma 差、
     * 三元选择 `(lb-la)>uDeadbandLuma ? b : a`、赋值整体 `vec4(…, 1.0)`（alpha 载重不变量）、
     * 声明 `uDeadbandLuma` uniform，且不含任何逐通道取大/取小/混合/半亮算子。
     * 返回违规清单（空 = 合规）。
     */
    private fun arcMergeFsViolations(shader: String): List<String> {
        val bad = ArrayList<String>()
        if (uaRgbSample.find(shader) == null) bad.add("uA 必须按 .rgb 采样（整 vec4 会把 alpha 带进选择）")
        if (ubRgbSample.find(shader) == null) bad.add("uB 必须按 .rgb 采样（整 vec4 会把 alpha 带进选择）")
        if (deadbandUniform.find(shader) == null) bad.add("必须声明 uniform float uDeadbandLuma")
        if (lumaA.find(shader) == null) bad.add("必须计算 float la = dot(a, 亮度权重)")
        if (lumaB.find(shader) == null) bad.add("必须计算 float lb = dot(b, 亮度权重)")
        if (selection.find(shader) == null) {
            bad.add("必须以 (lb - la) > uDeadbandLuma ? b : a 做整像素选择")
        }
        if (opaqueSelection.find(shader) == null) {
            bad.add(
                "必须强制不透明 vec4((lb-la)>uDeadbandLuma ? b : a, 1.0)：alpha=1.0 是载重不变量" +
                    "（同 PASS_THROUGH_FS 理由——HAL/解码器 alpha=0 会读出半透明）"
            )
        }
        forbiddenMergeOps.forEach { (re, label) ->
            if (re.containsMatchIn(shader)) {
                bad.add("ARC_MERGE_FS 出现旧/非整像素算子「$label」")
            }
        }
        return bad
    }

    @Test
    fun `真身ARC_MERGE_FS_整像素选择与差分死区`() {
        val bad = arcMergeFsViolations(Shaders.ARC_MERGE_FS)
        assertEquals("生产 ARC_MERGE_FS 违反整像素选择口径：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    @Test
    fun `尺子自己能红_ARC_MERGE_FS被改坏必报红`() {
        val good = Shaders.ARC_MERGE_FS
        assertEquals("良品必须先真的绿", 0, arcMergeFsViolations(good).size)
        val mutants = listOf(
            "选择式改回逐通道取大" to good.replace(selection, "max(a, b)"),
            "去掉死区（改 > 0.0）" to good.replace("> uDeadbandLuma", "> 0.0"),
            "命中时仍取 a" to good.replace("? b : a", "? a : a"),
            "alpha 由 1.0 改 0.0（透出 HAL/解码器的半透明）" to
                good.replace(opaqueSelection) { it.value.replace("1.0", "0.0") }
        )
        mutants.forEach { (name, mutated) ->
            assertTrue("突变锚点丢失：$name 未改动 shader 文本", mutated != good)
            assertTrue(
                "突变体「$name」必须报红，实际违规为空 ⇒ 源扫描对生产件不敏感",
                arcMergeFsViolations(mutated).isNotEmpty()
            )
        }
        // 反格式脆：等价的紧凑写法仍须全绿，避免生产合法改写误炸
        val compact = good
            .replace(lumaA, "float la=dot(a,vec3(0.299,0.587,0.114));")
            .replace(lumaB, "float lb=dot(b,vec3(0.299,0.587,0.114));")
            .replace(selection, "(lb-la)>uDeadbandLuma?b:a")
        assertTrue("紧凑写法必须真的改动了文本", compact != good)
        assertEquals(
            "容空白等价写法必须仍绿，实际：\n${arcMergeFsViolations(compact).joinToString("\n")}",
            0, arcMergeFsViolations(compact).size
        )
    }

    // endregion

    // region ⑤ 死区归一化与两路 uniform / a-b 实参顺序的源扫描

    @Test
    fun `死区归一化到0到1且在线与离线两路uniform都真下发`() {
        assertEquals("D=12 的 GLSL 归一值必须是 12/255", 12f / 255f, ArcMergeDeadband.luma(12), 0f)
        assertEquals("钳制后 D=32 ⇒ 32/255", 32f / 255f, ArcMergeDeadband.luma(99), 0f)
        assertEquals("D=0 ⇒ 0", 0f, ArcMergeDeadband.luma(-3), 0f)

        val uniform = Regex("""glUniform1f\s*\(\s*mergeUDeadband\s*,\s*deadbandLuma\s*\)""")
        val mend = codeOnly(mainSourceText("camera/ArcMendPass.kt"))
        val gl = codeOnly(mainSourceText("record/ArcRepairGl.kt"))
        assertTrue("在线 MEND 必须真下发 mergeUDeadband（删掉即回默认死区）", uniform.containsMatchIn(mend))
        assertTrue("离线修复 GL 必须真下发 mergeUDeadband", uniform.containsMatchIn(gl))
    }

    @Test
    fun `三处累积调用的ab实参顺序必须保持_a为保留累积面_b为新帧`() {
        // 选择式不对称：顺序反了会变成 select(cur, acc)（差分方向反转 = 回到更亮者胜）
        val mend = flatten(codeOnly(mainSourceText("camera/ArcMendPass.kt")))
        assertTrue(
            "ArcMendPass.accumulate 必须 mergeInto(a=acc, b=cur)",
            mend.contains("mergeInto(accTex[accIdx], curTex, accFbo[1 - accIdx])")
        )
        val gl = flatten(codeOnly(mainSourceText("record/ArcRepairGl.kt")))
        assertTrue(
            "ArcRepairGl.accumulate 必须 mergeOnGl(a=acc, b=cur)",
            gl.contains("mergeOnGl(accTex[accIdx], curTex, accFbo[1 - accIdx])")
        )
        val runner = flatten(codeOnly(mainSourceText("record/ArcRepairRunner.kt")))
        assertTrue(
            "execCpu 的累积必须 selectInto(a=acc, b=cur)",
            runner.contains("selectInto(accY, accU, accV, curY, curU, curV)")
        )
    }

    // endregion
}

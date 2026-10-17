package com.wotagei.cam

import com.wotagei.cam.camera.Shaders
import com.wotagei.cam.record.ArcRepairMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 补弧取大的**颜色域口径并列对账**：CPU = YUV 逐平面取大 / GPU = RGB 逐通道取大。
 *
 * 背景（2026-10-17 口径订正）：`Shaders.ARC_MERGE_FS` 的 KDoc 与 `ArcRepairPlan.kt` 的 v2 口径
 * 注释曾把 CPU/GPU 两条取大路径称作"同口径"，属**过度声称**。严格说两者**同语义、不同颜色
 * 空间**：
 * - CPU 路：`ArcRepairMerge.mergeMax` 按 **Y/U/V 逐平面**取大（补弧在解码后的 YUV 平面上做）；
 * - GPU 路：`ARC_MERGE_FS` 的 `max(a, b)` 按 **RGB 逐通道**取大（补弧在 GL 的 RGBA 面上做）。
 *
 * 取大算子对**对角**线性变换可交换、对**混通道**线性变换不可交换，而 YUV↔RGB 恰是混通道矩阵
 * ⇒"颜色域不同"确实会带来结果差异（不是措辞问题）。本测试把这条**非等价**事实钉成断言：防将来
 * 有人把注释改回"同口径"，也防有人按"两路等价"去合并两条实现。同时钉住"灰白弧下等值"这一半。
 *
 * 判据遵循 AGENTS 铁律——不测恒等式，测**计划→行为的桥**：桥就是下面两条"口径合并"纯函数
 * （[mergeInYuvThenRgb] = CPU 口径、[mergeInRgb] = GPU 口径），拿**同一份像素**喂两条桥再比。
 * 且必须有"尺子能红"的自证（[尺子能红_桥突变自证_取大改取小后灰白等价立刻失败]）与
 * "差异归因"的反证（[彩色差异源于色度_抹平UV后两口径又等价]），否则"等价/非等价"断言可能
 * 是 fixture 巧合或恒等式。
 *
 * **GPU 口径与真 shader 的连接（2026-10-17 审查订正）**：[mergeInRgb] 是 Kotlin **离线复刻**，
 * 与生产 `ARC_MERGE_FS` 没有任何连接——把 shader 的 `max` 改成 `min`/加权混合，本文件前三条断言
 * 全绿。故区域④另立**生产字符串守卫**：把 `Shaders.ARC_MERGE_FS` 的真值喂进纯判据函数
 * [arcMergeFsViolations]（逐分量 `max(a, b)` / `.rgb` 采样 / 不含 `min(`/`mix(`/`* 0.5`），
 * shader 被改坏即红，并用 mutate 过的文本做"尺子能红"自证。这样"GPU 口径"才真的钉在 shader 上。
 *
 * 说明：真实 CPU 路按整幅平面逐样本取大，本测试用**单像素模型**——`mergeMax` 逐样本独立、无
 * 跨格依赖（见其 KDoc），故一像素的等价性结论对整幅成立；本文件不重复测逐样本语义（那是
 * `ArcRepairPlanTest` 取大算子区的职责）。
 */
class ArcRepairMergeParityTest {

    // region 像素模型与两条口径的桥

    /** 8-bit YUV（本测试只做口径对账，平面分辨率无关紧要） */
    private data class Yuv(val y: Int, val u: Int, val v: Int)

    private data class Rgb(val r: Float, val g: Float, val b: Float)

    /**
     * BT.601（全范围）YUV→RGB，只用于"两口径是否等价"的对账。
     *
     * **等价性结论不依赖具体系数**（对角变换可交换、混通道矩阵不可交换是线性代数结论）；但正是
     * 这组系数把"中性色度 U=V=128 ⇒ R=G=B=Y"钉死，灰白弧等值即由此而来（偏移项 u−128、v−128
     * 归零，三通道都退化成 y）。
     */
    private fun toRgb(p: Yuv): Rgb {
        val y = p.y.toFloat()
        val u = p.u - 128f
        val v = p.v - 128f
        return Rgb(
            y + 1.402f * v,
            y - 0.344136f * u - 0.714136f * v,
            y + 1.772f * u
        )
    }

    /**
     * 桥 A（**CPU 口径**）：Y/U/V 逐平面取大，再还原成 RGB 好与桥 B 同域比较。
     *
     * 关键：逐平面取大**直接用生产算子** [ArcRepairMerge.mergeMax]（不是另写一份 max），于是
     * 该算子语义若被改动，本用例会跟着红。`toByte()` 后 >127 会变负，`mergeMax` 内部 `and 0xFF`
     * 按无符号比——顺带把它的无符号语义也走到。
     */
    private fun mergeInYuvThenRgb(a: Yuv, b: Yuv): Rgb {
        val ay = byteArrayOf(a.y.toByte()); val by = byteArrayOf(b.y.toByte())
        val au = byteArrayOf(a.u.toByte()); val bu = byteArrayOf(b.u.toByte())
        val av = byteArrayOf(a.v.toByte()); val bv = byteArrayOf(b.v.toByte())
        ArcRepairMerge.mergeMax(ay, by, 1)
        ArcRepairMerge.mergeMax(au, bu, 1)
        ArcRepairMerge.mergeMax(av, bv, 1)
        return toRgb(Yuv(ay[0].toInt() and 0xFF, au[0].toInt() and 0xFF, av[0].toInt() and 0xFF))
    }

    /**
     * 桥 B（**GPU 口径**）：先各自 YUV→RGB，再对 RGB 逐通道取大——复刻 `ARC_MERGE_FS` 的
     * `max(a, b)` 语义（GLSL 逐分量 max；JVM 无 GL 环境，按文档语义在本地等价复刻）。
     */
    private fun mergeInRgb(a: Yuv, b: Yuv): Rgb {
        val ca = toRgb(a)
        val cb = toRgb(b)
        return Rgb(maxOf(ca.r, cb.r), maxOf(ca.g, cb.g), maxOf(ca.b, cb.b))
    }

    /** 桥 A 的**突变版**（取小，非生产路径）：只为证明①的"等价"断言对算子敏感（尺子能红） */
    private fun mutateMinInYuvThenRgb(a: Yuv, b: Yuv): Rgb =
        toRgb(Yuv(minOf(a.y, b.y), minOf(a.u, b.u), minOf(a.v, b.v)))

    private fun assertRgbClose(tag: String, a: Rgb, b: Rgb) {
        assertTrue(
            "$tag 应等价，实际 CPU=$a GPU=$b",
            abs(a.r - b.r) <= 1e-3f && abs(a.g - b.g) <= 1e-3f && abs(a.b - b.b) <= 1e-3f
        )
    }

    // endregion

    // region ① 灰白弧（中性色度）下两条口径等价

    @Test
    fun `灰白弧_中性色度_两口径结果等价`() {
        // 灰白弧 = 暗场上的加性亮线：色度中性（U=V=128），只有亮度差
        val cases = listOf(
            Yuv(10, 128, 128) to Yuv(80, 128, 128),   // 弧盖过背景
            Yuv(60, 128, 128) to Yuv(60, 128, 128),   // 平坦区（并 = 恒等）
            Yuv(0, 128, 128) to Yuv(255, 128, 128),   // 极值
            Yuv(200, 128, 128) to Yuv(30, 128, 128)   // 反向（取大不依赖入参次序）
        )
        for ((a, b) in cases) {
            assertRgbClose("中性色度 U=V=128", mergeInYuvThenRgb(a, b), mergeInRgb(a, b))
        }
    }

    // endregion

    // region ② 彩色内容下两条口径不等价（把"非等价"钉成断言）

    @Test
    fun `彩色内容_两口径不等价_取大不可跨颜色空间交换`() {
        // A = 偏红（高 V）、B = 偏蓝（高 U），Y 相同。CPU 先把 U/V 各自拉到最大再转 RGB，
        // GPU 先转 RGB 再逐通道取大 ⇒ 绿通道（U/V 混合系数最大）先分叉。
        val a = Yuv(100, 128, 200)
        val b = Yuv(100, 200, 128)
        val cpu = mergeInYuvThenRgb(a, b)
        val gpu = mergeInRgb(a, b)
        // 本 fixture 下 G 通道差 ≈ 51（r/b 该例恰好同为 200.9/227.6，不构成判据）
        assertTrue("彩色内容下两口径必须可分辨（G 差 > 10），实际差 ${abs(cpu.g - gpu.g)}",
            abs(cpu.g - gpu.g) > 10f)
        // 至少一个通道明显不同 ⇒ "非等价"整体成立
        assertTrue("两口径至少一个通道不等价",
            abs(cpu.r - gpu.r) > 1e-3f || abs(cpu.g - gpu.g) > 1e-3f || abs(cpu.b - gpu.b) > 1e-3f)
    }

    @Test
    fun `彩色差异源于色度_抹平UV后两口径又等价`() {
        // 反证②的 fixture 不是巧合：同一对像素，只把色度抹成中性就立刻等价（差异确实来自
        // 颜色空间混通道，而不是 fixture 选错或算子有 bug）。
        val a = Yuv(100, 128, 200)
        val b = Yuv(100, 200, 128)
        assertTrue("原样两口径应有明显差异", abs(mergeInYuvThenRgb(a, b).g - mergeInRgb(a, b).g) > 10f)
        val neutralA = Yuv(a.y, 128, 128)
        val neutralB = Yuv(b.y, 128, 128)
        assertRgbClose("抹平色度后", mergeInYuvThenRgb(neutralA, neutralB), mergeInRgb(neutralA, neutralB))
    }

    // endregion

    // region ③ 尺子能红：突变自证（防止①/②变成恒等式或 fixture 巧合）

    @Test
    fun `尺子能红_桥突变自证_取大改取小后灰白等价立刻失败`() {
        // 把 CPU 桥的 max 换成 min 后，同一组中性色度输入的"等价"必须**不成立**——否则①
        // 对算子不敏感，将来把 mergeMax 语义改坏也会全绿（AGENTS 点名的"恒等式假证明"）。
        val a = Yuv(10, 128, 128)
        val b = Yuv(80, 128, 128)
        val gpu = mergeInRgb(a, b)
        val mutated = mutateMinInYuvThenRgb(a, b)
        assertTrue("取大被改成取小，灰白等价应立刻破裂；此处仍相等 ⇒ ① 的尺子失效",
            abs(mutated.g - gpu.g) > 1f)
        // 对照：真桥在同样输入下仍等价（防止本用例只证明了"突变版坏了"而真桥也坏）
        assertRgbClose("中性色度", mergeInYuvThenRgb(a, b), gpu)
    }

    // endregion

    // region ④ GPU 口径的真身：ARC_MERGE_FS 生产字符串必须逐分量取大

    /**
     * `Shaders.ARC_MERGE_FS` 的**生产字符串**判据：逐分量 `max(a, b)`、两个输入都按 `.rgb` 采样、
     * 且不含任何非取大算子（`min(` / `mix(` / `* 0.5`）。返回违规清单（空 = 合规）。
     *
     * 为什么必须补这条：上面的 [mergeInRgb] 是 Kotlin **离线复刻**，与真 shader 无任何连接
     * ——把 `ARC_MERGE_FS` 的 `max` 改成 `min`/加权混合，本文件前三条断言全绿（审查点名的"表述
     * 过头"）。这里把**生产常量**真值喂进来，shader 被改坏即红。
     *
     * 直接读生产常量而非切 `Shaders.kt` 源码文本：`ARC_MERGE_FS` 的值本身就是**着色器体**
     * （纯 GLSL，无 KDoc/注释混入的余地），比源码切片更紧（本仓 `ArcRepairGlSamplerGuardTest` 同法）。
     */
    private fun arcMergeFsViolations(shader: String): List<String> {
        val bad = ArrayList<String>()
        if (!shader.contains("vec3 a = texture2D(uA, vUv).rgb")) {
            bad.add("uA 必须按 .rgb 采样（缺 vec3 a = texture2D(uA, vUv).rgb）")
        }
        if (!shader.contains("vec3 b = texture2D(uB, vUv).rgb")) {
            bad.add("uB 必须按 .rgb 采样（缺 vec3 b = texture2D(uB, vUv).rgb）")
        }
        if (Regex("max\\s*\\(\\s*a\\s*,\\s*b\\s*\\)").find(shader) == null) {
            bad.add("必须逐分量取大 max(a, b)（GLSL 逐分量 max 即 RGB 逐通道取大）")
        }
        listOf("min(", "mix(", "* 0.5", "0.5 *").forEach { op ->
            if (shader.contains(op)) {
                bad.add("ARC_MERGE_FS 出现非取大算子「$op」（v1 的 0.5 混合/取小已废弃）")
            }
        }
        return bad
    }

    @Test
    fun `真身ARC_MERGE_FS_逐分量取大且按rgb采样`() {
        val bad = arcMergeFsViolations(Shaders.ARC_MERGE_FS)
        assertEquals("生产 ARC_MERGE_FS 违反取大口径：\n${bad.joinToString("\n")}", 0, bad.size)
    }

    /**
     * 突变自证：把**生产 shader 文本**改坏必报红——这是"GPU 口径"这条判据的靶子：
     * 取大改取小、改加权混合、改半亮缩放、或去掉 `.rgb`（整 vec4 采样）都要红。
     */
    @Test
    fun `尺子自己能红_ARC_MERGE_FS被改坏必报红`() {
        val good = Shaders.ARC_MERGE_FS
        assertEquals("良品必须先真的绿", 0, arcMergeFsViolations(good).size)
        val mutants = listOf(
            "max 改 min" to good.replace("max(a, b)", "min(a, b)"),
            "改成加权混合" to good.replace("max(a, b)", "mix(a, b, 0.5)"),
            "改成 0.5 缩放（v1 半亮重影）" to good.replace("max(a, b)", "a * 0.5"),
            "uA 去掉 .rgb（采样整 vec4）" to good.replace("texture2D(uA, vUv).rgb", "texture2D(uA, vUv)"),
        )
        mutants.forEach { (name, mutated) ->
            assertTrue("突变锚点丢失：$name 未改动 shader 文本", mutated != good)
            assertTrue(
                "突变体「$name」必须报红，实际违规为空 ⇒ 源扫描对生产件不敏感",
                arcMergeFsViolations(mutated).isNotEmpty()
            )
        }
    }

    // endregion
}

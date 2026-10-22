package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「霜绝不进编码器」这条红线的源码级守卫**（#84 步骤 2 · A2 混合路线）。
 *
 * 存在的理由是 [com.wotagei.cam.camera.FrostPlatePass] 类注释里那句话：
 * 「**绝不碰编码器**：本类只被 [com.wotagei.cam.camera.GlRenderEngine.drawWindowPass] 调用，
 * `drawEncoderPass` 里一个引用都不许出现（有源码级用例守着，见 `FrostEncoderGuardTest`）」。
 * 那句话在写下的时候是**假的**（这个文件当时不存在），本文件就是把它变成真话。
 *
 * # 为什么这条红线值得单独一座闸
 * 编码器那趟 `drawEncoderPass` 的产物直接进成片：它多改一次 `glViewport`、多绑一次纹理、
 * 多在 swap 前插一趟绘制，成片就跟着脏（视口错→录像拉伸、纹理绑错→录进去一块板、
 * 多一趟绘制→帧率掉到设定值以下）。而这些都是**只有真机录一段才看得见**的后果，
 * JVM 里没有任何其它手段能挡住"某天有人在编码 pass 里顺手加一行贴板"。
 *
 * # 判据为什么必须按函数体切片
 * 整份 `GlRenderEngine.kt` 里 `frost` 出现几十次（`drawFrostPass`、`publishedFrost` 都在同一文件），
 * 所以对整文件做 `contains` 判定永远红；而只写一句"文件里有 drawWindowPass"又永远绿。
 * 这里先把**函数体边界**解出来（[KotlinSourceScan]：注释与字符串遮蔽后再配平括号），
 * 再在边界内判定，且每条否定断言都配一条**正向锚点**（那段里必须有 `drawPass(encoderPass = true)`
 * 之类的实现标记）——否则"切片切空了"会让所有否定断言一起假绿。
 */
class FrostEncoderGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }

    /** 遮蔽后的文本：注释与字面量内容不再参与结构判定 */
    private val masked by lazy { KotlinSourceScan.codeOnly(engineSrc) }

    private val bodies by lazy { KotlinSourceScan.allBodies(masked) }

    /**
     * 禁止出现的符号全部以 `frost` 为前缀/中缀（`FrostPlatePass`、`drawFrostPlates`、`publishedFrost`、
     * `frostChain`、`frostTableGood`、`FrostCardTable`、`adoptFrostIntentFromTable`…），
     * 所以一条"忽略大小写不含 frost"就盖住了注释里点名的全部符号，且**新增符号也自动被盖住**
     * （不必每次红线扩了就回来补这张清单——那正是会漏抄的那种清单）。
     */
    private fun assertNoFrostSymbol(body: String, where: String) {
        val lower = body.lowercase()
        val hit = lower.indexOf("frost")
        assertEquals("$where 里出现了霜引用（第 ${hit + 1} 个字符起）：红线是编码路径与上屏路径互不知情", -1, hit)
        assertFalse("$where 不该有 FrostPlatePass", lower.contains("platepass"))
        assertFalse("$where 不该有矩形表", lower.contains("cardtable"))
    }

    @Test
    fun `编码 pass 的函数体里一个霜符号都不许出现`() {
        val raw = KotlinSourceScan.bodyOf(masked, "drawEncoderPass")
        val body = KotlinSourceScan.flatten(raw)
        // 正向锚点：先证明"切到的确实是那一段"，否则下面的否定断言全是空转
        assertTrue("锚点丢失：切片没截到 drawEncoderPass 的真身", body.contains("drawPass(encoderPass = true)"))
        assertTrue("锚点丢失：没截到编码器视口那行", body.contains("glViewport(0, 0, encoderWidth, encoderHeight)"))
        assertTrue("锚点丢失：没截到 swap(encoder)", body.contains("swap(encoder)"))
        assertTrue("锚点丢失：没截到呈现时间戳", body.contains("stampEncoderPresentation(encoder)"))
        assertTrue("函数体短到可疑（$raw）", raw.length > 100)
        assertNoFrostSymbol(body, "drawEncoderPass")
    }

    @Test
    fun `编码器那条只服务编码器的呈现时间戳函数也不含霜`() {
        val raw = KotlinSourceScan.bodyOf(masked, "stampEncoderPresentation")
        val body = KotlinSourceScan.flatten(raw)
        // 正向锚点 + 它唯一的 GL 调用（eglPresentationTimeANDROID）——这函数是成片帧号的真源
        assertTrue("锚点丢失：没截到呈现时间戳写入", body.contains("eglPresentationTimeANDROID(display, encoder, ptsNs)"))
        assertTrue("锚点丢失：没截到帧号自增", body.contains("encoderFrameIndex++"))
        assertNoFrostSymbol(body, "stampEncoderPresentation")
    }

    @Test
    fun `编码与上屏共用的那次 blit 不认识霜`() {
        // drawPass 是两条路唯一共用的绘制例程：它一旦知道霜，编码 pass 就再也切不干净了
        val raw = KotlinSourceScan.bodyOf(masked, "drawPass")
        val body = KotlinSourceScan.flatten(raw)
        assertTrue("锚点丢失：没截到两路选纹理坐标那行", body.contains("if (encoderPass) encoderTexCoordBuffer else displayTexCoordBuffer"))
        assertTrue("锚点丢失：没截到那次 drawArrays", body.contains("glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)"))
        assertTrue("锚点丢失：blit 只绑外部纹理", body.contains("GL_TEXTURE_EXTERNAL_OES"))
        assertNoFrostSymbol(body, "drawPass")
    }

    @Test
    fun `drawFrostPlates 的调用点有且只有一个且在上屏 pass 体内`() {
        val all = KotlinSourceScan.occurrences(masked, "drawFrostPlates")
        val decl = KotlinSourceScan.regionsOf(masked, "drawFrostPlates")
        assertTrue("源码里连 drawFrostPlates 的定义都找不到了（${all.size} 处出现）", decl.isNotEmpty())
        // 声明本身那一处要剔掉：`private fun drawFrostPlates()` 的名字出现在 fun 之后
        val declOffsets = Regex("\\bfun\\s+drawFrostPlates\\s*\\(")
            .findAll(masked)
            .map { masked.indexOf("drawFrostPlates", it.range.first) }
            .toSet()
        val calls = all.filter { it !in declOffsets }
        assertEquals(
            "贴板调用点必须唯一（红线：只有上屏那一条路贴板）。当前命中 ${calls.size} 处：${calls.map { KotlinSourceScan.lineOf(engineSrc, it) }}",
            1,
            calls.size
        )
        val call = calls[0]
        val window = KotlinSourceScan.regionOf(masked, "drawWindowPass")
        assertTrue(
            "drawFrostPlates() 的第 ${KotlinSourceScan.lineOf(engineSrc, call)} 行不在 drawWindowPass 体内（窗口体 ${window.start}..${window.end}）",
            call in window.start until window.end
        )
        val encoder = KotlinSourceScan.regionOf(masked, "drawEncoderPass")
        assertFalse(
            "drawFrostPlates() 落进了 drawEncoderPass 体内（第 ${KotlinSourceScan.lineOf(engineSrc, call)} 行）——成片会多出板",
            call in encoder.start until encoder.end
        )
        // 谁持有它： enclosing 判定与区间判定是两条独立的路，都得说同一个答案
        assertEquals("drawWindowPass", KotlinSourceScan.enclosingOf(bodies, call))
    }

    @Test
    fun `贴板发生在预览 blit 之后 swap 之前`() {
        // 顺序错了有两种坏观感：贴板在 blit 之前 = 霜被预览盖住（永远看不见）；
        // 在 swap 之后 = 那一帧已经交出去了，板要到下一帧才出现（卡片一闪一闪）
        val body = KotlinSourceScan.bodyOf(masked, "drawWindowPass")
        val blit = body.indexOf("drawPass(encoderPass = false)")
        val plate = body.indexOf("drawFrostPlates()")
        val swap = body.indexOf("swap(window)")
        assertTrue("锚点丢失：blit", blit >= 0)
        assertTrue("锚点丢失：贴板", plate >= 0)
        assertTrue("锚点丢失：swap", swap >= 0)
        assertTrue("贴板必须在预览 blit 之后（blit=$blit plate=$plate）", blit < plate)
        assertTrue("贴板必须在 swap 之前（plate=$plate swap=$swap）", plate < swap)
    }

    @Test
    fun `帧循环里编码 pass 排在所有霜步骤之前`() {
        // :911 那条注释的理由：翻开关那一次的着色器编译是毫秒级，不许挡在编码器的 swap 前面
        val body = KotlinSourceScan.bodyOf(masked, "drawFrame")
        val flat = KotlinSourceScan.flatten(body)
        val order = listOf(
            "drawEncoderPass()",
            "adoptFrostIntentFromTable()",
            "drawFrostPass(hasFrame)",
            "drawWindowPass(stateOnly)"
        )
        val indices = order.map {
            val at = flat.indexOf(it)
            assertTrue("drawFrame 里找不到 $it——帧循环的结构变了，这条红线的口径要重新对", at >= 0)
            at
        }
        assertEquals(
            "顺序必须是 编码 pass → 搬开关意图 → 离屏模糊 → 上屏（含贴板）：$order -> $indices",
            indices.sorted(),
            indices
        )
        order.forEach { assertEquals("$it 在帧循环里只该出现一次", 1, KotlinSourceScan.occurrences(body, it).size) }
        // 贴板不许在帧循环里被直接调第二次（它只能从 drawWindowPass 里进去）
        assertEquals("drawFrame 不许直接调 drawFrostPlates", 0, KotlinSourceScan.occurrences(body, "drawFrostPlates").size)
    }

    @Test
    fun `矩形表只在窗口那一侧被读`() {
        // tryReadInto 是全工程唯一读表点；它一旦出现在编码那一路，录出来的东西就和开关有关了（#85 对照作废）
        val reads = KotlinSourceScan.occurrences(masked, "tryReadInto")
        assertEquals("GlRenderEngine 里读表点必须唯一", 1, reads.size)
        val plateBody = KotlinSourceScan.regionOf(masked, "drawFrostPlates")
        assertTrue("读表点没落在 drawFrostPlates 体内", reads[0] in plateBody.start until plateBody.end)
        assertEquals("读表点的外层函数", "drawFrostPlates", KotlinSourceScan.enclosingOf(bodies, reads[0]))
        // 画板函数本体与编码函数本体必须互不相交（两个区间重叠 = 有人把贴板挪进了编码路）
        val encoder = KotlinSourceScan.regionOf(masked, "drawEncoderPass")
        assertFalse(
            "drawFrostPlates 的函数体与 drawEncoderPass 的函数体重叠了",
            plateBody.start < encoder.end && encoder.start < plateBody.end
        )
    }

    @Test
    fun `画板对象只允许被窗口那一侧与开关路径碰到`() {
        // frostPlatePass 的合法持有者：建资源（initGl）、编 program 与撤报（开关两条路）、
        // 拆资源（releaseGl）、唯一的绘制点 drawFrostPlates、以及 #84 霜探针那枚只读诊断入口
        // （frostPlateBrokenForDiagnostics：GL 线程写的 broken 位原样读出，不参与任何绘制判断）。
        // 白名单而不是黑名单：新增一个函数去碰它，守卫会直接问"你是哪条路"。
        val allowed = setOf(
            "initGl", "setFrostBlurEnabled", "adoptFrostIntentFromTable", "releaseGl", "drawFrostPlates",
            "frostPlateBrokenForDiagnostics"
        )
        val sites = KotlinSourceScan.occurrences(masked, "frostPlatePass").map {
            it to KotlinSourceScan.enclosingOf(bodies, it)
        }
        assertTrue("一处 frostPlatePass 都没找到，切片八成切空了（守卫空转）", sites.isNotEmpty())
        // 没有任何函数体包住的那几处就是属性声明本身（`private var frostPlatePass: FrostPlatePass? = null`）
        val decls = sites.filter { it.second == null }
        assertEquals(
            "frostPlatePass 的属性声明只该有一枚：${decls.map { KotlinSourceScan.lineOf(engineSrc, it.first) }}",
            1,
            decls.size
        )
        assertEquals(
            "frostPlatePass 的持有者集合变了（新增持有者要先回答它在哪条路上）",
            allowed,
            sites.mapNotNull { it.second }.toSet()
        )
    }
}

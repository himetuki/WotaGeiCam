package com.wotagei.cam

import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上屏档（`DisplayScale`）的**接线与红线守卫**（docs/plan/24 R3 §2.2–§2.4，25 §11.5）。
 *
 * 这一档的全部风险都在"接错地方"：
 * - 接回相机流（`setDefaultBufferSize(w/N,h/N)`）⇒ **成片降质**（R1 的机制已证伪并作废）；
 * - 接进编码 pass / MEND ⇒ 成片分辨率随预览档变；
 * - 原片档也建 RT/多一次 blit ⇒ 零代价口径破产（默认档必须与改前逐字节一致）；
 * - `uTexel` 跟着上屏 RT 走 ⇒ 降噪半径在预览里与成片不一致（26 §四）。
 *
 * 源码级判据 + 突变自证（GL 行为只有真机证：T4/T5/T16）。
 */
class DisplayScaleWiringGuardTest {

    private val engineSrc by lazy { KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt") }
    private val engine by lazy { KotlinSourceScan.codeOnly(engineSrc) }
    private val post by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/DisplayPostPass.kt")) }
    private val previewSink by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/PreviewSink.kt")) }
    private val camera2 by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")) }
    private val screen by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")) }
    private val pills by lazy { KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt")) }

    private fun body(name: String): String = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(engine, name))

    // ------------------------------------------------------------------ 原片档逐字不变

    /** 相机流判据：`onSizeChanged` 只设相机缓冲尺寸，不许出现 divisor / 上屏档 */
    private fun streamIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val size = runCatching {
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, "onSizeChanged"))
        }.getOrNull() ?: return listOf("onSizeChanged 定位失败")
        if (!size.contains("inputTexture?.setDefaultBufferSize(w, h)")) {
            bad.add("onSizeChanged 必须仍只设相机缓冲尺寸（成片唯一来源，不许降）")
        }
        if (size.contains("divisor") || size.contains("DisplayScale")) {
            bad.add("onSizeChanged 不许出现 divisor/DisplayScale —— 上屏档不接相机流")
        }
        return bad
    }

    /** 降噪纹素判据：`uTexel` 恒按 OES 帧尺寸 */
    private fun texelIssues(maskedSrc: String): List<String> {
        val body = runCatching {
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, "drawPass"))
        }.getOrNull() ?: return listOf("drawPass 定位失败")
        return if (body.contains("glUniform2f(p.uTexel, 1f / wantFrameWidth, 1f / wantFrameHeight)")) {
            emptyList()
        } else {
            listOf("降噪 uTexel 必须仍是 1/wantFrameWidth（上屏 RT 尺寸与降噪半径无关，26 §四）")
        }
    }

    /**
     * 回落判据：**瞬态 0/未量到的等比区绝不许置回落信号**（P1）。
     *
     * 为什么这条比别的守卫重要：回落会让 UI 把用户选的档位写回并落盘，而录制中 `PillKey.SIZE`
     * 是锁着的 —— 用户改不回来，设置就这么丢了。转屏/分屏/视图重建都会瞬时给出 0 尺寸。
     */
    private fun fallbackIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val body = runCatching {
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, "drawWindowPassScaled"))
        }.getOrNull() ?: return listOf("drawWindowPassScaled 定位失败")
        val guardAt = body.indexOf("if (contentW <= 0 || contentH <= 0) return false")
        val flagAt = body.indexOf("displayScaleFallback.value = true")
        if (guardAt < 0) bad.add("必须先判 content<=0 并静默返回（绝不置回落）")
        if (flagAt < 0) bad.add("尺寸为正但 begin 失败时仍须置回落（否则用户永远不知道档位没生效）")
        if (guardAt >= 0 && flagAt >= 0 && guardAt > flagAt) {
            bad.add("content<=0 的早退必须在置回落**之前**（顺序反了 = 瞬时 0 尺寸照样打回落）")
        }
        // 最近可用档：回落时不许固定写 ORIG
        val screenSrc = KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")
        if (!screenSrc.contains("nearestDisplayScale(")) {
            bad.add("回落时必须按最近可用档收回（24 §2.3），不许固定写 ORIG")
        }
        return bad
    }

    /** 原片路径的判据：必须是"清黑 → 视口=等比区 → drawPass → 霜板 → swap"，且不认识上屏 RT */
    private fun origPathIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val body = runCatching {
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, "drawWindowPassOrig"))
        }.getOrNull() ?: return listOf("drawWindowPassOrig 定位失败（原片路径的锚点没了）")
        for (token in listOf("displayPost", "displayTex", "DisplayScale", "blit", "displayFbo")) {
            if (body.contains(token)) bad.add("原片路径里出现了 $token —— ORIG 必须与改前逐字一致（不建 RT / 不 blit）")
        }
        // 2026-10-27（B8）：贴板+swap 收进了两条上屏路径共用的 `finishWindowFrame`
        // （`FrostEncoderGuardTest` 钉住"贴板只此一处、只被这两条路调用"），所以这里钉四步即可。
        val order = listOf(
            "glClearColor(0f, 0f, 0f, 1f)",
            "glClear(GLES20.GL_COLOR_BUFFER_BIT)",
            "glViewport(left, windowHeight - top - height, width, height)",
            "drawPass(encoderPass = false)",
            "finishWindowFrame(window)"
        )
        val idx = order.map {
            val at = body.indexOf(it)
            if (at < 0) bad.add("原片路径缺步骤：$it")
            at
        }
        if (bad.isEmpty() && idx != idx.sorted()) bad.add("原片路径步骤顺序被改：$order -> $idx")
        // 清黑必须在收窄视口之前（否则黑边被内容盖住）
        val clearAll = body.indexOf("glViewport(0, 0, windowWidth, windowHeight)")
        if (clearAll < 0 || !(clearAll < idx[1])) bad.add("必须先全屏清黑再把视口收窄到等比区")
        return bad
    }

    @Test
    fun `瞬态零尺寸不置回落且回落按最近可用档`() {
        val issues = fallbackIssues(engine)
        assertEquals(
            "回落口径缺口：" + issues.joinToString("\n"),
            emptyList<String>(),
            issues
        )
    }

    @Test
    fun `尺子能红_把回落挪到尺寸判定之前必红`() {
        assertEquals("真身必须绿", emptyList<String>(), fallbackIssues(engine))
        // 突变：先置回落、后判尺寸（= 瞬态 0 尺寸也会把用户档位打回）
        val swapped = engine.replace(
            "if (contentW <= 0 || contentH <= 0) return false",
            "displayScaleFallback.value = true\n        if (contentW <= 0 || contentH <= 0) return false"
        )
        assertTrue("顺序颠倒的突变必须报红", fallbackIssues(swapped).isNotEmpty())
        // 突变：删掉尺寸早退
        val noGuard = engine.replace("if (contentW <= 0 || contentH <= 0) return false", "")
        assertTrue("删掉尺寸早退的突变必须报红", fallbackIssues(noGuard).isNotEmpty())
    }

    @Test
    fun `原片档路径逐字不变_不建RT不blit`() {
        val issues = origPathIssues(engine)
        assertEquals("原片档红线：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
    }

    @Test
    fun `drawWindowPass按档位分流且同帧不双画`() {
        val body = body("drawWindowPass")
        assertTrue("必须按档位分流", body.contains("wantDisplayScale != DisplayScale.ORIG"))
        val scaledAt = body.indexOf("drawWindowPassScaled(window)")
        val origAt = body.indexOf("drawWindowPassOrig(window)")
        assertTrue("降档分支必须在场", scaledAt >= 0)
        assertTrue("原片分支必须在场", origAt >= 0)
        assertTrue("降档成功返回 true 时不进原片路径（同一帧不许两路都画）", body.contains("if (wantDisplayScale != DisplayScale.ORIG && drawWindowPassScaled(window)) return"))
        assertTrue("降档失败后原片路径必须排在它后面", origAt > scaledAt)
        // 共用的三道门仍在外层（displaySlot 空 / 节流 / makeCurrent）
        assertTrue("上屏 program 空守卫必须保留", body.contains("if (displaySlot.program == null) return"))
        assertTrue("节流必须保留", body.contains("!stateOnly && windowThrottled()"))
    }

    @Test
    fun `降档路径先画RT再blit回窗口`() {
        val body = body("drawWindowPassScaled")
        val begin = body.indexOf("post.begin(")
        val draw = body.indexOf("drawPass(encoderPass = false)")
        val blit = body.indexOf("post.blitToWindow(")
        val tail = body.indexOf("finishWindowFrame(window)")
        assertTrue("必须先 begin(RT)", begin >= 0)
        assertTrue("drawPass 必须在 begin 之后（视口已由 begin 设成 RT 尺寸）", draw > begin)
        assertTrue("blit 必须在 drawPass 之后", blit > draw)
        assertTrue("霜板/swap 的收尾在 blit 之后（霜板只上屏、用窗口坐标）", tail > blit)
        assertTrue("降档不可用时必须置回落信号", body.contains("displayScaleFallback.value = true"))
    }

    // ------------------------------------------------------------------ 编码侧零影响

    @Test
    fun `编码pass与MEND不认识上屏档`() {
        // 函数缺失**必须失败**（曾经的 `?: continue` 会让"改名/删函数"变成全绿：守卫失去输入）
        val zeroRefFns = listOf(
            // 编码两支路：成片与预览档无关
            "drawEncoderPass", "drawEncoderMended", "emitMended",
            // 其余不经过窗口尾巴的光栅路：Glow 链、霜离屏链、上屏槽同步、直方图采样
            "drawGlowPass", "drawFrostPass", "syncDisplaySlot", "maybeSampleHistogram"
        )
        for (fn in zeroRefFns) {
            assertTrue(
                "定位不到 $fn —— 守卫失去输入（改名/删函数都要同步改这里）",
                KotlinSourceScan.regionsOf(engine, fn).isNotEmpty()
            )
            val body = body(fn)
            assertTrue("$fn 的函数体切空了", body.isNotEmpty())
            // ⚠ 否定词只取**上屏档专有**的符号：`displayTex` 会撞上 `displayTexCoordBuffer`
            // （霜链的合法用法），拿它当反串会把正常代码判红。
            for (token in listOf("displayPost", "displayScale", "DisplayScale", "blitToWindow")) {
                assertFalse("$fn 不许出现 $token —— 上屏档只属于窗口尾巴那一段", body.contains(token))
            }
        }
        // uTexel（降噪半径）仍按 OES 帧尺寸，不随上屏 RT 变
        assertEquals(
            "降噪纹素缺口：" + texelIssues(engine).joinToString("\n"),
            emptyList<String>(),
            texelIssues(engine)
        )
    }

    @Test
    fun `相机流与会话结构不接上屏档`() {
        // PreviewSink 不新增方法（R1 的 onPreviewScaleChanged 不许回潮）
        for (token in listOf("DisplayScale", "displayScale", "previewScale")) {
            assertFalse("PreviewSink 不许出现 $token", previewSink.contains(token))
        }
        // Camera2Engine 不认上屏档（会话结构签名里没有它）
        assertFalse("Camera2Engine 不许出现 displayScale", camera2.contains("displayScale"))
        // onSizeChanged 仍然只设相机缓冲尺寸，没有 divisor
        assertEquals(
            "相机流缺口：" + streamIssues(engine).joinToString("\n"),
            emptyList<String>(),
            streamIssues(engine)
        )
    }

    // ------------------------------------------------------------------ 资源与无重画

    @Test
    fun `setDisplayScale不重画且ORIG释放RT`() {
        val body = body("setDisplayScale")
        assertFalse("setDisplayScale 不许调 requestRender", body.contains("requestRender"))
        assertFalse("setDisplayScale 不许置 dirty", body.contains("dirty = true"))
        assertFalse("setDisplayScale 不许 scheduleDraw", body.contains("scheduleDraw"))
        assertTrue(">ORIG 时预编 blit program", body.contains("displayPost?.prepare()"))
        assertTrue("回原片时释放 RT（只删两枚空纹理）", body.contains("displayPost?.releaseTarget()"))
        val release = body("releaseGl")
        assertTrue("releaseGl 必须收上屏 RT 与 program", release.contains("displayPost?.release()"))
        val init = body("initGl")
        assertTrue("initGl 必须建 DisplayPostPass", init.contains("DisplayPostPass(::link, positionBuffer)"))
    }

    @Test
    fun `blit复用现成着色器且过滤方式正确`() {
        val prepare = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(post, "prepare"))
        assertTrue(
            "blit program 必须是 FROST_QUAD_VS + PASS_THROUGH_2D_FS（不新增 shader 源码）",
            prepare.contains("linkProgram(Shaders.FROST_QUAD_VS, Shaders.PASS_THROUGH_2D_FS)")
        )
        val ensure = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(post, "ensureTarget"))
        assertTrue("RGBA8 纹理", ensure.contains("GLES20.GL_RGBA"))
        assertTrue("放大采样必须 GL_LINEAR（NEAREST 会变马赛克）", ensure.contains("GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR"))
        assertTrue("边缘必须 CLAMP_TO_EDGE（REPEAT 会绕回画面另一头）", ensure.contains("GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE"))
        assertTrue("必须验 FBO 完整性", ensure.contains("glCheckFramebufferStatus"))
        // blit 回默认面：载重步骤
        val blit = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(post, "blitToWindow"))
        assertTrue("blit 必须先回默认面", blit.contains("glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)"))
        assertTrue("blit 用恒等 UV", blit.contains("identityUv"))
        assertTrue("画完解绑纹理（下一帧要往同一张里画）", blit.contains("glBindTexture(GLES20.GL_TEXTURE_2D, 0)"))
        // 进出各存各恢复（与霜链/Glow 链同一条纪律）：program / 激活单元 / 两个属性 enable 位
        assertTrue("必须保存当前 program", blit.contains("GL_CURRENT_PROGRAM"))
        assertTrue("必须保存激活纹理单元", blit.contains("GL_ACTIVE_TEXTURE"))
        assertTrue("必须保存属性 enable 位", blit.contains("attribEnabled(aPosition)") && blit.contains("attribEnabled(aTexCoord)"))
        assertTrue("必须把两个属性下标还回去", blit.contains("glDisableVertexAttribArray(aPosition)"))
        val attribHelper = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(post, "attribEnabled"))
        assertTrue("attribEnabled 必须真的读 GL_VERTEX_ATTRIB_ARRAY_ENABLED", attribHelper.contains("GL_VERTEX_ATTRIB_ARRAY_ENABLED"))
        assertTrue("退出时必须还回 program", blit.contains("glUseProgram(programBackup[0])"))
        assertTrue("退出时必须还回激活单元", blit.contains("glActiveTexture(activeTextureBackup[0])"))
        // 失败就会停用（引擎当场回原片）
        assertTrue("连续报错必须能停用", post.contains("DISPLAY_POST_MAX_ERROR_STREAK"))
        assertTrue("release 只删自己那三件", KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(post, "release"))
            .contains("glDeleteProgram(program)"))
    }

    // ------------------------------------------------------------------ UI 接线

    @Test
    fun `画幅弹窗接上屏档且录制中锁住`() {
        val flatScreen = KotlinSourceScan.flatten(screen)
        assertTrue("长键必须含 displayScale（换引擎补发）", flatScreen.contains("arcMergeDeadband, videoDenoise, displayScale"))
        assertTrue("长体内必须下发", flatScreen.contains("gl.setDisplayScale(displayScale)"))
        assertTrue(
            "PillHost 必须把等比区实测尺寸交进去",
            flatScreen.contains("previewContentSize = { glEngine?.contentRect()?.let { it.width() to it.height() } }")
        )
        assertTrue("回落信号必须被收集（自动变化必须可见）", flatScreen.contains("displayScaleFallback.collect"))
        assertTrue(
            "回落必须把**最近可用档**写回总线（24 §2.3：16→8/4/2，兜底原片）",
            flatScreen.contains("params.displayScale.value = com.wotagei.cam.core.nearestDisplayScale(")
        )
        val sizePill = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(pills, "SizePill"))
        assertTrue("SizePill 必须读上屏档", sizePill.contains("params.displayScale.observed()"))
        assertTrue("SizePill 必须用同一支纯函数判可用性", sizePill.contains("resolveDisplayRtOrNull("))
        assertTrue("只在 GPU 档显示整段", sizePill.contains("if (gpuMode)"))
        assertTrue("录制中锁住（editable）", sizePill.contains("enabled = editable"))
        assertTrue("选档要落盘", sizePill.contains("onPersist()"))
        // 不得新增 PillSlider（上屏档是档位选择，不是滑杆）
        assertFalse("上屏档不许引入滑杆", sizePill.contains("PillSlider("))
    }

    // ------------------------------------------------------------------ 胶囊下方读数（钉子 3）

    /**
     * 画幅胶囊下方「预览：<档位值>」读数的判据（24 R3 §2.4 / 钉子 3）：
     * 仅 GPU、无背景、半透明灰 + 红描边、锚在 SIZE 胶囊实测矩形正下方。
     */
    private fun readoutIssues(maskedSrc: String): List<String> {
        val bad = ArrayList<String>()
        val body = runCatching {
            KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(maskedSrc, "DisplayScaleReadout"))
        }.getOrNull() ?: return listOf("DisplayScaleReadout 定位失败（读数组件被删/改名）")
        for (token in listOf("WotaColor.rec", "Stroke(", "WotaColor.textLo", "anchor.bottom")) {
            if (!body.contains(token)) bad.add("读数缺样式/位置锚点：$token")
        }
        // 2026-10-28 用户口径：再透一些、再小一些（她点名这块读数抢画面）。
        // 钉住三个具体值：9sp 字号 / 填充 30% 白 / 红描边 55%——谁改回去先红。
        for (token in listOf("fontSize = 9.sp", "WotaColor.textLo.copy(alpha = 0.75f)", "WotaColor.rec.copy(alpha = 0.55f)")) {
            if (!body.contains(token)) bad.add("读数样式退化（用户 2026-10-28 口径）：$token")
        }
        if (body.contains("background(")) bad.add("读数必须无背景（钉子 3 的样式）")
        val flat = KotlinSourceScan.flatten(maskedSrc)
        for (token in listOf(
            "cam_display_scale_readout",
            "pillAnchors[PillKey.SIZE]",
            "if (renderMode == RenderMode.GPU && !splitOn)",
            "DisplayScaleReadout(displayScale, sizeAnchor)"
        )) {
            if (!flat.contains(token)) bad.add("读数接线缺：$token")
        }
        return bad
    }

    @Test
    fun `画幅下方读数仅GPU且无背景红描边`() {
        val issues = readoutIssues(screen)
        assertEquals("胶囊下方读数缺口：\n${issues.joinToString("\n")}", emptyList<String>(), issues)
        // 突变 1：红描边换成普通颜色（无描边）
        assertTrue(
            "去掉红描边的突变必须报红",
            readoutIssues(screen.replace("WotaColor.rec", "WotaColor.textHi")).isNotEmpty()
        )
        // 突变 2：删掉 Stroke（只剩填充文字）
        assertTrue(
            "删掉 Stroke 的突变必须报红",
            readoutIssues(screen.replace("Stroke(width = outline)", "")).isNotEmpty()
        )
        // 突变 3：去掉仅 GPU 门（DIRECT 也显示）
        assertTrue(
            "去掉 GPU 门的突变必须报红",
            readoutIssues(screen.replace("if (renderMode == RenderMode.GPU && !splitOn)", "if (true)")).isNotEmpty()
        )
    }

    // ------------------------------------------------------------------ 突变自证

    @Test
    fun `尺子能红_原片路径塞RT或预览档接回相机流必红`() {
        assertEquals("真身必须绿", emptyList<String>(), origPathIssues(engine))
        // 突变 1：原片路径也建 RT（零代价破产）
        val withRt = engine.replace(
            "private fun drawWindowPassOrig(window: EGLSurface) {",
            "private fun drawWindowPassOrig(window: EGLSurface) { displayPost?.prepare()"
        )
        assertTrue("原片路径塞 RT 的突变必须报红", origPathIssues(withRt).isNotEmpty())
        // 突变 2：预览档接回相机流
        val backToStream = engine.replace(
            "inputTexture?.setDefaultBufferSize(w, h)",
            "inputTexture?.setDefaultBufferSize(w / wantDisplayScale.divisor, h / wantDisplayScale.divisor)"
        )
        assertTrue("把 divisor 接回相机缓冲的突变必须报红", streamIssues(backToStream).isNotEmpty())
        // 突变 3：uTexel 跟到上屏 RT（降噪半径与成片分叉）
        val texelWrong = engine.replace(
            "glUniform2f(p.uTexel, 1f / wantFrameWidth, 1f / wantFrameHeight)",
            "glUniform2f(p.uTexel, 1f / wantDisplayScale.divisor, 1f / wantDisplayScale.divisor)"
        )
        assertTrue("uTexel 跟着上屏 RT 走的突变必须报红", texelIssues(texelWrong).isNotEmpty())
    }
}

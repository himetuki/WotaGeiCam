package com.wotagei.cam

import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.isIdentityGains
import com.wotagei.cam.core.normalizeMaxToUnit
import com.wotagei.cam.core.wbApplyPlan
import com.wotagei.cam.source.KotlinSourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 手动白平衡「HAL 增益不被采纳 ⇒ 画面算法兜底」的口径与接线守卫（2026-10-28 用户需求）。
 *
 * 现场：WIKO GAR-AN60 选手动 5500K/+0，画面整屏发绿。根因不是"没这个功能"，而是
 * `AWB_MODE_OFF` 照做、`COLOR_CORRECTION_GAINS` 被 HAL 丢弃（四档色温逐像素无差别）——
 * 关掉自动白平衡又没有增益，就只剩传感器原色。系统相机能改是因为它走厂商私有元数据。
 *
 * 兜底口径：**不关 AWB**，色温/色调由场景层 LUT 乘一组相对增益（5500K = 恒等），
 * 并在运行时给出含"本机白平衡不接受手动增益"的提示。
 */
class WbFallbackPlanTest {

    // ---------------------------------------------------------------- 决策层

    @Test
    fun `自动档与预设档不做任何白平衡处理`() {
        val p = wbApplyPlan(manual = false, halSupportsGains = false, kelvin = 3200, tint = 0)
        assertFalse("自动/预设档不许改画面", p.byGl)
        assertNull(p.gains)
        assertFalse(p.noticeNeeded)
    }

    @Test
    fun `HAL能采纳增益时走HAL不做画面处理`() {
        val p = wbApplyPlan(manual = true, halSupportsGains = true, kelvin = 3200, tint = 0)
        assertTrue("常规路径：AWB_MODE_OFF + COLOR_CORRECTION_GAINS", p.byHal)
        assertFalse("能走 HAL 就不许再乘一遍（会双倍）", p.byGl)
        assertNull(p.gains)
        assertFalse("能走 HAL 不需要提示", p.noticeNeeded)
    }

    @Test
    fun `HAL不采纳增益时改由画面算法且必须提示`() {
        val p = wbApplyPlan(manual = true, halSupportsGains = false, kelvin = 3200, tint = 0)
        assertFalse("不许再发 AWB_MODE_OFF（那就是整屏发绿的成因）", p.byHal)
        assertTrue(p.byGl)
        assertNotNull(p.gains)
        assertTrue("自动降级必须提示", p.noticeNeeded)
    }

    @Test
    fun `相对增益按最大通道归一_只改色不改亮度`() {
        val p = wbApplyPlan(manual = true, halSupportsGains = false, kelvin = 3200, tint = 0)
        val g = p.gains!!
        assertEquals("最大通道必须归一到 1.0", 1f, g.max(), 1e-5f)
        assertTrue("3200K 必须补蓝：B 是最大通道", g[2] >= g[0])
        // 5500K（中性锚点）三通道相等 ⇒ 恒等（画面与自动白平衡一致，不会莫名其妙变绿/变紫）
        val mid = wbApplyPlan(manual = true, halSupportsGains = false, kelvin = 5500, tint = 0).gains!!
        assertEquals(mid[0], mid[1], 1e-4f)
        assertEquals(mid[1], mid[2], 1e-4f)
        assertTrue("中性档必须判成恒等（不然白白多跑一趟 LUT）", isIdentityGains(mid))
    }

    @Test
    fun `归一化的坏输入一律返回null`() {
        assertNull(normalizeMaxToUnit(null))
        assertNull(normalizeMaxToUnit(floatArrayOf(1f, 2f)))
        assertNull(normalizeMaxToUnit(floatArrayOf(0f, 0f, 0f)))
        assertNull(normalizeMaxToUnit(floatArrayOf(Float.NaN, 1f, 1f)))
        assertNull(normalizeMaxToUnit(floatArrayOf(-1f, 1f, 1f)))
    }

    // ---------------------------------------------------------------- 接线守卫

    private val requestSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/RequestApplier.kt"))
    }
    private val engineSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt"))
    }
    private val glSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("camera/GlRenderEngine.kt"))
    }
    private val curveSrc by lazy {
        KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("core/CurveModel.kt"))
    }

    @Test
    fun `兜底时请求侧必须留在自动白平衡`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(requestSrc, "applyWhiteBalance"))
        val glBranch = body.substringBefore("isManualWhiteBalance(p.wbPreset) &&")
            .let { body }
        assertTrue("快照必须带 wbByGl", requestSrc.contains("val wbByGl: Boolean = false"))
        assertTrue(
            "兜底分支必须先把 AWB 设回 AUTO 再返回",
            glBranch.contains("CONTROL_AWB_MODE_AUTO")
        )
        assertTrue(
            "兜底分支必须在手动分支之前（否则先发 OFF 再改回来，这一拍仍然绿）",
            body.indexOf("p.wbByGl") < body.indexOf("kelvinTintToRgb255(p.kelvin")
        )
    }

    @Test
    fun `引擎快照按能力与渲染模式裁决`() {
        assertTrue("快照必须填 wbByGl", engineSrc.contains("wbByGl = wbByGlNow()"))
        // wbByGlNow 是表达式体（`= a && b && c`），bodyOf 定位不到；三个条件在全文里本就唯一
        val fn = engineSrc.substringAfter("private fun wbByGlNow()").substringBefore("private val")
        assertTrue("必须要求手动档", fn.contains("WbPreset.MANUAL"))
        assertTrue("必须要求 GPU 管线（DIRECT 没有场景层）", fn.contains("RenderMode.GPU"))
        // 2026-10-28 口径：只认**正向声明**——没明确列出 TRANSFORM_MATRIX 就不信增益
        assertTrue("必须要求 HAL 未声明 TRANSFORM_MATRIX", fn.contains("halDeclaresTransformMatrix() != true"))
        assertTrue("不许再退回「查不到就当支持」的旧判据", !fn.contains("manualWhiteBalancePossible()"))
        // 2026-10-29：运行期闩锁必须两侧同源（wbApply 已吃，快照侧漏吃 = 请求发 OFF、GL 又乘增益）
        assertTrue("wbByGlNow 必须吃 wbHalRejected 闩锁", fn.contains("params.wbHalRejected.value"))
    }

    // ------------------------------------------------- 种子 CCM（2026-10-29 卓易通桥取证）

    @Test
    fun `手动档矩阵必须用HAL回报的种子_恒等只配当兜底`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(requestSrc, "applyWhiteBalance"))
        assertTrue(
            "快照必须带种子字段（行优先 9 元素）",
            requestSrc.contains("val wbSeedCcm9: FloatArray? = null")
        )
        assertTrue(
            "TRANSFORM 必须写 种子?:恒等（恒等矩阵跳过传感器→sRGB 校正，RGGB 原生绿独大 ⇒ 整屏发绿）",
            body.contains("colorTransformFrom9(p.wbSeedCcm9) ?: identityColorTransform()")
        )
        assertTrue(
            "不许再直接写恒等矩阵（ALN-AL00/卓易通 真机绿幕根因）",
            !body.contains("COLOR_CORRECTION_TRANSFORM, identityColorTransform()")
        )
    }

    @Test
    fun `引擎必须在3A还活着时抓种子并递进快照`() {
        val callback = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(engineSrc, "onCaptureCompleted")
        )
        // 抓种子只看"回报的 CCM 不是我们冻结的"（3A 自己在跑），不局限自动档——
        // 冷启即手动/稳压窗期间请求走自动，种子必须能续上
        val guardIdx = callback.indexOf(
            "reportedCcm != CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX"
        )
        val seedIdx = callback.indexOf("lastAwbCcm9 = it")
        assertTrue("必须有 reportedCcm 守卫的种子捕获", guardIdx in 0 until seedIdx)
        val manualIdx = callback.indexOf("if (!manual)")
        assertTrue("种子捕获必须在手动分支之前（门控期也要续种子）", guardIdx < manualIdx)
        assertTrue("快照必须把种子递给请求侧", engineSrc.contains("wbSeedCcm9 = lastAwbCcm9"))
    }

    @Test
    fun `下发侧种子未到手必须留在自动_不许发OFF加恒等`() {
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(requestSrc, "applyWhiteBalance"))
        val gateIdx = body.indexOf("if (p.wbSeedCcm9 == null)")
        val offIdx = body.indexOf("CONTROL_AWB_MODE_OFF")
        assertTrue("手动分支必须有种子门", gateIdx in 0 until offIdx)
        assertTrue(
            "种子门内必须留在自动白平衡",
            body.contains("p.wbSeedCcm9 == null")
        )
    }

    @Test
    fun `会话重建后手动档必须先过稳压窗`() {
        // onConfigured 成功侧布防
        val configured = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(engineSrc, "onConfigured")
        )
        assertTrue("会话配成必须布防稳压窗", configured.contains("wbHoldUntilMs = SystemClock"))
        // wbByGlNow（表达式体）吃稳压窗 + 闩锁，两侧同源
        val fn = engineSrc.substringAfter("private fun wbByGlNow()").substringBefore("private val")
        assertTrue("稳压窗必须参与裁决", fn.contains("wbHoldUntilMs"))
        // 探测门控覆盖稳压窗与种子未到手（都不是 HAL 拒绝的证据）
        val callback = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(engineSrc, "onCaptureCompleted")
        )
        val glGuardIdx = callback.indexOf("if (wbByGlNow() || lastAwbCcm9 == null)")
        val probeIdx = callback.indexOf("val applied =")
        assertTrue(
            "回声探测必须被 wbByGlNow+种子门控（GL/稳压/无种子时请求本就不带 TRANSFORM_MATRIX，探测会误闩）",
            glGuardIdx in 0 until probeIdx
        )
        // 2026-10-29 三段取证定稿：awbState 判据是这台桥「增益不会被采纳」的唯一可靠信号
        //（r26 删掉它 → 表面重挂后持续绿屏 G/B≈9.6；r25 有它 → 闩锁画面算法颜色正常）。
        assertTrue(
            "AWB-未停判据必须存在（桥的可靠拒绝信号，勿再删）",
            engineSrc.contains("CONTROL_AWB_STATE_INACTIVE")
        )
        // 显示面瞬态钩子只布防、不许立刻重发请求（2026-10-29 四段取证：markDirty 是桥过渡帧触发器）
        val transient = KotlinSourceScan.flatten(
            KotlinSourceScan.bodyOf(engineSrc, "onSinkSurfaceTransient")
        )
        assertTrue("瞬态钩子必须布防稳压窗", transient.contains("wbHoldUntilMs"))
        assertTrue("瞬态钩子不许 markDirty（会触发桥过渡帧=绿帧复现）", !transient.contains("markDirty()"))
    }

    @Test
    fun `LUT烘焍必须带白平衡增益且恒等时逐字节不变`() {
        assertTrue("bake 必须接受 wbGains", curveSrc.contains("wbGains: FloatArray? = null"))
        assertTrue("evaluate 必须接受单通道增益", curveSrc.contains("wbGain: Float = 1f"))
        assertTrue("增益必须钳在 0..1（LUT 是 8bit 显示域）", curveSrc.contains(".coerceIn(0f, 1f)"))
        val engineBake = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(glSrc, "bakeCurveLut"))
        assertTrue(
            "跳过 LUT 的判据必须含白平衡（只看曲线会静默丢掉色温）",
            engineBake.contains("!it.isPassthrough || !tone.isIdentity || wb != null")
        )
        assertTrue("必须真的把增益烘进表", engineBake.contains("bakeBytes(tone, wbGains = wb)"))
        val glSet = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(glSrc, "setWbGains"))
        assertTrue("恒等增益要折成 null（否则每帧多跑一趟）", glSet.contains("isIdentityGains"))
    }

    @Test
    fun `运行时提示文案必须含用户指定的那句话`() {
        var dir: File? = KotlinSourceScan.mainSourceFile("player/PlayerScreen.kt").parentFile
        while (dir != null && dir.name != "java") dir = dir.parentFile
        assertTrue("定位不到 src/main/java", dir != null)
        val xml = File(File(dir!!.parentFile, "res"), "values/strings_camera.xml")
            .readText(Charsets.UTF_8)
        assertTrue(
            "弹层说明必须含「本机白平衡不接受手动增益」（口径含未声明与运行期拒绝两类）",
            xml.contains("本机白平衡不接受手动增益")
        )
        assertTrue("必须有运行时提示那条", xml.contains("name=\"cam_wb_gl_fallback_toast\""))
        val screen = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
        assertTrue("运行时提示必须真的发出去", screen.contains("R.string.cam_wb_gl_fallback_toast"))
        val pills = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("ui/CameraPills.kt"))
        assertTrue("弹层里必须有常驻说明", pills.contains("R.string.cam_wb_gl_fallback"))
        assertTrue(
            "兜底可用时手动档必须可选（不能因为 HAL 不支持就把用户挡在外面）",
            pills.contains("ability.manualWhiteBalancePossible() ||")
        )
    }

    @Test
    fun `WotaParams是唯一裁决点`() {
        val src = KotlinSourceScan.codeOnly(KotlinSourceScan.mainSourceText("core/WotaParams.kt"))
        val body = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "wbApply"))
        assertTrue("必须走纯函数 wbApplyPlan", body.contains("wbApplyPlan("))
        assertTrue("DIRECT 不许出 byGl（没有场景层）", body.contains("renderMode.value != RenderMode.GPU"))
        assertTrue("落地路径必须只认正向声明", body.contains("halDeclaresTransformMatrix()"))
        assertTrue("运行期闩锁必须参与", body.contains("wbHalRejected.value"))
        assertEquals(
            "手动档的落地路径只许在这一处裁决（别处再算一遍就会算出第二种答案）",
            1,
            Regex("wbApplyPlan\\(").findAll(src).count()
        )
        // 回弹判据：只有"HAL 不行 且 画面算法也不行"才弹回自动
        val apply = KotlinSourceScan.flatten(KotlinSourceScan.bodyOf(src, "applyWhiteBalance"))
        assertTrue(
            "回弹必须同时要求 !manualSupported 与 !plan.byGl",
            apply.contains("!manualSupported && !plan.byGl")
        )
    }

    @Test
    fun `四种WB预设名仍在_没有误删档位`() {
        // 兜底只改落地路径，不改档位集合
        assertEquals(9, WbPreset.values().size)
    }
}

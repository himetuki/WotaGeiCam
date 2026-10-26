package com.wotagei.cam

import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.camera.RequestApplier
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect as WotaRect
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「EV 显示时调快门/帧率闪退」加固批的守门（诊断 `.tmp/ev-crash.md` + 审查 `.tmp/ev-crash-review.md`）。
 *
 * 修了四件事，本文件逐条钉住，全部可证伪（每条的注释写明「退回旧实现哪条红」）：
 * 1. **清残键**（审查 P2，根因向）：`RequestApplier.applyExposure` 原先两分支互不清键——builder
 *    每会话建一次且只有 set 没有删键，翻 MANUAL 的请求带着上次 auto 档的
 *    `AE_EXPOSURE_COMPENSATION` 旧值，反向循环后 auto 请求残留 SENSOR 三键 ⇒ 下发混合键集请求。
 *    修法是曝光组「键集并集全量重放」：抽 [RequestApplier.exposurePlan] 纯函数，两档的六个键
 *    每次都有确定值（行为测试直接断言 plan）；
 * 2. **堵逃逸通道**（审查 P1）：`flushTask` 直发 Handler 无 try、apply/build 在 try 外、
 *    引擎线程无 UncaughtExceptionHandler ⇒ 未捕获即进程死亡。源码钉扎锁 try 覆盖与分流；
 * 3. **IAE 按会话分流**（审查 P2）：普通会话的 IAE 原先被误路由进 downgradeHighSpeed，
 *    静默把用户 fps 改写成 30 并重建会话；
 * 4. **AE 翻转闸**（审查 P3 修正版判据）：原 `hudCycleStep` 的 AUTO→MANUAL 翻转只查 shutter
 *    一半。2026-10-17 点按循环整体删除后该闸随函数消失；本文件保留 EV 显示等不相关断言，
 *    循环相关用例已删（`PillInteractionGuardTest` 锁"循环残留必须为零"）。
 */
class EvCrashGuardTest {

    // -------------------------------------------------- 行为测试：清残键（exposurePlan 纯函数）

    /** 与本机能力同档：iso 100..16000、曝光 1ms..667.847ms、EV 步数域 [-4,4]、25fps 固定档 */
    private fun state() = RequestApplier.State(
        activeArray = WotaRect(0, 0, 4000, 3000),
        isoMin = 100, isoMax = 16000,
        exposureMinNs = 1_000_000L, exposureMaxNs = 667_847_000L,
        evMinSteps = -4, evMaxSteps = 4,
        fpsRanges = listOf(RangeI(5, 30)),
        maxRegionsAf = 1, maxRegionsAe = 1,
        manualFocusMaxDiopter = 10f, zoomMin = 1f, zoomMax = 8f,
        useZoomRatio = false, flashAvailable = true, oisAvailable = true, eisAvailable = true,
        highSpeed = false, eisBlockedBySize = false
    )

    /** evSteps=+2 是「用户调过 EV」的记号：清残键不许把它弄丢 */
    private fun snapshot(aeMode: AeMode) = RequestApplier.Snapshot(
        aeMode = aeMode, iso = 800, shutterNs = 20_000_000L, evSteps = 2,
        fps = FpsPick(25, 25, true), afMode = AfMode.CONTINUOUS_VIDEO, afModeOverride = null,
        focusDiopter = 0f, zoom = 1f, wbPreset = WbPreset.AUTO, kelvin = 5500, tint = 0,
        flashOverride = null, flash = Flash.OFF, stabilize = Stabilize.OFF,
        afRegions = null, aeRegions = null, afTrigger = null
    )

    @Test
    fun manualPlanZeroesCompensationWhileSensorKeysStayPresent() {
        val plan = RequestApplier.exposurePlan(state(), snapshot(AeMode.MANUAL))
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, plan.aeMode)
        assertEquals("AE_OFF 下补偿无语义，必须归零而不是留 auto 档旧值（混合键集根因之一）", 0, plan.aeComp)
        assertEquals(800, plan.sensitivity)
        assertEquals(20_000_000L, plan.exposureNs)          // 20ms < 25fps 帧周期 40ms，不被钳
        assertEquals(40_000_000L, plan.frameDurationNs)
        assertFalse(plan.aeLock)
    }

    @Test
    fun autoPlanOmitsSensorTrioAndKeepsUserEv() {
        // 2026-10-26 反馈 #1 方案A：AUTO 档 SENSOR 三键必须**缺席**（null = 不写）——
        // 部分原子不守约的 MTK HAL 把三键当钳制采纳，与连续对焦搜索互相拉扯 ⇒ 成片曝光闪动。
        // 残值清理由引擎在 AE 翻转时整 builder 重建承担（见 Camera2Engine.applyRepeatingNow）。
        val plan = RequestApplier.exposurePlan(state(), snapshot(AeMode.AUTO))
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, plan.aeMode)
        assertEquals("EV 用户值不许因清键丢失（clampEv(2)=2，混合键集根因之二）", 2, plan.aeComp)
        assertNull("AUTO 档 ISO 必须缺席（写了就会被不守约的 HAL 采纳）", plan.sensitivity)
        assertNull("AUTO 档曝光必须缺席", plan.exposureNs)
        assertNull("AUTO 档帧周期必须缺席", plan.frameDurationNs)
    }

    @Test
    fun sensorTrioIsPresentOnlyInManualAndDeterministic() {
        // 2026-10-26 语义更新：manual 档三键必须给出确定值（可重复）；
        // auto 档三键恒缺席（null）——残值清理由"翻转重建 builder"负责，不是计划层的事
        val s = state()
        val manual1 = RequestApplier.exposurePlan(s, snapshot(AeMode.MANUAL))
        val manual2 = RequestApplier.exposurePlan(s, snapshot(AeMode.MANUAL))
        val auto = RequestApplier.exposurePlan(s, snapshot(AeMode.AUTO))
        assertEquals("manual 三键可重复", manual1.sensitivity, manual2.sensitivity)
        assertEquals(manual1.exposureNs, manual2.exposureNs)
        assertEquals(manual1.frameDurationNs, manual2.frameDurationNs)
        assertNotNull(manual1.sensitivity)
        assertNotNull(manual1.exposureNs)
        assertNotNull(manual1.frameDurationNs)
        assertNull(auto.sensitivity)
        assertNotEquals(manual1.aeMode, auto.aeMode)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, manual1.aeMode)
        assertEquals(0, manual1.aeComp)
        assertEquals(2, auto.aeComp)
    }

    // -------------------------------------------------- 源码钉扎（判定只作用在 codeOnly 遮蔽后的文本上）

    @Test
    fun applyExposureReplaysTheWholeExposureKeySet() {
        val src = codeOnly(KotlinSourceScan.mainSourceText("camera/RequestApplier.kt"))
        val apply = bodyOf(src, "applyExposure")
        assertTrue(
            "applyExposure 必须消费 exposurePlan（退回 if(manual) 分支写键 = 混合键集回归）",
            KotlinSourceScan.occurrences(apply, "exposurePlan(state, p)").isNotEmpty()
        )
        assertEquals(
            "曝光组不许再按档分支写键（builder 没有删键，分支写法必然留残值）",
            0, KotlinSourceScan.occurrences(apply, "if (manual)").size
        )
        assertTrue("六键一次 set 全", KotlinSourceScan.occurrences(apply, "builder.set").size >= 6)
        for (key in listOf(
            "CONTROL_AE_LOCK", "CONTROL_AE_MODE", "CONTROL_AE_EXPOSURE_COMPENSATION",
            "SENSOR_SENSITIVITY", "SENSOR_EXPOSURE_TIME", "SENSOR_FRAME_DURATION"
        )) {
            assertTrue("曝光组键集必须覆盖 $key（任一缺失 = 该键又变残键）",
                KotlinSourceScan.occurrences(apply, key).isNotEmpty())
        }
        val plan = bodyOf(src, "exposurePlan")
        assertTrue(
            "manual 档 AE_COMP 置 0 必须在场（退回旧实现这条红）",
            KotlinSourceScan.occurrences(plan, "if (manual) 0 else").isNotEmpty()
        )
        for (field in listOf(
            "aeLock =", "aeMode =", "aeComp =", "sensitivity =", "exposureNs =", "frameDurationNs ="
        )) {
            assertTrue("exposurePlan 必须给出 $field 的确定值（auto 档也写 SENSOR 三键）",
                KotlinSourceScan.occurrences(plan, field).isNotEmpty())
        }
    }

    /**
     * 「apply/build 落在 try 内」的判据抽出来，尺子测试要拿 mutate 文本复验。
     *
     * 锚点从 `RequestApplier.apply` 换成 `applyParams(`（2026-10-26）：下发那一句包进了引擎的
     * 私有 helper（降噪残留账号的记账点与下发必须同处），但"整条路径必须在 try 内"这条 P1 纪律
     * 一字不变 —— helper 体内仍然调 RequestApplier.apply（见本文件另一条断言）。
     */
    private fun applyInsideTry(body: String): Boolean {
        val tryAt = KotlinSourceScan.occurrences(body, "try {")
        val applyAt = body.indexOf("applyParams(")
        return tryAt.isNotEmpty() && applyAt >= 0 && tryAt[0] < applyAt
    }

    @Test
    fun repeatingApplyWrapsTheWholePathAndRoutesIaeBySessionKind() {
        val src = codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt"))
        val apply = bodyOf(src, "applyRepeatingNow")
        // P1：syncFpsPick/apply/build 原先在 try 外，任何 RuntimeException 穿透即进程死亡。
        // 证伪：把 apply 挪回 try 外（或整体删 try）→ applyInsideTry 红
        //（indexOf 裸比较在 try 被删时为 -1 < 正数，会假绿，所以判据必须先验 try 在场）
        assertTrue("参数下发必须落在 try 之内（P1 逃逸通道回归点）", applyInsideTry(apply))
        assertEquals(
            "参数下发必须集中在 applyParams 一处（散成多份就会再次出现'某条通路的 try 覆盖漏了'）",
            1, KotlinSourceScan.occurrences(src, "RequestApplier.apply").size
        )
        // P2：IAE 原先不分会话类型一律 downgradeHighSpeed。证伪：删掉分流 →
        // "highSpeedSession != null" 从 catch 里消失 → occurrences == 0 红
        val branch = KotlinSourceScan.occurrences(apply, "highSpeedSession != null")
        assertTrue("IAE 必须按会话类型分流（普通会话不许进 downgradeHighSpeed）", branch.isNotEmpty())
        val downgrade = KotlinSourceScan.occurrences(apply, "downgradeHighSpeed")
        assertTrue("降级调用必须落在分流守卫之后（旧实现无条件降级，顺序断言红）",
            downgrade.isNotEmpty() && branch[0] < downgrade[0])
    }

    @Test
    fun flushTaskCarriesItsOwnTry() {
        // flushTask 是属性初始化器（非 fun），bodyOf 定位不到——仿 PillEnabledGuardTest 的花括号配对
        val src = codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt"))
        val decl = "private val flushTask = Runnable {"
        val at = src.indexOf(decl)
        assertTrue("flushTask 定义被改名/挪走，守卫失去输入", at >= 0)
        val open = src.indexOf('{', at)
        var depth = 0
        var end = -1
        for (i in open until src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        assertTrue("flushTask 体没有配平", end > open)
        val body = src.substring(open, end)
        assertTrue("flushTask 本体必须自带 try（markDirty 直发 Handler，不经 post() 的 catch）",
            KotlinSourceScan.occurrences(body, "try {").isNotEmpty())
        assertTrue("catch 里必须带参数快照取证", KotlinSourceScan.occurrences(body, "paramSnapshot()").isNotEmpty())
    }

    @Test
    fun evReadoutRequiresEvCapability() {
        val flat = KotlinSourceScan.flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")))
        assertTrue(
            "EV 读数显示判据必须含 ev.enabled（缺 EV 能力的机器显示假 +0.0EV，P3 同类口径）",
            flat.contains("aeMode == AeMode.AUTO && ev.enabled")
        )
    }

    @Test
    fun rulerItselfCanTurnRed() {
        // 良品先绿，mutate 后必红——两把尺各验一次（循环那组已随 hudCycleStep 删除）
        val flat = KotlinSourceScan.flatten(codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt")))
        assertTrue(flat.contains("aeMode == AeMode.AUTO && ev.enabled"))
        assertFalse(
            "ev.enabled 判据被删必须红",
            flat.replace(" && ev.enabled", "").contains("aeMode == AeMode.AUTO && ev.enabled")
        )

        val engine = codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt"))
        val apply = bodyOf(engine, "applyRepeatingNow")
        assertTrue(applyInsideTry(apply))
        assertFalse(
            "try 覆盖被摘（apply 回到 try 外）必须红",
            applyInsideTry(apply.replace("try {", "     "))
        )
    }
}

package com.wotagei.cam

import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.camera.RequestApplier
import com.wotagei.cam.core.AeMode
import com.wotagei.cam.core.AfMode
import com.wotagei.cam.core.Flash
import com.wotagei.cam.core.FpsPick
import com.wotagei.cam.core.HudItem
import com.wotagei.cam.core.ParamState
import com.wotagei.cam.core.RangeI
import com.wotagei.cam.core.Rect as WotaRect
import com.wotagei.cam.core.Stabilize
import com.wotagei.cam.core.WbPreset
import com.wotagei.cam.core.WotaParams
import com.wotagei.cam.core.WotaTiers
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.ui.hudCycleStep
import com.wotagei.cam.ui.manualExposureUsable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
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
 * 4. **AE 翻转闸**（审查 P3 修正版判据）：`hudCycleStep` 的 AUTO→MANUAL 翻转原先不查
 *    手动曝光能力（面板 `AeModeRow` 判 `manualExposurePossible() = iso.ok() && exposureNs.ok()`），
 *    点按循环只查 shutter 一半——本条用行为测试锁判据、用扫描锁两个分支都挂闸。
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
    fun autoPlanKeepsUserEvAndRewritesSensorKeysWithDeterministicValues() {
        val plan = RequestApplier.exposurePlan(state(), snapshot(AeMode.AUTO))
        assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, plan.aeMode)
        assertEquals("EV 用户值不许因清键丢失（clampEv(2)=2，混合键集根因之二）", 2, plan.aeComp)
        // auto 档也写 SENSOR 三键（AE_ON 下 HAL 按官方契约忽略），但值必须确定、域内、与 fps 自洽
        assertEquals(800, plan.sensitivity)
        assertEquals(20_000_000L, plan.exposureNs)
        assertEquals(40_000_000L, plan.frameDurationNs)
    }

    @Test
    fun sensorKeyValuesAreIdenticalAcrossTheAeFlip() {
        // 证伪「两分支互不清键」：旧实现 auto 分支根本不写 SENSOR 三键（值是 builder 里的残值），
        // 谈不到"两档同值"；新实现三键从同一套钳制算出，翻转只许改 AE_MODE/AE_COMP/AE_LOCK
        val s = state()
        val manual = RequestApplier.exposurePlan(s, snapshot(AeMode.MANUAL))
        val auto = RequestApplier.exposurePlan(s, snapshot(AeMode.AUTO))
        assertEquals("ISO 值两档同源", manual.sensitivity, auto.sensitivity)
        assertEquals("曝光时间值两档同源", manual.exposureNs, auto.exposureNs)
        assertEquals("帧周期值两档同源", manual.frameDurationNs, auto.frameDurationNs)
        assertNotEquals(manual.aeMode, auto.aeMode)
        assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, manual.aeMode)
        assertEquals(0, manual.aeComp)
        assertEquals(2, auto.aeComp)
    }

    // -------------------------------------------------- 行为测试：AE 翻转闸（判据与面板同源）

    private fun params(isoOk: Boolean, shutterOk: Boolean): WotaParams {
        val p = WotaParams(CoroutineScope(Dispatchers.Unconfined))
        // 与 applyAbility 缺能力产物同形：range=null 时 enabled=false（无解态 ParamState(lo,null,false) 同形）。
        // 快门 range 给全域宽档：ns 候选非空由 range 保证，不依赖 SHUTTER_DENOM 具体表——
        // 本组测的是「闸拦不拦」，候选空早退是既有用例（missingCapabilityReportsFailure）的事
        p.iso.value = if (isoOk) ParamState(100, 100..16000) else ParamState(100, null, enabled = false)
        p.shutter.value = if (shutterOk) ParamState(40_000_000L, 1L..100_000_000_000L)
        else ParamState(40_000_000L, null, enabled = false)
        return p
    }

    @Test
    fun gateMatchesPanelSemantics() {
        // 对照 AeModeRow 的 ability.manualExposurePossible() = iso.ok() && exposureNs.ok()：
        // 审查 P3 修正——只查 shutter 一半不够，iso 缺能力但曝光时间有的机器上点按循环
        // 会翻 MANUAL 而面板禁选，那正是要堵的口径分叉
        assertTrue("ISO+快门都有能力：放行", manualExposureUsable(params(isoOk = true, shutterOk = true)))
        assertFalse("ISO 缺能力必须拦（旧判据只查 shutter，这条红）", manualExposureUsable(params(isoOk = false, shutterOk = true)))
        assertFalse("快门缺能力必须拦", manualExposureUsable(params(isoOk = true, shutterOk = false)))
        assertFalse("双缺必须拦", manualExposureUsable(params(isoOk = false, shutterOk = false)))
    }

    @Test
    fun shutterTapIsRefusedWhenIsoCapabilityMissing() {
        val p = params(isoOk = false, shutterOk = true)
        assertFalse("缺手动能力时点快门不许翻 AE（返回 false 让 UI 出提示）", hudCycleStep(HudItem.SHUTTER, p))
        assertEquals("aeMode 不许被改走（退回旧实现这里变 MANUAL，红）", AeMode.AUTO, p.aeMode.value)
        assertEquals(40_000_000L, p.shutter.value.value)
    }

    @Test
    fun isoTapIsRefusedWhenShutterCapabilityMissing() {
        val p = params(isoOk = true, shutterOk = false)
        assertFalse("缺快门能力时点 ISO 同样不许翻 AE（闸对两个分支对称）", hudCycleStep(HudItem.ISO, p))
        assertEquals(AeMode.AUTO, p.aeMode.value)
        assertEquals(100, p.iso.value.value)
    }

    @Test
    fun wrapBackToAutoIsNotBlockedByTheGate() {
        // 护栏：闸只拦 AUTO→MANUAL 翻转，走完一圈回 AUTO 不经过闸——写反了（闸住整条链）
        // 会把用户锁死在手动档，这条红
        val p = params(isoOk = false, shutterOk = true)
        p.aeMode.value = AeMode.MANUAL
        val fastest = WotaTiers.SHUTTER_DENOM.map { WotaTiers.NS_PER_SECOND / it }.sortedDescending().last()
        p.shutter.value = ParamState(fastest, 1L..100_000_000_000L)
        assertTrue("走完一圈回 AUTO 不该被闸挡住", hudCycleStep(HudItem.SHUTTER, p))
        assertEquals(AeMode.AUTO, p.aeMode.value)
    }

    // -------------------------------------------------- 源码钉扎（判定只作用在 codeOnly 遮蔽后的文本上）

    @Test
    fun cycleFlipBranchesCarryTheGate() {
        // 证伪：删掉任一分支的闸调用 → occurrences < 2 红；把闸挪到赋值之后 → 顺序断言红。
        // 定位串用赋值形态 "value = AeMode.MANUAL"：函数体开头的 `!= AeMode.MANUAL`（aeAuto 判定）
        // 不含它，避免把"翻转赋值"定位到开头那行造成假红
        val body = bodyOf(codeOnly(KotlinSourceScan.mainSourceText("ui/HudCycle.kt")), "hudCycleStep")
        val gates = KotlinSourceScan.occurrences(body, "manualExposureUsable")
        assertTrue("SHUTTER 与 ISO 两个翻转分支都必须挂闸（缺一即混合口径回归）", gates.size >= 2)
        val firstFlip = body.indexOf("value = AeMode.MANUAL")
        assertTrue("闸必须出现在第一次翻 MANUAL 赋值之前（写在赋值之后等于没闸）",
            gates[0] < firstFlip && firstFlip >= 0)
    }

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

    /** 「apply/build 落在 try 内」的判据抽出来，尺子测试要拿 mutate 文本复验 */
    private fun applyInsideTry(body: String): Boolean {
        val tryAt = KotlinSourceScan.occurrences(body, "try {")
        return tryAt.isNotEmpty() && tryAt[0] < body.indexOf("RequestApplier.apply")
    }

    @Test
    fun repeatingApplyWrapsTheWholePathAndRoutesIaeBySessionKind() {
        val src = codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt"))
        val apply = bodyOf(src, "applyRepeatingNow")
        // P1：syncFpsPick/apply/build 原先在 try 外，任何 RuntimeException 穿透即进程死亡。
        // 证伪：把 apply 挪回 try 外（或整体删 try）→ applyInsideTry 红
        //（indexOf 裸比较在 try 被删时为 -1 < 正数，会假绿，所以判据必须先验 try 在场）
        assertTrue("RequestApplier.apply 必须落在 try 之内（P1 逃逸通道回归点）", applyInsideTry(apply))
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
        // 良品先绿，mutate 后必红——三把尺各验一次（写法对照 PillEnabledGuardTest.尺子自己能红）
        val cycle = codeOnly(KotlinSourceScan.mainSourceText("ui/HudCycle.kt"))
        val gates = KotlinSourceScan.occurrences(bodyOf(cycle, "hudCycleStep"), "manualExposureUsable")
        assertTrue(gates.size >= 2)
        val mutated = bodyOf(cycle.replace("if (!manualExposureUsable(params)) return false", "Unit"), "hudCycleStep")
        assertEquals("闸调用被删必须红", 0, KotlinSourceScan.occurrences(mutated, "manualExposureUsable").size)

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

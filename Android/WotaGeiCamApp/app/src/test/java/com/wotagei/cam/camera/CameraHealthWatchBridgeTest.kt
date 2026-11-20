package com.wotagei.cam.camera

import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.flatten
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y07 · 看门狗判活必须吃「代数 + 单调帧计数」的同代快照。
 *
 * ## 缺陷
 * 两处自愈看门狗（[MainActivity] 的 Activity 级循环、[CameraScreen] 的页面级循环）
 * 都只比裸帧计数（`cam != camLast`，初值 `-1`）。替代引擎的 `camResultTotal` 从 0 起步：
 * `-1 → 0 = 首次采样`、`N → 0 = 换了引擎` 在计数上都是"变化"，于是**一帧没出的新引擎被
 * 当成活着**，`camGrowAtMs` 被推到当下，4s/10s 的自愈计时整段重来——真死时看门狗延迟介入，
 * 用户黑屏多等一轮。
 *
 * ## 修法
 * 相机 owner（[CameraController]）发布 [CameraHealth]（引擎代 + 单调计数），每次 `bind`
 * 新建引擎 `engineEpoch++`；看门狗用 [CameraWatchClock] 消费：换代 = [CameraPulse.REBASED]
 * （只重建基线，**0 帧不算进展**），同代首次见帧 = [CameraPulse.FIRST_FRAMES] 才推进基准
 * 并放开上屏判据。
 */
class CameraHealthWatchBridgeTest {

    // ------------------------------------------------------------------ 状态机（纯行为）

    @Test
    fun `换代零帧不算进展_首帧到达才推进基准`() {
        val clock = CameraWatchClock(1_000L)

        assertEquals(
            "没有基线时首个快照必须重建基线",
            CameraPulse.REBASED,
            clock.observe(CameraHealth(epoch = 1L, frameCount = 0L), 1_000L)
        )
        assertEquals(1_000L, clock.grewAtMs)
        assertFalse("0 帧不许启用上屏判据", clock.everSawFrames)

        // 同一台新引擎连续 4 秒 0 帧：旧实现把 -1→0 当涨帧；现在必须"没进展"。
        assertEquals(
            CameraPulse.STILL,
            clock.observe(CameraHealth(epoch = 1L, frameCount = 0L), 5_000L)
        )
        assertEquals("0 帧期间基准时刻不许被推进（否则 4s 判死永远到不了点）", 1_000L, clock.grewAtMs)
        assertFalse(clock.everSawFrames)

        // 首帧到达才推进基准。
        assertEquals(
            CameraPulse.FIRST_FRAMES,
            clock.observe(CameraHealth(epoch = 1L, frameCount = 1L), 6_000L)
        )
        assertEquals(6_000L, clock.grewAtMs)
        assertTrue("首帧之后才允许上屏判据", clock.everSawFrames)

        // 正常涨帧推进；计数不动不推进。
        assertEquals(
            CameraPulse.ADVANCED,
            clock.observe(CameraHealth(epoch = 1L, frameCount = 2L), 7_000L)
        )
        assertEquals(7_000L, clock.grewAtMs)
        assertEquals(
            CameraPulse.STILL,
            clock.observe(CameraHealth(epoch = 1L, frameCount = 2L), 9_000L)
        )
        assertEquals(7_000L, clock.grewAtMs)

        // 换代（替代引擎计数变小）：重建基线，不把"变小"误判成进展；首帧前关掉上屏判据。
        assertEquals(
            CameraPulse.REBASED,
            clock.observe(CameraHealth(epoch = 2L, frameCount = 0L), 10_000L)
        )
        assertEquals(10_000L, clock.grewAtMs)
        assertFalse(clock.everSawFrames)
        assertEquals(
            CameraPulse.STILL,
            clock.observe(CameraHealth(epoch = 2L, frameCount = 0L), 14_000L)
        )
        assertEquals("换引擎后 0 帧的 4 秒窗必须真的走满", 10_000L, clock.grewAtMs)

        // 引擎不在：保留基准继续计时（到点由调用方重绑），但不许拿旧代计数喂上屏判据。
        assertEquals(CameraPulse.STILL, clock.observe(null, 15_000L))
        assertEquals(10_000L, clock.grewAtMs)
        assertFalse(clock.everSawFrames)

        // 显式 arm（暂停/录制/重建后）：基准推到当下，同样清零"见过帧"。
        clock.arm(20_000L)
        assertEquals(20_000L, clock.grewAtMs)
        assertFalse(clock.everSawFrames)
    }

    // ------------------------------------------------------------------ 桥（真实 Controller → Engine → 时钟）

    @Test
    fun `控制器换代发号_替代引擎从零帧起步且首帧才推进真实时钟`() {
        CameraRecoveryStartFixture(javaClass.classLoader).use { p ->
            p.bind()
            val first = health(p) ?: error("bind 之后必须有引擎健康快照")
            assertEquals("新引擎在出帧前计数必须是 0", 0L, frameCount(p, first))
            val firstEpoch = epoch(p, first)

            val clockClass = p.platform.type("com.wotagei.cam.camera.CameraWatchClock")
            val clock = clockClass
                .getConstructor(Long::class.javaPrimitiveType)
                .newInstance(1_000L)
            assertEquals(
                "首个快照必须重建基线（不许把 -1→0 当涨帧）",
                "REBASED",
                pulse(p, clock, first, 1_000L)
            )
            assertEquals(1_000L, grewAt(p, clock))
            assertEquals(
                "同一台 0 帧引擎 4 秒后必须 STILL（旧实现这里已经当成进展了）",
                "STILL",
                pulse(p, clock, first, 5_000L)
            )
            assertEquals("0 帧不许推进真实时钟的基准", 1_000L, grewAt(p, clock))

            // 喂一拍：计数涨 1，时钟才允许推进并放开上屏判据。
            deliverFrame(p)
            val second = health(p) ?: error("引擎必须在")
            assertEquals(1L, frameCount(p, second))
            assertEquals("首帧必须 FIRST_FRAMES", "FIRST_FRAMES", pulse(p, clock, second, 6_000L))
            assertEquals(6_000L, grewAt(p, clock))

            // 换代：替代引擎仍在 0 帧——必须重建基线且不算进展。
            p.rebind()
            val replacement = health(p) ?: error("forceRebind 之后必须有新引擎健康快照")
            assertEquals("替代引擎的帧计数必须从 0 起步", 0L, frameCount(p, replacement))
            assertEquals("每次新建引擎必须发一枚新代", firstEpoch + 1, epoch(p, replacement))
            assertEquals("换代必须 REBASED", "REBASED", pulse(p, clock, replacement, 7_000L))
            assertEquals(7_000L, grewAt(p, clock))
            assertEquals(
                "替代引擎 4 秒 0 帧不许视为进展（本轮缺陷的核心）",
                "STILL",
                pulse(p, clock, replacement, 11_000L)
            )
            assertEquals(
                "换代后的基准必须停在 REBASED 时刻，4s 判死才能到点",
                7_000L,
                grewAt(p, clock)
            )

            // 替代引擎首帧到达才推进。
            repeat(2) { deliverFrame(p, p.engine()) }
            val live = health(p) ?: error("引擎必须在")
            assertEquals(2L, frameCount(p, live))
            assertEquals("替代引擎首帧必须 FIRST_FRAMES", "FIRST_FRAMES", pulse(p, clock, live, 12_000L))
            assertEquals(12_000L, grewAt(p, clock))
        }
    }

    // ------------------------------------------------------------------ 接线守卫

    private val activitySrc by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("ui/MainActivity.kt"))
    }
    private val screenSrc by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("ui/CameraScreen.kt"))
    }
    private val ctrlSrc by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("camera/CameraController.kt"))
    }
    private val healthSrc by lazy {
        codeOnly(KotlinSourceScan.mainSourceText("camera/CameraHealth.kt"))
    }

    private fun watchdogIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        if (occurrences(src, "CameraWatchClock").isEmpty()) {
            bad.add("看门狗没有心跳时钟——判活基准仍由本地变量维护，换代后无从对齐")
        }
        if (occurrences(src, "ctrl.cameraHealth()").isEmpty()) {
            bad.add("看门狗没读相机健康快照——代数与帧计数不是同代读出")
        }
        if (occurrences(src, "clock.observe(health, nowMs)").isEmpty()) {
            bad.add("看门狗没消费代数裁决（REBASED/FIRST_FRAMES/STILL）")
        }
        if (occurrences(src, "clock.grewAtMs").isEmpty()) {
            bad.add("无帧计时没用时钟基准——换代/0 帧会被当成进展，自愈计时白等一轮")
        }
        if (occurrences(src, "camLast").isNotEmpty()) {
            bad.add("仍有裸 camLast 跨代比较（替代引擎 0 帧会被当成涨帧）")
        }
        if (occurrences(src, "camSeenFrames").isNotEmpty()) {
            bad.add("上屏判据仍用本地「见过帧」变量——必须由时钟 everSawFrames 提供同代语义")
        }
        return bad
    }

    private fun controllerIssues(ctrl: String): List<String> {
        val bad = ArrayList<String>()
        val bind = runCatching { bodyOf(ctrl, "bind") }.getOrNull()
            ?: return listOf("定位不到 CameraController.bind 的体——守卫失去输入，不许算通过")
        val epochAt = occurrences(ctrl, "engineEpoch++").firstOrNull()
        val ctorAt = occurrences(ctrl, "engine = Camera2Engine(").firstOrNull()
        if (epochAt == null || ctorAt == null || epochAt > ctorAt) {
            bad.add("新引擎必须在构造之前发号（engineEpoch++）——否则看门狗分不清换代与涨帧")
        }
        if (occurrences(ctrl, "fun cameraHealth(): CameraHealth?").isEmpty()) {
            bad.add("门面没有健康快照入口 cameraHealth()")
        }
        if (occurrences(ctrl, "CameraHealth(engineEpoch").isEmpty()) {
            bad.add("健康快照没带引擎代——跨代计数仍不可比")
        }
        if (occurrences(ctrl, "it.frameResultCount()").isEmpty()) {
            bad.add("健康快照没读引擎单调计数")
        }
        if (occurrences(bind, "engineEpoch++").isEmpty()) {
            bad.add("发号不在 bind 里——换代路径（forceRebind 也走 bind）会漏号")
        }
        return bad
    }

    private fun clockIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        val observe = runCatching { bodyOf(src, "observe") }.getOrNull()
            ?: return listOf("定位不到 CameraWatchClock.observe 的体——守卫失去输入")
        if (occurrences(observe, "health.epoch != epoch").isEmpty()) {
            bad.add("换代判据缺失——不同代的计数被直接相比")
        }
        if (occurrences(observe, "health.frameCount == frameCount").isEmpty()) {
            bad.add("同代计数未动的判据缺失——STILL 无法成立")
        }
        if (occurrences(observe, "CameraPulse.REBASED").isEmpty()) {
            bad.add("换代没有 REBASED 裁决")
        }
        if (occurrences(observe, "CameraPulse.FIRST_FRAMES").isEmpty()) {
            bad.add("首帧没有 FIRST_FRAMES 裁决（0 帧与首帧分不开）")
        }
        if (occurrences(observe, "grewAtMs = nowMs").isEmpty()) {
            bad.add("裁决不推进基准时刻——时间基准不在时钟里")
        }
        val arm = runCatching { bodyOf(src, "arm") }.getOrNull()
            ?: return bad + "定位不到 CameraWatchClock.arm 的体"
        if (occurrences(arm, "everSawFrames = false").isEmpty()) {
            bad.add("arm 没有清零「见过帧」——重建后上屏判据会带着旧代结论启用")
        }
        return bad
    }

    private val activityWatchBody by lazy {
        flatten(bodyOf(activitySrc, "onRouteForPreviewWatch"))
    }

    @Test
    fun `两处看门狗都走同代健康快照`() {
        assertEquals(
            "Activity 级看门狗接线缺件：\n${watchdogIssues(activityWatchBody).joinToString("\n")}",
            emptyList<String>(),
            watchdogIssues(activityWatchBody)
        )
        assertEquals(
            "页面级看门狗接线缺件：\n${watchdogIssues(screenSrc).joinToString("\n")}",
            emptyList<String>(),
            watchdogIssues(screenSrc)
        )
    }

    @Test
    fun `门面按引擎代发布健康快照`() {
        assertEquals(
            "健康快照接线缺件：\n${controllerIssues(ctrlSrc).joinToString("\n")}",
            emptyList<String>(),
            controllerIssues(ctrlSrc)
        )
        assertEquals(
            "心跳时钟接线缺件：\n${clockIssues(healthSrc).joinToString("\n")}",
            emptyList<String>(),
            clockIssues(healthSrc)
        )
    }

    @Test
    fun `尺子自己能红_退回裸计数与摘掉换代裁决都必须报红`() {
        // 真身必须绿：否则下面的突变"红"可能来自守卫本身坏掉。
        assertTrue(controllerIssues(ctrlSrc).isEmpty())
        assertTrue(clockIssues(healthSrc).isEmpty())
        assertTrue(watchdogIssues(activityWatchBody).isEmpty())
        assertTrue(watchdogIssues(screenSrc).isEmpty())

        // 突变 1：门面不发号（缺陷原形：跨代计数不可比）。
        val noEpoch = ctrlSrc.replace("engineEpoch++", "")
        assertTrue(
            "不发号必须红",
            controllerIssues(noEpoch).any { it.contains("发号") }
        )

        // 突变 2：健康快照丢掉代数。
        val epochless = ctrlSrc.replace("CameraHealth(engineEpoch,", "CameraHealth(0L,")
        assertTrue(
            "快照不带代必须红",
            controllerIssues(epochless).any { it.contains("引擎代") }
        )

        // 突变 3：时钟不分辨换代（把换代当同代继续比计数）。
        val noRebase = healthSrc.replace("if (health.epoch != epoch) {", "if (false) {")
        assertTrue(
            "不分辨换代必须红",
            clockIssues(noRebase).any { it.contains("换代") }
        )

        // 突变 4：Activity 看门狗退回裸计数比较。
        val rawCompare = activityWatchBody.replace(
            "clock.observe(health, nowMs)",
            "CameraPulse.STILL"
        )
        assertTrue(
            "不消费代数裁决必须红",
            watchdogIssues(rawCompare).any { it.contains("代数裁决") }
        )
        val reintroduced = activityWatchBody.replace(
            "val clock = CameraWatchClock(",
            "var camLast = -1L\nval clock = CameraWatchClock("
        )
        assertTrue(
            "退回裸 camLast 必须红",
            watchdogIssues(reintroduced).any { it.contains("camLast") }
        )

        // 突变 5：页面看门狗读裸计数而不是健康快照。
        val rawHealth = screenSrc.replace("ctrl.cameraHealth()", "ctrl.frameResultCount()")
        assertTrue(
            "页面看门狗不走健康快照必须红",
            watchdogIssues(rawHealth).any { it.contains("健康快照") }
        )
    }

    // ------------------------------------------------------------------ 夹具搬运

    private fun health(p: CameraRecoveryStartFixture): Any? =
        p.platform.call(p.controller, "cameraHealth")

    private fun epoch(p: CameraRecoveryStartFixture, health: Any): Long =
        p.platform.call(health, "getEpoch") as Long

    private fun frameCount(p: CameraRecoveryStartFixture, health: Any): Long =
        p.platform.call(health, "getFrameCount") as Long

    /** 直接喂生产高速回执（只维护帧账与判活计数，不碰白平衡/曝光）。 */
    private fun deliverFrame(p: CameraRecoveryStartFixture, engine: Any = p.engine()) {
        val callback = p.platform.field(engine, "frameCountCallback")
            ?: error("真实 Camera2Engine 没有 frameCountCallback")
        // 生产回调参数是平台非空类型；反射喂 null 会在 Kotlin 入口的空检查处直接 NPE。
        // 桩回执只用于帧账，三段都给真实实例，不放松被测判据。
        p.platform.call(
            callback, "onCaptureCompleted",
            p.platform.new("android.hardware.camera2.CameraCaptureSession"),
            p.platform.new("android.hardware.camera2.CaptureRequest"),
            p.platform.new("android.hardware.camera2.TotalCaptureResult"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun pulse(p: CameraRecoveryStartFixture, clock: Any, health: Any?, nowMs: Long): String =
        (p.platform.call(clock, "observe", health, nowMs) as Enum<*>).name

    private fun grewAt(p: CameraRecoveryStartFixture, clock: Any): Long =
        p.platform.call(clock, "getGrewAtMs") as Long
}
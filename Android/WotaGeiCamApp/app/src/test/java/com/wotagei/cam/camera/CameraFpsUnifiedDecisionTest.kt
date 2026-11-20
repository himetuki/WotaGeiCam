package com.wotagei.cam.camera

import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y05 行为 + 接线：fps 的「会话模态 / 下发区间 / 退档 / 提示」只许有一个决议出口
 * （[com.wotagei.cam.core.CameraAbility.decideFps]，普通表先来）。
 *
 * ## 缺陷
 * `syncFpsPick` 的**通用退档**（目标档两张表都不覆盖）取 `state.fpsRanges`：高速态下那就是
 * 高速表，退档会写回一个「界面与普通流都对不上」的值；而且旧实现只改总线、不调
 * `onFpsDowngraded`——用户看到帧率自己变了却没有任何提示。会话类型那边（open /
 * ensureSession / onStructuralPulse）另写 `highSpeedCapable && needsHighSpeed(...)`，
 * 与 UI 落档用的 `decideFps` 是第二份判据，两边各改一次就会出现「界面按普通档显示、引擎按高速档开会话」。
 *
 * ## 修法
 * 1. [Camera2Engine.fpsDecision] 是唯一出口：open / wantHighSpeed / onStructuralPulse /
 *    syncFpsPick / downgradeHighSpeed 全部消费它；
 * 2. 通用退档候选取**普通表**（`normalFpsRangesOf`），并走与高速失败同一个 `onFpsDowngraded`；
 * 3. 决议要求换会话模态时 `syncFpsPick` 返回 true，本拍不 build，交 `ensureSession` 重建后重放。
 *
 * 行为用例走真实 Controller/Engine：设备只有普通表 `[25,25]`/`[30,30]`（无高帧率能力）。
 * 表外档位 120 只能退到 30——总线、真实下发请求、界面通知三处必须一致；表内档位则一字不改。
 */
class CameraFpsUnifiedDecisionTest {

    @Test
    fun `表外档位退到普通表上限且总线请求通知三处一致`() {
        CameraRecoveryStartFixture(
            javaClass.classLoader,
            recordingRequests(),
            fpsRanges = "new android.util.Range[]{new android.util.Range<Integer>(25,25)," +
                "new android.util.Range<Integer>(30,30)}",
        ).use { p ->
            p.bind()
            p.start()
            p.drain()
            p.completeOpen(0)
            p.completeSession(0)

            // 起点：默认 1/25 在表内——不许被退档改写，也不许发提示
            assertEquals("起点 25 在普通表内，不许被改写", 25, fpsValue(p))
            assertNull("表内档位不得发降级通知", downgraded(p))
            assertRequestRange(p, "起点必须按 UI 落档的 [25,25] 下发", 25, 25)

            // 交错 1：表外 120（本机无高帧率能力）⇒ 通用退档到普通表上限 30
            val before = builtRequests(p).size
            p.setFps(120)
            p.drain()
            p.runDelayed()
            assertEquals("最终 fps 必须落在 UI 可选档位上（普通表上限 30）", 30, fpsValue(p))
            assertEquals("退档回写必须是精确档（UI 对 30 落 [30,30]）", true, fpsExact(p))
            assertTrue("退档后总线档位必须可用（不许把灰显态带回去）", fpsEnabled(p))
            assertEquals("通用退档必须走同一通知出口（旧实现只改总线）", 30, downgraded(p))
            assertTrue("退档必须真的重发一次请求", builtRequests(p).size > before)
            assertRequestRange(p, "下发区间必须跟随新档位", 30, 30)
            assertEquals("引擎决议与总线同源", 30, p.platform.call(chosenFps(p), "getLo"))
            assertEquals("引擎决议与总线同源", 30, p.platform.call(chosenFps(p), "getHi"))

            // 交错 2：用户改回表内 25 ⇒ 最终 fps 与 UI 选择一致，且不再报降级
            p.platform.call(p.controller, "clearFpsDowngrade")
            assertNull("清槽后不许残留提示", downgraded(p))
            p.setFps(25, 25..25, exact = true)
            p.drain()
            p.runDelayed()
            assertEquals("表内档位必须原样落到总线", 25, fpsValue(p))
            assertNull("表内档位不许重复报降级（提示不是闩锁）", downgraded(p))
            assertRequestRange(p, "表内档位下发区间回到 UI 落档", 25, 25)

            // 交错 3：再来一次表外 ⇒ 退档与提示都必须再次发生（不是一次性闩锁）
            p.setFps(120)
            p.drain()
            p.runDelayed()
            assertEquals(30, fpsValue(p))
            assertEquals("降级提示必须每次自动降档都发", 30, downgraded(p))
            assertRequestRange(p, "再次退档的下发区间仍跟随新档位", 30, 30)
        }
    }

    // ------------------------------------------------------------------ 接线守卫（同一决议出口）

    private val engine by lazy { codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")) }

    /** 五个消费点各查一遍；空 = 同一出口接线完整 */
    private fun outletIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        // 会话类型 / 下发 / 退档都要落在 decideFps（或其引擎私有别名 fpsDecision）上
        for (name in listOf("open", "wantHighSpeed", "onStructuralPulse", "syncFpsPick", "downgradeHighSpeed")) {
            val body = runCatching { bodyOf(src, name) }.getOrNull()
                ?: return bad + "定位不到 $name 的体——守卫失去输入，不许算通过"
            if (occurrences(body, "decideFps").isEmpty() && occurrences(body, "fpsDecision").isEmpty()) {
                bad.add("$name 没走统一择档出口（decideFps/fpsDecision）——第二份判据会让界面与下发分叉")
            }
            // [occurrences] 是**词边界**匹配：needle 以 `(` 收尾时，紧随其后的实参首字符是
            // 标识符字符 ⇒ 调用永远不被计数。这里按标识符本身判（括号由 decideFps 那条反向排）。
            if (occurrences(body, "needsHighSpeed").isNotEmpty()) {
                bad.add("$name 仍另算 needsHighSpeed(...)——会话模态又出现了第二份判据")
            }
        }
        val sync = bodyOf(src, "syncFpsPick")
        if (occurrences(sync, "onFpsDowngraded").isEmpty()) {
            bad.add("syncFpsPick 的通用退档没发 onFpsDowngraded——用户只看到帧率自己变了")
        }
        if (occurrences(sync, "pickFps(state.fpsRanges").isNotEmpty()) {
            bad.add("退档区间仍直接吃 pickFps(state.fpsRanges)（高速态下那是高速表，写回值对不上普通流）")
        }
        val ceiling = bodyOf(src, "ceilingFps")
        if (occurrences(ceiling, "normalFpsRangesOf").isEmpty()) {
            bad.add("ceilingFps 没有走普通表真源 normalFpsRangesOf——退档候选可能落在高速表上")
        }
        return bad
    }

    @Test
    fun `会话类型与退档提示接线在同一决议出口`() {
        assertEquals(
            "fps 决议出口发现断点：\n${outletIssues(engine).joinToString("\n")}",
            emptyList<String>(),
            outletIssues(engine)
        )
    }

    @Test
    fun `尺子自己能红_另算会话类型与摘掉退档提示必须报红`() {
        assertTrue("真身必须绿", outletIssues(engine).isEmpty())

        // 突变 1：onStructuralPulse 退回旧口径（highSpeedCapable && needsHighSpeed）
        val oldModality = engine.replace(
            "val highSpeed = slotAbility.decideFps(pulse.size, pulse.fps)?.highSpeed == true",
            "val highSpeed = slotAbility.highSpeedCapable && slotAbility.needsHighSpeed(pulse.size, pulse.fps)"
        )
        assertTrue(
            "会话类型另算一份必须红",
            outletIssues(oldModality).any { it.contains("onStructuralPulse") }
        )

        // 突变 2：open 退回旧口径（开场就与 UI 分叉）
        val oldOpen = engine.replace(
            "slot.ability.decideFps(params.size.value, fpsValue())?.highSpeed == true",
            "slot.ability.highSpeedCapable && slot.ability.needsHighSpeed(params.size.value, fpsValue())"
        )
        assertTrue(
            "开场会话类型另算必须红",
            outletIssues(oldOpen).any { it.contains("open") }
        )

        // 突变 3：摘掉通用退档的提示（旧行为：只改总线）
        val silent = engine.replace("onFpsDowngraded(ceiling)", "")
        assertTrue(
            "退档提示被摘必须红",
            outletIssues(silent).any { it.contains("onFpsDowngraded") }
        )

        // 突变 4：退档候选退回状态表（高速态下是高速表）
        val stateTable = engine.replace(
            "val ranges = normalFpsRangesOf().ifEmpty { state.fpsRanges }",
            "val ranges = state.fpsRanges"
        )
        assertTrue(
            "退档候选退回 state.fpsRanges 必须红",
            outletIssues(stateTable).any { it.contains("normalFpsRangesOf") }
        )

        // 突变 5：退档区间改回直接 pickFps(state.fpsRanges)
        val oldPick = engine.replace(
            "val fallback = fpsDecision(ceiling)?.pick",
            "val fallback = RequestApplier.pickFps(state.fpsRanges, ceiling, false)"
        )
        assertTrue(
            "退档区间直接吃 state.fpsRanges 必须红",
            outletIssues(oldPick).any { it.contains("pickFps(state.fpsRanges") }
        )
    }

    // ------------------------------------------------------------------ 夹具观测

    private fun params(p: CameraRecoveryStartFixture): Any =
        p.platform.call(p.controller, "getParams") ?: error("CameraController 没有 params")

    private fun fpsState(p: CameraRecoveryStartFixture): Any {
        val flow = p.platform.call(params(p), "getFps") ?: error("WotaParams 没有 fps")
        return p.platform.call(flow, "getValue") ?: error("fps StateFlow 没有值")
    }

    private fun fpsValue(p: CameraRecoveryStartFixture): Int =
        p.platform.call(fpsState(p), "getValue") as Int

    private fun fpsExact(p: CameraRecoveryStartFixture): Boolean =
        p.platform.call(fpsState(p), "getExact") as Boolean

    private fun fpsEnabled(p: CameraRecoveryStartFixture): Boolean =
        p.platform.call(fpsState(p), "getEnabled") as Boolean

    /** 界面提示槽（`CameraController.fpsDowngraded` 的当前值；null = 没有待读的降级） */
    private fun downgraded(p: CameraRecoveryStartFixture): Any? =
        p.platform.call(
            p.platform.call(p.controller, "getFpsDowngraded")
                ?: error("CameraController 没有 fpsDowngraded"),
            "getValue"
        )

    private fun chosenFps(p: CameraRecoveryStartFixture): Any =
        p.platform.field(p.engine(), "chosenFps") ?: error("引擎还没有帧率决议（chosenFps == null）")

    /** 最近一次 build 出来的请求里的 `CONTROL_AE_TARGET_FPS_RANGE` 上下界 */
    private fun assertRequestRange(p: CameraRecoveryStartFixture, label: String, lo: Int, hi: Int) {
        val request = builtRequests(p).lastOrNull() ?: error("$label：没有任何 build 过的请求")
        val range = checkNotNull(request["CONTROL_AE_TARGET_FPS_RANGE"]) {
            "$label：请求必须带 CONTROL_AE_TARGET_FPS_RANGE"
        }
        assertEquals("$label：区间下界", lo, p.platform.call(range, "getLower"))
        assertEquals("$label：区间上界", hi, p.platform.call(range, "getUpper"))
    }

    private fun builtRequests(p: CameraRecoveryStartFixture): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val built = p.platform.type("android.hardware.camera2.CaptureRequest")
            .getField("BUILT").get(null) as List<Map<String, Any?>>
        return built
    }

    companion object {
        private fun keys(): String = CaptureRequest::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} " +
                    "${field.name} = new Key<>(\"${field.name}\");"
            }

        /**
         * 只替换 `CaptureRequest`：Builder 记录 set 过的键，build() 把键值快照追加到静态 `BUILT`。
         * 会话/设备等其余边界沿用 [CameraRecoveryStartFixture] 的默认桩。
         */
        private fun recordingRequests(): Map<String, String> = mapOf(
            "android.hardware.camera2.CaptureRequest" to """
                package android.hardware.camera2;
                public class CaptureRequest {
                    public static class Key<T> {
                        public final String name;
                        public Key(String n){name=n;}
                        public String getName(){return name;}
                    }
                    ${keys()}
                    public static final java.util.List<java.util.Map<String,Object>> BUILT=new java.util.ArrayList<>();
                    public static class Builder {
                        private final java.util.Map<Key<?>,Object> values=new java.util.HashMap<>();
                        public <T>void set(Key<T> key,T value){values.put(key,value);}
                        @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)values.get(key);}
                        public void addTarget(android.view.Surface surface){}
                        public CaptureRequest build(){
                            java.util.Map<String,Object> copy=new java.util.HashMap<>();
                            for(java.util.Map.Entry<Key<?>,Object> entry:values.entrySet())copy.put(entry.getKey().name,entry.getValue());
                            BUILT.add(copy);
                            return new CaptureRequest();
                        }
                    }
                }
            """
        )
    }
}
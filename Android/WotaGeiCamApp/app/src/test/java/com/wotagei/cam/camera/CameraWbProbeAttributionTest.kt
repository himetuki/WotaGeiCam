package com.wotagei.cam.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.source.KotlinSourceScan
import com.wotagei.cam.source.KotlinSourceScan.bodyOf
import com.wotagei.cam.source.KotlinSourceScan.codeOnly
import com.wotagei.cam.source.KotlinSourceScan.occurrences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y06 · 手动白平衡判负的**归属**（行为 + 接线守卫）。
 *
 * ## 缺陷
 * `onCaptureCompleted` 用**回报到达时**的总线值判「这是不是手动拍」：
 * `val manual = RequestApplier.isManualWhiteBalance(params.wbMode.value)`。
 * 回报天然滞后请求 2~4 帧，于是三类本来就走自动白平衡的回报被计进手动判负：
 * ① 用户刚切到手动、改档前发出的 AUTO 请求的在途回报；
 * ② 种子窗（HAL 种子未到手，`applyWhiteBalance` 这拍留在 AUTO）/ 会话稳压窗；
 * ③ 旧会话 / 旧世代迟到的回报。
 * 连续 4 拍就翻 `wbHalRejected`——HAL 明明没被验过，白平衡却被闩到画面算法。
 *
 * ## 修法
 * 判负归属 = `总线仍是手动` **且** `manualWbRequestOf(session, request)`：
 * ① 会话身份 = 当前会话（旧会话/旧世代按代数丢弃；切镜头必换会话，镜头身份一并锁住）；
 * ② 请求实际写键 = `CONTROL_AWB_MODE=OFF` **且** `COLOR_CORRECTION_MODE=TRANSFORM_MATRIX`
 *    （`applyWhiteBalance` 只在「手动档 + 种子到手 + 不走画面算法」时写这对键）。
 * 读键失败按「不是手动请求」处理（fail-closed）：宁可少判负，不许把自动回报计进手动判负。
 *
 * 行为用例走真实 Controller/Engine：设备**明确声明** TRANSFORM_MATRIX（`getKeys` 之外的隐藏键
 * `android.colorCorrection.availableModes` 必须真的出现在 `keys` 里才会被读到），手动档 + 种子
 * 到手 ⇒ 探针是活的。直接调引擎的 `captureCallback.onCaptureCompleted` 喂三类回报：
 * AUTO 请求、旧会话的手动请求、当前会话的手动请求。
 */
class CameraWbProbeAttributionTest {

    @Test
    fun `AUTO回报与旧会话迟到回报都不计入手动判负`() {
        CameraRecoveryStartFixture(javaClass.classLoader, stubs()).use { p ->
            p.bind()
            p.start()
            p.drain()
            p.completeOpen(0)
            p.completeSession(0)

            val engine = p.engine()
            val params = p.platform.call(p.controller, "getParams") ?: error("CameraController 没有 params")
            // 总线：手动档（判负分支的入口条件）
            setEnum(p, params, "getWbMode", "com.wotagei.cam.core.WbPreset", "MANUAL")
            p.platform.set(engine, "lastAwbCcm9", FloatArray(9))
            // 桩钟 elapsedRealtime() 恒 0：会话配成时布的稳压窗（now+800ms）永远「未到期」。
            // 这里把窗直接关掉 = 模拟会话已装稳（否则 wbByGlNow 恒真，探针整段不跑，断言全是空转）。
            p.platform.set(engine, "wbHoldUntilMs", 0L)
            val session = p.platform.field(engine, "session")
                ?: error("引擎没有当前会话（会话配成后必须存在）")
            val failing = failingResult(p)
            val autoRequest = request(p, manual = false)
            val manualRequest = request(p, manual = true)
            val startTotal = p.platform.field(engine, "camResultTotal") as Long

            // ① AUTO 请求的回报：总线现在是手动，但这几拍请求写的是 AUTO+FAST。
            // 旧实现按总线判 manual=true、种子也在手 ⇒ 第 4 拍就翻闩锁。
            repeat(5) { deliver(p, engine, session, autoRequest, failing) }
            assertEquals(
                "回调必须先真的被走到（否则下面的 0 是空转）",
                startTotal + 5,
                p.platform.field(engine, "camResultTotal") as Long
            )
            assertFalse("AUTO 回报不许计入手动判负（旧实现 4 拍即误闩）", halRejected(p, params))
            assertEquals("AUTO 回报不许累计探测失败", 0, intField(p, engine, "wbProbeFails"))
            assertEquals("AUTO 回报不许累计 AWB-未停判负", 0, intField(p, engine, "wbAwbActiveFails"))
            assertEquals("AUTO 回报不许累计增益采纳判负", 0, intField(p, engine, "wbAdoptFails"))

            // ② 旧会话迟到的「手动请求」回报：按会话代数丢弃，不许计进当前会话的手动判负。
            val stale = p.platform.new("android.hardware.camera2.CameraCaptureSession")
            repeat(6) { deliver(p, engine, stale, manualRequest, failing) }
            assertFalse("旧会话迟到回报不许计入当前会话的手动判负", halRejected(p, params))
            assertEquals(0, intField(p, engine, "wbProbeFails"))
            assertEquals(0, intField(p, engine, "wbAwbActiveFails"))

            // ③ 对照：当前会话 + 真写了 OFF+TRANSFORM 的请求 ⇒ 判负必须照旧生效。
            // 这一段证明上面的 0 不是因为探针被整体关掉（不然「不计负」是假绿）。
            repeat(4) { deliver(p, engine, session, manualRequest, failing) }
            assertTrue(
                "当前会话的手动请求连续 4 拍不采纳，必须仍然翻闩锁（探针没被禁用）",
                halRejected(p, params)
            )
            assertEquals("翻闩的那一拍必须清零连续计数", 0, intField(p, engine, "wbProbeFails"))
            assertEquals(
                "AWB-未停判据必须照旧累计（闩锁已由探测分支先翻，计数保留本轮值）",
                4,
                intField(p, engine, "wbAwbActiveFails")
            )
        }
    }

    // ------------------------------------------------------------------ 接线守卫（请求/会话归属）

    private val engine by lazy { codeOnly(KotlinSourceScan.mainSourceText("camera/Camera2Engine.kt")) }

    /** 仅在遮蔽文本中把具名对象声明头转为函数锚（同 WbFallbackPlanTest 的两层定位手法）。 */
    private fun normalCaptureCompleted(engine: String): String {
        val callback = bodyOf(
            engine.replace(
                "private val captureCallback = object : CameraCaptureSession.CaptureCallback()",
                "private fun captureCallback()"
            ),
            "captureCallback"
        )
        return bodyOf(callback, "onCaptureCompleted")
    }

    /** 判负归属的接线问题；空 = 干净 */
    private fun attributionIssues(src: String): List<String> {
        val bad = ArrayList<String>()
        val callback = runCatching { normalCaptureCompleted(src) }.getOrNull()
            ?: return listOf("定位不到普通 captureCallback.onCaptureCompleted——守卫失去输入，不许算通过")
        if (occurrences(callback, "manualWbRequestOf").isEmpty()) {
            bad.add("手动判负仍只看总线（manualWbRequestOf 缺失）——在途 AUTO 回报会被计进手动判负")
        }
        val helper = runCatching { bodyOf(src, "manualWbRequestOf") }.getOrNull()
            ?: return bad + "定位不到 manualWbRequestOf 的体——守卫失去输入"
        if (occurrences(helper, "session !== this.session").isEmpty()) {
            bad.add("归属没锁会话代数——旧会话/旧世代的迟到回报会混进当前会话判负")
        }
        if (occurrences(helper, "CONTROL_AWB_MODE_OFF").isEmpty()) {
            bad.add("归属没检查请求实际写了 AWB_OFF——改档前发出的 AUTO 请求会被当成手动拍")
        }
        if (occurrences(helper, "COLOR_CORRECTION_MODE_TRANSFORM_MATRIX").isEmpty()) {
            bad.add("归属没检查请求实际写了 TRANSFORM_MATRIX——种子窗/稳压窗的 AUTO 拍会混进来")
        }
        if (occurrences(helper, "getOrDefault(false)").isEmpty()) {
            bad.add("读键失败没有 fail-closed（默认 false）——读不到键会当手动拍误判负")
        }
        return bad
    }

    @Test
    fun `手动判负归属请求实际写键与当前会话代数`() {
        assertEquals(
            "白平衡判负归属接线缺件：\n${attributionIssues(engine).joinToString("\n")}",
            emptyList<String>(),
            attributionIssues(engine)
        )
    }

    @Test
    fun `尺子自己能红_退回总线口径与摘掉会话或键检查都必须报红`() {
        assertTrue("真身必须绿", attributionIssues(engine).isEmpty())

        // 突变 1：判负退回「只看总线」（缺陷原形）
        val busOnly = engine.replace("manualWbRequestOf(session, request)", "true")
        assertTrue(
            "退回总线口径必须红",
            attributionIssues(busOnly).any { it.contains("manualWbRequestOf") }
        )

        // 突变 2：摘掉会话代数门
        val noSession = engine.replace(
            "if (session !== this.session) return false",
            "if (false) return false"
        )
        assertTrue(
            "摘掉会话代数必须红",
            attributionIssues(noSession).any { it.contains("会话代数") }
        )

        // 突变 3：摘掉请求写键检查（AWB_OFF）
        val noAwbKey = engine.replace(
            "request.get(CaptureRequest.CONTROL_AWB_MODE) == CaptureRequest.CONTROL_AWB_MODE_OFF",
            "true"
        )
        assertTrue(
            "摘掉 AWB_OFF 写键检查必须红",
            attributionIssues(noAwbKey).any { it.contains("AWB_OFF") }
        )

        // 突变 4：读键失败改成 fail-open（读不到就当手动）
        val failOpen = engine.replace("getOrDefault(false)", "getOrDefault(true)")
        assertTrue(
            "fail-open 必须红",
            attributionIssues(failOpen).any { it.contains("fail-closed") }
        )
    }

    // ------------------------------------------------------------------ 夹具驱动

    private fun setEnum(
        p: CameraRecoveryStartFixture,
        params: Any,
        getter: String,
        enumType: String,
        name: String,
    ) {
        val flow = p.platform.call(params, getter) ?: error("WotaParams 没有 $getter")
        val value = p.platform.type(enumType).getField(name).get(null)
        p.platform.call(flow, "setValue", value)
    }

    /** `CaptureRequest.wb(manual=true)` 写 OFF+TRANSFORM；`false` 写 AUTO+FAST（真实 Applier 的两条路） */
    private fun request(p: CameraRecoveryStartFixture, manual: Boolean): Any =
        p.platform.type("android.hardware.camera2.CaptureRequest")
            .getMethod("wb", Boolean::class.javaPrimitiveType)
            .invoke(null, manual)

    /** HAL 不采纳的回报：CCM 回声 FAST + AWB 还在 SEARCHING */
    private fun failingResult(p: CameraRecoveryStartFixture): Any =
        p.platform.type("android.hardware.camera2.TotalCaptureResult")
            .getMethod("failing")
            .invoke(null)

    /**
     * 直接喂生产回执（真实 Engine 的普通 captureCallback）。`lastWbKeepAliveMs` 置负只为让
     * 500ms 保活节流在桩钟（elapsedRealtime 恒 0）下放行——不是被测判据本身。
     */
    private fun deliver(
        p: CameraRecoveryStartFixture,
        engine: Any,
        session: Any,
        request: Any,
        result: Any,
    ) {
        p.platform.set(engine, "lastWbKeepAliveMs", -1_000L)
        val callback = p.platform.field(engine, "captureCallback")
            ?: error("真实 Camera2Engine 没有 captureCallback")
        p.platform.call(callback, "onCaptureCompleted", session, request, result)
    }

    private fun intField(p: CameraRecoveryStartFixture, engine: Any, name: String): Int =
        p.platform.field(engine, name) as Int

    private fun halRejected(p: CameraRecoveryStartFixture, params: Any): Boolean {
        val flow = p.platform.call(params, "getWbHalRejected") ?: error("WotaParams 没有 wbHalRejected")
        return p.platform.call(flow, "getValue") as Boolean
    }

    companion object {
        private fun keysOf(type: Class<*>): String = type.declaredFields
            .filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} " +
                    "${field.name} = new Key<>(\"${field.name}\");"
            }

        /**
         * 三处平台边界：
         * - `CaptureRequest`：Builder 真记键值、`get` 按**键名**读（两家 classloader 的 Key 不是同一个对象）；
         * - `TotalCaptureResult`：`get` 走值表（默认桩恒 null，判负分支看不见 AWB_STATE）；
         * - `CameraCharacteristics`：`getKeys()` 真的列出隐藏键 `android.colorCorrection.availableModes`，
         *   并回报 `{FAST, TRANSFORM_MATRIX}`——只有「HAL 明确声明」的机器上探针才是活的。
         * 其余边界沿用 [CameraRecoveryStartFixture] 的默认桩。
         */
        private fun stubs(): Map<String, String> = mapOf(
            "android.hardware.camera2.CaptureRequest" to """
                package android.hardware.camera2;
                public class CaptureRequest {
                    public static class Key<T> {
                        public final String name;
                        public Key(String n){name=n;}
                        public String getName(){return name;}
                    }
                    ${keysOf(CaptureRequest::class.java)}
                    private final java.util.Map<String,Object> values;
                    public CaptureRequest(){this(new java.util.HashMap<>());}
                    public CaptureRequest(java.util.Map<String,Object> v){values=v;}
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){
                        return key==null?null:(T)values.get(key.name);
                    }
                    public static CaptureRequest wb(boolean manual){
                        java.util.Map<String,Object> v=new java.util.HashMap<>();
                        v.put(CONTROL_AWB_MODE.name, manual?0:1);
                        v.put(COLOR_CORRECTION_MODE.name, manual?1:0);
                        return new CaptureRequest(v);
                    }
                    public static class Builder {
                        private final java.util.Map<String,Object> values=new java.util.HashMap<>();
                        public <T>void set(Key<T> key,T value){values.put(key.name,value);}
                        public <T>T get(Key<T> key){return null;}
                        public void addTarget(android.view.Surface surface){}
                        public CaptureRequest build(){return new CaptureRequest(new java.util.HashMap<>(values));}
                    }
                }
            """,
            "android.hardware.camera2.TotalCaptureResult" to """
                package android.hardware.camera2;
                public class TotalCaptureResult extends CaptureResult {
                    private final java.util.Map<String,Object> values;
                    public TotalCaptureResult(){this(new java.util.HashMap<>());}
                    public TotalCaptureResult(java.util.Map<String,Object> v){values=v;}
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){
                        return key==null?null:(T)values.get(key.name);
                    }
                    /** HAL 不采纳：CCM 回声 FAST，AWB 仍在 SEARCHING */
                    public static TotalCaptureResult failing(){
                        java.util.Map<String,Object> v=new java.util.HashMap<>();
                        v.put(COLOR_CORRECTION_MODE.name, 0);
                        v.put(CONTROL_AWB_STATE.name, 1);
                        return new TotalCaptureResult(v);
                    }
                }
            """,
            "android.hardware.camera2.CameraCharacteristics" to """
                package android.hardware.camera2;
                public class CameraCharacteristics {
                    public static class Key<T>{
                        public final String name;
                        public Key(String n){name=n;}
                        public String getName(){return name;}
                    }
                    ${keysOf(CameraCharacteristics::class.java)}
                    /** 公开 API 没有这个键（引擎注里写明是隐藏键）：只有 getKeys 真的列出它才读得到 */
                    public static final Key<int[]> CCM_AVAILABLE_MODES =
                        new Key<>("android.colorCorrection.availableModes");
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){
                        Object value=null;
                        switch(key.name){
                            case "SCALER_STREAM_CONFIGURATION_MAP":value=new android.hardware.camera2.params.StreamConfigurationMap();break;
                            case "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES":value=new android.util.Range[]{new android.util.Range<Integer>(30,30)};break;
                            case "REQUEST_AVAILABLE_CAPABILITIES":case "CONTROL_AF_AVAILABLE_MODES":case "CONTROL_AWB_AVAILABLE_MODES":value=new int[]{0};break;
                            case "android.colorCorrection.availableModes":value=new int[]{0,1,2};break;
                            case "LENS_FACING":value=1;break;
                            case "SENSOR_ORIENTATION":value=90;break;
                            case "INFO_SUPPORTED_HARDWARE_LEVEL":value=1;break;
                            case "LENS_INFO_AVAILABLE_FOCAL_LENGTHS":value=new float[]{4f};break;
                            case "SENSOR_INFO_ACTIVE_ARRAY_SIZE":value=new android.graphics.Rect(0,0,640,480);break;
                        }
                        return (T)value;
                    }
                    public java.util.List<Key<?>> getKeys(){
                        return java.util.Collections.<Key<?>>singletonList(CCM_AVAILABLE_MODES);
                    }
                    public java.util.Set<String> getPhysicalCameraIds(){return java.util.Collections.emptySet();}
                }
            """
        )
    }
}
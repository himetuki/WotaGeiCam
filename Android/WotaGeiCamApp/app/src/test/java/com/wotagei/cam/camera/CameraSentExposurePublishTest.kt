package com.wotagei.cam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B01 行为测试：`actualSentExposureNs`（HUD 实测读数）只在 repeating 请求**真正提交成功**后发布，
 * 且发布的是请求里实际写下的那份（钳制 + 纠偏之后的值），不是参数层的计划值。
 *
 * 旧行为：`applyParams` 在 build 之前就把候选值写进总线。build → setRepeatingRequest 之间还有
 * 平台调用，被拒（普通会话 IAE）或会话不在（session==null 早退）时 HUD 已经显示了一个从未生效的
 * 读数，排障时会把人带偏（"发的 38ms" vs 实际没发出去）。
 *
 * 平台边界只做两件事：`CaptureRequest.Builder` 记录每次 build 的键值；`CameraCaptureSession`
 * 记录提交并把 `REJECT_ON_SUBMIT` 变成一次脚本化拒绝。
 */
class CameraSentExposurePublishTest {

    @Test
    fun `送出曝光只在提交成功后发布且等于真实请求里的值`() {
        CameraRecoveryStartFixture(
            javaClass.classLoader!!,
            boundaries,
            // 等号档用例必须声明 24/25 两档：默认能力表只有 [30,30]，引擎的通用退档会把
            // 1/25 请求改写成 30fps 并按 1/30 钳制快门，等号档前提（40ms@25fps）在测试里
            // 就不存在了，断言会落在「发送值 == 用户档位值」的假红上。
            fpsRanges = "new android.util.Range[]{new android.util.Range<Integer>(24,24)," +
                "new android.util.Range<Integer>(25,25),new android.util.Range<Integer>(30,30)}",
        ).use { p ->
            p.bind()
            p.start()
            p.drain()
            p.completeOpen(0)
            p.completeSession(0)

            val params = p.platform.call(p.controller, "getParams") ?: error("CameraController 没有 params")
            assertEquals("自动档没有曝光可言：提交成功后读数必须是 null", null, readout(p, params))

            // 手动档 + 默认 1/25（40ms）等号档：纠偏（自动档、无实测 Δ ⇒ 5% 下限）会把发送值
            // 压到 40ms 以内 —— 发布值必须等于这个"实际发送值"，不是用户档位值
            val manual = p.platform.type("com.wotagei.cam.core.AeMode").getField("MANUAL").get(null)
            val ae = p.platform.call(params, "getAeMode") ?: error("WotaParams 没有 aeMode")
            p.platform.call(ae, "setValue", manual)
            val before = builtRequests(p).size
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()

            val built = builtRequests(p).drop(before)
            assertTrue("手动档必须真的下发一次请求", built.isNotEmpty())
            val sentExp = built.last()["SENSOR_EXPOSURE_TIME"] as? Long
            assertNotNull("手动档请求必须带 SENSOR_EXPOSURE_TIME", sentExp)
            val requested = p.platform.call(params, "getShutter")!!.let {
                p.platform.call(p.platform.call(it, "getValue")!!, "getValue") as Long
            }
            assertEquals("HUD 读数必须等于请求里实际写下的曝光", sentExp, readout(p, params))
            assertTrue(
                "发布值必须是钳制+纠偏后的发送值（40ms 等号档会留余量 ⇒ 小于用户档位值）",
                sentExp!! < requested
            )

            // 平台拒绝：build 成功、提交失败 ⇒ 读数绝不许改（旧实现此时已经显示新值了）。
            // 切回 AUTO 会让下一份请求里 SENSOR 三键缺席 —— 若"提交失败也发布"，读数会被写成 null。
            setRejectOnSubmit(p, true)
            p.platform.call(ae, "setValue", p.platform.type("com.wotagei.cam.core.AeMode").getField("AUTO").get(null))
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()
            assertEquals("提交失败不许发布送出值", sentExp, readout(p, params))

            // 恢复提交：同一份参数再下发，读数才跟上新的发送值（AUTO ⇒ 三键缺席 ⇒ null）
            setRejectOnSubmit(p, false)
            val beforeOk = builtRequests(p).size
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()
            val okBuilt = builtRequests(p).drop(beforeOk)
            assertTrue(okBuilt.isNotEmpty())
            assertEquals("AUTO 请求不许自带 SENSOR 键", null, okBuilt.last()["SENSOR_EXPOSURE_TIME"])
            assertEquals("提交成功后必须发布新发送值（AUTO ⇒ 读数清空）", null, readout(p, params))
        }
    }

    // ------------------------------------------------------------------ 平台边界

    /** HUD 读数（`params.actualSentExposureNs` StateFlow 的当前值） */
    private fun readout(p: CameraRecoveryStartFixture, params: Any): Any? {
        val flow = p.platform.call(params, "getActualSentExposureNs") ?: error("WotaParams 没有 actualSentExposureNs")
        return p.platform.call(flow, "getValue")
    }

    /** 每个 build 出来的请求的键值快照（顺序 = build 顺序） */
    private fun builtRequests(p: CameraRecoveryStartFixture): List<Map<String, Any?>> {
        @Suppress("UNCHECKED_CAST")
        val built = p.platform.type("android.hardware.camera2.CaptureRequest")
            .getField("BUILT").get(null) as List<Map<String, Any?>>
        return built
    }

    private fun setRejectOnSubmit(p: CameraRecoveryStartFixture, value: Boolean) {
        p.platform.type("android.hardware.camera2.CameraCaptureSession")
            .getField("REJECT_ON_SUBMIT").set(null, value)
    }

    companion object {
        private fun keys(): String = CaptureRequest::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} " +
                    "${field.name} = new Key<>(\"${field.name}\");"
            }

        /**
         * 只替换两枚平台类型：
         * - `CaptureRequest`：Builder 记录 set 过的键，build() 追加键值快照到静态 `BUILT`；
         * - `CameraCaptureSession`：`REJECT_ON_SUBMIT` 时 setRepeatingRequest 抛 IAE（普通会话
         *   被拒的真实分支），否则照常返回 1。
         */
        private val boundaries = mapOf(
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
            """,
            "android.hardware.camera2.CameraCaptureSession" to """
                package android.hardware.camera2;
                public class CameraCaptureSession {
                    public static boolean REJECT_ON_SUBMIT=false;
                    public int closeCount, repeatingCount;
                    public int setRepeatingRequest(CaptureRequest r,CaptureCallback c,android.os.Handler h){
                        if(REJECT_ON_SUBMIT)throw new IllegalArgumentException("scripted reject");
                        repeatingCount++;return 1;
                    }
                    public int capture(CaptureRequest r,CaptureCallback c,android.os.Handler h){return 1;}
                    public void stopRepeating(){} public void close(){closeCount++;}
                    public abstract static class StateCallback {
                        public abstract void onConfigured(CameraCaptureSession s);
                        public abstract void onConfigureFailed(CameraCaptureSession s);
                    }
                    public static class CaptureCallback {
                        public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long t,long f){}
                        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){}
                    }
                }
            """
        )
    }
}

package com.wotagei.cam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y02 行为测试：真实 CameraController/Camera2Engine 字节码，只在平台边界记录 **实际 build 出来的
 * repeating 请求**里带了哪些键。
 *
 * 验收口径（R02/Y02）：`CaptureRequest.Builder` 每会话只建一次、跨次下发复用且**没有单键 remove**，
 * 手动档写进去的 SENSOR 三键在切回 AUTO 后只能靠「整 builder 重建」清掉。若翻转重建失败/未重建，
 * AUTO 请求会带着旧 SENSOR 三键下发（部分 MTK HAL 把三键当钳制采纳 ⇒ 成片与预览曝光闪动）。
 */
class CameraAeFlipRequestKeyTest {

    @Test
    fun `手动切AUTO后的真实repeating请求不得残留SENSOR三键`() {
        CameraRecoveryStartFixture(javaClass.classLoader!!, boundaries).use { p ->
            p.bind()
            p.start()
            p.drain()
            p.completeOpen(0)
            p.completeSession(0)

            val manual = enumValue(p, "com.wotagei.cam.core.AeMode", "MANUAL")
            val auto = enumValue(p, "com.wotagei.cam.core.AeMode", "AUTO")
            val params = p.platform.call(p.controller, "getParams") ?: error("CameraController 没有 params")
            val ae = p.platform.call(params, "getAeMode") ?: error("WotaParams 没有 aeMode")

            p.platform.call(ae, "setValue", manual)
            val beforeManual = builtRequests(p).size
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()
            val manualKeys = builtRequests(p).drop(beforeManual)
            assertTrue("手动档必须真的下发一次请求", manualKeys.isNotEmpty())
            assertTrue(
                "手动档请求必须带 SENSOR 三键（否则夹具没测到 manual 分支）",
                manualKeys.last().containsAll(SENSOR_KEYS)
            )

            p.platform.call(ae, "setValue", auto)
            val beforeAuto = builtRequests(p).size
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()
            val autoKeys = builtRequests(p).drop(beforeAuto)
            assertTrue("切回 AUTO 必须真的下发一次请求", autoKeys.isNotEmpty())
            assertEquals(
                "AE 翻转必须整 builder 重建：AUTO 请求里不许残留任何 SENSOR 键",
                emptyList<String>(),
                autoKeys.last().filter { it in SENSOR_KEYS }
            )
            assertFalse(
                "重建后的 builder 不许沿用旧键集（同一 builder 复用会把三键留在请求里）",
                autoKeys.last().contains("SENSOR_EXPOSURE_TIME")
            )
        }
    }

    // ------------------------------------------------------------------ 平台边界（只记录请求键）

    private fun enumValue(p: CameraRecoveryStartFixture, type: String, name: String): Any =
        p.platform.type(type).getField(name).get(null)!!

    /** 每个 build 出来的请求的键名集合（顺序 = build 顺序；平台桩只记录，不改生产语义）。 */
    private fun builtRequests(p: CameraRecoveryStartFixture): List<List<String>> {
        @Suppress("UNCHECKED_CAST")
        val built = p.platform.type("android.hardware.camera2.CaptureRequest")
            .getField("BUILT").get(null) as List<Map<String, Any?>>
        return built.map { it.keys.toList() }
    }

    companion object {
        private val SENSOR_KEYS = setOf(
            "SENSOR_SENSITIVITY", "SENSOR_EXPOSURE_TIME", "SENSOR_FRAME_DURATION"
        )

        private fun keys(): String = CaptureRequest::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} " +
                    "${field.name} = new Key<>(\"${field.name}\");"
            }

        /**
         * 只替换 CaptureRequest：Builder 把 set 过的键记进实例 map，build() 时按构建顺序追加一份
         * 键值快照到静态 BUILT，供测试读取「真正下发出去的那份请求」。
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
            """
        )
    }
}
package com.wotagei.cam.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Y02 行为测试（重建失败分支）：手动档 builder 切 AUTO 时如果 `createCaptureRequest` 抛异常，
 * 绝不能拿旧 builder 继续下发——AUTO 分支不写 SENSOR 三键，旧三键会原样留在请求里。
 *
 * 平台边界：
 * - `CaptureRequest` 记录每次 `build()` 的键值快照（BUILT）；
 * - `CameraDevice.createCaptureRequest` 由测试开关脚本化失败（真实代码里该分支被 catch）。
 */
class CameraAeFlipRebuildFailureTest {

    @Test
    fun `AUTO重建失败时不得用残留三键的旧builder下发 恢复后必须换干净builder`() {
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
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()
            val manualBuilt = builtRequests(p)
            assertTrue("手动档必须真的下发一次请求", manualBuilt.isNotEmpty())
            assertTrue(
                "手动档请求必须带 SENSOR 三键（否则用例没测到 manual 分支）",
                manualBuilt.last().containsAll(SENSOR_KEYS)
            )

            // 脚本化重建失败：createCaptureRequest 抛 RuntimeException（prepareBuilder 的真实 catch 分支）
            setCreateFailure(p, true)
            val beforeAuto = builtRequests(p).size
            p.platform.call(ae, "setValue", auto)
            p.platform.call(p.engine(), "markDirty")
            p.runDelayed()

            assertEquals(
                "AUTO 重建失败时不得 build/下发任何请求（旧 builder 仍带手动 SENSOR 三键）",
                beforeAuto,
                builtRequests(p).size
            )
            assertTrue("重建失败必须排短重试", p.delayedCount() > 0)

            // 恢复设备：重试必须重建干净 builder，AUTO 请求不含任何 SENSOR 键
            setCreateFailure(p, false)
            val beforeRetry = builtRequests(p).size
            p.runDelayed()
            val autoBuilt = builtRequests(p).drop(beforeRetry)
            assertTrue("恢复后重试必须真的下发 AUTO 请求", autoBuilt.isNotEmpty())
            assertEquals(
                "重建后的 AUTO 请求里不许残留 SENSOR 三键",
                emptyList<String>(),
                autoBuilt.last().filter { it in SENSOR_KEYS }
            )
        }
    }

    private fun enumValue(p: CameraRecoveryStartFixture, type: String, name: String): Any =
        p.platform.type(type).getField(name).get(null)!!

    private fun setCreateFailure(p: CameraRecoveryStartFixture, value: Boolean) {
        p.platform.type("android.hardware.camera2.CameraDevice")
            .getField("FAIL_CREATE").setBoolean(null, value)
    }

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
            "android.hardware.camera2.CameraDevice" to """
                package android.hardware.camera2;
                public class CameraDevice {
                    private final CameraManager manager;
                    public int closeCount;
                    /** 测试开关：模拟重建 builder 时的真实 RuntimeException 分支。 */
                    public static boolean FAIL_CREATE=false;
                    public CameraDevice(CameraManager m){manager=m;}
                    public void close(){closeCount++;}
                    public CaptureRequest.Builder createCaptureRequest(int template){
                        if(FAIL_CREATE)throw new IllegalStateException("scripted createCaptureRequest failure");
                        return new CaptureRequest.Builder();
                    }
                    public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config){manager.create(config);}
                    public abstract static class StateCallback {
                        public abstract void onOpened(CameraDevice d);
                        public abstract void onDisconnected(CameraDevice d);
                        public abstract void onError(CameraDevice d,int error);
                        public static final int ERROR_CAMERA_IN_USE=4, ERROR_MAX_CAMERAS_IN_USE=5, ERROR_CAMERA_DISABLED=3;
                    }
                }
            """
        )
    }
}

package com.wotagei.cam.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** 只控制平台队列与相机会话边界；生命周期决策执行真实 Controller/Engine 字节码。 */
class CameraLifecycleBridgeTest {
    @Test
    fun `pause已入队而resume先到达时最终必须恢复预览`() = platform { p, controller, engine, device ->
        val oldSession = p.field(engine, "session")!!
        p.call(controller, "pause")
        // 工作队列尚未放行：旧会话仍在，主线程此时判活会错误地吞掉 resume。
        assertSame(oldSession, p.field(engine, "session"))
        assertEquals(0, p.field(oldSession, "closeCount"))
        p.call(controller, "resume")
        p.drain(engine)

        assertEquals("最新 resume 意图不能被迟到的 stop 覆盖", true, p.field(engine, "desiredPreview"))
        assertEquals("必须实际向平台请求重建会话，不能只改期望位", 2, p.field(device, "createCount"))
        assertEquals(1, p.field(oldSession, "closeCount"))
        assertNotSame(oldSession, p.field(engine, "session"))
        assertEquals("恢复后的真实会话必须重新下发 repeating", 1,
            p.field(p.field(engine, "session")!!, "repeatingCount"))
        assertTrue(p.call(engine, "isPreviewLive") as Boolean)
    }

    @Test
    fun `pause停止完成后resume仍能恢复同一设备的预览`() = platform { p, controller, engine, device ->
        p.call(controller, "pause")
        p.drain(engine)
        assertEquals(false, p.field(engine, "desiredPreview"))
        assertFalse(p.call(engine, "isPreviewLive") as Boolean)

        p.call(controller, "resume")
        p.drain(engine)
        assertSame(device, p.field(engine, "device"))
        assertEquals(2, p.field(device, "createCount"))
        assertEquals(1, p.field(p.field(engine, "session")!!, "repeatingCount"))
        assertTrue(p.call(engine, "isPreviewLive") as Boolean)
    }

    @Test
    fun `最后一次生命周期意图是pause时不得被较早的resume复活`() = platform { p, controller, engine, device ->
        p.call(controller, "pause")
        p.call(controller, "resume")
        p.call(controller, "pause")
        p.drain(engine)

        assertEquals(false, p.field(engine, "desiredPreview"))
        assertEquals("STOP", p.call(engine, "getPreviewStatus").toString())
        assertFalse(p.call(engine, "isPreviewLive") as Boolean)
        assertSame(device, p.field(engine, "device"))
    }

    @Test
    fun `已经存活的预览重复resume不重建会话也不重提请求`() = platform { p, controller, engine, device ->
        val session = p.field(engine, "session")!!
        repeat(2) { p.call(controller, "resume") }
        p.drain(engine)

        assertSame(session, p.field(engine, "session"))
        assertEquals(1, p.field(device, "createCount"))
        assertEquals(0, p.field(session, "stopCount"))
        assertEquals(0, p.field(session, "closeCount"))
        assertEquals(1, p.field(session, "repeatingCount"))
        assertTrue(p.call(engine, "isPreviewLive") as Boolean)
    }

    @Test
    fun `previewKeptBehind覆盖期生命周期回调完全保留原会话`() = platform { p, controller, engine, device ->
        val session = p.field(engine, "session")!!
        val ownerType = p.type("androidx.lifecycle.LifecycleOwner")
        val owner = Proxy.newProxyInstance(ownerType.classLoader, arrayOf(ownerType)) { _, _, _ -> null }
        p.call(controller, "setPreviewKeptBehind", true)
        p.call(controller, "onPause", owner)
        p.call(controller, "onResume", owner)
        p.drain(engine)

        assertSame(session, p.field(engine, "session"))
        assertEquals(1, p.field(device, "createCount"))
        assertEquals(0, p.field(session, "stopCount"))
        assertEquals(0, p.field(session, "closeCount"))
        assertEquals(1, p.field(session, "repeatingCount"))
        assertTrue(p.call(engine, "isPreviewLive") as Boolean)
    }

    private fun platform(block: (N12CameraControllerFixture, Any, Any, Any) -> Unit) =
        N12CameraControllerFixture(checkNotNull(javaClass.classLoader), boundaries).use { p ->
            val controller = p.controller(p.application())
            p.call(controller, "bind", p.sink("lifecycle-preview"))
            val engine = p.engineOf(controller)
            p.drain(engine)
            // seam 从已打开的设备开始，不仿写 Controller 的生命周期分支或 Engine 的会话算法。
            val device = p.new("android.hardware.camera2.CameraDevice")
            p.set(engine, "device", device)
            val abilityCompanion = checkNotNull(p.type("com.wotagei.cam.core.CameraAbility").getField("Companion").get(null))
            p.set(engine, "ability", p.call(
                abilityCompanion, "from", "lifecycle", p.new("android.hardware.camera2.CameraCharacteristics"), null
            ))
            val opened = p.type("com.wotagei.cam.camera.DeviceStatus").enumConstants
                .single { it.toString() == "OPEN_SUCCEED" }
            p.call(engine, "publishDevice", opened)
            p.call(engine, "startPreview")
            p.drain(engine)
            assertTrue("真实 startPreview/onConfigured 必须先建立存活会话", p.call(engine, "isPreviewLive") as Boolean)
            assertEquals(1, p.field(device, "createCount"))
            assertEquals("夹具必须让真实 repeating 出口确实下发，避免空状态假幂等", 1,
                p.field(p.field(engine, "session")!!, "repeatingCount"))
            try {
                block(p, controller, engine, device)
            } finally {
                p.call(controller, "stop")
                p.drain(engine)
            }
        }

    companion object {
        private fun characteristicKeys(): String = android.hardware.camera2.CameraCharacteristics::class.java.declaredFields
            .filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} " +
                    "${field.name} = new Key<>(\"${field.name}\");"
            }

        // 仅扩展本测试的平台边界，不改变旧 N12 或 CameraPlatformFixture 的调度语义。
        private val boundaries = mapOf(
            "android.hardware.camera2.CameraCharacteristics" to """
                package android.hardware.camera2;
                public class CameraCharacteristics {
                    public static class Key<T> {
                        public final String name;
                        public Key(String n){name=n;}
                        public String getName(){return name;}
                    }
                    ${characteristicKeys()}
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){
                        if(key.name.equals("SENSOR_INFO_ACTIVE_ARRAY_SIZE"))return (T)new android.graphics.Rect(0,0,1920,1080);
                        if(key.name.equals("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES"))return (T)new android.util.Range[]{new android.util.Range<Integer>(25,25)};
                        if(key.name.equals("SCALER_STREAM_CONFIGURATION_MAP"))return (T)new android.hardware.camera2.params.StreamConfigurationMap();
                        return null;
                    }
                    public java.util.List<Key<?>> getKeys(){return java.util.Collections.emptyList();}
                    public java.util.Set<String> getPhysicalCameraIds(){return java.util.Collections.emptySet();}
                }
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public final class Diag {
                    public static final Diag INSTANCE=new Diag();
                    public void i(String tag,String message){}
                    public void d(String tag,String message){}
                    public void w(String tag,String message){System.err.println(message);}
                    public void w(String tag,String message,Throwable error){System.err.println(message);error.printStackTrace();}
                    public void e(String tag,String message){System.err.println(message);}
                    public void e(String tag,String message,Throwable error){System.err.println(message);error.printStackTrace();}
                }
            """,
            "android.hardware.camera2.CameraDevice" to """
                package android.hardware.camera2;
                public class CameraDevice {
                    public int createCount;
                    public void close(){}
                    public CaptureRequest.Builder createCaptureRequest(int template){return new CaptureRequest.Builder();}
                    public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config){
                        createCount++;
                        config.executor.execute(() -> config.callback.onConfigured(new CameraCaptureSession()));
                    }
                    public abstract static class StateCallback {
                        public abstract void onOpened(CameraDevice d);
                        public abstract void onDisconnected(CameraDevice d);
                        public abstract void onError(CameraDevice d,int error);
                        public static final int ERROR_CAMERA_IN_USE=4, ERROR_MAX_CAMERAS_IN_USE=5, ERROR_CAMERA_DISABLED=3;
                    }
                }
            """,
            "android.hardware.camera2.params.SessionConfiguration" to """
                package android.hardware.camera2.params;
                public class SessionConfiguration {
                    public static final int SESSION_REGULAR=0, SESSION_HIGH_SPEED=1;
                    public final java.util.concurrent.Executor executor;
                    public final android.hardware.camera2.CameraCaptureSession.StateCallback callback;
                    public SessionConfiguration(int type,java.util.List<OutputConfiguration> outputs,
                        java.util.concurrent.Executor e,android.hardware.camera2.CameraCaptureSession.StateCallback c){executor=e;callback=c;}
                }
            """,
            "android.hardware.camera2.CameraCaptureSession" to """
                package android.hardware.camera2;
                public class CameraCaptureSession {
                    public int stopCount,closeCount,repeatingCount;
                    public int setRepeatingRequest(CaptureRequest r,CaptureCallback c,android.os.Handler h){repeatingCount++;return 1;}
                    public int capture(CaptureRequest r,CaptureCallback c,android.os.Handler h){return 1;}
                    public void stopRepeating(){stopCount++;}
                    public void close(){closeCount++;}
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

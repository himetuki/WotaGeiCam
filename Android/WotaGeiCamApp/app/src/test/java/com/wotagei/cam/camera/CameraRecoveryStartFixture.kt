package com.wotagei.cam.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.Closeable

/** 复用 N12 的真实 Controller/Engine 桥，只补可控的设备打开与会话配置平台回调。 */
internal class CameraRecoveryStartFixture(
    parent: ClassLoader,
    boundarySources: Map<String, String> = emptyMap(),
    /**
     * 能力表 `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 的 Java 表达式。
     *
     * 默认只有 `[30,30]`（既有相机恢复用例的旧口径，行为不变）。等号档（1/24、1/25）的用例
     * 必须显式声明对应档位：默认表里没有 24/25 时 [Camera2Engine.syncFpsPick] 的通用退档会把
     * 总线 fps 改写成 30、并把快门按 1/30 钳制——等号档前提（40ms@25fps）在测试里根本不存在，
     * 断言会落在「发送值 == 用户档位值」的假红上。
     */
    fpsRanges: String = DEFAULT_FPS_RANGES,
) : Closeable {
    val platform = N12CameraControllerFixture(
        parent,
        BOUNDARIES +
            mapOf("android.hardware.camera2.CameraCharacteristics" to characteristics(fpsRanges)) +
            boundarySources,
    )
    val application = platform.application()
    val controller = platform.controller(application)
    val manager = platform.call(application, "getSystemService", "camera")!!
    val sink = platform.sink("recovery-preview")

    fun bind() { platform.call(controller, "bind", sink) }
    fun start() { platform.call(controller, "start") }
    fun renderMode(name: String) {
        val params = platform.call(controller, "getParams") ?: error("CameraController 没有 params")
        val flow = platform.call(params, "getRenderMode") ?: error("WotaParams 没有 renderMode")
        val value = platform.type("com.wotagei.cam.core.RenderMode").getField(name).get(null)
        platform.call(flow, "setValue", value)
    }
    fun retry() { platform.call(controller, "restartPreview") }
    fun rebind() { platform.call(controller, "forceRebind", sink) }
    fun engine(): Any = platform.engineOf(controller)
    fun drain(engine: Any = engine()) { platform.drain(engine) }
    fun opened(): Int = platform.call(manager, "getOpenCount") as Int
    fun sessions(): Int = platform.call(manager, "getSessionCount") as Int
    fun available(value: Boolean) { platform.set(manager, "available", value) }
    fun rejectOpen(value: Boolean) { platform.set(manager, "rejectOpen", value) }
    fun cameraPermission(value: Boolean) {
        platform.type("androidx.core.content.ContextCompat").getField("cameraGranted").setBoolean(null, value)
    }
    fun audioPermission(value: Boolean) {
        platform.type("androidx.core.content.ContextCompat").getField("audioGranted").setBoolean(null, value)
    }
    fun state(name: String): Any? = platform.call(platform.call(controller, "getState")!!.let {
        platform.call(it, "getValue")!!
    }, "get$name")
    fun outputNames(index: Int): List<*> = platform.call(manager, "outputNames", index) as List<*>
    fun deviceCloseCount(index: Int): Int = platform.call(manager, "deviceCloseCount", index) as Int
    fun sessionCloseCount(index: Int): Int = platform.call(manager, "sessionCloseCount", index) as Int

    fun completeOpen(index: Int, engine: Any = engine()) {
        platform.call(manager, "completeOpen", index)
        drain(engine)
    }

    fun completeSession(index: Int, engine: Any = engine()) {
        platform.call(manager, "completeSession", index)
        drain(engine)
    }

    fun readiness(engine: Any = engine()): Any =
        platform.call(engine, "sessionReadiness") ?: error("真实 Camera2Engine 没有就绪快照")

    fun ready(r: Any): Boolean = platform.call(r, "getReady") as Boolean
    fun attempt(r: Any): Int = platform.call(r, "getAttempt") as Int
    fun recordingWindow(r: Any): Boolean = platform.call(r, "getRecordingWindow") as Boolean
    fun recordingTargetEpoch(r: Any): Int = platform.call(r, "getRecordingTargetEpoch") as Int

    fun runDelayed(engine: Any = engine()) {
        val handler = platform.field(engine, "workHandler") ?: error("真实 Camera2Engine 没有 workHandler")
        platform.call(handler, "runDelayed")
        drain(engine)
    }

    fun delayedCount(engine: Any = engine()): Int {
        val handler = platform.field(engine, "workHandler") ?: error("真实 Camera2Engine 没有 workHandler")
        return platform.call(handler, "delayedCount") as Int
    }

    fun assertLive(engine: Any = engine()) {
        assertTrue("必须通过真实 open/configure/repeating 链恢复预览", platform.call(engine, "isPreviewLive") as Boolean)
        assertEquals("ING", platform.call(engine, "getPreviewStatus").toString())
        val session = requireNotNull(platform.field(engine, "session"))
        assertTrue("当前会话必须收到生产 repeating 请求", platform.field(session, "repeatingCount") as Int > 0)
    }

    override fun close() {
        val engine = platform.field(controller, "engine")
        platform.close()
        if (engine != null) platform.drain(engine)
    }

    companion object {
        /** 既有相机恢复用例的默认档位表（与旧 `characteristics()` 里的值逐字一致）。 */
        internal const val DEFAULT_FPS_RANGES =
            "new android.util.Range[]{new android.util.Range<Integer>(30,30)}"

        private fun characteristics(fpsRanges: String): String {
            val keys = CameraCharacteristics::class.java.declaredFields
                .filter { it.type.simpleName == "Key" }
                .joinToString("\n") { field ->
                    "public static final ${field.genericType.typeName.replace('$', '.')} " +
                        "${field.name} = new Key<>(\"${field.name}\");"
                }
            return """
                package android.hardware.camera2;
                public class CameraCharacteristics {
                    public static class Key<T>{public final String name;public Key(String n){name=n;}public String getName(){return name;}}
                    $keys
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){
                        Object value=null;
                        switch(key.name){
                            case "SCALER_STREAM_CONFIGURATION_MAP":value=new android.hardware.camera2.params.StreamConfigurationMap();break;
                            case "CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES":value=$fpsRanges;break;
                            case "REQUEST_AVAILABLE_CAPABILITIES":case "CONTROL_AF_AVAILABLE_MODES":case "CONTROL_AWB_AVAILABLE_MODES":value=new int[]{0};break;
                            case "LENS_FACING":value=1;break;
                            case "SENSOR_ORIENTATION":value=90;break;
                            case "INFO_SUPPORTED_HARDWARE_LEVEL":value=1;break;
                            case "LENS_INFO_AVAILABLE_FOCAL_LENGTHS":value=new float[]{4f};break;
                            case "SENSOR_INFO_ACTIVE_ARRAY_SIZE":value=new android.graphics.Rect(0,0,640,480);break;
                        }
                        return (T)value;
                    }
                    public java.util.List<Key<?>> getKeys(){return java.util.Collections.emptyList();}
                    public java.util.Set<String> getPhysicalCameraIds(){return java.util.Collections.emptySet();}
                }
            """
        }

        private val BOUNDARIES = mapOf(
            "android.hardware.camera2.params.StreamConfigurationMap" to """
                package android.hardware.camera2.params;
                public class StreamConfigurationMap {
                    public android.util.Size[] getOutputSizes(int format){return new android.util.Size[]{new android.util.Size(640,480)};}
                    public android.util.Size[] getHighSpeedVideoSizes(){return new android.util.Size[0];}
                    public android.util.Range<Integer>[] getHighSpeedVideoFpsRangesFor(android.util.Size size){return new android.util.Range[0];}
                    public int[] getOutputFormats(){return new int[]{34,35};}
                }
            """,
            "androidx.core.content.ContextCompat" to """
                package androidx.core.content;
                public final class ContextCompat {
                    public static boolean cameraGranted=true, audioGranted=true;
                    public static int checkSelfPermission(android.content.Context context,String permission){
                        boolean granted=permission.equals("android.permission.CAMERA")?cameraGranted:audioGranted;
                        return granted?0:-1;
                    }
                }
            """,
            "android.os.Handler" to """
                package android.os;
                public class Handler {
                    private final Looper looper;
                    private final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>();
                    private final java.util.ArrayList<Runnable> delayed=new java.util.ArrayList<>();
                    public Handler(Looper l){looper=l;}
                    public boolean post(Runnable r){if(looper.closed)return false;queue.addLast(r);return true;}
                    public boolean postDelayed(Runnable r,long ms){if(looper.closed)return false;delayed.add(r);return true;}
                    public void removeCallbacks(Runnable r){queue.removeIf(item->item==r);delayed.removeIf(item->item==r);}
                    public void removeCallbacksAndMessages(Object token){queue.clear();delayed.clear();}
                    public void drain(){while(!queue.isEmpty()){queue.removeFirst().run();}}
                    public int delayedCount(){return delayed.size();}
                    public void runDelayed(){
                        java.util.ArrayList<Runnable> copy=new java.util.ArrayList<>(delayed);
                        delayed.clear();
                        for(Runnable r:copy)r.run();
                    }
                }
            """,
            "android.hardware.camera2.CameraManager" to """
                package android.hardware.camera2;
                public class CameraManager {
                    public boolean available=true, rejectOpen;
                    private final java.util.ArrayList<CameraDevice.StateCallback> callbacks=new java.util.ArrayList<>();
                    private final java.util.ArrayList<CameraDevice> devices=new java.util.ArrayList<>();
                    private final java.util.ArrayList<android.hardware.camera2.params.SessionConfiguration> configs=new java.util.ArrayList<>();
                    private final java.util.ArrayList<CameraCaptureSession> sessions=new java.util.ArrayList<>();
                    public String[] getCameraIdList(){return available?new String[]{"0"}:new String[0];}
                    public CameraCharacteristics getCameraCharacteristics(String id){
                        if(!available||!id.equals("0"))throw new IllegalArgumentException("camera unavailable: "+id);
                        return new CameraCharacteristics();
                    }
                    public void openCamera(String id,CameraDevice.StateCallback callback,android.os.Handler handler){
                        if(!id.equals("0"))throw new AssertionError("unexpected camera: "+id);
                        callbacks.add(callback);devices.add(new CameraDevice(this));
                        if(rejectOpen)throw new IllegalArgumentException("scripted open failure");
                    }
                    public int getOpenCount(){return callbacks.size();}
                    public int getSessionCount(){return configs.size();}
                    public void completeOpen(int index){callbacks.get(index).onOpened(devices.get(index));}
                    public void create(android.hardware.camera2.params.SessionConfiguration config){
                        configs.add(config);sessions.add(new CameraCaptureSession());
                    }
                    public void completeSession(int index){
                        android.hardware.camera2.params.SessionConfiguration config=configs.get(index);
                        config.executor.execute(()->config.callback.onConfigured(sessions.get(index)));
                    }
                    public java.util.List<String> outputNames(int index){
                        java.util.ArrayList<String> names=new java.util.ArrayList<>();
                        for(android.hardware.camera2.params.OutputConfiguration output:configs.get(index).outputs){names.add(output.surface.toString());}
                        return names;
                    }
                    public int deviceCloseCount(int index){return devices.get(index).closeCount;}
                    public int sessionCloseCount(int index){return sessions.get(index).closeCount;}
                }
            """,
            "android.hardware.camera2.CameraDevice" to """
                package android.hardware.camera2;
                public class CameraDevice {
                    private final CameraManager manager;
                    public int closeCount;
                    public CameraDevice(CameraManager m){manager=m;}
                    public void close(){closeCount++;}
                    public CaptureRequest.Builder createCaptureRequest(int template){return new CaptureRequest.Builder();}
                    public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config){manager.create(config);}
                    public abstract static class StateCallback {
                        public abstract void onOpened(CameraDevice d);
                        public abstract void onDisconnected(CameraDevice d);
                        public abstract void onError(CameraDevice d,int error);
                        public static final int ERROR_CAMERA_IN_USE=4, ERROR_MAX_CAMERAS_IN_USE=5, ERROR_CAMERA_DISABLED=3;
                    }
                }
            """,
            "android.hardware.camera2.CameraCaptureSession" to """
                package android.hardware.camera2;
                public class CameraCaptureSession {
                    public int closeCount, repeatingCount;
                    public int setRepeatingRequest(CaptureRequest r,CaptureCallback c,android.os.Handler h){repeatingCount++;return 1;}
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
            """,
            "android.hardware.camera2.params.OutputConfiguration" to """
                package android.hardware.camera2.params;
                public class OutputConfiguration {
                    public final android.view.Surface surface;
                    public OutputConfiguration(android.view.Surface s){surface=s;}
                    public void setPhysicalCameraId(String id){}
                }
            """,
            "android.hardware.camera2.params.SessionConfiguration" to """
                package android.hardware.camera2.params;
                public class SessionConfiguration {
                    public static final int SESSION_REGULAR=0, SESSION_HIGH_SPEED=1;
                    public final java.util.List<OutputConfiguration> outputs;
                    public final java.util.concurrent.Executor executor;
                    public final android.hardware.camera2.CameraCaptureSession.StateCallback callback;
                    public SessionConfiguration(int type,java.util.List<OutputConfiguration> o,java.util.concurrent.Executor e,android.hardware.camera2.CameraCaptureSession.StateCallback c){outputs=o;executor=e;callback=c;}
                }
            """
        )
    }
}

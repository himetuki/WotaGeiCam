package com.wotagei.cam.stance

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import com.wotagei.cam.camera.N12CameraControllerFixture
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 相机与 full 站位的 reader 桥：复用 N12 的真实 Controller/Engine 加载器，追加真实站位字节码。
 * 只替换 Android 调度、Camera2、ImageReader 与 LiteRT JNI；不复制挂槽、推理或释放决策。
 * 相机队列可控，Analysis 使用真实工作线程，资源账记录平台句柄的访问和归还顺序。
 */
internal class StanceReaderCameraBridgeFixture(root: File, parent: ClassLoader) : Closeable {
    val platform = N12CameraControllerFixture(parent, boundarySources(), setOf("com.wotagei.cam.stance."))
    val app = platform.application()
    val camera = platform.controller(app)
    val stance = platform.type("com.wotagei.cam.stance.StanceController")
        .getDeclaredConstructor(platform.type("android.content.Context"))
        .apply { isAccessible = true }.newInstance(app)
    private val pack = File(root, "pack").apply {
        mkdirs()
        File(this, "yolo11n_int8.tflite").writeBytes(ByteArray(16))
        File(this, "yolo11n_fp32.tflite").writeBytes(ByteArray(16))
        File(this, "head.json").writeText("""{"coef":[0,0,0,0,0],"intercept":0}""")
        File(this, "margins.json").writeText("""{"bands":{}}""")
    }
    private val gates = mutableListOf<CountDownLatch>()

    init {
        val attach: (Any, Any?) -> Boolean = { surface, reader ->
            call(camera, "setAnalysisTarget", surface, reader) as Boolean
        }
        val detach: () -> Unit = { call(camera, "setAnalysisTarget", null, null); Unit }
        val characteristics: () -> Any? = { call(camera, "currentCharacteristics") }
        call(stance, "setAttachHook", attach)
        call(stance, "setDetachHook", detach)
        call(stance, "setCharacteristicsProvider", characteristics)
        // 对齐 CameraScreen 的接线：Controller 销毁时把 reader 收尾交给分析 owner。
        platform.set(camera, "analysisOwnerRelease", {
            call(stance, "onCameraControllerCleared")
            Unit
        })
        val prefs = call(app, "getSharedPreferences", "wota_settings", 0)!!
        call(stance, "loadPrefs", prefs)
    }

    fun call(target: Any, name: String, vararg args: Any?): Any? = platform.call(target, name, *args)
    fun field(target: Any, name: String): Any? = platform.field(target, name)
    fun analysis(): Any = requireNotNull(field(stance, "analysis"))
    fun reader(): Any = requireNotNull(call(analysis(), "reader"))
    fun engine(): Any = platform.engineOf(camera)
    fun stanceState(): Any = (call(stance, "getState") as StateFlow<*>).value!!
    fun output(): Any? = call(stanceState(), "getOutput")
    fun cameraDecision(): Any = call((call(camera, "getState") as StateFlow<*>).value!!, "getAnalysisDecision")!!
    fun closeCount(reader: Any): Int = platform.readerCloseCount(reader)
    fun imageReturned(reader: Any): Int = call(reader, "getReturnedCount") as Int
    fun invalidAccesses(reader: Any): Int = call(reader, "getInvalidAccesses") as Int
    fun events(reader: Any): List<String> = (field(reader, "events") as List<*>).map { it.toString() }
    fun closeOwners(reader: Any): List<*> = field(reader, "closeOwners") as List<*>
    fun surfaceLive(reader: Any): Boolean = call(call(reader, "getSurface")!!, "isValid") as Boolean

    /** 相当于 CameraScreen 的回执 effect；值只取真实 CameraController，不伪造 INCLUDED。 */
    fun relayDecision() {
        call(stance, "onAnalysisSessionDecision", cameraDecision())
    }

    fun renderMode(value: String) {
        val params = call(camera, "getParams")!!
        val mode = platform.type("com.wotagei.cam.core.RenderMode").enumConstants.single { it.toString() == value }
        call(call(params, "getRenderMode")!!, "setValue", mode)
    }

    fun start(sink: Any) {
        renderMode("DIRECT")
        call(camera, "bind", sink)
        call(camera, "start")
        pumpCameraAndMain()
        // INCLUDED 是建会话前的协商回执，不代表参数下发成功；先验真实预览已提交 repeating。
        requireRepeatingSession()
        runBlocking {
            withTimeout(5_000L) {
                suspendCoroutine<Unit> { continuation ->
                    val result = call(stance, "applyPack", pack, emptyMap<String, Any>(), "fixture", continuation)
                    if (result !== COROUTINE_SUSPENDED) continuation.resume(Unit)
                }
            }
        }
        // 不注入 attached/running：从真实开关，经真实 hooks 完成挂流与会话重建。
        call(stance, "setEnabled", app, true)
        pumpCameraAndMain()
        requireRepeatingSession()
        relayDecision()
        analysisIdle()
    }

    /** 只验平台实际收到的请求；不得用反射 submit、补 builder 或直接 emit reader 修补正向对照。 */
    private fun requireRepeatingSession(): Any {
        val session = requireNotNull(field(engine(), "session")) { "平台正向准备失败：真实普通会话尚未配置" }
        check(call(session, "hasRepeatingRequest") == true && (call(session, "getRepeatingSubmissions") as Int) > 0) {
            val diag = platform.type("com.wotagei.cam.diag.Diag")
            "平台正向准备失败：onConfigured 不等于 repeating 已提交；" +
                "诊断=${diag.getField("messages").get(null)}；异常=${diag.getField("errors").get(null)}"
        }
        return session
    }

    fun pumpCameraAndMain() {
        platform.type("android.os.Looper").getMethod("idleManual").invoke(null)
    }

    fun analysisIdle() {
        val handler = requireNotNull(field(analysis(), "handler"))
        val marker = FutureTask { Unit }
        check(call(handler, "post", marker) == true) { "分析 owner 拒绝收尾标记" }
        marker.get(5, TimeUnit.SECONDS)
    }

    /** 由真实会话的 repeating targets 投递平台帧，缺分析目标时绝不绕道直接 emit reader。 */
    fun emitCurrentSession(): Int {
        val analysis = analysis()
        analysis.javaClass.getDeclaredField("lastFrameMs").apply { isAccessible = true }.setLong(analysis, 0L)
        val session = requireRepeatingSession()
        val emitted = call(session, "emitAnalysis") as Int
        analysisIdle()
        pumpCameraAndMain()
        return emitted
    }

    fun configuredTargets(): List<*> {
        val session = requireNotNull(field(engine(), "session"))
        return call(session, "getConfiguredTargets") as List<*>
    }

    fun repeatingTargets(): List<*> {
        val session = requireRepeatingSession()
        return call(session, "getRepeatingTargets") as List<*>
    }

    fun blockNextPlanes(reader: Any, entered: CountDownLatch, proceed: CountDownLatch) {
        gates += proceed
        call(reader, "blockNextPlanes", entered, proceed)
    }

    /** 不等待分析线程：供测试在真实 planes 读取窗口内发起 release。 */
    fun emitWithoutWaiting() {
        val analysis = analysis()
        analysis.javaClass.getDeclaredField("lastFrameMs").apply { isAccessible = true }.setLong(analysis, 0L)
        val session = requireRepeatingSession()
        check(call(session, "emitAnalysis") == 1) { "正向对照会话必须实际把一帧送到 analysis reader" }
    }

    fun awaitStopped(analysis: Any) {
        val worker = field(analysis, "thread") as Thread
        worker.join(5_000L)
        check(!worker.isAlive) { "分析 owner 未在有界等待内退出" }
    }

    override fun close() {
        // 即使行为断言失败，也先放行所有平台 latch，再走真实 release，不能留下挂起 owner。
        gates.forEach { it.countDown() }
        try {
            call(stance, "release")
            call(camera, "stop")
            pumpCameraAndMain()
            val workers = platform.type("android.os.HandlerThread").getField("workers").get(null) as List<*>
            workers.filterIsInstance<Thread>().forEach {
                it.join(5_000L)
                check(!it.isAlive) { "reader 桥留下分析工作线程" }
            }
        } finally {
            platform.close()
        }
    }

    companion object {
        private fun keys(type: Class<*>): String = type.declaredFields.filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} ${field.name}=new Key<>(\"${field.name}\");"
            }

        private fun boundarySources(): Map<String, String> = mapOf(
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public final class Diag {
                    public static final Diag INSTANCE=new Diag();
                    public static final java.util.List<String> messages=new java.util.concurrent.CopyOnWriteArrayList<>();
                    public static final java.util.List<Throwable> errors=new java.util.concurrent.CopyOnWriteArrayList<>();
                    private void record(String tag,String message){messages.add(tag+": "+message);}
                    public void i(String tag,String message){record(tag,message);}public void d(String tag,String message){record(tag,message);}
                    public void w(String tag,String message){record(tag,message);}public void e(String tag,String message){record(tag,message);}
                    public void w(String tag,String message,Throwable error){record(tag,message);errors.add(error);}
                    public void e(String tag,String message,Throwable error){record(tag,message);errors.add(error);}
                }
            """,
            "android.content.SharedPreferences" to """
                package android.content;
                public interface SharedPreferences {
                    boolean getBoolean(String k,boolean d); String getString(String k,String d);
                    int getInt(String k,int d); long getLong(String k,long d); float getFloat(String k,float d);
                    Editor edit();
                    interface Editor {
                        Editor putBoolean(String k,boolean v); Editor putString(String k,String v);
                        Editor putInt(String k,int v); Editor putLong(String k,long v); Editor putFloat(String k,float v); void apply();
                    }
                    class Impl implements SharedPreferences,Editor {
                        private final java.util.Map<String,Object> data=new java.util.HashMap<>();
                        public Impl(){data.put("stance_gpu_mode","off");}
                        public boolean getBoolean(String k,boolean d){return (Boolean)data.getOrDefault(k,d);}
                        public String getString(String k,String d){return (String)data.getOrDefault(k,d);}
                        public int getInt(String k,int d){return (Integer)data.getOrDefault(k,d);}
                        public long getLong(String k,long d){return (Long)data.getOrDefault(k,d);}
                        public float getFloat(String k,float d){return (Float)data.getOrDefault(k,d);}
                        public Editor edit(){return this;}
                        public Editor putBoolean(String k,boolean v){data.put(k,v);return this;}
                        public Editor putString(String k,String v){data.put(k,v);return this;}
                        public Editor putInt(String k,int v){data.put(k,v);return this;}
                        public Editor putLong(String k,long v){data.put(k,v);return this;}
                        public Editor putFloat(String k,float v){data.put(k,v);return this;}
                        public void apply(){}
                    }
                }
            """,
            "android.content.Context" to """
                package android.content;
                public class Context {
                    public static final String CAMERA_SERVICE="camera"; public static final int MODE_PRIVATE=0;
                    private final android.hardware.camera2.CameraManager manager;
                    private final SharedPreferences prefs=new SharedPreferences.Impl();
                    public Context(android.hardware.camera2.CameraManager manager){this.manager=manager;}
                    public Context getApplicationContext(){return this;}
                    public Object getSystemService(String name){return manager;}
                    public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
                    public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
                }
            """,
            "android.content.pm.PackageManager" to """
                package android.content.pm;
                public class PackageManager {
                    public static final int PERMISSION_GRANTED=0;
                    public boolean hasSystemFeature(String name){return false;}
                }
            """,
            "android.os.Build" to """
                package android.os;
                public class Build {
                    public static String HARDWARE="reader-fixture",SOC_MODEL="fixture",SOC_MANUFACTURER="fixture";
                    public static class VERSION {public static int SDK_INT=29;}
                }
            """,
            "android.os.Looper" to """
                package android.os;
                public class Looper {
                    private static final java.util.List<Looper> manual=new java.util.concurrent.CopyOnWriteArrayList<>();
                    private static final Looper MAIN=new Looper(false);
                    public final java.util.concurrent.BlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();
                    public final java.util.List<Runnable> delayed=new java.util.concurrent.CopyOnWriteArrayList<>();
                    public volatile boolean closed;
                    public Looper(boolean worker){if(!worker)manual.add(this);}
                    public static Looper getMainLooper(){return MAIN;}
                    public static void idleManual(){
                        for(int round=0;round<10000;round++){
                            boolean ran=false;
                            for(Looper l:manual){Runnable r=l.queue.poll();if(r!=null){ran=true;r.run();}}
                            if(!ran)return;
                        }
                        throw new AssertionError("manual handler did not settle");
                    }
                }
            """,
            "android.os.HandlerThread" to """
                package android.os;
                public class HandlerThread extends Thread {
                    public static final java.util.List<Thread> workers=new java.util.concurrent.CopyOnWriteArrayList<>();
                    private final boolean worker;
                    private final Looper looper;
                    public HandlerThread(String name){super(name);worker=name.equals("WotaStanceAnalysis");looper=new Looper(worker);setDaemon(true);}
                    public synchronized void start(){if(worker){workers.add(this);super.start();}}
                    public Looper getLooper(){return looper;}
                    public void run(){
                        try{while(!looper.closed)looper.queue.take().run();}
                        catch(InterruptedException e){Thread.currentThread().interrupt();}
                    }
                    public boolean quitSafely(){if(worker)looper.queue.add(()->looper.closed=true);else looper.closed=true;return true;}
                }
            """,
            "android.os.Handler" to """
                package android.os;
                public class Handler {
                    private final Looper looper;
                    public Handler(Looper looper){this.looper=looper;}
                    public Looper getLooper(){return looper;}
                    public boolean post(Runnable task){return !looper.closed&&looper.queue.offer(task);}
                    public boolean postDelayed(Runnable task,long ms){if(looper.closed)return false;looper.delayed.add(task);return true;}
                    public void removeCallbacks(Runnable task){looper.queue.remove(task);looper.delayed.remove(task);}
                    public void removeCallbacksAndMessages(Object token){looper.queue.clear();looper.delayed.clear();}
                    public void drain(){Looper.idleManual();}
                }
            """,
            "android.graphics.ImageFormat" to """
                package android.graphics;
                public class ImageFormat {public static final int PRIVATE=34,YUV_420_888=35,JPEG=256;}
            """,
            "android.view.Surface" to """
                package android.view;
                public class Surface {
                    private final String name; public volatile boolean valid=true;
                    public android.media.ImageReader reader;
                    public Surface(String name){this.name=name;}
                    public boolean isValid(){return valid;}
                    public String toString(){return name;}
                }
            """,
            "android.hardware.camera2.CameraCharacteristics" to """
                package android.hardware.camera2;
                public class CameraCharacteristics {
                    public static class Key<T>{public final String name;public Key(String name){this.name=name;}public String getName(){return name;}}
                    ${keys(CameraCharacteristics::class.java)}
                    private final java.util.Map<String,Object> values=new java.util.HashMap<>();
                    public void put(String key,Object value){values.put(key,value);}
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)values.get(key.name);}
                    public java.util.List<Key<?>> getKeys(){return java.util.Collections.emptyList();}
                    public java.util.Set<String> getPhysicalCameraIds(){return java.util.Collections.emptySet();}
                }
            """,
            "android.hardware.camera2.CameraManager" to """
                package android.hardware.camera2;
                public class CameraManager {
                    private final CameraCharacteristics cc=new CameraCharacteristics();
                    public CameraManager(){
                        cc.put("LENS_FACING",1);cc.put("LENS_INFO_AVAILABLE_FOCAL_LENGTHS",new float[]{4f});
                        cc.put("REQUEST_AVAILABLE_CAPABILITIES",new int[0]);
                        cc.put("INFO_SUPPORTED_HARDWARE_LEVEL",1);cc.put("SENSOR_ORIENTATION",0);
                        // API 29 下真实 RequestApplier 要用有效传感器区域计算 CROP_REGION；缺省零矩形不是可出帧设备。
                        cc.put("SENSOR_INFO_ACTIVE_ARRAY_SIZE",new android.graphics.Rect(0,0,640,480));
                        cc.put("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES",new android.util.Range[]{new android.util.Range<Integer>(30,30)});
                        cc.put("CONTROL_AF_AVAILABLE_MODES",new int[]{0});cc.put("CONTROL_AWB_AVAILABLE_MODES",new int[]{1});
                        cc.put("SCALER_STREAM_CONFIGURATION_MAP",new android.hardware.camera2.params.StreamConfigurationMap());
                    }
                    public String[] getCameraIdList(){return new String[]{"0"};}
                    public CameraCharacteristics getCameraCharacteristics(String id){if(!id.equals("0"))throw new IllegalArgumentException(id);return cc;}
                    public void openCamera(String id,CameraDevice.StateCallback callback,android.os.Handler handler){handler.post(()->callback.onOpened(new CameraDevice()));}
                }
            """,
            "android.hardware.camera2.params.StreamConfigurationMap" to """
                package android.hardware.camera2.params;
                public class StreamConfigurationMap {
                    public android.util.Size[] getOutputSizes(int format){return new android.util.Size[]{new android.util.Size(format==35?2:640,format==35?2:480)};}
                    public int[] getOutputFormats(){return new int[]{34,35,256};}
                    public android.util.Size[] getHighSpeedVideoSizes(){return new android.util.Size[0];}
                    public android.util.Range<Integer>[] getHighSpeedVideoFpsRangesFor(android.util.Size size){return new android.util.Range[0];}
                }
            """,
            "android.hardware.camera2.CaptureRequest" to """
                package android.hardware.camera2;
                public class CaptureRequest {
                    public static class Key<T>{public final String name;public Key(String name){this.name=name;}}
                    ${keys(CaptureRequest::class.java)}
                    public final java.util.List<android.view.Surface> targets;
                    CaptureRequest(java.util.List<android.view.Surface> targets){this.targets=new java.util.ArrayList<>(targets);}
                    public static class Builder {
                        private final java.util.List<android.view.Surface> targets=new java.util.ArrayList<>();
                        private final java.util.Map<String,Object> values=new java.util.HashMap<>();
                        public <T>void set(Key<T> key,T value){values.put(key.name,value);}
                        @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)values.get(key.name);}
                        public void addTarget(android.view.Surface surface){targets.add(surface);}
                        public CaptureRequest build(){return new CaptureRequest(targets);}
                    }
                }
            """,
            "android.hardware.camera2.params.OutputConfiguration" to """
                package android.hardware.camera2.params;
                public class OutputConfiguration {
                    private final android.view.Surface surface;
                    public OutputConfiguration(android.view.Surface surface){if(!surface.isValid())throw new IllegalArgumentException("abandoned");this.surface=surface;}
                    public android.view.Surface getSurface(){return surface;}
                    public void setPhysicalCameraId(String id){}
                }
            """,
            "android.hardware.camera2.params.SessionConfiguration" to """
                package android.hardware.camera2.params;
                public class SessionConfiguration {
                    public static final int SESSION_REGULAR=0,SESSION_HIGH_SPEED=1;
                    public final java.util.List<OutputConfiguration> outputs;
                    public final java.util.concurrent.Executor executor;
                    public final android.hardware.camera2.CameraCaptureSession.StateCallback callback;
                    public SessionConfiguration(int type,java.util.List<OutputConfiguration> outputs,java.util.concurrent.Executor executor,android.hardware.camera2.CameraCaptureSession.StateCallback callback){this.outputs=outputs;this.executor=executor;this.callback=callback;}
                }
            """,
            "android.hardware.camera2.CameraDevice" to """
                package android.hardware.camera2;
                public class CameraDevice {
                    public void close(){}
                    public CaptureRequest.Builder createCaptureRequest(int template){return new CaptureRequest.Builder();}
                    public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config){
                        CameraCaptureSession session=new CameraCaptureSession(config);
                        config.executor.execute(()->config.callback.onConfigured(session));
                    }
                    public abstract static class StateCallback {
                        public abstract void onOpened(CameraDevice device);public abstract void onDisconnected(CameraDevice device);public abstract void onError(CameraDevice device,int error);
                    }
                }
            """,
            "android.hardware.camera2.CameraCaptureSession" to """
                package android.hardware.camera2;
                public class CameraCaptureSession {
                    private final java.util.List<android.view.Surface> configured=new java.util.ArrayList<>();
                    private CaptureRequest repeating;private boolean closed;private int repeatingSubmissions;
                    public CameraCaptureSession(){}
                    public CameraCaptureSession(android.hardware.camera2.params.SessionConfiguration config){for(android.hardware.camera2.params.OutputConfiguration o:config.outputs)configured.add(o.getSurface());}
                    public java.util.List<android.view.Surface> getConfiguredTargets(){return configured;}
                    public boolean hasRepeatingRequest(){return !closed&&repeating!=null;}
                    public int getRepeatingSubmissions(){return repeatingSubmissions;}
                    public java.util.List<android.view.Surface> getRepeatingTargets(){if(repeating==null)throw new AssertionError("no repeating request");return repeating.targets;}
                    public int setRepeatingRequest(CaptureRequest request,CaptureCallback callback,android.os.Handler handler){repeating=request;return ++repeatingSubmissions;}
                    public int capture(CaptureRequest request,CaptureCallback callback,android.os.Handler handler){return 1;}
                    public void stopRepeating(){repeating=null;}public void close(){closed=true;}
                    public int emitAnalysis(){
                        if(closed)throw new AssertionError("closed session");int emitted=0;
                        for(android.view.Surface target:getRepeatingTargets())if(target.reader!=null){
                            if(!configured.contains(target))throw new AssertionError("unconfigured request target");
                            target.reader.emit();emitted++;
                        }
                        return emitted;
                    }
                    public abstract static class StateCallback {public abstract void onConfigured(CameraCaptureSession session);public abstract void onConfigureFailed(CameraCaptureSession session);}
                    public static class CaptureCallback {
                        public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long t,long f){}
                        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){}
                    }
                }
            """,
            "android.media.ImageReader" to """
                package android.media;
                public class ImageReader implements java.io.Closeable {
                    public interface OnImageAvailableListener {void onImageAvailable(ImageReader reader);}
                    private static int next;
                    public final java.util.List<String> events=new java.util.concurrent.CopyOnWriteArrayList<>();
                    public final java.util.List<Thread> closeOwners=new java.util.concurrent.CopyOnWriteArrayList<>();
                    private final android.view.Surface surface;
                    private final java.util.Queue<Image> pending=new java.util.concurrent.ConcurrentLinkedQueue<>();
                    private final java.util.Set<Image> acquired=java.util.concurrent.ConcurrentHashMap.newKeySet();
                    private final java.util.concurrent.atomic.AtomicInteger closes=new java.util.concurrent.atomic.AtomicInteger();
                    private final java.util.concurrent.atomic.AtomicInteger returned=new java.util.concurrent.atomic.AtomicInteger();
                    private final java.util.concurrent.atomic.AtomicInteger invalid=new java.util.concurrent.atomic.AtomicInteger();
                    private final int width,height;
                    private volatile OnImageAvailableListener listener;private volatile android.os.Handler handler;
                    public volatile boolean closed;
                    private volatile java.util.concurrent.CountDownLatch planeEntered,planeContinue;
                    public ImageReader(String name){this(name,2,2);}
                    private ImageReader(String name,int w,int h){surface=new android.view.Surface(name);surface.reader=this;width=w;height=h;}
                    public static ImageReader newInstance(int w,int h,int format,int max){return new ImageReader("analysis-"+(++next),w,h);}
                    public android.view.Surface getSurface(){return surface;}
                    public void setOnImageAvailableListener(OnImageAvailableListener listener,android.os.Handler handler){this.handler=handler;this.listener=listener;}
                    public Image acquireLatestImage(){
                        if(closed)throw new IllegalStateException("reader closed");Image image=pending.poll();
                        if(image!=null){acquired.add(image);events.add("image.acquire");}return image;
                    }
                    public void emit(){
                        if(closed)throw new IllegalStateException("emit to closed reader");
                        pending.add(new Image(this,width,height));OnImageAvailableListener callback=listener;android.os.Handler target=handler;
                        if(callback!=null)target.post(()->callback.onImageAvailable(this));
                    }
                    public void blockNextPlanes(java.util.concurrent.CountDownLatch entered,java.util.concurrent.CountDownLatch proceed){planeEntered=entered;planeContinue=proceed;}
                    void readingPlanes(){
                        java.util.concurrent.CountDownLatch entered=planeEntered,proceed=planeContinue;
                        if(entered!=null){
                            planeEntered=null;events.add("image.planes.enter");entered.countDown();
                            try{if(!proceed.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("planes gate timeout");}
                            catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                        }
                    }
                    void invalidAccess(){invalid.incrementAndGet();events.add("image.invalid-access");}
                    void giveBack(Image image){acquired.remove(image);returned.incrementAndGet();events.add("image.return");}
                    public void close(){closes.incrementAndGet();closeOwners.add(Thread.currentThread());events.add("reader.close");closed=true;surface.valid=false;}
                    public int getCloseCount(){return closes.get();}public int getReturnedCount(){return returned.get();}public int getInvalidAccesses(){return invalid.get();}
                }
            """,
            "android.media.Image" to """
                package android.media;
                public class Image implements java.io.Closeable {
                    private final ImageReader owner;private final int width,height;private boolean closed;
                    Image(ImageReader owner,int width,int height){this.owner=owner;this.width=width;this.height=height;}
                    private void checkAccess(){if(closed||owner.closed){owner.invalidAccess();throw new IllegalStateException("acquired image invalidated");}}
                    public int getWidth(){checkAccess();return width;}public int getHeight(){checkAccess();return height;}
                    public int getFormat(){checkAccess();return 35;}
                    public Plane[] getPlanes(){
                        checkAccess();owner.readingPlanes();checkAccess();
                        byte[] y=new byte[width*height],u=new byte[(width+1)/2*((height+1)/2)],v=new byte[u.length];
                        java.util.Arrays.fill(y,(byte)100);java.util.Arrays.fill(u,(byte)128);java.util.Arrays.fill(v,(byte)128);
                        return new Plane[]{new Plane(y,width),new Plane(u,(width+1)/2),new Plane(v,(width+1)/2)};
                    }
                    public void close(){if(!closed){closed=true;owner.giveBack(this);}}
                    public static class Plane {
                        private final byte[] data;private final int row;
                        Plane(byte[] data,int row){this.data=data;this.row=row;}
                        public java.nio.ByteBuffer getBuffer(){return java.nio.ByteBuffer.wrap(data);}
                        public int getRowStride(){return row;}public int getPixelStride(){return 1;}
                    }
                }
            """,
            "org.tensorflow.lite.Delegate" to "package org.tensorflow.lite;public interface Delegate {}",
            "org.tensorflow.lite.DataType" to "package org.tensorflow.lite;public enum DataType {FLOAT32,UINT8,INT8}",
            "org.tensorflow.lite.Tensor" to "package org.tensorflow.lite;public interface Tensor {int[] shape();DataType dataType();}",
            "org.tensorflow.lite.gpu.GpuDelegateFactory" to "package org.tensorflow.lite.gpu;public class GpuDelegateFactory {public static class Options {}}",
            "org.tensorflow.lite.gpu.GpuDelegate" to """
                package org.tensorflow.lite.gpu;
                public class GpuDelegate implements org.tensorflow.lite.Delegate,java.io.Closeable {
                    public static class Options extends GpuDelegateFactory.Options {}
                    public GpuDelegate(){}public GpuDelegate(GpuDelegateFactory.Options options){}public void close(){}
                }
            """,
            "org.tensorflow.lite.gpu.CompatibilityList" to """
                package org.tensorflow.lite.gpu;
                public class CompatibilityList implements java.io.Closeable {
                    public boolean isDelegateSupportedOnThisDevice(){return false;}
                    public GpuDelegate.Options getBestOptionsForThisDevice(){return new GpuDelegate.Options();}public void close(){}
                }
            """,
            "org.tensorflow.lite.Interpreter" to """
                package org.tensorflow.lite;
                public class Interpreter implements java.io.Closeable {
                    public static class Options {public Options setNumThreads(int count){return this;}public Options addDelegate(Delegate delegate){return this;}}
                    public Interpreter(java.nio.ByteBuffer model,Options options){}
                    public Tensor getInputTensor(int index){return new Tensor(){public int[] shape(){return new int[]{1,3,2,2};}public DataType dataType(){return DataType.FLOAT32;}};}
                    public Tensor getOutputTensor(int index){return new Tensor(){public int[] shape(){return new int[]{1,5,2};}public DataType dataType(){return DataType.FLOAT32;}};}
                    public void run(Object input,Object output){
                        if(output instanceof java.nio.ByteBuffer)return;
                        float[][][] rows=(float[][][])output;
                        rows[0][0][0]=0.5f;rows[0][1][0]=0.5f;rows[0][2][0]=0.3f;rows[0][3][0]=0.4f;rows[0][4][0]=0.9f;
                    }
                    public void close(){}
                }
            """
        )
    }
}

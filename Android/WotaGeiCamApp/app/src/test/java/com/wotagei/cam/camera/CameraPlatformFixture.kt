package com.wotagei.cam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader

/**
 * 只替换 Android 相机/线程/Surface 边界。枚举、能力收集、参数、引擎、回调和帧账均加载生产字节码。
 * 不仿写枚举/会话/心跳算法，不启动真实线程，不打开设备，也不把平台桩编译错误算作行为 RED。
 */
internal class CameraPlatformFixture(classes: File, parent: ClassLoader) : Closeable {
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                STUBS.any { name == it || name.startsWith("$it\$") } -> findClass(name)
                name.startsWith("com.wotagei.cam.core.") ||
                    name == "com.wotagei.cam.camera.PreviewSink" ||
                    name.startsWith("com.wotagei.cam.camera.Camera2Engine") ||
                    name.startsWith("com.wotagei.cam.camera.RequestApplier") ||
                    name in setOf("com.wotagei.cam.camera.DeviceStatus", "com.wotagei.cam.camera.PreviewStatus",
                        "com.wotagei.cam.camera.RecordStatus", "com.wotagei.cam.camera.AnalysisSessionDecision",
                        "com.wotagei.cam.camera.FrameRateFallback", "com.wotagei.cam.record.FrameLedger",
                        "com.wotagei.cam.record.FrameCounterSnapshot") -> {
                    val resource = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(resource)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少实际生产字节码：$resource")
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
    val manager: Any = new("android.hardware.camera2.CameraManager")
    private val engines = ArrayList<Any>()
    private val job = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(job + Dispatchers.Unconfined)

    val events: MutableList<String>
        @Suppress("UNCHECKED_CAST")
        get() = type("probe.CameraEvents").getField("all").get(null) as MutableList<String>
    val sessions: List<Any>
        @Suppress("UNCHECKED_CAST")
        get() = manager.javaClass.getField("sessions").get(manager) as List<Any>
    val opened: List<String>
        @Suppress("UNCHECKED_CAST")
        get() = manager.javaClass.getField("opened").get(manager) as List<String>

    fun type(name: String): Class<*> = loader.loadClass(name)
    fun new(name: String): Any = type(name).getDeclaredConstructor().newInstance()
    fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(instance)
    fun set(instance: Any, name: String, value: Any?) = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.set(instance, value)
    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val methods = (instance.javaClass.declaredMethods.asList() + instance.javaClass.methods.asList()).distinct()
        val method = methods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return unwrap { method.invoke(instance, *args) }
    }
    private fun staticCall(name: String, method: String, vararg args: Any?): Any? = unwrap {
        type(name).declaredMethods.single { it.name == method && it.parameterCount == args.size }.invoke(null, *args)
    }
    private fun <T> unwrap(block: () -> T): T = try { block() } catch (e: InvocationTargetException) {
        throw e.targetException
    }
    fun surface(name: String): Any = type("android.view.Surface").getConstructor(String::class.java).newInstance(name)
    fun clock(ms: Long) { type("android.os.SystemClock").getField("now").setLong(null, ms) }

    /** 固定平台输入，不包含任何生产枚举决策；隐藏候选是否可独立打开由平台脚本单独声明。 */
    fun camera(id: String, front: Boolean = false, logical: Boolean = false,
               physicals: List<String> = emptyList(), physicalReadFails: Boolean = false,
               openable: Boolean = true, focal: Float = 4f, highSpeed: Boolean = false,
               af: IntArray = intArrayOf(0)): Any {
        val cc = new("android.hardware.camera2.CameraCharacteristics")
        fun put(key: String, value: Any) { call(cc, "put", key, value) }
        put("LENS_FACING", if (front) 0 else 1)
        put("REQUEST_AVAILABLE_CAPABILITIES", (listOfNotNull(if (logical) 11 else null,
            if (highSpeed) 9 else null)).toIntArray())
        put("LENS_INFO_AVAILABLE_FOCAL_LENGTHS", floatArrayOf(focal))
        put("SENSOR_INFO_PHYSICAL_SIZE", type("android.util.SizeF").getConstructor(
            Float::class.javaPrimitiveType, Float::class.javaPrimitiveType).newInstance(6f, 4f))
        put("CONTROL_AF_AVAILABLE_MODES", af)
        put("CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES", call(manager, "normalRanges")!!)
        put("SCALER_STREAM_CONFIGURATION_MAP", type("android.hardware.camera2.params.StreamConfigurationMap")
            .getConstructor(Boolean::class.javaPrimitiveType).newInstance(highSpeed))
        set(cc, "physicalIds", physicals.toSet())
        set(cc, "physicalReadFails", physicalReadFails)
        call(manager, "add", id, cc, openable)
        return cc
    }
    fun publicIds(vararg ids: String) { set(manager, "publicIds", ids) }
    @Suppress("UNCHECKED_CAST")
    fun enumerate(): List<Any> = staticCall("com.wotagei.cam.core.LensSlotKt", "enumerateLenses", manager) as List<Any>
    fun key(slot: Any): String = call(slot, "getKey") as String
    fun logic(slot: Any): String = call(slot, "getLogicId") as String
    fun physical(slot: Any): String? = call(slot, "getPhysicalId") as String?
    fun ability(slot: Any): Any = call(slot, "getAbility")!!
    fun abilityOf(id: String): Any {
        val companion = type("com.wotagei.cam.core.CameraAbility").getField("Companion").get(null)
        return call(companion, "from", id, call(manager, "getCameraCharacteristics", id), null)!!
    }
    fun slot(id: String, physical: String? = null): Any {
        val lensType = enum("com.wotagei.cam.core.LensType", "WIDE")
        return type("com.wotagei.cam.core.LensSlot").declaredConstructors.single { it.parameterCount == 5 }
            .newInstance(lensType, id, physical, 24f, abilityOf(physical ?: id))
    }
    private fun enum(name: String, value: String): Any = type(name).enumConstants.single { it.toString() == value }
    fun value(params: Any, property: String): Any? = call(call(params, "get$property")!!, "getValue")
    fun value(params: Any, property: String, value: Any?) { call(call(params, "get$property")!!, "setValue", value) }
    fun fps(params: Any, fps: Int) {
        val old = value(params, "Fps")!!
        val next = call(old, "copy", fps, call(old, "getRange"), true, true)!!
        call(params, "setFps", next)
    }
    fun manualWb(params: Any) { value(params, "WbMode", enum("com.wotagei.cam.core.WbPreset", "MANUAL")) }

    data class Harness(val engine: Any, val params: Any, val preview: Any,
                       val analysis: Any, val extra: Any, val decisions: MutableList<String>)

    fun engine(fps: Int = 30, gpu: Boolean = false): Harness {
        val params = type("com.wotagei.cam.core.WotaParams").getConstructor(CoroutineScope::class.java).newInstance(scope)
        val size = type("com.wotagei.cam.core.Size").getConstructor(Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType).newInstance(640, 480)
        value(params, "Size", size)
        value(params, "RenderMode", enum("com.wotagei.cam.core.RenderMode", if (gpu) "GPU" else "DIRECT"))
        fps(params, fps)
        val preview = surface("preview")
        val sink = Proxy.newProxyInstance(loader, arrayOf(type("com.wotagei.cam.camera.PreviewSink"))) { _, method, _ ->
            when (method.name) {
                "cameraTargets" -> listOf(preview)
                "structuralToken" -> "fixture"
                "toString" -> "fixture-sink"
                else -> null
            }
        }
        val context = type("android.content.Context").getConstructor(manager.javaClass).newInstance(manager)
        val decisions = ArrayList<String>()
        val status: (Any) -> Unit = {}
        val downgraded: (Any) -> Unit = { events.add("downgrade:$it") }
        val decision: (Any) -> Unit = { decisions.add(it.toString()); events.add("analysis:$it") }
        val engine = type("com.wotagei.cam.camera.Camera2Engine").declaredConstructors.single { it.parameterCount == 8 }
            .newInstance(context, params, sink, scope, status, status, downgraded, decision)
        engines.add(engine)
        return Harness(engine, params, preview, surface("yuv-analysis"), surface("encoder"), decisions)
    }
    fun open(h: Harness, slot: Any) { call(h.engine, "open", slot); call(h.engine, "startPreview") }
    fun analysis(h: Harness, enabled: Boolean = true) { call(h.engine, "setAnalysisTarget", if (enabled) h.analysis else null, null) }
    fun extra(h: Harness, enabled: Boolean = true) { call(h.engine, "setExtraTarget", if (enabled) h.extra else null, null) }
    fun pulse(h: Harness, fps: Int) {
        fps(h.params, fps)
        val pulse = type("com.wotagei.cam.camera.Camera2Engine\$Pulse").declaredConstructors.single().apply {
            isAccessible = true
        }.newInstance(value(h.params, "Size"), value(h.params, "RenderMode"), fps, 40_000_000L, 100)
        call(h.engine, "onStructuralPulse", pulse)
    }
    fun sessionType(config: Any = sessions.last()): Int = call(config, "getSessionType") as Int
    @Suppress("UNCHECKED_CAST")
    fun outputs(config: Any = sessions.last()): List<Any> = call(config, "getOutputConfigurations") as List<Any>
    fun outputNames(config: Any = sessions.last()): List<String> = outputs(config).map { call(it, "getSurface").toString() }
    fun outputPhysicals(config: Any = sessions.last()): List<String?> = outputs(config).map { call(it, "getPhysicalCameraId") as String? }

    /** 经真实 repeating 出口拿到真实生产回调；测试绝不自行选择或实现回调。 */
    fun repeating(h: Harness, highSpeed: Boolean): Any {
        val session = new("android.hardware.camera2." + if (highSpeed) "CameraConstrainedHighSpeedCaptureSession" else "CameraCaptureSession")
        set(h.engine, "session", session)
        set(h.engine, "highSpeedSession", if (highSpeed) session else null)
        call(h.engine, "submitRepeating", new("android.hardware.camera2.CaptureRequest"))
        return session
    }
    fun frame(session: Any, frame: Long) { call(session, "emit", frame) }
    fun ledger(): Long {
        val ledger = type("com.wotagei.cam.record.FrameLedger").getField("INSTANCE").get(null)
        return call(call(ledger, "snapshot")!!, "getCameraStarted") as Long
    }
    override fun close() {
        try { engines.forEach { call(it, "close") } } finally { job.cancel(); loader.close() }
    }

    companion object {
        private val STUBS = setOf("probe.CameraEvents", "android.content.Context", "android.view.Surface",
            "android.os.Looper", "android.os.Handler", "android.os.HandlerThread", "android.os.SystemClock",
            "android.os.Build", "android.util.Size", "android.util.SizeF", "android.util.Range", "android.util.Rational",
            "android.graphics.Rect", "android.hardware.camera2.CameraCharacteristics", "android.hardware.camera2.CameraManager",
            "android.hardware.camera2.CameraDevice", "android.hardware.camera2.CameraCaptureSession",
            "android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession", "android.hardware.camera2.CaptureRequest",
            "android.hardware.camera2.CaptureResult", "android.hardware.camera2.TotalCaptureResult",
            "android.hardware.camera2.params.StreamConfigurationMap", "android.hardware.camera2.params.OutputConfiguration",
            "android.hardware.camera2.params.SessionConfiguration", "com.wotagei.cam.diag.Diag")

        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val generated = SOURCES + mapOf(
                "android.hardware.camera2.CameraCharacteristics" to characteristicSource(),
                "android.hardware.camera2.CaptureRequest" to requestSource(),
                "android.hardware.camera2.CaptureResult" to resultSource()
            )
            val sources = generated.map { (name, source) ->
                File(root, name.replace('.', '/') + ".java").apply { parentFile.mkdirs(); writeText(source.trimIndent()) }
            }
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
            assertNotNull("使用已有宿主 JDK，不下载任何依赖", compiler)
            val classpath = listOf(Camera2Engine::class.java, kotlin.Unit::class.java,
                CoroutineScope::class.java, Context::class.java).map {
                File(it.protectionDomain.codeSource.location.toURI()).absolutePath
            }.distinct().joinToString(File.pathSeparator)
            val args = listOf("-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", classes.absolutePath) +
                sources.map { it.absolutePath }
            val run = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java,
                OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
            assertEquals("相机平台边界桩编译失败不算有效 RED", 0,
                run.invoke(compiler, null, null, null, args.toTypedArray()))
            return classes
        }

        // 只从 SDK 的公开字段签名生成同名 Key，避免手抄几百个 Android 键；不读生产源码或复制业务决策。
        private fun keys(type: Class<*>): String = type.declaredFields.filter { it.type.simpleName == "Key" }
            .joinToString("\n") { f ->
                "public static final ${f.genericType.typeName.replace('$', '.')} ${f.name} = new Key<>(\"${f.name}\");"
            }
        private fun characteristicSource(): String = """
            package android.hardware.camera2;
            public class CameraCharacteristics {
                public static class Key<T> { public final String name; public Key(String n) { name=n; } public String getName(){return name;} }
                ${keys(CameraCharacteristics::class.java)}
                public final java.util.Map<String,Object> values = new java.util.HashMap<>();
                public java.util.Set<String> physicalIds = new java.util.HashSet<>();
                public boolean physicalReadFails;
                public void put(String key,Object value){values.put(key,value);}
                @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)values.get(key.name);}
                public java.util.List<Key<?>> getKeys(){return java.util.Collections.emptyList();}
                public java.util.Set<String> getPhysicalCameraIds(){if(physicalReadFails)throw new IllegalStateException("physicalIds unavailable");return physicalIds;}
            }
        """
        private fun requestSource(): String = """
            package android.hardware.camera2;
            public class CaptureRequest {
                public static class Key<T>{public final String name;public Key(String n){name=n;}public String getName(){return name;}}
                ${keys(CaptureRequest::class.java)}
                public static class Builder {
                    private final java.util.Map<Key<?>,Object> values=new java.util.HashMap<>();
                    public <T>void set(Key<T> key,T value){values.put(key,value);}
                    @SuppressWarnings("unchecked") public <T>T get(Key<T> key){return (T)values.get(key);}
                    public void addTarget(android.view.Surface surface){}
                    public CaptureRequest build(){return new CaptureRequest();}
                }
            }
        """
        private fun resultSource(): String = """
            package android.hardware.camera2;
            public class CaptureResult {
                public static class Key<T>{public final String name;public Key(String n){name=n;}public String getName(){return name;}}
                ${keys(CaptureResult::class.java)}
                public <T>T get(Key<T> key){return null;}
            }
        """

        private val SOURCES = mapOf(
            "probe.CameraEvents" to """
                package probe;
                public class CameraEvents {public static final java.util.List<String> all=new java.util.ArrayList<>();}
            """,
            "android.content.Context" to """
                package android.content;
                public class Context {
                    private final android.hardware.camera2.CameraManager manager;
                    public Context(android.hardware.camera2.CameraManager m){manager=m;}
                    public Context getApplicationContext(){return this;}
                    public Object getSystemService(String name){return manager;}
                }
            """,
            "android.view.Surface" to """
                package android.view;
                public class Surface {private final String name;public Surface(String n){name=n;}public boolean isValid(){return true;}public String toString(){return name;}}
            """,
            "android.os.Looper" to "package android.os; public class Looper {public boolean closed;}",
            "android.os.HandlerThread" to """
                package android.os;
                public class HandlerThread extends Thread {
                    private final Looper looper=new Looper();public HandlerThread(String n){super(n);}
                    @Override public synchronized void start(){}
                    public Looper getLooper(){return looper;}public boolean quitSafely(){looper.closed=true;return true;}
                }
            """,
            "android.os.Handler" to """
                package android.os;
                public class Handler {
                    private final Looper looper;private final java.util.List<Runnable> delayed=new java.util.ArrayList<>();
                    public Handler(Looper l){looper=l;}
                    public boolean post(Runnable r){if(looper.closed)return false;r.run();return true;}
                    public boolean postDelayed(Runnable r,long ms){if(looper.closed)return false;delayed.add(r);return true;}
                    public void removeCallbacks(Runnable r){delayed.remove(r);}
                    public void removeCallbacksAndMessages(Object o){delayed.clear();}
                }
            """,
            "android.os.SystemClock" to "package android.os; public class SystemClock {public static long now;public static long elapsedRealtime(){return now;}}",
            "android.os.Build" to "package android.os; public class Build {public static class VERSION {public static int SDK_INT=29;}}",
            "android.util.Size" to """
                package android.util;
                public class Size {private final int w,h;public Size(int w,int h){this.w=w;this.h=h;}public int getWidth(){return w;}public int getHeight(){return h;}}
            """,
            "android.util.SizeF" to """
                package android.util;
                public class SizeF {private final float w,h;public SizeF(float w,float h){this.w=w;this.h=h;}public float getWidth(){return w;}public float getHeight(){return h;}}
            """,
            "android.util.Range" to """
                package android.util;
                public class Range<T extends Comparable<? super T>> {private final T lo,hi;public Range(T l,T h){lo=l;hi=h;}public T getLower(){return lo;}public T getUpper(){return hi;}}
            """,
            "android.util.Rational" to """
                package android.util;
                public class Rational extends Number {private final int n,d;public Rational(int n,int d){this.n=n;this.d=d;}public int getNumerator(){return n;}public int getDenominator(){return d;}public int intValue(){return n/d;}public long longValue(){return n/d;}public float floatValue(){return (float)n/d;}public double doubleValue(){return (double)n/d;}}
            """,
            "android.graphics.Rect" to """
                package android.graphics;
                public class Rect {public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
            """,
            "android.hardware.camera2.params.StreamConfigurationMap" to """
                package android.hardware.camera2.params;
                public class StreamConfigurationMap {
                    private final boolean hs;public StreamConfigurationMap(boolean h){hs=h;}
                    public android.util.Size[] getOutputSizes(int f){return new android.util.Size[]{new android.util.Size(640,480)};}
                    public android.util.Size[] getHighSpeedVideoSizes(){return hs?getOutputSizes(34):new android.util.Size[0];}
                    @SuppressWarnings("unchecked") public android.util.Range<Integer>[] getHighSpeedVideoFpsRangesFor(android.util.Size s){return new android.util.Range[]{new android.util.Range<Integer>(30,120)};}
                    public int[] getOutputFormats(){return new int[]{34,35,256};}
                }
            """,
            "android.hardware.camera2.CameraManager" to """
                package android.hardware.camera2;
                public class CameraManager {
                    public String[] publicIds=new String[0];
                    private final java.util.Map<String,CameraCharacteristics> cameras=new java.util.HashMap<>();
                    private final java.util.Set<String> openable=new java.util.HashSet<>();
                    public final java.util.List<String> opened=new java.util.ArrayList<>();
                    public final java.util.List<android.hardware.camera2.params.SessionConfiguration> sessions=new java.util.ArrayList<>();
                    public void add(String id,CameraCharacteristics c,boolean canOpen){cameras.put(id,c);if(canOpen)openable.add(id);}
                    public String[] getCameraIdList(){return publicIds;}
                    public CameraCharacteristics getCameraCharacteristics(String id){if(!cameras.containsKey(id))throw new IllegalArgumentException(id);return cameras.get(id);}
                    @SuppressWarnings("unchecked") public android.util.Range<Integer>[] normalRanges(){return new android.util.Range[]{new android.util.Range<Integer>(30,30)};}
                    public void openCamera(String id,CameraDevice.StateCallback callback,android.os.Handler handler){
                        opened.add(id);probe.CameraEvents.all.add("open:"+id);
                        if(!openable.contains(id))throw new IllegalArgumentException("not independently openable: "+id);
                        handler.post(()->callback.onOpened(new CameraDevice(id,this)));
                    }
                }
            """,
            "android.hardware.camera2.CameraDevice" to """
                package android.hardware.camera2;
                public class CameraDevice {
                    private final String id;private final CameraManager manager;
                    public CameraDevice(String id,CameraManager m){this.id=id;manager=m;}
                    public String getId(){return id;}
                    public void close(){probe.CameraEvents.all.add("device.close:"+id);}
                    public CaptureRequest.Builder createCaptureRequest(int template){return new CaptureRequest.Builder();}
                    public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config){
                        manager.sessions.add(config);probe.CameraEvents.all.add("create:"+config.getSessionType());
                    }
                    public abstract static class StateCallback {
                        public abstract void onOpened(CameraDevice d);public abstract void onDisconnected(CameraDevice d);public abstract void onError(CameraDevice d,int error);
                    }
                }
            """,
            "android.hardware.camera2.params.OutputConfiguration" to """
                package android.hardware.camera2.params;
                public class OutputConfiguration {
                    private final android.view.Surface surface;private String physical;
                    public OutputConfiguration(android.view.Surface s){surface=s;}
                    public android.view.Surface getSurface(){return surface;}
                    public void setPhysicalCameraId(String id){physical=id;probe.CameraEvents.all.add("physical:"+id);}
                    public String getPhysicalCameraId(){return physical;}
                }
            """,
            "android.hardware.camera2.params.SessionConfiguration" to """
                package android.hardware.camera2.params;
                public class SessionConfiguration {
                    private final int type;private final java.util.List<OutputConfiguration> outputs;
                    private final java.util.concurrent.Executor executor;private final android.hardware.camera2.CameraCaptureSession.StateCallback callback;
                    public SessionConfiguration(int t,java.util.List<OutputConfiguration> o,java.util.concurrent.Executor e,android.hardware.camera2.CameraCaptureSession.StateCallback c){type=t;outputs=o;executor=e;callback=c;}
                    public int getSessionType(){return type;}public java.util.List<OutputConfiguration> getOutputConfigurations(){return outputs;}
                    public java.util.concurrent.Executor getExecutor(){return executor;}public android.hardware.camera2.CameraCaptureSession.StateCallback getStateCallback(){return callback;}
                }
            """,
            "android.hardware.camera2.CameraCaptureSession" to """
                package android.hardware.camera2;
                public class CameraCaptureSession {
                    protected CaptureCallback callback;protected CaptureRequest request;
                    public int setRepeatingRequest(CaptureRequest r,CaptureCallback c,android.os.Handler h){request=r;callback=c;return 1;}
                    public int capture(CaptureRequest r,CaptureCallback c,android.os.Handler h){return 1;}
                    public void stopRepeating(){}public void close(){}
                    public void emit(long frame){if(callback==null)throw new AssertionError("生产出口没有绑定回调");callback.onCaptureStarted(this,request,frame*1000000,frame);callback.onCaptureCompleted(this,request,new TotalCaptureResult());}
                    public abstract static class StateCallback {public abstract void onConfigured(CameraCaptureSession s);public abstract void onConfigureFailed(CameraCaptureSession s);}
                    public static class CaptureCallback {
                        public void onCaptureStarted(CameraCaptureSession s,CaptureRequest r,long timestamp,long frame){}
                        public void onCaptureCompleted(CameraCaptureSession s,CaptureRequest r,TotalCaptureResult result){}
                    }
                }
            """,
            "android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession" to """
                package android.hardware.camera2;
                public class CameraConstrainedHighSpeedCaptureSession extends CameraCaptureSession {
                    public java.util.List<CaptureRequest> createHighSpeedRequestList(CaptureRequest r){return java.util.Collections.singletonList(r);}
                    public int setRepeatingBurst(java.util.List<CaptureRequest> requests,CaptureCallback c,android.os.Handler h){return setRepeatingRequest(requests.get(0),c,h);}
                    public int captureBurst(java.util.List<CaptureRequest> requests,CaptureCallback c,android.os.Handler h){return 1;}
                }
            """,
            "android.hardware.camera2.TotalCaptureResult" to "package android.hardware.camera2; public class TotalCaptureResult extends CaptureResult {}",
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public class Diag {
                    public static final Diag INSTANCE=new Diag();
                    public void i(String t,String m){}public void d(String t,String m){}
                    public void w(String t,String m){}public void w(String t,String m,Throwable e){}
                    public void e(String t,String m){}public void e(String t,String m,Throwable e){}
                }
            """
        )
    }
}

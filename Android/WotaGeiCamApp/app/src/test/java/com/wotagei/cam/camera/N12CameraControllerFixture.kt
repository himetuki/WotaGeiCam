package com.wotagei.cam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import kotlinx.coroutines.CoroutineScope
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
 * N12 专用平台桥：只加载真实 CameraController/Camera2Engine 字节码，平台边界由可控桩承接。
 *
 * 与旧 CameraPlatformFixture 分开，避免改变既有测试的同步调度语义。Handler 默认只入队，
 * 测试显式 drain 某一台真实引擎，从而能稳定重放旧引擎 close 晚于新引擎 reader 重挂的窗口。
 */
internal class N12CameraControllerFixture(
    parent: ClassLoader,
    boundarySources: Map<String, String> = emptyMap(),
    productionPrefixes: Set<String> = emptySet(),
) : Closeable {
    private val root = File(
        "build/tmp/n12-camera-controller-${System.nanoTime()}"
    ).apply { mkdirs() }
    // 新的 full reader 桥只覆盖平台/JNI 边界；默认参数保持旧 N12 的排队语义不变。
    private val boundaries = STUBS + boundarySources.keys
    private val classes = compile(root, boundarySources)
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                boundaries.any { name == it || name.startsWith("$it$") } -> findClass(name)
                productionPrefixes.any { name.startsWith(it) } ||
                    name == "com.wotagei.cam.camera.CameraController" ||
                    name.startsWith("com.wotagei.cam.camera.") ||
                    name.startsWith("com.wotagei.cam.core.") ||
                    name == "com.wotagei.cam.record.FrameLedger" ||
                    name == "com.wotagei.cam.record.FrameCounterSnapshot" -> {
                    val resource = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(resource)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少真实生产字节码：$resource")
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }

    private val manager: Any = new("android.hardware.camera2.CameraManager")

    fun type(name: String): Class<*> = loader.loadClass(name)

    fun new(name: String): Any = type(name).getDeclaredConstructor().newInstance()

    fun application(): Any = type("android.app.Application")
        .getConstructor(manager.javaClass)
        .newInstance(manager)

    fun controller(application: Any): Any = rememberController(
        type("com.wotagei.cam.camera.CameraController")
            .getConstructor(type("android.app.Application"))
            .newInstance(application)
    )

    fun sink(name: String): Any {
        val preview = surface(name)
        return Proxy.newProxyInstance(loader, arrayOf(type("com.wotagei.cam.camera.PreviewSink"))) {
                _, method, _ ->
            when (method.name) {
                "cameraTargets" -> listOf(preview)
                "structuralToken" -> "n12-$name"
                "toString" -> "n12-sink-$name"
                else -> null
            }
        }
    }

    fun surface(name: String): Any = type("android.view.Surface")
        .getConstructor(String::class.java)
        .newInstance(name)

    fun reader(name: String): Any = type("android.media.ImageReader")
        .getConstructor(String::class.java)
        .newInstance(name)

    fun engineOf(controller: Any): Any = field(controller, "engine")
        ?: error("真实 CameraController 没有当前 Camera2Engine")

    fun drain(engine: Any) {
        call(field(engine, "workHandler") ?: error("真实 Camera2Engine 没有 workHandler"), "drain")
    }

    fun readerCloseCount(reader: Any): Int = call(reader, "getCloseCount") as Int

    fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(instance)

    fun set(instance: Any, name: String, value: Any?) = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.set(instance, value)

    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val methods = (instance.javaClass.declaredMethods.asList() + instance.javaClass.methods.asList())
            .distinct()
        val method = methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: error("找不到 ${instance.javaClass.name}.$name(${args.size})")
        method.isAccessible = true
        return unwrap { method.invoke(instance, *args) }
    }

    private fun <T> unwrap(block: () -> T): T = try {
        block()
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

    override fun close() {
        runCatching {
            val controllerClass = type("com.wotagei.cam.camera.CameraController")
            val controller = trackedController
            if (controller != null) {
                controllerClass.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(controller)
            }
        }
        loader.close()
        root.deleteRecursively()
    }

    private var trackedController: Any? = null

    init {
        // 留住最后一个真实 Controller，仅用于 fixture 收尾时走生产 onCleared。
        // 测试通过 controller() 创建它；不参与行为断言。
    }

    private fun rememberController(controller: Any): Any = controller.also { trackedController = it }

    companion object {
        private val STUBS = setOf(
            "android.Manifest",
            "android.app.Application",
            "android.content.Context",
            "android.content.SharedPreferences",
            "android.content.pm.PackageManager",
            "android.graphics.ImageFormat",
            "android.graphics.Rect",
            "android.hardware.camera2.CameraAccessException",
            "android.hardware.camera2.CameraCharacteristics",
            "android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession",
            "android.hardware.camera2.CameraDevice",
            "android.hardware.camera2.CameraManager",
            "android.hardware.camera2.CameraMetadata",
            "android.hardware.camera2.CameraCaptureSession",
            "android.hardware.camera2.CaptureRequest",
            "android.hardware.camera2.CaptureResult",
            "android.hardware.camera2.TotalCaptureResult",
            "android.hardware.camera2.params.ColorSpaceTransform",
            "android.hardware.camera2.params.MeteringRectangle",
            "android.hardware.camera2.params.OutputConfiguration",
            "android.hardware.camera2.params.RggbChannelVector",
            "android.hardware.camera2.params.SessionConfiguration",
            "android.hardware.camera2.params.StreamConfigurationMap",
            "android.media.Image",
            "android.media.ImageReader",
            "android.os.Build",
            "android.os.Handler",
            "android.os.HandlerThread",
            "android.os.Looper",
            "android.os.SystemClock",
            "android.util.Range",
            "android.util.Rational",
            "android.util.Size",
            "android.util.SizeF",
            "android.view.Surface",
            "androidx.core.content.ContextCompat",
            "androidx.lifecycle.AndroidViewModel",
            "androidx.lifecycle.DefaultLifecycleObserver",
            "androidx.lifecycle.Lifecycle",
            "androidx.lifecycle.LifecycleOwner",
            "androidx.lifecycle.ViewModel",
            "androidx.lifecycle.ViewModelProvider",
            "androidx.lifecycle.ViewModelStoreOwner",
            "com.wotagei.cam.diag.Diag",
            "kotlinx.coroutines.Dispatchers"
        )

        private fun compile(root: File, boundarySources: Map<String, String>): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val generated = SOURCES + mapOf(
                "android.hardware.camera2.CameraCharacteristics" to characteristicSource(),
                "android.hardware.camera2.CaptureRequest" to requestSource(),
                "android.hardware.camera2.CaptureResult" to resultSource()
            ) + boundarySources
            val sources = generated.map { (name, source) ->
                File(root, name.replace('.', '/') + ".java").apply {
                    parentFile.mkdirs()
                    writeText(source.trimIndent())
                }
            }
            val compiler = Class.forName("javax.tools.ToolProvider")
                .getMethod("getSystemJavaCompiler")
                .invoke(null)
            assertNotNull("使用已有宿主 JDK，不下载任何依赖", compiler)
            val classpath = listOf(
                Camera2Engine::class.java,
                kotlin.Unit::class.java,
                CoroutineScope::class.java,
                Context::class.java
            ).map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath }
                .distinct()
                .joinToString(File.pathSeparator)
            val args = listOf(
                "-proc:none", "-encoding", "UTF-8", "-classpath", classpath,
                "-d", classes.absolutePath
            ) + sources.map { it.absolutePath }
            val run = Class.forName("javax.tools.Tool")
                .getMethod(
                    "run",
                    InputStream::class.java,
                    OutputStream::class.java,
                    OutputStream::class.java,
                    Array<String>::class.java
                )
            assertEquals(
                "N12 相机平台边界桩编译失败不算有效 RED",
                0,
                run.invoke(compiler, null, null, null, args.toTypedArray())
            )
            return classes
        }

        private fun keys(type: Class<*>): String = type.declaredFields
            .filter { it.type.simpleName == "Key" }
            .joinToString("\n") { field ->
                "public static final ${field.genericType.typeName.replace('$', '.')} " +
                    "${field.name} = new Key<>(\"${field.name}\");"
            }

        private fun characteristicSource(): String = """
            package android.hardware.camera2;
            public class CameraCharacteristics {
                public static class Key<T> { public final String name; public Key(String n) { name=n; } }
                ${keys(CameraCharacteristics::class.java)}
                public <T>T get(Key<T> key){return null;}
                public java.util.Set<String> getPhysicalCameraIds(){return java.util.Collections.emptySet();}
            }
        """

        private fun requestSource(): String = """
            package android.hardware.camera2;
            public class CaptureRequest {
                public static class Key<T>{public final String name;public Key(String n){name=n;}}
                ${keys(CaptureRequest::class.java)}
                public static class Builder {
                    public <T>void set(Key<T> key,T value){}
                    public <T>T get(Key<T> key){return null;}
                    public void addTarget(android.view.Surface surface){}
                    public CaptureRequest build(){return new CaptureRequest();}
                }
            }
        """

        private fun resultSource(): String = """
            package android.hardware.camera2;
            public class CaptureResult {
                public static class Key<T>{public final String name;public Key(String n){name=n;}}
                ${keys(CaptureResult::class.java)}
                public <T>T get(Key<T> key){return null;}
            }
        """

        private val SOURCES = mapOf(
            "android.Manifest" to """
                package android;
                public final class Manifest {
                    public static final class permission {
                        public static final String CAMERA="android.permission.CAMERA";
                        public static final String RECORD_AUDIO="android.permission.RECORD_AUDIO";
                    }
                }
            """,
            "android.app.Application" to """
                package android.app;
                public class Application extends android.content.Context {
                    public Application(android.hardware.camera2.CameraManager manager){super(manager);}
                }
            """,
            "android.content.Context" to """
                package android.content;
                public class Context {
                    public static final String CAMERA_SERVICE="camera";
                    public static final int MODE_PRIVATE=0;
                    private final android.hardware.camera2.CameraManager manager;
                    private final SharedPreferences prefs=new SharedPreferences.Impl();
                    public Context(android.hardware.camera2.CameraManager m){manager=m;}
                    public Context getApplicationContext(){return this;}
                    public Object getSystemService(String name){return manager;}
                    public SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
                }
            """,
            "android.content.SharedPreferences" to """
                package android.content;
                public interface SharedPreferences {
                    boolean getBoolean(String key, boolean fallback);
                    Editor edit();
                    interface Editor { Editor putBoolean(String key, boolean value); void apply(); }
                    class Impl implements SharedPreferences, Editor {
                        private boolean value;
                        public boolean getBoolean(String key, boolean fallback){return value;}
                        public Editor edit(){return this;}
                        public Editor putBoolean(String key, boolean next){value=next;return this;}
                        public void apply(){}
                    }
                }
            """,
            "android.content.pm.PackageManager" to """
                package android.content.pm;
                public final class PackageManager { public static final int PERMISSION_GRANTED=0; }
            """,
            "android.graphics.ImageFormat" to """
                package android.graphics;
                public final class ImageFormat { public static final int PRIVATE=34; }
            """,
            "android.graphics.Rect" to """
                package android.graphics;
                public class Rect { public int left,top,right,bottom; public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;} }
            """,
            "android.hardware.camera2.CameraAccessException" to """
                package android.hardware.camera2;
                public class CameraAccessException extends Exception {
                    public static final int CAMERA_IN_USE=4, MAX_CAMERAS_IN_USE=5, CAMERA_DISABLED=3;
                    private final int reason;
                    public CameraAccessException(int r){reason=r;}
                    public int getReason(){return reason;}
                }
            """,
            "android.hardware.camera2.CameraCaptureSession" to """
                package android.hardware.camera2;
                public class CameraCaptureSession {
                    public int setRepeatingRequest(CaptureRequest r,CaptureCallback c,android.os.Handler h){return 0;}
                    public int capture(CaptureRequest r,CaptureCallback c,android.os.Handler h){return 0;}
                    public void stopRepeating(){} public void close(){}
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
            "android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession" to """
                package android.hardware.camera2;
                public class CameraConstrainedHighSpeedCaptureSession extends CameraCaptureSession {
                    public java.util.List<CaptureRequest> createHighSpeedRequestList(CaptureRequest r){return java.util.Collections.singletonList(r);}
                    public int setRepeatingBurst(java.util.List<CaptureRequest> rs,CaptureCallback c,android.os.Handler h){return 0;}
                    public int captureBurst(java.util.List<CaptureRequest> rs,CaptureCallback c,android.os.Handler h){return 0;}
                }
            """,
            "android.hardware.camera2.CameraDevice" to """
                package android.hardware.camera2;
                public class CameraDevice {
                    public void close(){}
                    public CaptureRequest.Builder createCaptureRequest(int template){return new CaptureRequest.Builder();}
                    public void createCaptureSession(android.hardware.camera2.params.SessionConfiguration config){}
                    public abstract static class StateCallback {
                        public abstract void onOpened(CameraDevice d);
                        public abstract void onDisconnected(CameraDevice d);
                        public abstract void onError(CameraDevice d,int error);
                        public static final int ERROR_CAMERA_IN_USE=4, ERROR_MAX_CAMERAS_IN_USE=5, ERROR_CAMERA_DISABLED=3;
                    }
                }
            """,
            "android.hardware.camera2.CameraManager" to """
                package android.hardware.camera2;
                public class CameraManager {
                    public String[] getCameraIdList(){return new String[0];}
                    public CameraCharacteristics getCameraCharacteristics(String id){return new CameraCharacteristics();}
                    public void openCamera(String id,CameraDevice.StateCallback callback,android.os.Handler handler){}
                }
            """,
            "android.hardware.camera2.CameraMetadata" to """
                package android.hardware.camera2;
                public class CameraMetadata {
                    public static final int LENS_FACING_BACK=1, LENS_FACING_FRONT=0;
                    public static final int REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA=11;
                }
            """,
            "android.hardware.camera2.TotalCaptureResult" to """
                package android.hardware.camera2;
                public class TotalCaptureResult extends CaptureResult {}
            """,
            "android.hardware.camera2.params.ColorSpaceTransform" to """
                package android.hardware.camera2.params;
                public class ColorSpaceTransform { public float getElement(int row,int col){return 0f;} }
            """,
            "android.hardware.camera2.params.MeteringRectangle" to """
                package android.hardware.camera2.params;
                public class MeteringRectangle {
                    public MeteringRectangle(int x,int y,int w,int h,int weight){}
                }
            """,
            "android.hardware.camera2.params.OutputConfiguration" to """
                package android.hardware.camera2.params;
                public class OutputConfiguration {
                    public OutputConfiguration(android.view.Surface surface){}
                    public void setPhysicalCameraId(String id){}
                }
            """,
            "android.hardware.camera2.params.RggbChannelVector" to """
                package android.hardware.camera2.params;
                public class RggbChannelVector {
                    public float getRed(){return 1f;} public float getGreenEven(){return 1f;} public float getBlue(){return 1f;}
                }
            """,
            "android.hardware.camera2.params.SessionConfiguration" to """
                package android.hardware.camera2.params;
                public class SessionConfiguration {
                    public static final int SESSION_REGULAR=0, SESSION_HIGH_SPEED=1;
                    public SessionConfiguration(int t,java.util.List<OutputConfiguration> o,java.util.concurrent.Executor e,android.hardware.camera2.CameraCaptureSession.StateCallback c){}
                }
            """,
            "android.hardware.camera2.params.StreamConfigurationMap" to """
                package android.hardware.camera2.params;
                public class StreamConfigurationMap {
                    public android.util.Size[] getOutputSizes(int format){return new android.util.Size[0];}
                    public android.util.Size[] getHighSpeedVideoSizes(){return new android.util.Size[0];}
                    public android.util.Range<Integer>[] getHighSpeedVideoFpsRangesFor(android.util.Size size){return new android.util.Range[0];}
                    public int[] getOutputFormats(){return new int[0];}
                }
            """,
            "android.media.Image" to """
                package android.media;
                public class Image { public void close(){} }
            """,
            "android.media.ImageReader" to """
                package android.media;
                public class ImageReader {
                    private final android.view.Surface surface;
                    private int closeCount;
                    public ImageReader(String name){surface=new android.view.Surface(name+"-surface");}
                    public android.view.Surface getSurface(){return surface;}
                    public void setOnImageAvailableListener(OnImageAvailableListener listener,android.os.Handler handler){}
                    public Image acquireLatestImage(){return null;}
                    public void close(){closeCount++;}
                    public int getCloseCount(){return closeCount;}
                    public interface OnImageAvailableListener { void onImageAvailable(ImageReader reader); }
                }
            """,
            "android.os.Build" to """
                package android.os;
                public final class Build { public static final class VERSION { public static int SDK_INT=29; } }
            """,
            "android.os.Handler" to """
                package android.os;
                public class Handler {
                    private final Looper looper;
                    private final java.util.ArrayDeque<Runnable> queue=new java.util.ArrayDeque<>();
                    public Handler(Looper l){looper=l;}
                    public boolean post(Runnable r){if(looper.closed)return false;queue.addLast(r);return true;}
                    public boolean postDelayed(Runnable r,long ms){return post(r);}
                    public void removeCallbacks(Runnable r){queue.removeIf(item -> item == r);}
                    public void removeCallbacksAndMessages(Object token){queue.clear();}
                    public void drain(){while(!queue.isEmpty()){queue.removeFirst().run();}}
                }
            """,
            "android.os.HandlerThread" to """
                package android.os;
                public class HandlerThread extends Thread {
                    private final Looper looper=new Looper();
                    public HandlerThread(String name){super(name);}
                    public synchronized void start(){}
                    public Looper getLooper(){return looper;}
                    public boolean quitSafely(){looper.closed=true;return true;}
                }
            """,
            "android.os.Looper" to """
                package android.os;
                public class Looper { public boolean closed; }
            """,
            "android.os.SystemClock" to """
                package android.os;
                public final class SystemClock { public static long elapsedRealtime(){return 0L;} }
            """,
            "android.util.Range" to """
                package android.util;
                public class Range<T extends Comparable<? super T>> {
                    private final T lower,upper;
                    public Range(T l,T u){lower=l;upper=u;}
                    public T getLower(){return lower;} public T getUpper(){return upper;}
                }
            """,
            "android.util.Rational" to """
                package android.util;
                public class Rational extends Number {
                    public Rational(int n,int d){}
                    public int intValue(){return 0;} public long longValue(){return 0L;}
                    public float floatValue(){return 0f;} public double doubleValue(){return 0d;}
                }
            """,
            "android.util.Size" to """
                package android.util;
                public class Size {
                    private final int width,height;
                    public Size(int w,int h){width=w;height=h;}
                    public int getWidth(){return width;} public int getHeight(){return height;}
                }
            """,
            "android.util.SizeF" to """
                package android.util;
                public class SizeF {
                    public SizeF(float w,float h){}
                    public float getWidth(){return 0f;} public float getHeight(){return 0f;}
                }
            """,
            "android.view.Surface" to """
                package android.view;
                public class Surface {
                    private final String name;
                    public Surface(String n){name=n;}
                    public boolean isValid(){return true;}
                    public String toString(){return name;}
                }
            """,
            "androidx.core.content.ContextCompat" to """
                package androidx.core.content;
                public final class ContextCompat {
                    public static int checkSelfPermission(android.content.Context context,String permission){return android.content.pm.PackageManager.PERMISSION_GRANTED;}
                }
            """,
            "androidx.lifecycle.AndroidViewModel" to """
                package androidx.lifecycle;
                public class AndroidViewModel extends ViewModel {
                    private final android.app.Application application;
                    public AndroidViewModel(android.app.Application app){application=app;}
                    public android.app.Application getApplication(){return application;}
                }
            """,
            "androidx.lifecycle.DefaultLifecycleObserver" to """
                package androidx.lifecycle;
                public interface DefaultLifecycleObserver {
                    default void onPause(LifecycleOwner owner){}
                    default void onResume(LifecycleOwner owner){}
                }
            """,
            "androidx.lifecycle.Lifecycle" to """
                package androidx.lifecycle;
                public class Lifecycle {
                    public void addObserver(DefaultLifecycleObserver observer){}
                    public void removeObserver(DefaultLifecycleObserver observer){}
                }
            """,
            "androidx.lifecycle.LifecycleOwner" to """
                package androidx.lifecycle;
                public interface LifecycleOwner {}
            """,
            "androidx.lifecycle.ViewModel" to """
                package androidx.lifecycle;
                public class ViewModel { protected void onCleared(){} }
            """,
            "androidx.lifecycle.ViewModelProvider" to """
                package androidx.lifecycle;
                public class ViewModelProvider {
                    public ViewModelProvider(ViewModelStoreOwner owner){}
                    public <T extends ViewModel>T get(Class<T> modelClass){return null;}
                }
            """,
            "androidx.lifecycle.ViewModelStoreOwner" to """
                package androidx.lifecycle;
                public interface ViewModelStoreOwner {}
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public final class Diag {
                    public static final Diag INSTANCE=new Diag();
                    public void i(String tag,String message){}
                    public void d(String tag,String message){}
                    public void w(String tag,String message){}
                    public void w(String tag,String message,Throwable error){}
                    public void e(String tag,String message){}
                    public void e(String tag,String message,Throwable error){}
                }
            """,
            "kotlinx.coroutines.Dispatchers" to """
                package kotlinx.coroutines;
                public final class Dispatchers {
                    public static final Dispatchers INSTANCE=new Dispatchers();
                    private static final MainCoroutineDispatcher INLINE=new MainCoroutineDispatcher(){
                        public MainCoroutineDispatcher getImmediate(){return this;}
                        public void dispatch(kotlin.coroutines.CoroutineContext context,Runnable block){block.run();}
                    };
                    public static MainCoroutineDispatcher getMain(){return INLINE;}
                    public static CoroutineDispatcher getIO(){return INLINE;}
                    public static CoroutineDispatcher getDefault(){return INLINE;}
                    public static CoroutineDispatcher getUnconfined(){return INLINE;}
                }
            """
        )
    }
}

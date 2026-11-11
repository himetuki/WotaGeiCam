package com.wotagei.cam.stance

import org.junit.Assert.assertEquals
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 仅隔离 Android 调度/帧源/音频和 TFLite JNI；站位装配、推理封装与生命周期均加载生产字节码。 */
internal class StanceLifecyclePlatform(root: File) : Closeable {
    private val classes = File(root, "classes").apply { mkdirs() }
    private val sources = linkedMapOf(
        "android/os/Build.java" to """
            package android.os;
            public class Build {
                public static String HARDWARE="fixture", SOC_MODEL="fixture", SOC_MANUFACTURER="fixture";
                public static class VERSION { public static int SDK_INT=34; }
            }
        """,
        "android/os/Looper.java" to """
            package android.os;
            public class Looper {
                public static final Looper MAIN=new Looper();
                public final java.util.concurrent.BlockingQueue<Runnable> queue=new java.util.concurrent.LinkedBlockingQueue<>();
                public final java.util.List<Scheduled> delayed=new java.util.ArrayList<>();
                public volatile boolean quitting;
                public long now;
                public static Looper getMainLooper(){return MAIN;}
                public static class Scheduled {
                    final Runnable task; final long at;
                    Scheduled(Runnable task,long at){this.task=task;this.at=at;}
                }
                public void idle(){ Runnable r; while((r=queue.poll())!=null)r.run(); }
                public void advanceBy(long delta){
                    long end=now+delta;
                    while(true){
                        Scheduled next=null;
                        for(Scheduled s:delayed) if(s.at<=end&&(next==null||s.at<next.at)) next=s;
                        if(next==null)break;
                        delayed.remove(next); now=next.at; next.task.run(); idle();
                    }
                    now=end; idle();
                }
            }
        """,
        "android/os/HandlerThread.java" to """
            package android.os;
            public class HandlerThread extends Thread {
                public static final java.util.List<Thread> threads=new java.util.concurrent.CopyOnWriteArrayList<>();
                public static java.util.concurrent.CountDownLatch startEntered,startContinue;
                private final Looper looper=new Looper();
                public HandlerThread(String name){super(name);setDaemon(true);threads.add(this);}
                public synchronized void start(){
                    super.start();
                    if(startEntered!=null){
                        startEntered.countDown();
                        try { if(!startContinue.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("start gate timeout"); }
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    }
                }
                public Looper getLooper(){return looper;}
                public void run(){
                    try { while(!looper.quitting){Runnable r=looper.queue.take();r.run();} }
                    catch(InterruptedException e){Thread.currentThread().interrupt();}
                }
                public boolean quitSafely(){looper.queue.add(()->looper.quitting=true);return true;}
            }
        """,
        "android/os/Handler.java" to """
            package android.os;
            public class Handler {
                private final Looper looper;
                public Handler(Looper looper){this.looper=looper;}
                public Looper getLooper(){return looper;}
                public boolean post(Runnable r){if(looper.quitting)return false;return looper.queue.offer(r);}
                public boolean postDelayed(Runnable r,long delay){looper.delayed.add(new Looper.Scheduled(r,looper.now+delay));return true;}
                public void removeCallbacksAndMessages(Object token){looper.delayed.clear();looper.queue.clear();}
                public void removeCallbacks(Runnable task){looper.delayed.removeIf(s->s.task==task);looper.queue.remove(task);}
            }
        """,
        "android/util/Size.java" to """
            package android.util;
            public class Size {private final int w,h; public Size(int w,int h){this.w=w;this.h=h;} public int getWidth(){return w;} public int getHeight(){return h;}}
        """,
        "android/graphics/ImageFormat.java" to "package android.graphics; public class ImageFormat {public static final int YUV_420_888=35;}",
        "android/view/Surface.java" to "package android.view; public class Surface {public boolean isValid(){return true;}}",
        "android/media/Image.java" to """
            package android.media;
            public class Image implements java.io.Closeable {
                public boolean closed;
                public int getWidth(){return 2;} public int getHeight(){return 2;} public int getFormat(){return 35;}
                public Plane[] getPlanes(){return new Plane[]{new Plane(new byte[]{100,100,100,100},2),new Plane(new byte[]{-128},1),new Plane(new byte[]{-128},1)};}
                public void close(){closed=true;}
                public static class Plane {
                    private final byte[] data; private final int row;
                    Plane(byte[] data,int row){this.data=data;this.row=row;}
                    public java.nio.ByteBuffer getBuffer(){return java.nio.ByteBuffer.wrap(data);}
                    public int getRowStride(){return row;} public int getPixelStride(){return 1;}
                }
            }
        """,
        "android/media/ImageReader.java" to """
            package android.media;
            public class ImageReader implements java.io.Closeable {
                public interface OnImageAvailableListener {void onImageAvailable(ImageReader reader);}
                public static int created;
                private OnImageAvailableListener listener; private android.os.Handler handler;
                private Image image;
                private final android.view.Surface surface=new android.view.Surface();
                public static ImageReader newInstance(int w,int h,int format,int max){created++;return new ImageReader();}
                public android.view.Surface getSurface(){return surface;}
                public void setOnImageAvailableListener(OnImageAvailableListener listener,android.os.Handler handler){this.listener=listener;this.handler=handler;}
                public Image acquireLatestImage(){Image out=image;image=null;return out;}
                public void emit(){image=new Image();if(listener!=null)handler.post(()->listener.onImageAvailable(this));}
                public void close(){}
            }
        """,
        "android/content/pm/PackageManager.java" to "package android.content.pm; public class PackageManager {public boolean hasSystemFeature(String name){return false;}}",
        "android/content/res/AssetFileDescriptor.java" to """
            package android.content.res;
            public class AssetFileDescriptor implements java.io.Closeable {public final String name; public AssetFileDescriptor(String name){this.name=name;} public void close(){}}
        """,
        "android/content/res/AssetManager.java" to """
            package android.content.res;
            public class AssetManager {public AssetFileDescriptor openFd(String name){return new AssetFileDescriptor(name);}}
        """,
        "android/content/Context.java" to """
            package android.content;
            public class Context {
                public Context getApplicationContext(){return this;}
                public android.content.pm.PackageManager getPackageManager(){return new android.content.pm.PackageManager();}
                public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
            }
        """,
        "android/media/AudioAttributes.java" to """
            package android.media;
            public class AudioAttributes {public static class Builder {
                public Builder setUsage(int n){return this;} public Builder setContentType(int n){return this;}
                public AudioAttributes build(){return new AudioAttributes();}
            }}
        """,
        "android/media/SoundPool.java" to """
            package android.media;
            public class SoundPool {
                public static final java.util.List<String> plays=new java.util.ArrayList<>();
                public static final java.util.List<Integer> stops=new java.util.ArrayList<>();
                public static int releases;
                private final java.util.Map<Integer,String> names=new java.util.HashMap<>();
                private int next=1;
                public static class Builder {
                    public Builder setMaxStreams(int n){return this;} public Builder setAudioAttributes(AudioAttributes a){return this;}
                    public SoundPool build(){return new SoundPool();}
                }
                public int load(android.content.res.AssetFileDescriptor fd,int priority){int id=next++;names.put(id,fd.name);return id;}
                public int play(int id,float left,float right,int priority,int loop,float rate){plays.add(names.get(id));return plays.size();}
                public void stop(int stream){stops.add(stream);} public void release(){releases++;}
            }
        """,
        "com/wotagei/cam/diag/Diag.java" to """
            package com.wotagei.cam.diag;
            public class Diag {
                public static final Diag INSTANCE=new Diag();
                public static final java.util.List<String> messages=new java.util.ArrayList<>();
                public void i(String tag,String message){messages.add(message);} public void w(String tag,String message){messages.add(message);}
            }
        """,
        "org/tensorflow/lite/Delegate.java" to "package org.tensorflow.lite; public interface Delegate {}",
        "org/tensorflow/lite/DataType.java" to "package org.tensorflow.lite; public enum DataType {FLOAT32,UINT8,INT8}",
        "org/tensorflow/lite/Tensor.java" to "package org.tensorflow.lite; public interface Tensor {int[] shape();DataType dataType();}",
        "org/tensorflow/lite/gpu/GpuDelegateFactory.java" to "package org.tensorflow.lite.gpu; public class GpuDelegateFactory {public static class Options {}}",
        "org/tensorflow/lite/gpu/GpuDelegate.java" to """
            package org.tensorflow.lite.gpu;
            public class GpuDelegate implements org.tensorflow.lite.Delegate,java.io.Closeable {
                public static class Options extends GpuDelegateFactory.Options {}
                public static int created,closed;
                public GpuDelegate(){created++;org.tensorflow.lite.Interpreter.event("delegate:new");}
                public GpuDelegate(GpuDelegateFactory.Options options){this();}
                public void close(){closed++;org.tensorflow.lite.Interpreter.event("delegate:close");}
            }
        """,
        "org/tensorflow/lite/gpu/CompatibilityList.java" to """
            package org.tensorflow.lite.gpu;
            public class CompatibilityList implements java.io.Closeable {
                public static int created,closed;
                public CompatibilityList(){created++;}
                public void close(){closed++;}
                public boolean isDelegateSupportedOnThisDevice(){return true;}
                public GpuDelegate.Options getBestOptionsForThisDevice(){return new GpuDelegate.Options();}
            }
        """,
        "org/tensorflow/lite/Interpreter.java" to """
            package org.tensorflow.lite;
            public class Interpreter implements java.io.Closeable {
                public static boolean failGpuConstructor,failCpuConstructor,failVerify,failNextDetect;
                public static java.util.concurrent.CountDownLatch verifyEntered,verifyContinue;
                public static final java.util.List<String> events=new java.util.concurrent.CopyOnWriteArrayList<>();
                public static final java.util.List<Object> outputs=new java.util.ArrayList<>();
                public static void event(String name){events.add(name+"@"+Thread.currentThread().getName()+":"+Thread.currentThread().getId());}
                public static class Options {
                    boolean gpu;
                    public Options setNumThreads(int n){return this;}
                    public Options addDelegate(Delegate d){gpu=true;return this;}
                }
                private final boolean gpu;
                public Interpreter(java.nio.ByteBuffer model,Options options){
                    gpu=options.gpu; event(gpu?"gpu:new":"cpu:new");
                    if(gpu&&failGpuConstructor)throw new IllegalStateException("fixture GPU constructor");
                    if(!gpu&&failCpuConstructor)throw new IllegalStateException("fixture CPU constructor");
                }
                public Tensor getInputTensor(int i){return new Tensor(){public int[] shape(){return new int[]{1,3,2,2};} public DataType dataType(){return DataType.FLOAT32;}};}
                public Tensor getOutputTensor(int i){return new Tensor(){public int[] shape(){return new int[]{1,5,2};} public DataType dataType(){return DataType.FLOAT32;}};}
                public void run(Object input,Object output){
                    boolean verify=output instanceof java.nio.ByteBuffer;
                    event((gpu?"gpu:":"cpu:")+(verify?"verify":"detect"));
                    if(gpu&&verify&&verifyEntered!=null){
                        verifyEntered.countDown();
                        try { if(!verifyContinue.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("verify gate timeout"); }
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    }
                    if(gpu&&verify&&failVerify)throw new IllegalStateException("fixture GPU verify");
                    if(gpu&&!verify&&failNextDetect){failNextDetect=false;throw new IllegalStateException("fixture GPU runtime");}
                    if(!verify){
                        outputs.add(output);
                        float[][][] rows=(float[][][])output;
                        rows[0][0][0]=0.5f;rows[0][1][0]=0.5f;rows[0][2][0]=0.3f;rows[0][3][0]=0.4f;rows[0][4][0]=0.9f;
                    }
                }
                public void close(){event(gpu?"gpu:close":"cpu:close");}
            }
        """
    )

    val loader: ClassLoader
    private val boundary: BoundaryLoader
    init {
        val files = sources.map { (path, source) -> File(root, path).apply { parentFile.mkdirs(); writeText(source.trimIndent()) } }
        val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
        val run = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
        assertEquals("平台/JNI 边界必须先成功编译", 0, run.invoke(compiler, null, null, null,
            (listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath) + files.map { it.absolutePath }).toTypedArray()))
        boundary = BoundaryLoader(classes, javaClass.classLoader, sources.keys.map { it.removeSuffix(".java").replace('/', '.') }.toSet())
        loader = boundary
    }

    fun type(name: String): Class<*> = loader.loadClass(name)
    fun stance(name: String): Class<*> = type("com.wotagei.cam.stance.$name")
    fun instance(name: String): Any = stance(name).getConstructor().newInstance()
    fun context(): Any = type("android.content.Context").getConstructor().newInstance()
    fun setStatic(name: String, field: String, value: Any) = type(name).getField(field).set(null, value)
    fun static(name: String, field: String): Any = type(name).getField(field).get(null)
    fun events(): List<String> = (static("org.tensorflow.lite.Interpreter", "events") as List<*>).map { it.toString() }
    fun mainIdle() = type("android.os.Looper").getMethod("idle").invoke(static("android.os.Looper", "MAIN"))
    fun advance(ms: Long) = type("android.os.Looper").getMethod("advanceBy", Long::class.javaPrimitiveType).invoke(static("android.os.Looper", "MAIN"), ms)
    fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    fun inject(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }.apply { isAccessible = true }
        return try { method.invoke(target, *args) } catch (e: InvocationTargetException) { throw e.targetException }
    }
    fun callSuspending(target: Any, name: String, vararg args: Any?): Any? = runBlocking {
        withTimeout(5_000L) { callSuspendingInContext(target, name, *args) }
    }
    suspend fun callSuspendingInContext(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.single { it.name == name }.apply { isAccessible = true }
        return if (method.parameterCount == args.size) call(target, name, *args) else suspendCoroutine { continuation ->
            val result = try { method.invoke(target, *args, continuation) } catch (e: InvocationTargetException) { throw e.targetException }
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
        }
    }
    fun onAnalysis(analysis: Any, action: () -> Unit = {}) {
        val handler = requireNotNull(field(analysis, "handler"))
        val task = FutureTask { action(); Unit }
        check(call(handler, "post", task) == true)
        task.get(5L, TimeUnit.SECONDS)
    }
    fun emit(analysis: Any) {
        inject(analysis, "lastFrameMs", 0L)
        val reader = requireNotNull(call(analysis, "reader"))
        call(reader, "emit")
        onAnalysis(analysis)
    }
    override fun close() {
        val threads = (static("android.os.HandlerThread", "threads") as List<*>).filterIsInstance<Thread>()
        threads.forEach { call(it, "quitSafely") }
        threads.forEach { it.join(5_000L); check(!it.isAlive) { "分析线程未收尾" } }
        boundary.close()
    }

    private class BoundaryLoader(classes: File, parent: ClassLoader, private val platform: Set<String>) :
        URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                name in platform || platform.any { name.startsWith("$it$") } -> findClass(name)
                name.startsWith("com.wotagei.cam.stance.") -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = requireNotNull(parent.getResourceAsStream(path)) { "缺少真实站位字节码 $path" }.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}

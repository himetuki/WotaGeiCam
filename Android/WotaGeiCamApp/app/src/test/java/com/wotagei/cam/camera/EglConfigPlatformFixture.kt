package com.wotagei.cam.camera

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * U01 独立 EGL 边界。选择/重试/字段交接/清理全部运行生产字节码；桩只匹配平台能力表。
 * pbuffer 固定失败，故不模拟 shader/OES/FBO/像素。post 同步执行，不证明真实 owner 或竞态。
 * 不依赖 Camera/Export/Echo 共享 fixture，不写生产 EGL 字段，不用 Unsafe。
 */
internal class EglConfigPlatformFixture(classes: File, parent: ClassLoader) : Closeable {
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                SOURCES.keys.any { name == it || name.startsWith("$it\$") } -> findClass(name)
                // 接口、嵌套类、nativeFloatBuffer 和 FrostCardTable 同 loader，避免 Android 签名及静态表串扰。
                name.startsWith("com.wotagei.cam.camera.") ||
                    name == "com.wotagei.cam.record.ArcRepairGl" ||
                    name.startsWith("com.wotagei.cam.record.ArcRepairGl\$") -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少真实生产字节码：$path")
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
    private val probe = loader.loadClass("probe.EglProbe")
    private var engine: Any? = null

    fun config(
        id: String,
        renderable: Int = 0x0044,
        surfaces: Int = 0x0005,
        rgba: IntArray = intArrayOf(8, 8, 8, 8),
        recordable: Boolean = false,
    ) {
        require(rgba.size == 4)
        val method = probe.getMethod("addConfig", String::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        unwrap { method.invoke(null, id, renderable, surfaces, rgba[0], rgba[1], rgba[2], rgba[3], recordable) }
    }

    fun script(name: String, value: Any) { probe.getField(name).set(null, value) }
    fun number(name: String): Int = (probe.getField(name).get(null) as Number).toInt()
    @Suppress("UNCHECKED_CAST")
    private fun <T> list(name: String): List<T> = probe.getField(name).get(null) as List<T>
    val events: List<String> get() = list<String>("events").toList()
    val created: List<String> get() = list<String>("created").toList()
    val contextAttempts: List<String> get() = list<String>("contextAttempts").toList()
    val violations: List<String> get() = list<String>("violations").toList()
    val requests: List<List<Int>> get() = list<IntArray>("requests").map { it.toList() }

    /** 预览构造自动 init；Arc 正常构造后只调用一次 start。不手排 choose/context，也不补写 EGL 字段。 */
    fun start(arc: Boolean): Boolean {
        check(engine == null)
        val type = loader.loadClass(if (arc) "com.wotagei.cam.record.ArcRepairGl" else "com.wotagei.cam.camera.GlRenderEngine")
        engine = unwrap {
            if (arc) type.declaredConstructors.single { it.parameterCount == 4 }
                .apply { isAccessible = true }.newInstance(16, 12, 0L, 0)
            else type.getDeclaredConstructor().newInstance()
        }
        return call(if (arc) "start" else "isReady") as Boolean
    }
    fun engineField(name: String): Any? = requireNotNull(engine).javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(engine)
    fun sentinel(name: String): Any = loader.loadClass("android.opengl.EGL14").getField(name).get(null)
    private fun call(name: String): Any? = unwrap {
        requireNotNull(engine).javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(engine)
    }
    fun release() { if (engine != null) call("release") }
    private fun <T> unwrap(block: () -> T): T = try { block() } catch (error: InvocationTargetException) {
        throw error.targetException
    }
    override fun close() { try { release() } finally { loader.close() } }

    companion object {
        /** 仅主审执行测试时编译到工作区；编译/链接错误不是有效行为 RED。 */
        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val sources = SOURCES.map { (name, body) ->
                File(root, name.replace('.', '/') + ".java").apply {
                    parentFile.mkdirs()
                    writeText(body.trimIndent())
                }
            }
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
                ?: error("U01 边界需要已有宿主 JDK；缺失编译器不是行为 RED")
            val output = ByteArrayOutputStream()
            val options = listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath) + sources.map { it.absolutePath }
            val exit = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java,
                OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
                .invoke(compiler, null, output, output, options.toTypedArray()) as Int
            check(exit == 0) { "U01 平台边界编译失败，不是有效行为 RED：\n${output.toString("UTF-8")}" }
            return classes
        }

        private val SOURCES = mapOf(
            "probe.EglProbe" to """
                package probe;
                import java.util.*;
                import android.opengl.*;
                public class EglProbe {
                    public static final EGLDisplay DISPLAY=new EGLDisplay("display");
                    public static final EGLConfig POISON=new EGLConfig("poison",0x44,5,8,8,8,8,true);
                    public static final List<EGLConfig> configs=new ArrayList<>();
                    public static final Set<EGLConfig> selected=new HashSet<>();
                    public static final Set<EGLDisplay> initialized=new HashSet<>();
                    public static final Set<EGLContext> contexts=new HashSet<>();
                    public static final List<int[]> requests=new ArrayList<>();
                    public static final List<String> events=new ArrayList<>(),created=new ArrayList<>(),
                        contextAttempts=new ArrayList<>(),violations=new ArrayList<>();
                    public static boolean failEs3,failEs2,rejectRecordable;
                    // 1=false（留下正 count/毒 config）；2=true+num0（留下毒 config）。不按调用次序给答案。
                    public static int chooseFault;
                    public static int getDisplayCalls,initializeCalls,terminateCalls,destroyContextCalls,
                        pbufferCalls,quitCalls,liveContextCount,initializedDisplayCount,lastError=0x3000,nextContext;
                    public static void addConfig(String id,int renderable,int surfaces,int r,int g,int b,int a,boolean recordable){
                        configs.add(new EGLConfig(id,renderable,surfaces,r,g,b,a,recordable));
                    }
                    public static int get(int[] attrs,int offset,int key,int fallback){
                        for(int i=offset;i<attrs.length&&attrs[i]!=0x3038;i+=2) if(attrs[i]==key)return attrs[i+1];
                        return fallback;
                    }
                    public static int[] snapshot(int[] attrs,int offset){
                        int end=offset;
                        while(end<attrs.length&&attrs[end]!=0x3038){
                            if(end+1>=attrs.length)throw new IllegalArgumentException("EGL attributes not paired");
                            end+=2;
                        }
                        if(end>=attrs.length)throw new IllegalArgumentException("EGL_NONE missing");
                        return Arrays.copyOfRange(attrs,offset,end+1);
                    }
                    public static void counts(){liveContextCount=contexts.size();initializedDisplayCount=initialized.size();}
                }
            """,
            "android.opengl.EGLDisplay" to """
                package android.opengl; public class EGLDisplay {
                    public final String id; public EGLDisplay(String id){this.id=id;}
                }
            """,
            "android.opengl.EGLConfig" to """
                package android.opengl; public class EGLConfig {
                    public final String id; public final int renderable,surfaces,r,g,b,a; public final boolean recordable;
                    public EGLConfig(String id,int renderable,int surfaces,int r,int g,int b,int a,boolean recordable){
                        this.id=id;this.renderable=renderable;this.surfaces=surfaces;this.r=r;this.g=g;this.b=b;this.a=a;this.recordable=recordable;
                    }
                }
            """,
            "android.opengl.EGLContext" to """
                package android.opengl; public class EGLContext {
                    public final String id; public final EGLDisplay display;
                    public EGLContext(String id,EGLDisplay display){this.id=id;this.display=display;}
                }
            """,
            "android.opengl.EGLSurface" to """
                package android.opengl; public class EGLSurface {
                    public final String id; public EGLSurface(String id){this.id=id;}
                }
            """,
            "android.opengl.EGL14" to """
                package android.opengl;
                import probe.EglProbe;
                public class EGL14 {
                    public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay("NO_DISPLAY");
                    public static final EGLContext EGL_NO_CONTEXT=new EGLContext("NO_CONTEXT",null);
                    public static final EGLSurface EGL_NO_SURFACE=new EGLSurface("NO_SURFACE");
                    public static EGLDisplay eglGetDisplay(int id){
                        EglProbe.getDisplayCalls++;EglProbe.events.add("display.get");return EglProbe.DISPLAY;
                    }
                    public static boolean eglInitialize(EGLDisplay d,int[] major,int majorOffset,int[] minor,int minorOffset){
                        EglProbe.initializeCalls++;EglProbe.events.add("display.initialized");
                        if(d!=EglProbe.DISPLAY)return false;
                        major[majorOffset]=1;minor[minorOffset]=5;EglProbe.initialized.add(d);EglProbe.counts();return true;
                    }
                    public static boolean eglChooseConfig(EGLDisplay d,int[] attrs,int offset,EGLConfig[] out,int outOffset,int size,int[] count,int countOffset){
                        int[] request=EglProbe.snapshot(attrs,offset);EglProbe.requests.add(request);
                        int recordable=EglProbe.get(request,0,0x3142,-1);
                        String group=recordable==1?"recordable":"generic";
                        if(!EglProbe.initialized.contains(d))throw new IllegalStateException("choose before initialize");
                        // false 的输出未定义，故有意留下正 count/毒句柄；调用方必须先尊重 Boolean。
                        if(EglProbe.chooseFault==1||(recordable!=-1&&EglProbe.rejectRecordable)){
                            count[countOffset]=1;if(size>0)out[outOffset]=EglProbe.POISON;
                            EglProbe.lastError=0x3004;EglProbe.events.add("choose.false:"+group);return false;
                        }
                        if(EglProbe.chooseFault==2){
                            count[countOffset]=0;if(size>0)out[outOffset]=EglProbe.POISON;
                            EglProbe.events.add("choose.empty:"+group);return true;
                        }
                        int renderable=EglProbe.get(request,0,0x3040,1),surfaces=EglProbe.get(request,0,0x3033,4);
                        int r=EglProbe.get(request,0,0x3024,0),g=EglProbe.get(request,0,0x3023,0),
                            b=EglProbe.get(request,0,0x3022,0),a=EglProbe.get(request,0,0x3021,0);
                        // 仅 EGL 平台的位集合全包含/颜色最小值/扩展值匹配，不推导调用方的重试顺序。
                        for(EGLConfig c:EglProbe.configs){
                            if((c.renderable&renderable)!=renderable||(c.surfaces&surfaces)!=surfaces||
                                c.r<r||c.g<g||c.b<b||c.a<a)continue;
                            if(recordable!=-1&&c.recordable!=(recordable==1))continue;
                            count[countOffset]=1;if(size>0)out[outOffset]=c;EglProbe.selected.add(c);
                            EglProbe.events.add("choose.selected:"+group+":"+c.id);return true;
                        }
                        count[countOffset]=0;if(size>0)out[outOffset]=EglProbe.POISON;
                        EglProbe.events.add("choose.empty:"+group);return true;
                    }
                    public static EGLContext eglCreateContext(EGLDisplay d,EGLConfig c,EGLContext share,int[] attrs,int offset){
                        int version=EglProbe.get(attrs,offset,0x3098,1);
                        String identity=(c==null?"null":c.id)+"/"+version;EglProbe.contextAttempts.add(identity);
                        int bit=version==3?0x40:version==2?4:1;
                        if(!EglProbe.initialized.contains(d)||!EglProbe.selected.contains(c)||share!=EGL_NO_CONTEXT||
                            (c.renderable&bit)!=bit||(version==3&&EglProbe.failEs3)||(version==2&&EglProbe.failEs2)){
                            EglProbe.lastError=0x3009;EglProbe.events.add("context.rejected:"+identity);return EGL_NO_CONTEXT;
                        }
                        EGLContext context=new EGLContext("context-"+(++EglProbe.nextContext),d);
                        EglProbe.contexts.add(context);EglProbe.created.add(identity);EglProbe.counts();
                        EglProbe.events.add("context.created:"+context.id+":"+identity);return context;
                    }
                    public static EGLSurface eglCreatePbufferSurface(EGLDisplay d,EGLConfig c,int[] attrs,int offset){
                        if(!EglProbe.initialized.contains(d)||!EglProbe.selected.contains(c))throw new IllegalStateException("pbuffer before config");
                        EglProbe.pbufferCalls++;EglProbe.lastError=0x3003;EglProbe.events.add("pbuffer.rejected");
                        return EGL_NO_SURFACE;
                    }
                    public static boolean eglMakeCurrent(EGLDisplay d,EGLSurface draw,EGLSurface read,EGLContext context){
                        if(draw!=EGL_NO_SURFACE||read!=EGL_NO_SURFACE||context!=EGL_NO_CONTEXT)
                            throw new AssertionError("U01 EGL-only boundary must not bind a live pbuffer");
                        if(!EglProbe.initialized.contains(d))EglProbe.violations.add("unbind without initialized display");
                        EglProbe.events.add("context.unbound");return true;
                    }
                    public static boolean eglDestroyContext(EGLDisplay d,EGLContext c){
                        EglProbe.destroyContextCalls++;
                        boolean valid=c!=EGL_NO_CONTEXT&&c.display==d&&EglProbe.initialized.contains(d)&&EglProbe.contexts.remove(c);
                        if(!valid)EglProbe.violations.add("destroy unknown context");
                        EglProbe.events.add("context.destroyed:"+c.id);EglProbe.counts();return valid;
                    }
                    public static boolean eglDestroySurface(EGLDisplay d,EGLSurface s){
                        EglProbe.violations.add("destroy surface never created by this boundary");return false;
                    }
                    public static boolean eglTerminate(EGLDisplay d){
                        EglProbe.terminateCalls++;
                        boolean valid=EglProbe.initialized.remove(d);
                        if(!valid)EglProbe.violations.add("terminate uninitialized display");
                        // 终止 display 会收回非 current 资源；显式 context close 另由独立调用账断言。
                        EglProbe.contexts.removeIf(c->c.display==d);EglProbe.counts();
                        EglProbe.events.add("display.terminated");return valid;
                    }
                    public static int eglGetError(){int error=EglProbe.lastError;EglProbe.lastError=0x3000;return error;}
                }
            """,
            "android.os.Looper" to """
                package android.os; public class Looper { public boolean quitting; }
            """,
            "android.os.HandlerThread" to """
                package android.os; public class HandlerThread extends Thread {
                    private final Looper looper=new Looper();
                    public HandlerThread(String name){super(name);} public HandlerThread(String name,int priority){super(name);}
                    // 仅确定性调度边界，不启动真实线程，不声称验证了 native owner。
                    public synchronized void start(){} public Looper getLooper(){return looper;}
                    public boolean quitSafely(){looper.quitting=true;probe.EglProbe.quitCalls++;return true;}
                }
            """,
            "android.os.Handler" to """
                package android.os; public class Handler {
                    private final Looper looper; public Handler(Looper looper){this.looper=looper;}
                    public boolean post(Runnable task){if(looper.quitting)return false;task.run();return true;}
                }
            """,
            "android.graphics.Rect" to """
                package android.graphics; public class Rect {
                    public int left,top,right,bottom;
                    public Rect(){} public Rect(int l,int t,int r,int b){set(l,t,r,b);}
                    public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}
                    public void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
                    public int width(){return right-left;} public int height(){return bottom-top;}
                }
            """,
            "android.graphics.SurfaceTexture" to """
                package android.graphics; public class SurfaceTexture {
                    public interface OnFrameAvailableListener {void onFrameAvailable(SurfaceTexture texture);}
                    public SurfaceTexture(int texture){throw new AssertionError("SurfaceTexture must remain beyond the controlled pbuffer failure");}
                    public void setDefaultBufferSize(int w,int h){}
                    public void setOnFrameAvailableListener(OnFrameAvailableListener listener){}
                    public void setOnFrameAvailableListener(OnFrameAvailableListener listener,android.os.Handler handler){}
                    public void updateTexImage(){} public void getTransformMatrix(float[] matrix){} public void release(){}
                }
            """,
            "android.view.Surface" to """
                package android.view; public class Surface {
                    public Surface(){} public Surface(android.graphics.SurfaceTexture texture){}
                    public boolean isValid(){return true;} public void release(){}
                }
            """,
            "android.opengl.GLES20" to """
                package android.opengl; public class GLES20 {
                    public static void glGenTextures(int n,int[] ids,int offset){throw new AssertionError("GL allocation must remain beyond the controlled pbuffer failure");}
                    public static int glGetError(){return 0;}
                }
            """,
            "android.opengl.GLES11Ext" to """
                package android.opengl; public class GLES11Ext {public static final int GL_TEXTURE_EXTERNAL_OES=0x8D65;}
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag; public class Diag {
                    public static final Diag INSTANCE=new Diag();
                    public void i(String tag,String message){} public void d(String tag,String message){}
                    public void w(String tag,String message){} public void w(String tag,String message,Throwable error){}
                    public void e(String tag,String message){} public void e(String tag,String message,Throwable error){}
                }
            """
        )
    }
}

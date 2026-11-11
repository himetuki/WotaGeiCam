package com.wotagei.cam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 导出回归的平台边界：加载实际 Runner/预扫/数据类/GL 字节码，不复制生产样本或收尾循环。
 * 管线测试只用脚本代替编解码、GL 像素、Provider；GL 测试则加载真实两条 GL 链，仅替换 EGL/GLES。
 * 所有桩由主审运行测试时用已有 JDK 编译到工作区 TemporaryFolder，不引依赖、不接触真实媒体。
 */
internal class ExportPlatformFixture(classes: File, parent: ClassLoader, realGl: Boolean = false) : Closeable {
    private val real = setOf(
        "com.wotagei.cam.player.SceneExportRunner", "com.wotagei.cam.player.ClipExportKt",
        "com.wotagei.cam.player.RemuxOrientationKt", "com.wotagei.cam.media.VideoClip",
        "com.wotagei.cam.record.OrientationExportKt", "com.wotagei.cam.record.ArcRepairSession",
        "com.wotagei.cam.record.ArcFail"
    ) + if (realGl) setOf("com.wotagei.cam.player.SceneExportGl", "com.wotagei.cam.record.ArcRepairGl") else emptySet()
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                real.any { name == it || name.startsWith("$it\$") } -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少实际生产字节码：$path")
                    defineClass(name, bytes, 0, bytes.size)
                }
                SOURCES.keys.any { name == it || name.startsWith("$it\$") } -> findClass(name)
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }

    fun type(name: String): Class<*> = loader.loadClass(name)
    fun new(name: String): Any = type(name).getDeclaredConstructor().newInstance()
    fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(instance)
    fun set(instance: Any, name: String, value: Any?) = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.set(instance, value)
    fun script(name: String, value: Any?) = type("probe.ExportScript").getField(name).set(null, value)
    fun script(name: String): Any? = type("probe.ExportScript").getField(name).get(null)
    @Suppress("UNCHECKED_CAST")
    fun <T> list(name: String): List<T> = script(name) as List<T>
    val events: List<String> get() = list("events")
    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val method = instance.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return invoke { method.invoke(instance, *args) }
    }
    private fun <T> invoke(block: () -> T): T = try { block() } catch (e: InvocationTargetException) { throw e.targetException }

    fun runner(): Any {
        val context = new("android.content.Context")
        return type("com.wotagei.cam.player.SceneExportRunner").getConstructor(context.javaClass).newInstance(context)
    }
    fun clip(durationMs: Long): Any {
        val uri = type("android.net.Uri").getConstructor(String::class.java).newInstance("content://source/1")
        // 真实生产数据类的完整主构造，不仿写属性；没有 Room/MediaRepo 查询，也不读取宿主文件。
        return type("com.wotagei.cam.media.VideoClip").declaredConstructors.single { it.parameterCount == 16 }
            .newInstance(1L, uri, "source.mp4", null, "Movies/WotaGeiCam", 0L, 100_000L,
                durationMs, 640, 480, "video/avc", emptySet<String>(), false, null, null, 1)
    }
    suspend fun export(
        runner: Any, durationMs: Long = 3_000, startMs: Long = 0, endMs: Long = 2_000,
        track: SelectedTrack = SelectedTrack.ENV, offsetMs: Long = 0,
        onProgress: (Float) -> Unit = {}
    ): Any = suspendCoroutine { continuation ->
        try {
            val result = call(runner, "export", clip(durationMs), startMs, endMs, track, offsetMs,
                SceneExportEffects(null, true), onProgress, continuation)
            if (result !== COROUTINE_SUSPENDED) continuation.resume(result!!)
        } catch (e: Throwable) { continuation.resumeWithException(e) }
    }
    fun writable(extractor: Any, baseUs: Long, shiftUs: Long, endRelUs: Long, trackIndex: Int = 2): Boolean {
        val method = type("com.wotagei.cam.player.ClipExportKt").declaredMethods.single { it.name == "audioTrackWritable" }
        method.isAccessible = true
        return invoke { method.invoke(null, extractor, trackIndex, baseUs, shiftUs, endRelUs, 8) as Boolean }
    }
    fun gl(scene: Boolean, basePtsNs: Long = 0L): Any {
        val instance = if (scene) {
            type("com.wotagei.cam.player.SceneExportGl").getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, SceneExportEffects::class.java
            ).newInstance(640, 480, SceneExportEffects(null, false))
        } else {
            type("com.wotagei.cam.record.ArcRepairGl").getDeclaredConstructor(
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).newInstance(640, 480, basePtsNs, 0)
        }
        set(instance, "eglDisplay", new("android.opengl.EGLDisplay"))
        set(instance, "eglContext", new("android.opengl.EGLContext"))
        set(instance, if (scene) "encoderSurface" else "encoderEglSurface", new("android.opengl.EGLSurface"))
        set(instance, "pass2dProgram", 1)
        return instance
    }
    fun arcSession(gl: Any, onProgress: (Float) -> Unit = {}): Any {
        // 真实构造只初始化会话数据；不调 run/open，不进行 native/输出装配。
        // 即使旧代码错误地计成功，也有合法回调，不能用未初始化字段的 NPE 冒充行为 RED。
        val uri = type("android.net.Uri").getConstructor(String::class.java).newInstance("content://source/1")
        return type("com.wotagei.cam.record.ArcRepairSession").declaredConstructors.single { it.parameterCount == 8 }
            .apply { isAccessible = true }
            .newInstance(new("android.content.Context"), uri, true, 24, 0, 0, onProgress, { false })
            .also { set(it, "arcGl", gl) }
    }
    /**
     * 只关闭本隔离 loader 的生产单 owner 执行器并等它真正退出。
     * 取消类用例必须先等到 owner 回收完成再读资源账，否则会把异步清理误判成泄漏。
     */
    fun awaitOwnerQuiescence(timeoutMs: Long): Boolean {
        val runner = type("com.wotagei.cam.player.SceneExportRunner")
        val onOuter = runCatching { runner.getDeclaredField("EXPORT_OWNER_EXECUTOR") }.getOrNull()
        val field = onOuter ?: type("com.wotagei.cam.player.SceneExportRunner\$Companion")
            .getDeclaredField("EXPORT_OWNER_EXECUTOR")
        field.isAccessible = true
        val target = if (onOuter != null) null else runner.getField("Companion").get(null)
        val executor = field.get(target) as ThreadPoolExecutor
        executor.shutdown()
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() = loader.close()

    companion object {
        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val files = SOURCES.map { (name, source) ->
                File(root, name.replace('.', '/') + ".java").apply { parentFile.mkdirs(); writeText(source.trimIndent()) }
            }
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
            assertNotNull("需要工程现有 JDK；平台桩编译失败不算有效 RED", compiler)
            val classpath = listOf(SceneExportEffects::class.java, kotlin.Unit::class.java).map {
                File(it.protectionDomain.codeSource.location.toURI()).absolutePath
            }.distinct().joinToString(File.pathSeparator)
            val options = listOf("-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", classes.absolutePath)
            val run = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java,
                OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
            assertEquals("平台边界桩必须成功编译", 0,
                run.invoke(compiler, null, null, null, (options + files.map { it.absolutePath }).toTypedArray()))
            return classes
        }

        private val SOURCES = mapOf(
            // U05 默认关闭：只阻塞平台入口、记录资源身份/线程，不决定生产取消或回收。
            "probe.ExportBlocking" to """
                package probe;
                public class ExportBlocking {
                    public static volatile String blockAt;
                    public static volatile boolean guardTripped;
                    public static final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
                    public static final java.util.concurrent.CountDownLatch proceed = new java.util.concurrent.CountDownLatch(1);
                    public static final java.util.concurrent.CountDownLatch returned = new java.util.concurrent.CountDownLatch(1);
                    public static final java.util.List<Entry> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                    private static final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
                    private static final Object resourceLock = new Object();
                    private static final java.util.Map<String,String> active = new java.util.HashMap<>();
                    public static final class Entry {
                        public final String operation, resource;
                        public final long thread;
                        Entry(String operation,String resource) {
                            this.operation=operation; this.resource=resource; this.thread=Thread.currentThread().getId();
                        }
                    }
                    public static void note(String operation,String resource) {
                        if(blockAt!=null) events.add(new Entry(operation,resource));
                    }
                    public static String create(String kind) {
                        if(blockAt==null) return "";
                        String id=kind+"#"+sequence.incrementAndGet();
                        acquire(kind,id); return id;
                    }
                    public static void acquire(String kind,String id) {
                        if(blockAt==null) return;
                        synchronized(resourceLock) {
                            active.put(id,kind); note(kind+".create",id); resourceLock.notifyAll();
                        }
                    }
                    public static void release(String kind,String id) {
                        if(blockAt==null || id.isEmpty()) return;
                        synchronized(resourceLock) {
                            // 重复释放也记账，不能让集合remove的幂等性掩盖双close。
                            note(kind+".release",id); active.remove(id); resourceLock.notifyAll();
                        }
                    }
                    public static void pause(String stage) {
                        if(!stage.equals(blockAt)) return;
                        entered.countDown();
                        boolean interrupted=false;
                        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                        try {
                            while(proceed.getCount()!=0) {
                                long left=end-System.nanoTime();
                                if(left<=0) { guardTripped=true; throw new IllegalStateException("U05 test gate safety deadline"); }
                                try {
                                    if(proceed.await(left,java.util.concurrent.TimeUnit.NANOSECONDS)) break;
                                } catch(InterruptedException ignored) {
                                    // 模拟不因协程取消/Thread.interrupt而返回的同步Binder；仍受测试安全上限约束。
                                    interrupted=true;
                                }
                            }
                        } finally {
                            if(interrupted) Thread.currentThread().interrupt();
                        }
                    }
                    public static void didReturn(String stage,String id) {
                        note(stage+".return",id);
                        if(stage.equals(blockAt)) {
                            synchronized(resourceLock) { returned.countDown(); resourceLock.notifyAll(); }
                        }
                    }
                    public static boolean awaitDrained(long timeoutMs) throws InterruptedException {
                        long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
                        synchronized(resourceLock) {
                            while(returned.getCount()!=0 || !active.isEmpty()) {
                                long left=end-System.nanoTime();
                                if(left<=0) return false;
                                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(resourceLock,left);
                            }
                            return true;
                        }
                    }
                }
            """,
            "probe.ExportScript" to """
                package probe;
                public class ExportScript {
                    public static final java.util.List<String> events = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
                    public static final java.util.List<Long> audioWritten = new java.util.ArrayList<>();
                    public static final java.util.List<Integer> audioTracks = new java.util.ArrayList<>();
                    public static final java.util.List<Long> renderedSource = new java.util.ArrayList<>();
                    public static final java.util.List<Long> renderedPts = new java.util.ArrayList<>();
                    public static long[] videoPts = {0, 100000, 200000, 300000, 400000};
                    public static boolean[] videoSync = {true, false, false, false, false};
                    public static long[] audioPts = {0, 100000, 200000, 300000, 400000};
                    public static int audioCount = 2, reorderDepth = 0, reads = 0, decoded = 0;
                    public static int decoderEos = 0, encoderEos = 0, awaits = 0, acquiredOnly = 0;
                    public static int commitResult = 1, commitCalls = 0, stampMode = 1, swapCalls = 0, stampCalls = 0;
                    public static int[] commitResults;
                    public static boolean renderOk = true, swapOk = true, stopThrows = false;
                    public static boolean glThreadAlive;
                    public static Thread glThread = new Thread();
                    public static int[] decoderReadyAfterInputs;
                    public static Runnable afterAudioWrite, beforeAudioEof, afterStop, afterDecoded;
                    public static long currentSource, clockStepMs, clockMs;
                    public static void add(String e) { events.add(e); }
                }
            """,
            "android.net.Uri" to """
                package android.net;
                public class Uri {
                    private final String value;
                    public Uri(String value) { this.value = value; }
                    public String toString() { return value; }
                }
            """,
            "android.content.Context" to """
                package android.content;
                public class Context {
                    private final ContentResolver resolver = new ContentResolver();
                    public ContentResolver getContentResolver() { return resolver; }
                }
            """,
            "android.content.ContentValues" to """
                package android.content;
                public class ContentValues {
                    public void put(String key, String value) {}
                    public void put(String key, Integer value) {}
                }
            """,
            "android.content.ContentUris" to """
                package android.content;
                public class ContentUris { public static long parseId(android.net.Uri uri) { return 42L; } }
            """,
            "android.content.ContentResolver" to """
                package android.content;
                public class ContentResolver {
                    public android.net.Uri insert(android.net.Uri uri, ContentValues values) {
                        probe.ExportScript.add("pending.insert");
                        probe.ExportBlocking.note("insert.enter",uri.toString());
                        probe.ExportBlocking.pause("insert");
                        android.net.Uri result=new android.net.Uri("content://output/42");
                        probe.ExportBlocking.acquire("pending",result.toString());
                        probe.ExportBlocking.didReturn("insert",result.toString());
                        return result;
                    }
                    public android.content.res.AssetFileDescriptor openAssetFileDescriptor(android.net.Uri uri, String mode) {
                        probe.ExportBlocking.note("open.enter",uri.toString());
                        probe.ExportBlocking.pause("open");
                        android.content.res.AssetFileDescriptor result=new android.content.res.AssetFileDescriptor();
                        probe.ExportBlocking.didReturn("open",result.traceId);
                        return result;
                    }
                    public int update(android.net.Uri uri, ContentValues values, String selection, String[] args) {
                        int call=++probe.ExportScript.commitCalls;
                        probe.ExportBlocking.note("commit.enter",uri.toString());
                        probe.ExportBlocking.note("pending.commit",uri.toString());
                        probe.ExportScript.add("pending.commit");
                        if(call==1) probe.ExportBlocking.pause("commit");
                        int[] results=probe.ExportScript.commitResults;
                        int result=results==null ? probe.ExportScript.commitResult : results[Math.min(call-1,results.length-1)];
                        probe.ExportBlocking.didReturn("commit",uri.toString());
                        if (result < 0) throw new IllegalStateException("provider busy");
                        return result;
                    }
                    public int delete(android.net.Uri uri, String selection, String[] args) {
                        probe.ExportScript.add("pending.delete");
                        probe.ExportBlocking.note("delete.enter",uri.toString());
                        probe.ExportBlocking.note("pending.delete",uri.toString());
                        probe.ExportBlocking.pause("delete");
                        probe.ExportBlocking.release("pending",uri.toString());
                        probe.ExportBlocking.didReturn("delete",uri.toString());
                        return 1;
                    }
                }
            """,
            "android.content.res.AssetFileDescriptor" to """
                package android.content.res;
                public class AssetFileDescriptor implements java.io.Closeable {
                    public final String traceId=probe.ExportBlocking.create("fd");
                    public java.io.FileDescriptor getFileDescriptor() {
                        probe.ExportBlocking.note("fd.use",traceId); return new java.io.FileDescriptor();
                    }
                    public void close() {
                        probe.ExportScript.add("fd.close");
                        probe.ExportBlocking.note("fd.close",traceId);
                        probe.ExportBlocking.release("fd",traceId);
                    }
                }
            """,
            "android.provider.MediaStore" to """
                package android.provider;
                public class MediaStore {
                    public static class MediaColumns {
                        public static final String DISPLAY_NAME="_display_name", MIME_TYPE="mime_type", RELATIVE_PATH="relative_path", IS_PENDING="is_pending";
                    }
                    public static class Video { public static class Media extends MediaColumns {
                        public static final android.net.Uri EXTERNAL_CONTENT_URI = new android.net.Uri("content://video");
                    } }
                }
            """,
            "android.os.SystemClock" to """
                package android.os;
                public class SystemClock {
                    public static long elapsedRealtime() {
                        if(probe.ExportScript.clockStepMs>0) return probe.ExportScript.clockMs+=probe.ExportScript.clockStepMs;
                        return System.nanoTime()/1000000L;
                    }
                }
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public class Diag {
                    public static final Diag INSTANCE = new Diag();
                    public void i(String tag, String msg) { probe.ExportScript.add("info:"+msg); }
                    public void w(String tag, String msg) { probe.ExportScript.add("warn:"+msg); }
                    public void w(String tag, String msg, Throwable t) { w(tag,msg); }
                    public void e(String tag, String msg) { probe.ExportScript.add("error:"+msg); }
                    public void d(String tag, String msg) {}
                }
            """,
            "android.media.MediaFormat" to """
                package android.media;
                public class MediaFormat {
                    private final java.util.Map<String,Object> data = new java.util.HashMap<>();
                    public MediaFormat() {}
                    public MediaFormat(MediaFormat other) { data.putAll(other.data); }
                    public static MediaFormat createVideoFormat(String mime, int w, int h) {
                        MediaFormat f=new MediaFormat(); f.setString("mime",mime); f.setInteger("width",w); f.setInteger("height",h); return f;
                    }
                    public void setString(String k,String v) { data.put(k,v); }
                    public void setInteger(String k,int v) { data.put(k,v); }
                    public void setFloat(String k,float v) { data.put(k,v); }
                    public void setByteBuffer(String k,java.nio.ByteBuffer v) { data.put(k,v); }
                    public String getString(String k) { return (String)data.get(k); }
                    public int getInteger(String k) { return ((Number)data.get(k)).intValue(); }
                    public boolean containsKey(String k) { return data.containsKey(k); }
                    public java.util.Set<String> getKeys() { return data.keySet(); }
                    public void removeKey(String k) { data.remove(k); }
                }
            """,
            "android.media.MediaExtractor" to """
                package android.media;
                public class MediaExtractor {
                    private final String traceId=probe.ExportBlocking.create("extractor");
                    private final java.util.Set<Integer> selected=new java.util.LinkedHashSet<>();
                    private final int[] positions=new int[3];
                    public void setDataSource(android.content.Context c,android.net.Uri u,java.util.Map<String,String> h) {
                        probe.ExportBlocking.note("extractor.source",traceId);
                    }
                    public int getTrackCount() { return 1+probe.ExportScript.audioCount; }
                    public MediaFormat getTrackFormat(int track) {
                        MediaFormat f=MediaFormat.createVideoFormat(track==0 ? "video/avc" : "audio/mp4a-latm",640,480);
                        f.setInteger("frame-rate",30); f.setInteger("bitrate",2000000); f.setInteger("rotation-degrees",90);
                        if(track>0) { f.setInteger("sample-rate",48000); f.setInteger("channel-count",2); }
                        return f;
                    }
                    public void selectTrack(int track) { selected.add(track); probe.ExportScript.add("select:"+track); }
                    public void unselectTrack(int track) { selected.remove(track); probe.ExportScript.add("unselect:"+track); }
                    public void seekTo(long time,int mode) {
                        for(int track:selected) {
                            long[] pts=track==0 ? probe.ExportScript.videoPts : probe.ExportScript.audioPts;
                            int at=0; long best=Long.MIN_VALUE;
                            for(int i=0;i<pts.length;i++) if(pts[i]<=time && pts[i]>=best && (track!=0 || probe.ExportScript.videoSync[i])) { at=i; best=pts[i]; }
                            positions[track]=at;
                        }
                    }
                    private int currentTrack() {
                        int best=-1; long time=Long.MAX_VALUE;
                        for(int track:selected) { long[] p=track==0 ? probe.ExportScript.videoPts : probe.ExportScript.audioPts;
                            if(positions[track]<p.length && p[positions[track]]<time) { best=track; time=p[positions[track]]; }
                        }
                        return best;
                    }
                    public int getSampleTrackIndex() { return currentTrack(); }
                    public long getSampleTime() { int t=currentTrack(); return t<0 ? -1L : (t==0 ? probe.ExportScript.videoPts : probe.ExportScript.audioPts)[positions[t]]; }
                    public long getSampleSize() { return currentTrack()<0 ? -1L : 8L; }
                    public int getSampleFlags() { return currentTrack()==0 && probe.ExportScript.videoSync[positions[0]] ? 1:0; }
                    public int readSampleData(java.nio.ByteBuffer b,int offset) {
                        int t=currentTrack(); if(t<0) return -1;
                        b.putInt(offset,positions[t]); b.putInt(offset+4,t); if(t==0) probe.ExportScript.reads++; return 8;
                    }
                    public boolean advance() {
                        int t=currentTrack(); if(t<0)return false; positions[t]++;
                        if(t>0 && positions[t]>=probe.ExportScript.audioPts.length && probe.ExportScript.beforeAudioEof!=null) probe.ExportScript.beforeAudioEof.run();
                        return currentTrack()>=0;
                    }
                    public void release() {
                        probe.ExportScript.add("extractor.release"); probe.ExportBlocking.release("extractor",traceId);
                    }
                }
            """,
            "android.media.MediaMetadataRetriever" to """
                package android.media;
                public class MediaMetadataRetriever {
                    private final String traceId=probe.ExportBlocking.create("retriever");
                    public void setDataSource(android.content.Context c,android.net.Uri uri) {
                        probe.ExportBlocking.note("retriever.source",traceId);
                    }
                    public String extractMetadata(int key) { return "90"; }
                    public void release() { probe.ExportBlocking.release("retriever",traceId); }
                }
            """,
            "android.media.MediaCrypto" to "package android.media; public class MediaCrypto {}",
            "android.media.MediaCodec" to """
                package android.media;
                public class MediaCodec {
                    public static class BufferInfo {
                        public int offset,size,flags; public long presentationTimeUs;
                        public void set(int offset,int size,long pts,int flags) { this.offset=offset;this.size=size;this.presentationTimeUs=pts;this.flags=flags; }
                    }
                    private static class Packet { long pts; int flags; Packet(long p,int f){pts=p;flags=f;} }
                    private static MediaCodec encoder;
                    private final boolean encoding;
                    private final String traceId;
                    private final java.nio.ByteBuffer input=java.nio.ByteBuffer.allocate(65536);
                    private final java.util.PriorityQueue<Packet> packets=new java.util.PriorityQueue<>(java.util.Comparator.comparingLong(p->p.pts));
                    private final java.util.ArrayDeque<Packet> deliveryOrder=new java.util.ArrayDeque<>();
                    private int inputs,outputs;
                    private boolean eos=false,eosReturned=false,formatReturned=false;
                    private Packet output;
                    private MediaCodec(boolean e){encoding=e;traceId=probe.ExportBlocking.create(e ? "encoder" : "decoder");}
                    public static MediaCodec createEncoderByType(String mime) { encoder=new MediaCodec(true); return encoder; }
                    public static MediaCodec createDecoderByType(String mime) { return new MediaCodec(false); }
                    public void configure(MediaFormat f,android.view.Surface s,MediaCrypto c,int flags) {
                        if(!encoding) probe.ExportScript.add("decoder.rotation:"+f.containsKey("rotation-degrees"));
                    }
                    public android.view.Surface createInputSurface() { return new android.view.Surface(); }
                    public void start() {}
                    public int dequeueInputBuffer(long timeout) { return 0; }
                    public java.nio.ByteBuffer getInputBuffer(int i) { input.clear(); return input; }
                    public void queueInputBuffer(int i,int offset,int size,long pts,int flags) {
                        if((flags&4)!=0) { eos=true;probe.ExportScript.decoderEos++;probe.ExportScript.add("decoder.eos"); }
                        else {
                            Packet packet=new Packet(pts,0); inputs++;
                            if(probe.ExportScript.decoderReadyAfterInputs!=null) deliveryOrder.add(packet);
                            else packets.add(packet);
                        }
                    }
                    public int dequeueOutputBuffer(BufferInfo info,long timeout) {
                        if(encoding && !formatReturned) { formatReturned=true;return -2; }
                        int[] ready=encoding ? null:probe.ExportScript.decoderReadyAfterInputs;
                        int hold=encoding ? 0:probe.ExportScript.reorderDepth;
                        boolean available=ready!=null
                            ? !deliveryOrder.isEmpty() && (eos || inputs>=ready[Math.min(outputs,ready.length-1)])
                            : !packets.isEmpty() && (eos || packets.size()>hold);
                        if(available) {
                            output=ready!=null ? deliveryOrder.remove():packets.remove(); outputs++;
                            info.set(0,8,output.pts,output.flags);
                            if(!encoding) {
                                probe.ExportScript.decoded++;
                                if(probe.ExportScript.afterDecoded!=null)probe.ExportScript.afterDecoded.run();
                            }
                            return 0;
                        }
                        if(eos && packets.isEmpty() && deliveryOrder.isEmpty() && !eosReturned) { eosReturned=true;output=new Packet(0,4);info.set(0,0,0,4);return 0; }
                        return -1;
                    }
                    public java.nio.ByteBuffer getOutputBuffer(int index) { java.nio.ByteBuffer b=java.nio.ByteBuffer.allocate(8); b.putLong(0,output.pts); return b; }
                    public MediaFormat getOutputFormat() { return MediaFormat.createVideoFormat("video/avc",640,480); }
                    public void releaseOutputBuffer(int index,boolean render) {
                        if(!encoding && output.flags==0) probe.ExportScript.currentSource=output.pts;
                    }
                    public static void encoderFrame(long pts) { encoder.packets.add(new Packet(pts,0)); }
                    public void signalEndOfInputStream() { eos=true;probe.ExportScript.encoderEos++;probe.ExportScript.add("encoder.eos"); }
                    public void stop() { probe.ExportScript.add(encoding ? "encoder.stop":"decoder.stop"); }
                    public void release() {
                        probe.ExportScript.add(encoding ? "encoder.release":"decoder.release");
                        probe.ExportBlocking.release(encoding ? "encoder" : "decoder",traceId);
                    }
                }
            """,
            "android.media.MediaMuxer" to """
                package android.media;
                public class MediaMuxer {
                    private final String traceId=probe.ExportBlocking.create("muxer");
                    private final java.util.List<String> tracks=new java.util.ArrayList<>();
                    private boolean started;
                    public MediaMuxer(java.io.FileDescriptor fd,int format) {}
                    public void setOrientationHint(int hint) { probe.ExportScript.add("muxer.rotation:"+hint); }
                    public int addTrack(MediaFormat f) {
                        if(started)throw new IllegalStateException("addTrack after start");
                        tracks.add(f.getString("mime"));probe.ExportScript.add("track.add:"+f.getString("mime"));return tracks.size()-1;
                    }
                    public void start() { started=true;probe.ExportScript.add("muxer.start"); }
                    public void writeSampleData(int track,java.nio.ByteBuffer b,MediaCodec.BufferInfo info) {
                        if(!started)throw new IllegalStateException("write before start");
                        if(tracks.get(track).startsWith("audio/")) {
                            probe.ExportScript.audioWritten.add(info.presentationTimeUs);probe.ExportScript.audioTracks.add(b.getInt(info.offset+4));
                            probe.ExportScript.add("audio.write"); if(probe.ExportScript.afterAudioWrite!=null) probe.ExportScript.afterAudioWrite.run();
                        }
                    }
                    public void stop() {
                        probe.ExportScript.add("muxer.stop"); if(probe.ExportScript.stopThrows) throw new IllegalStateException("unsealed");
                        if(probe.ExportScript.afterStop!=null)probe.ExportScript.afterStop.run();
                    }
                    public void release() {
                        probe.ExportScript.add("muxer.release"); probe.ExportBlocking.release("muxer",traceId);
                    }
                }
            """,
            "com.wotagei.cam.player.SceneExportGl" to """
                package com.wotagei.cam.player;
                public class SceneExportGl {
                    private final String traceId=probe.ExportBlocking.create("gl");
                    public SceneExportGl(int w,int h,SceneExportEffects effects) {}
                    public boolean start(){return true;}
                    public boolean attachEncoderSurface(android.view.Surface s){return true;}
                    public android.view.Surface decoderOutputSurface(){return new android.view.Surface();}
                    public boolean awaitFrame(long timeout){probe.ExportScript.awaits++;return true;}
                    public static boolean awaitFrame${'$'}default(SceneExportGl gl,long timeout,int mask,Object marker){return gl.awaitFrame(timeout);}
                    public boolean acquireAndRender(long pts){
                        probe.ExportBlocking.note("render.enter",traceId);
                        probe.ExportBlocking.pause("render");
                        if(!probe.ExportScript.renderOk)return false;
                        probe.ExportScript.renderedSource.add(probe.ExportScript.currentSource);probe.ExportScript.renderedPts.add(pts);
                        android.media.MediaCodec.encoderFrame(pts);
                        probe.ExportBlocking.didReturn("render",traceId);
                        return true;
                    }
                    public boolean acquireOnly(){probe.ExportScript.acquiredOnly++;return true;}
                    public void release(){probe.ExportScript.add("gl.release");probe.ExportBlocking.release("gl",traceId);}
                    public boolean glThreadAlive(){return probe.ExportScript.glThreadAlive;}
                    public Thread exportThread(){return probe.ExportScript.glThread;}
                }
            """,
            "android.view.Surface" to "package android.view; public class Surface { public void release(){} }",
            "android.os.Looper" to "package android.os; public class Looper {}",
            "android.os.HandlerThread" to """
                package android.os;
                public class HandlerThread extends Thread {
                    private final Looper looper=new Looper(); public HandlerThread(String name){super(name);}
                    public Looper getLooper(){return looper;} public boolean quitSafely(){return true;}
                }
            """,
            "android.os.Handler" to """
                package android.os;
                public class Handler { public Handler(Looper l){} public boolean post(Runnable r){r.run();return true;} }
            """,
            "android.graphics.SurfaceTexture" to """
                package android.graphics;
                public class SurfaceTexture {
                    public interface OnFrameAvailableListener { void onFrameAvailable(SurfaceTexture texture); }
                    public SurfaceTexture(int texture) {}
                    public void setDefaultBufferSize(int w,int h) {}
                    public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {}
                    public void setOnFrameAvailableListener(OnFrameAvailableListener listener,android.os.Handler handler) {}
                    public void updateTexImage() {}
                    public void getTransformMatrix(float[] matrix) {}
                    public void release() {}
                }
            """,
            "android.opengl.EGLDisplay" to "package android.opengl; public class EGLDisplay {}",
            "android.opengl.EGLContext" to "package android.opengl; public class EGLContext {}",
            "android.opengl.EGLSurface" to "package android.opengl; public class EGLSurface {}",
            "android.opengl.EGLConfig" to "package android.opengl; public class EGLConfig {}",
            "android.opengl.EGL14" to """
                package android.opengl;
                public class EGL14 {
                    public static final EGLDisplay EGL_NO_DISPLAY=new EGLDisplay();
                    public static final EGLContext EGL_NO_CONTEXT=new EGLContext();
                    public static final EGLSurface EGL_NO_SURFACE=new EGLSurface();
                    public static boolean eglMakeCurrent(EGLDisplay d,EGLSurface a,EGLSurface b,EGLContext c){return true;}
                    public static boolean eglSwapBuffers(EGLDisplay d,EGLSurface s){probe.ExportScript.swapCalls++;probe.ExportScript.add("egl.swap");return probe.ExportScript.swapOk;}
                    public static int eglGetError(){return 0;}
                }
            """,
            "android.opengl.EGLExt" to """
                package android.opengl;
                public class EGLExt {
                    public static boolean eglPresentationTimeANDROID(EGLDisplay d,EGLSurface s,long pts){
                        probe.ExportScript.stampCalls++;probe.ExportScript.add("egl.stamp:"+pts);
                        if(probe.ExportScript.stampMode<0)throw new IllegalStateException("stamp failed");
                        return probe.ExportScript.stampMode>0;
                    }
                }
            """,
            "android.opengl.GLES20" to """
                package android.opengl;
                public class GLES20 {
                    public static void glBindFramebuffer(int target,int framebuffer){}
                    public static void glViewport(int x,int y,int w,int h){}
                    public static void glUseProgram(int p){}
                    public static void glActiveTexture(int u){}
                    public static void glBindTexture(int t,int texture){}
                    public static void glUniform1i(int l,int v){}
                    public static void glUniformMatrix4fv(int l,int n,boolean transpose,float[] m,int offset){}
                    public static void glUniform1f(int l,float v){}
                    public static void glUniform2f(int l,float a,float b){}
                    public static void glUniform3f(int l,float a,float b,float c){}
                    public static void glEnableVertexAttribArray(int a){}
                    public static void glVertexAttribPointer(int a,int n,int type,boolean norm,int stride,java.nio.Buffer b){}
                    public static void glDrawArrays(int mode,int first,int count){}
                    public static void glDisableVertexAttribArray(int a){}
                    public static int glGetError(){return 0;}
                }
            """
        )
    }
}

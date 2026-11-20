package com.wotagei.cam.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * 只替换编码器、采集与文件 I/O 边界；Recorder/RecordProfile/换段/排水/PTS/暂存缓冲均加载生产字节码。
 * 与 C01 一样使用宿主 JDK 编译边界桩，不引入 mock 框架或联网依赖，不代表设备编码器实测。
 */
internal class RecorderPlatformFixture(classes: File, parent: ClassLoader) : Closeable {
    private val loader = object : URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                STUBS.any { name == it || name.startsWith("$it\$") } -> findClass(name)
                name.startsWith("com.wotagei.cam.record.") -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() }
                        ?: throw ClassNotFoundException("缺少实际生产字节码：$path")
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
    val events: MutableList<String>
        @Suppress("UNCHECKED_CAST")
        get() = type("probe.Events").getField("all").get(null) as MutableList<String>

    fun type(name: String): Class<*> = loader.loadClass(name)
    fun new(name: String): Any = type(name).getDeclaredConstructor().newInstance()
    fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(instance)
    fun set(instance: Any, name: String, value: Any?) = instance.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.set(instance, value)
    fun call(instance: Any, name: String, vararg args: Any?): Any? {
        val method = instance.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try {
            method.invoke(instance, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    fun recorder(mr: Boolean = false): Any {
        val context = new("android.content.Context")
        val store = new("com.wotagei.cam.record.VideoStore")
        val klass = type("com.wotagei.cam.record." + if (mr) "MrRecorder" else "CodecRecorder")
        return if (mr) klass.getDeclaredConstructor(context.javaClass, store.javaClass).newInstance(context, store)
        else {
            val routeType = type("com.wotagei.cam.record.AudioRouteProbe")
            val route = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(routeType)) { _, _, _ -> false }
            klass.getDeclaredConstructor(context.javaClass, store.javaClass, routeType).newInstance(context, store, route)
        }
    }

    fun profile(gpu: Boolean = false, hevc: Boolean = false, bitrate: Int = 2_000_000): Any {
        // 参数对象也是真实生产类；仅构造固定测试输入，不仿写属性计算或录制决策。
        val klass = type("com.wotagei.cam.record.RecordProfile")
        val original = RecordProfile(
            width = 640, height = 480, fps = 30, captureRate = 30f,
            bitrate = bitrate, codec = if (hevc) RecordProfile.CODEC_HEVC else RecordProfile.CODEC_H264,
            sampleRate = 48_000,
            audioEnabled = false, orientationHint = 0, mirrored = false, useGpu = gpu
        )
        val ctor = klass.declaredConstructors.single { it.parameterCount == 17 }
        return ctor.newInstance(
            original.width, original.height, original.fps, original.captureRate, original.bitrate,
            original.codec, original.sampleRate, original.channels, original.audioBitrate,
            original.audioEnabled, original.orientationHint, original.mirrored, original.useGpu,
            original.fpsNtsc, original.arcConvert, original.captureAudio, original.audioDenoise
        )
    }

    fun sink(part: Int = 0): Any {
        val uri = type("android.net.Uri").getConstructor(String::class.java).newInstance("content://record/$part")
        return type("com.wotagei.cam.record.OutputSink\$Pending").getConstructor(uri.javaClass).newInstance(uri)
    }

    fun codec(kind: String): Any = type("android.media.MediaCodec").getConstructor(String::class.java).newInstance(kind)
    fun packet(codec: Any, size: Int, flags: Int, pts: Long, offset: Int = 0) {
        call(codec, "packet", size, flags, pts, offset)
    }
    fun format(codec: Any) { call(codec, "event", -2) }
    fun waitOutput(codec: Any) { call(codec, "event", -1) }
    fun static(name: String, field: String, value: Any?) { type(name).getField(field).set(null, value) }
    override fun close() = loader.close()

    companion object {
        private val STUBS = setOf(
            "probe.Events", "android.content.Context", "android.net.Uri", "android.view.Surface",
            "android.os.SystemClock", "android.os.Process", "android.media.MediaCodec", "android.media.MediaCrypto",
            "android.media.MediaCodecList", "android.media.MediaCodecInfo", "android.media.MediaFormat",
            "android.media.MediaMuxer", "android.media.MediaRecorder", "com.wotagei.cam.diag.Diag",
            "com.wotagei.cam.record.OutputTarget", "com.wotagei.cam.record.VideoStore",
            "com.wotagei.cam.record.AudioProbe", "com.wotagei.cam.record.AudioFeeder",
            "com.wotagei.cam.record.PlaybackCaptureController"
        )

        fun compile(root: File): File {
            val classes = File(root, "classes").apply { mkdirs() }
            val sources = SOURCES.map { (name, source) ->
                File(root, name.replace('.', '/') + ".java").apply {
                    requireNotNull(parentFile).mkdirs()
                    writeText(source.trimIndent())
                }
            }
            // Android 单测 bootclasspath 不含 javax.tools，只在 JVM 运行测试时反射宿主 JDK。
            val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
            assertNotNull("需要已有 JDK，禁止下载测试依赖", compiler)
            val classpath = listOf(Recorder::class.java, kotlin.Unit::class.java).map {
                File(requireNotNull(it.protectionDomain?.codeSource?.location).toURI()).absolutePath
            }.distinct().joinToString(File.pathSeparator)
            val options = listOf("-proc:none", "-encoding", "UTF-8", "-classpath", classpath, "-d", classes.absolutePath)
            val compile = Class.forName("javax.tools.Tool").getMethod(
                "run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java
            )
            assertEquals("平台边界桩编译失败不算 RED", 0,
                compile.invoke(compiler, null, null, null, (options + sources.map { it.absolutePath }).toTypedArray()))
            return classes
        }

        private val SOURCES = mapOf(
            "probe.Events" to """
                package probe;
                public class Events {
                    public static final java.util.List<String> all = new java.util.concurrent.CopyOnWriteArrayList<>();
                    public static void add(String value) { all.add(value); }
                }
            """,
            "android.content.Context" to "package android.content; public class Context {}",
            "android.net.Uri" to """
                package android.net;
                public class Uri {
                    private final String value;
                    public Uri(String value) { this.value = value; }
                    public String toString() { return value; }
                }
            """,
            "android.view.Surface" to """
                package android.view;
                public class Surface {
                    public final String id;
                    public Surface(String id) { this.id = id; }
                    public void release() { probe.Events.add("surface.release:" + id); }
                    public String toString() { return id; }
                }
            """,
            "android.os.SystemClock" to """
                package android.os;
                public class SystemClock { public static long elapsedRealtime() { return System.nanoTime() / 1000000L; } }
            """,
            "android.os.Process" to """
                package android.os;
                public class Process { public static void setThreadPriority(int value) {} }
            """,
            "com.wotagei.cam.diag.Diag" to """
                package com.wotagei.cam.diag;
                public class Diag {
                    public static final Diag INSTANCE = new Diag();
                    public void i(String tag, String msg) {}
                    public void d(String tag, String msg) {}
                    public void w(String tag, String msg) {}
                    public void e(String tag, String msg) { probe.Events.add("error:" + msg); }
                    public void frameAccount(String line) {}
                    public void clearFrameAccount() {}
                }
            """,
            "android.media.MediaCrypto" to "package android.media; public class MediaCrypto {}",
            "android.media.MediaFormat" to """
                package android.media;
                public class MediaFormat {
                    public String kind;
                    public String mime;
                    public static MediaFormat createVideoFormat(String mime, int w, int h) { MediaFormat f = new MediaFormat(); f.mime = mime; return f; }
                    public static MediaFormat createAudioFormat(String mime, int sr, int ch) { MediaFormat f = new MediaFormat(); f.mime = mime; return f; }
                    public void setInteger(String key, int value) {}
                    public void setFloat(String key, float value) {}
                    /** Y15 的段内格式指纹要遍历键集（API 29+ 真 API 有）；空集=所有通知视作同格式 */
                    public java.util.Set<String> getKeys() { return java.util.Collections.emptySet(); }
                }
            """,
            "android.media.MediaCodecList" to """
                package android.media;
                public class MediaCodecList {
                    public MediaCodecList(int kind) {}
                    public MediaCodecInfo[] getCodecInfos() { return new MediaCodecInfo[] { new MediaCodecInfo() }; }
                }
            """,
            "android.media.MediaCodecInfo" to """
                package android.media;
                public class MediaCodecInfo {
                    public boolean isEncoder() { return true; }
                    public String[] getSupportedTypes() { return new String[] { "video/avc", "video/hevc" }; }
                    public String getName() { return "test-avc"; }
                    public CodecCapabilities getCapabilitiesForType(String type) { return new CodecCapabilities(); }
                    public static class CodecCapabilities {
                        public int[] colorFormats = { 2130708361 };
                        public boolean isFormatSupported(MediaFormat fmt) { return true; }
                    }
                }
            """,
            "android.media.MediaCodec" to """
                package android.media;
                import android.view.Surface;
                public class MediaCodec {
                    public static int sequence, persistentCount;
                    public static boolean rejectBinding;
                    /** Y17：ROM 宣称支持 HEVC 但 configure 实拒（真机已知形态） */
                    public static boolean rejectHevcConfigure;
                    /** Y20：建实例即抛运行时异常（编码器资源耗尽/厂商实现崩溃的形态） */
                    public static boolean throwOnCreate;
                    /** 按尝试顺序记录 configure 收到的 mime（证明"HEVC 预筛通过、实配失败后按 AVC 重试"） */
                    public static final java.util.List<String> configureMimes = new java.util.ArrayList<>();
                    public final String kind, id;
                    private final java.util.List<long[]> output = new java.util.ArrayList<>();
                    private final java.util.Map<Integer, java.nio.ByteBuffer> buffers = new java.util.HashMap<>();
                    private final java.util.Set<Integer> released = new java.util.HashSet<>();
                    private int cursor, nextIndex, exhausted;
                    public MediaCodec(String kind) { this.kind = kind; this.id = "codec" + (++sequence); }
                    public static MediaCodec createByCodecName(String name) { if (throwOnCreate) throw new IllegalStateException("codec create failed"); return defaultVideo(); }
                    public static MediaCodec createEncoderByType(String mime) { if (throwOnCreate) throw new IllegalStateException("codec create failed"); return defaultVideo(); }
                    private static MediaCodec defaultVideo() {
                        MediaCodec c = new MediaCodec("V"); c.event(-2); c.packet(32, 1, 1000000L, 0); c.packet(0, 4, 2000000L, 0); return c;
                    }
                    public static Surface createPersistentInputSurface() { persistentCount++; return new Surface("persistent" + persistentCount); }
                    public void configure(MediaFormat fmt, Surface output, MediaCrypto crypto, int flags) {
                        probe.Events.add(id + ".configure:" + output);
                        String mime = fmt == null ? null : fmt.mime;
                        configureMimes.add(String.valueOf(mime));
                        if (rejectHevcConfigure && mime != null && mime.toLowerCase().contains("hevc")) {
                            throw new IllegalArgumentException("hevc configure rejected");
                        }
                    }
                    public void setInputSurface(Surface surface) {
                        probe.Events.add(id + ".bind:" + surface);
                        if (rejectBinding) throw new IllegalArgumentException("拒绝输入面");
                    }
                    public Surface createInputSurface() {
                        Surface result = new Surface(id + "-input"); probe.Events.add(id + ".create:" + result); return result;
                    }
                    public void start() { probe.Events.add(id + ".start"); }
                    public void stop() { probe.Events.add(id + ".stop"); }
                    public void release() { probe.Events.add(id + ".release"); }
                    public void signalEndOfInputStream() {}
                    public MediaFormat getOutputFormat() { MediaFormat fmt = new MediaFormat(); fmt.kind = kind; return fmt; }
                    public void event(int code) { output.add(new long[] { code, 0, 0, 0, 0 }); }
                    public void packet(int size, int flags, long pts, int offset) {
                        int index = nextIndex++;
                        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(size + offset);
                        for (int i = 0; i < size; i++) bytes.put(offset + i, (byte)(17 + index));
                        buffers.put(index, bytes);
                        output.add(new long[] { index, size, flags, pts, offset });
                    }
                    public int dequeueOutputBuffer(BufferInfo info, long timeout) {
                        if (cursor == output.size()) {
                            if (++exhausted > 30) throw new AssertionError("脚本已耗尽但生产排水未收口：" + kind);
                            return -1;
                        }
                        long[] e = output.get(cursor++); info.set((int)e[4], (int)e[1], e[3], (int)e[2]); return (int)e[0];
                    }
                    public java.nio.ByteBuffer getOutputBuffer(int index) {
                        if (released.contains(index)) throw new AssertionError("访问已释放输出：" + kind + index);
                        return buffers.get(index).duplicate();
                    }
                    public void releaseOutputBuffer(int index, boolean render) {
                        if (!released.add(index)) throw new AssertionError("重复释放：" + kind + index);
                        probe.Events.add("release:" + kind + ":" + index);
                    }
                    public int dequeueInputBuffer(long timeout) { return -1; }
                    public java.nio.ByteBuffer getInputBuffer(int index) { return java.nio.ByteBuffer.allocate(4096); }
                    public void queueInputBuffer(int i, int o, int s, long p, int f) {}
                    public static class BufferInfo {
                        public int offset, size, flags; public long presentationTimeUs;
                        public void set(int o, int s, long p, int f) { offset=o; size=s; presentationTimeUs=p; flags=f; }
                    }
                }
            """,
            "android.media.MediaMuxer" to """
                package android.media;
                public class MediaMuxer {
                    public final java.util.List<String> writes = new java.util.ArrayList<>();
                    public final java.util.List<String> tracks = new java.util.ArrayList<>();
                    private boolean started;
                    public MediaMuxer(String path, int fmt) {}
                    public MediaMuxer(java.io.FileDescriptor fd, int fmt) {}
                    public void setOrientationHint(int hint) {}
                    public int addTrack(MediaFormat fmt) { tracks.add(fmt.kind); return tracks.size()-1; }
                    public void start() { if (started) throw new AssertionError("重复启动 muxer"); started=true; probe.Events.add("mux.start"); }
                    public void writeSampleData(int track, java.nio.ByteBuffer bytes, MediaCodec.BufferInfo info) {
                        if (!started) throw new AssertionError("尚未启动就写样本");
                        if (info.size <= 0) throw new AssertionError("空样本不应写入");
                        String kind = tracks.get(track);
                        String value = kind + ":" + info.size + ":" + bytes.get(info.offset);
                        writes.add(value); probe.Events.add("write:" + value);
                    }
                    public void stop() { if (!started) throw new IllegalStateException("未启动"); }
                    public void release() {}
                }
            """,
            "android.media.MediaRecorder" to """
                package android.media;
                import android.view.Surface;
                public class MediaRecorder {
                    public static boolean rejectPersistent;
                    /** Y17（MR 路）：编码器族被平台拒绝（HEVC 不可用）——按 AVC 换代重试 */
                    public static boolean rejectHevcEncoder;
                    /** Y20：实例构造即抛运行时异常（平台边界崩溃形态） */
                    public static boolean throwOnConstruct;
                    /** Y20：prepare 抛 RuntimeException（applyConfig 的新兜底分支） */
                    public static RuntimeException prepareThrow;
                    /** Y19：stop 抛 ISE（一帧未写/平台已自停的形态） */
                    public static boolean stopThrows;
                    public static int sequence;
                    private final String id = "mr" + (++sequence);
                    private final Surface surface = new Surface(id + "-input");
                    public MediaRecorder() {
                        if (throwOnConstruct) throw new IllegalStateException("MediaRecorder ctor failed");
                    }
                    public interface OnInfoListener { void onInfo(MediaRecorder recorder, int what, int extra); }
                    public interface OnErrorListener { void onError(MediaRecorder recorder, int what, int extra); }
                    public Surface getSurface() { return surface; }
                    public void reset() {}
                    public void setAudioSource(int value) {}
                    public void setVideoSource(int value) {}
                    public void setOutputFormat(int value) {}
                    public void setOutputFile(String value) {}
                    public void setOutputFile(java.io.FileDescriptor value) {}
                    public void setMaxFileSize(long value) {}
                    public void setVideoFrameRate(int value) {}
                    public void setVideoEncodingBitRate(int value) {}
                    public void setVideoSize(int w, int h) {}
                    public void setAudioEncoder(int value) {}
                    public void setAudioSamplingRate(int value) {}
                    public void setAudioChannels(int value) {}
                    public void setAudioEncodingBitRate(int value) {}
                    public void setVideoEncoder(int value) {
                        // AOSP 常量：H264=2、HEVC=5（编译期已内联进生产字节码，这里按值判）
                        if (rejectHevcEncoder && value == 5) throw new IllegalArgumentException("hevc encoder rejected");
                    }
                    public void setCaptureRate(double value) {}
                    public void setOrientationHint(int value) {}
                    public void setInputSurface(Surface value) { if (rejectPersistent) throw new IllegalArgumentException("拒绝 persistent"); }
                    public void setOnInfoListener(OnInfoListener value) {}
                    public void setOnErrorListener(OnErrorListener value) {}
                    public void prepare() { if (prepareThrow != null) throw prepareThrow; }
                    public void start() { probe.Events.add(id + ".start"); }
                    public void stop() {
                        probe.Events.add(id + ".stop");
                        if (stopThrows) throw new IllegalStateException("stop failed");
                    }
                    public void release() { probe.Events.add(id + ".release"); }
                }
            """,
            "com.wotagei.cam.record.OutputTarget" to """
                package com.wotagei.cam.record;
                public class OutputTarget {
                    public static int opens, closes;
                    public static final Companion Companion = new Companion();
                    public String getPath() { return "boundary.mp4"; }
                    public java.io.FileDescriptor getFileDescriptor() { return null; }
                    public boolean getUsable() { return true; }
                    public void close() { closes++; }
                    public static class Companion {
                        public OutputTarget open(android.content.Context ctx, OutputSink sink) {
                            opens++;
                            return new OutputTarget();
                        }
                    }
                }
            """,
            "com.wotagei.cam.record.VideoStore" to """
                package com.wotagei.cam.record;
                public class VideoStore {
                    public String checkFreeSpace() { return null; }
                    public long getSeriesId() { return 1L; }
                    public void discard(OutputSink sink) { probe.Events.add("store.discard"); }
                    public android.net.Uri createPending(int part, String prefix) { return new android.net.Uri("content://record/" + part); }
                    public static android.net.Uri createPending${'$'}default(VideoStore self, int part, String prefix, int mask, Object marker) {
                        return self.createPending(part, prefix);
                    }
                    public VideoSegment seal(OutputSink sink, int part, long start, long duration, boolean keep) {
                        probe.Events.add("store.seal:" + part + ":" + duration + ":" + keep);
                        return new VideoSegment(null, "boundary.mp4", part, 1024L, start, duration, true);
                    }
                }
            """,
            "com.wotagei.cam.record.AudioProbe" to """
                package com.wotagei.cam.record;
                public class AudioProbe {
                    public static final AudioProbe INSTANCE = new AudioProbe();
                    public boolean hasRecordPermission(android.content.Context ctx) { return true; }
                    public int pickSource(android.content.Context ctx) { return 1; }
                    public int minBufferSize(int sr, int ch) { return 4096; }
                    public boolean isSampleRateSupported(int sr, int ch) { return true; }
                }
            """,
            "com.wotagei.cam.record.AudioFeeder" to """
                package com.wotagei.cam.record;
                public class AudioFeeder {
                    public boolean alive() { return true; }
                    public boolean captureActive() { return true; }
                    public Packet poll(long timeout) { return null; }
                    public int pending() { return 0; }
                    public int getSampleRate() { return 48000; }
                    public int getChannels() { return 2; }
                    public boolean getSawNonZero() { return true; }
                    public void stop() {}
                    public void drain() {}
                    public static class Packet {
                        public byte[] getData() { return new byte[0]; }
                        public long getPtsUs() { return 0L; }
                    }
                }
            """,
            "com.wotagei.cam.record.PlaybackCaptureController" to """
                package com.wotagei.cam.record;
                public class PlaybackCaptureController {
                    public static final Companion Companion = new Companion();
                    public static class Companion {
                        public PlaybackCaptureController get(android.content.Context ctx) { return new PlaybackCaptureController(); }
                    }
                    public void resetCaptureSilenceTake() {}
                    public void reportCaptureSilence() {}
                    public void reportTrackLost(CaptureTrackLostReason reason, String detail) { probe.Events.add("capture.lost:" + reason); }
                }
            """
        )
    }
}

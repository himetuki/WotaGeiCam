package com.wotagei.cam.media

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URLClassLoader
import java.lang.reflect.Proxy
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** 仅替换平台边界，执行真实仓库过滤与缩略图方法；不替代设备的Provider/解码器验证。 */
class MediaCompatibilityTest {

    @get:Rule
    val temporary = TemporaryFolder(File("build/tmp/media-compatibility-test").apply { mkdirs() })

    @Test
    fun `API29回收条目必须携带可还原状态`() = platform(29) { loader ->
        val result = filter(loader, systemTrashed = false, localTrashed = true, tab = "Trash")
        assertEquals(1, result.size)
        assertTrue("不能只过滤入回收站而不给UI还原状态", trashed(result.single()))
        assertTrue(filter(loader, false, true, "All").isEmpty())
    }

    @Test
    fun `API30不让残留本地表覆盖系统正常态`() = platform(30) { loader ->
        assertTrue(filter(loader, false, true, "Trash").isEmpty())
        val result = filter(loader, false, true, "All")
        assertEquals(1, result.size)
        assertFalse(trashed(result.single()))
    }

    @Test
    fun `API30系统回收状态不依赖本地表`() = platform(30) { loader ->
        assertTrue(trashed(filter(loader, true, false, "Trash").single()))
        assertTrue(filter(loader, true, false, "All").isEmpty())
    }

    @Test
    fun `六十秒视频真正请求三十秒中点`() = platform(29) { loader ->
        decode(loader, "60000")
        assertEquals(30_000_000L, retriever(loader).getField("requestedTimeUs").getLong(null))
    }

    @Test
    fun `高分辨率源直接请求缩放帧而非先解整张`() = platform(29) { loader ->
        val bitmap = decode(loader, "60000")
        assertEquals(0, retriever(loader).getField("unscaledCalls").getInt(null))
        assertEquals(1, retriever(loader).getField("scaledCalls").getInt(null))
        assertTrue(bitmap.javaClass.getMethod("getWidth").invoke(bitmap) as Int <= 512)
        assertTrue(bitmap.javaClass.getMethod("getHeight").invoke(bitmap) as Int <= 512)
    }

    @Test
    fun `缺少时长仍以缩放路径请求代表帧`() = platform(29) { loader ->
        decode(loader, null)
        assertEquals(-1L, retriever(loader).getField("requestedTimeUs").getLong(null))
        assertEquals(0, retriever(loader).getField("unscaledCalls").getInt(null))
    }

    @Test
    fun `异常超长时长换算饱和不溢出为负数`() = platform(29) { loader ->
        decode(loader, Long.MAX_VALUE.toString())
        assertEquals(Long.MAX_VALUE, retriever(loader).getField("requestedTimeUs").getLong(null))
    }

    @Test
    fun `API29单片查询也携带本地回收状态`() = platform(29) { loader ->
        assertTrue("从回收站进入单片播放器也必须可还原", trashed(singleClip(loader, "once")))
    }

    @Test
    fun `API29单片流也携带本地回收状态`() = platform(29) { loader ->
        assertTrue(trashed(singleClip(loader, "flow")))
    }

    @Test
    fun `API29媒体URI查询也携带本地回收状态`() = platform(29) { loader ->
        assertTrue(trashed(singleClip(loader, "uri")))
    }

    @Test
    fun `API30单片查询忽略残留本地回收状态`() = platform(30) { loader ->
        for (entry in listOf("once", "flow", "uri")) assertFalse(entry, trashed(singleClip(loader, entry)))
    }

    @Test
    fun `API30单片三入口保留系统回收状态`() = platform(30) { loader ->
        loader.loadClass("android.database.FixtureCursor").getField("trashed").setInt(null, 1)
        for (entry in listOf("once", "flow", "uri")) assertTrue(entry, trashed(singleClip(loader, entry)))
    }

    @Test
    fun `API29单片三入口查无记录时明确返回空`() = platform(29) { loader ->
        loader.loadClass("android.database.FixtureCursor").getField("present").setBoolean(null, false)
        for (entry in listOf("once", "flow", "uri")) assertEquals(entry, null, singleClip(loader, entry))
    }

    private fun singleClip(loader: ClassLoader, entry: String): Any? {
        val repoClass = loader.loadClass("com.wotagei.cam.media.MediaRepo")
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, repoClass)
        fun inject(name: String, value: Any) = repoClass.getDeclaredField(name).apply { isAccessible = true }.set(repo, value)
        inject("app", loader.loadClass("android.content.Context").getConstructor().newInstance())
        inject("projectionBase", listOf("_id", "_display_name", "duration", "width", "height"))
        val local = loader.loadClass("com.wotagei.cam.media.TrashItem")
            .getConstructor(String::class.java, Long::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType, String::class.java)
            .newInstance("content://media/external/video/media/1", 1L, "fixture.mp4", 0L, "Movies/WotaGeiCam")
        val trashType = loader.loadClass("com.wotagei.cam.media.TrashDao")
        inject("trashDao", Proxy.newProxyInstance(loader, arrayOf(trashType)) { _, method, _ ->
            check(method.name == "observeAll") { "意外调用 ${method.name}" }
            flowOf(listOf(local))
        })
        val tagType = loader.loadClass("com.wotagei.cam.media.MediaTagDao")
        inject("tagDao", Proxy.newProxyInstance(loader, arrayOf(tagType)) { _, method, _ ->
            when (method.name) {
                "tagsOf" -> emptyList<String>()
                "observeAll" -> flowOf(emptyList<Any>())
                else -> error("意外调用 ${method.name}")
            }
        })
        inject("version", MutableStateFlow(0L))
        inject("refreshGate", MutableStateFlow(false))
        val seriesType = repoClass.getDeclaredField("seriesDao").type
        inject("seriesDao", Proxy.newProxyInstance(loader, arrayOf(seriesType)) { _, method, _ ->
            check(method.name in setOf("observeAllSeries", "observeAllParts")) { "意外调用 ${method.name}" }
            flowOf(emptyList<Any>())
        })
        return runBlocking {
            withTimeout(5_000L) {
                if (entry == "flow") {
                    (repoClass.getMethod("clipById", Long::class.javaPrimitiveType).invoke(repo, 1L) as Flow<*>).first()
                } else {
                    val uriType = loader.loadClass("android.net.Uri")
                    val query = if (entry == "once") {
                        repoClass.getDeclaredMethod("clipByIdOnce", Long::class.javaPrimitiveType, kotlin.coroutines.Continuation::class.java)
                    } else {
                        repoClass.getDeclaredMethod("mediaStoreClipByUri", uriType, kotlin.coroutines.Continuation::class.java)
                    }.apply { isAccessible = true }
                    val input = if (entry == "once") 1L else uriType.getConstructor(String::class.java)
                        .newInstance("content://media/external/video/media/1")
                    suspendCoroutine<Any?> { continuation ->
                        val result = query.invoke(repo, input, continuation)
                        if (result !== COROUTINE_SUSPENDED) continuation.resume(result)
                    }
                }
            }
        }
    }

    private fun trashed(clip: Any?) = requireNotNull(clip).javaClass.getMethod("isTrashed").invoke(clip) as Boolean

    private fun filter(loader: ClassLoader, systemTrashed: Boolean, localTrashed: Boolean, tab: String): List<*> {
        val uri = loader.loadClass("android.net.Uri").getConstructor(String::class.java).newInstance("content://media/external/video/media/1")
        val clip = loader.loadClass("com.wotagei.cam.media.VideoClip").declaredConstructors
            .single { it.parameterTypes.size == 16 }.newInstance(
                1L, uri, "fixture.mp4", null, "Movies/WotaGeiCam", 0L, 1L, 60_000L,
                3840, 2160, "video/mp4", emptySet<String>(), systemTrashed, null, null, 1
            )
        val local = if (localTrashed) listOf(loader.loadClass("com.wotagei.cam.media.TrashItem")
            .getConstructor(String::class.java, Long::class.javaPrimitiveType, String::class.java, Long::class.javaPrimitiveType, String::class.java)
            .newInstance(uri.toString(), 1L, "fixture.mp4", 0L, "Movies/WotaGeiCam")) else emptyList()
        val filterClass = loader.loadClass("com.wotagei.cam.media.ClipFilter")
        val selected = loader.loadClass("com.wotagei.cam.media.ClipFilter\$$tab").getField("INSTANCE").get(null)
        val repoClass = loader.loadClass("com.wotagei.cam.media.MediaRepo")
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val repo = unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, repoClass)
        return repoClass.getDeclaredMethod("filterByTab", List::class.java, filterClass, List::class.java)
            .apply { isAccessible = true }.invoke(repo, listOf(clip), selected, local) as List<*>
    }

    private fun retriever(loader: ClassLoader) = loader.loadClass("android.media.MediaMetadataRetriever")

    private fun decode(loader: ClassLoader, duration: String?): Any {
        retriever(loader).getField("duration").set(null, duration)
        val contextClass = loader.loadClass("android.content.Context")
        val uriClass = loader.loadClass("android.net.Uri")
        val thumb = loader.loadClass("com.wotagei.cam.media.ThumbLoader")
        return requireNotNull(thumb.getDeclaredMethod("decode", contextClass, uriClass, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(
                thumb.getField("INSTANCE").get(null), contextClass.getConstructor().newInstance(),
                uriClass.getConstructor(String::class.java).newInstance("content://fixture/video"), 512
            ))
    }

    private fun platform(sdk: Int, body: (ClassLoader) -> Unit) {
        val root = temporary.newFolder()
        val classes = File(root, "classes").apply { mkdirs() }
        val sources = mapOf(
            "android/os/Build.java" to "package android.os; public class Build { public static class VERSION { public static int SDK_INT = $sdk; } }",
            "android/net/Uri.java" to """
                package android.net;
                public class Uri {
                    private final String value;
                    public Uri(String value) { this.value = value; }
                    public String toString() { return value; }
                    public String getAuthority() { return java.net.URI.create(value).getAuthority(); }
                    public java.util.List<String> getPathSegments() { return java.util.Arrays.asList(java.net.URI.create(value).getPath().substring(1).split("/")); }
                }
            """.trimIndent(),
            "android/content/Context.java" to """
                package android.content;
                public class Context { public ContentResolver getContentResolver() { return new ContentResolver(); } }
            """.trimIndent(),
            "android/content/ContentResolver.java" to """
                package android.content;
                public class ContentResolver {
                    public android.graphics.Bitmap loadThumbnail(android.net.Uri uri, android.util.Size size, android.os.CancellationSignal signal) { return null; }
                    public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection, String[] args, String sort) { return new android.database.FixtureCursor(); }
                }
            """.trimIndent(),
            "android/content/ContentUris.java" to """
                package android.content;
                public class ContentUris {
                    public static android.net.Uri withAppendedId(android.net.Uri base, long id) { return new android.net.Uri(base.toString()+"/"+id); }
                    public static long parseId(android.net.Uri uri) { java.util.List<String> p=uri.getPathSegments(); return Long.parseLong(p.get(p.size()-1)); }
                }
            """.trimIndent(),
            "android/provider/MediaStore.java" to """
                package android.provider;
                public class MediaStore {
                    public static class Video { public static class Media {
                        public static android.net.Uri EXTERNAL_CONTENT_URI = new android.net.Uri("content://media/external/video/media");
                    } }
                }
            """.trimIndent(),
            "android/database/Cursor.java" to """
                package android.database;
                public interface Cursor extends java.io.Closeable {
                    boolean moveToNext(); int getColumnIndex(String name); int getColumnIndexOrThrow(String name);
                    long getLong(int column); int getInt(int column); String getString(int column); void close();
                }
            """.trimIndent(),
            "android/database/FixtureCursor.java" to """
                package android.database;
                public class FixtureCursor implements Cursor {
                    private boolean moved;
                    public static boolean present = true;
                    public static int trashed;
                    public boolean moveToNext() { if (moved || !present) return false; moved=true; return true; }
                    public int getColumnIndex(String name) {
                        if ("_id".equals(name)) return 0;
                        return "is_trashed".equals(name) && android.os.Build.VERSION.SDK_INT >= 30 ? 1 : -1;
                    }
                    public int getColumnIndexOrThrow(String name) { if (!"_id".equals(name)) throw new IllegalArgumentException(name); return 0; }
                    public long getLong(int column) { return 1L; }
                    public int getInt(int column) { return column == 1 ? trashed : 0; }
                    public String getString(int column) { return null; }
                    public void close() {}
                }
            """.trimIndent(),
            "android/os/CancellationSignal.java" to "package android.os; public class CancellationSignal {}",
            "android/util/Size.java" to "package android.util; public class Size { public Size(int width, int height) {} }",
            "android/util/LruCache.java" to """
                package android.util;
                public class LruCache<K,V> {
                    public LruCache(int max) {}
                    protected int sizeOf(K key,V value) { return 1; }
                    public V get(K key) { return null; }
                    public V put(K key,V value) { return null; }
                    public V remove(K key) { return null; }
                }
            """.trimIndent(),
            "android/graphics/Bitmap.java" to """
                package android.graphics;
                public class Bitmap {
                    private final int width, height;
                    public Bitmap(int width, int height) { this.width=width; this.height=height; }
                    public int getWidth() { return width; }
                    public int getHeight() { return height; }
                    public int getByteCount() { return width*height*4; }
                    public void recycle() {}
                    public static Bitmap createScaledBitmap(Bitmap src,int width,int height,boolean filter) { return new Bitmap(width,height); }
                }
            """.trimIndent(),
            "android/media/MediaMetadataRetriever.java" to """
                package android.media;
                public class MediaMetadataRetriever {
                    public static String duration;
                    public static long requestedTimeUs;
                    public static int unscaledCalls, scaledCalls;
                    public void setDataSource(android.content.Context context, android.net.Uri uri) {}
                    public String extractMetadata(int key) { return key == 9 ? duration : key == 18 ? "3840" : key == 19 ? "2160" : "0"; }
                    public android.graphics.Bitmap getFrameAtTime(long time,int option) { requestedTimeUs=time; unscaledCalls++; return new android.graphics.Bitmap(3840,2160); }
                    public android.graphics.Bitmap getFrameAtTime() { return getFrameAtTime(-1L,2); }
                    public android.graphics.Bitmap getScaledFrameAtTime(long time,int option,int width,int height) { requestedTimeUs=time; scaledCalls++; return new android.graphics.Bitmap(width,height); }
                    public void release() {}
                }
            """.trimIndent()
        )
        val files = sources.map { (path, source) -> File(root, path).apply { requireNotNull(parentFile).mkdirs(); writeText(source) } }
        val compiler = Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null)
        val run = Class.forName("javax.tools.Tool").getMethod("run", InputStream::class.java, OutputStream::class.java, OutputStream::class.java, Array<String>::class.java)
        assertEquals(0, run.invoke(compiler, null, null, null,
            (listOf("-proc:none", "-encoding", "UTF-8", "-d", classes.absolutePath) + files.map { it.absolutePath }).toTypedArray()))
        BoundaryLoader(classes, requireNotNull(javaClass.classLoader), sources.keys.map { it.removeSuffix(".java").replace('/', '.') }.toSet()).use(body)
    }

    private class BoundaryLoader(classes: File, parent: ClassLoader, private val platform: Set<String>) :
        URLClassLoader(arrayOf(classes.toURI().toURL()), parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(this) {
            val loaded = findLoadedClass(name) ?: when {
                name in platform || name.startsWith("android.os.Build\$") || name.startsWith("android.provider.MediaStore\$") -> findClass(name)
                name.startsWith("com.wotagei.cam.media.") || name == "com.wotagei.cam.core.MediaAccessKt" -> {
                    val path = name.replace('.', '/') + ".class"
                    val bytes = requireNotNull(parent.getResourceAsStream(path)) { "缺少真实生产字节码 $path" }.use { it.readBytes() }
                    defineClass(name, bytes, 0, bytes.size)
                }
                else -> super.loadClass(name, false)
            }
            if (resolve) resolveClass(loaded)
            loaded
        }
    }
}
